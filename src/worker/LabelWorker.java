package worker;

import das.Commandable;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.ArrayUtils;
import org.w3c.dom.Element;
import io.Readable;
import io.Writable;
import das.CommandPool;
import io.mqtt.MqttWriting;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.RealVal;
import util.data.RealtimeValues;
import util.database.QueryWriting;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * This class retrieves @see worker.Datagram s from a @see BlockingQueue. 
 * Next the content of @see Datagram is investigated and processed. 
 *
 * @author Michiel TJampens @vliz
 */
public class LabelWorker implements Runnable {

	private final Map<String, ValMap> mappers = new HashMap<>();
	private final Map<String, Readable> readables = new HashMap<>();

	private final ArrayList<DatagramProcessing> dgProc = new ArrayList<>();

	private BlockingQueue<Datagram> dQueue;      // The queue holding raw data for processing
	private final RealtimeValues rtvals;
	private final Path settingsPath;
	private boolean goOn=true;
	protected CommandPool reqData;

	protected boolean debugMode = false;

	private final AtomicInteger procCount = new AtomicInteger(0);
	private long procTime = Instant.now().toEpochMilli();

	ThreadPoolExecutor executor = new ThreadPoolExecutor(1,
			Math.min(3, Runtime.getRuntime().availableProcessors()), // max allowed threads
			30L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>());

	ScheduledExecutorService debug = Executors.newSingleThreadScheduledExecutor();

	// Debug help
	int readCount=0;
	int oldReadCount=0;
	String lastOrigin="";

	Writable spy;
	String spyingOn ="";
	/* ***************************** C O N S T R U C T O R **************************************/

	/**
	 * Default constructor that gets a queue to use
	 *
	 * @param dQueue The queue to use
	 */
	public LabelWorker(Path settingsPath, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals) {
		this.settingsPath=settingsPath;
		this.dQueue = dQueue;
		this.rtvals=rtvals;

		Logger.info("Using " + Math.min(3, Runtime.getRuntime().availableProcessors()) + " threads");
		debug.scheduleAtFixedRate(this::selfCheck,5,30,TimeUnit.MINUTES);

		loadValMaps(true);
	}

	/**
	 * Set the BaseReq for this worker to use
	 *
	 * @param commandPool The default BaseReq or extended one
	 */
	public void setCommandReq(CommandPool commandPool) {
		this.reqData = commandPool;
	}

	/**
	 * Set or remove debugmode flag
	 * @param deb New state for the debugmode
	 */
	public void setDebugging(boolean deb) {
		this.debugMode = deb;
	}
	public void addDatagramProcessing( DatagramProcessing dgp){
		dgProc.add(dgp);
	}
	/* ****************************************** V A L M A P S *************************************************** */
	private void addValMap(ValMap map) {
		mappers.put(map.getID(), map);
		Logger.info("Added generic " + map.getID());
	}
	public void loadValMaps(boolean clear){
		var settingsDocOpt = XMLtools.readXML(settingsPath);
		if( clear ){
			mappers.clear();
		}
		if( settingsDocOpt.isEmpty()){
			return;
		}
		var settingsDoc = settingsDocOpt.get();

		XMLfab.getRootChildren(settingsDoc, "dcafs","valmaps","valmap")
				.forEach( ele ->  addValMap( ValMap.readFromXML(ele) ) );

		// Find the path ones?
		XMLfab.getRootChildren(settingsPath, "dcafs","paths","path")
				.forEach( ele -> {
							String imp = ele.getAttribute("import");

							int a=1;
							if( !imp.isEmpty() ){ //meaning imported
								var importPath = Path.of(imp);
								if( !importPath.isAbsolute())
									importPath = settingsPath.getParent().resolve(importPath);
								String file = importPath.getFileName().toString();
								file = file.substring(0,file.length()-4);//remove the .xml

								for( Element vm : XMLfab.getRootChildren(importPath, "dcafs", "paths", "path", "valmap")){
									if( !vm.hasAttribute("id")){ //if it hasn't got an id, give it one
										vm.setAttribute("id",file+"_vm"+a);
										a++;
									}
									if( !vm.hasAttribute("delimiter") ) //if it hasn't got an id, give it one
										vm.setAttribute("delimiter",vm.getAttribute("delimiter"));
									addValMap( ValMap.readFromXML(vm) );
								}
							}
							String delimiter = XMLtools.getStringAttribute(ele,"delimiter","");
							for( Element vm : XMLtools.getChildElements(ele,"valmap")){
								if( !vm.hasAttribute("id")){ //if it hasn't got an id, give it one
									vm.setAttribute("id",ele.getAttribute("id")+"_vm"+a);
									a++;
								}
								if( !vm.hasAttribute("delimiter") && !delimiter.isEmpty()) //if it hasn't got an id, give it one
									vm.setAttribute("delimiter",delimiter);
								addValMap( ValMap.readFromXML(vm) );
							}
						}
				);
	}

	/* ******************************** Q U E U E S **********************************************/
	public int getWaitingQueueSize(){
		return executor.getQueue().size();
	}
	/**
	 * Get the queue for adding work for this worker
	 *
	 * @return The qeueu
	 */
	public BlockingQueue<Datagram> getQueue() {
		return dQueue;
	}

	/**
	 * Set the queue for adding work for this worker
	 *
	 * @param d The queue
	 */
	public void setQueue(BlockingQueue<Datagram> d) {
		this.dQueue = d;
	}

	/* ******************************* D E F A U L T   S T U F F **************************************** */
	private void checkRead( String from, Writable wr, String id){
		if(wr!=null){
			var ids = id.split(",");
			var read = readables.get(ids[0]);
			if( read != null && !read.isInvalid()){

				read.addTarget(wr,ids.length==2?ids[1]:"*");
				Logger.info("Added "+wr.getID()+ " to target list of "+read.getID());
			}else{
				Logger.error(wr.getID()+" asked for data from "+id+" but doesn't exists (anymore)");
			}
			readables.entrySet().removeIf( entry -> entry.getValue().isInvalid());
		}else{
			Logger.error("No valid writable in the datagram from "+from);
		}
	}
	public void checkTelnet(Datagram d) {
		Writable dt = d.getWritable();
		d.origin("telnet");

		if( d.getData().equalsIgnoreCase("spy:off")||d.getData().equalsIgnoreCase("spy:stop")){
			spy.writeLine("Stopped spying...");
			spy=null;
			return;
		}else if( d.getData().startsWith("spy:")){
			spyingOn =d.getData().split(":")[1];
			spy=d.getWritable();
			spy.writeLine("Started spying on "+ spyingOn);
			return;
		}

		if (!d.getData().equals("status")) {
			String from = " for ";

			if (dt != null) {
				from += dt.getID();
			}
			if (!d.getData().isBlank())
				Logger.info("Executing telnet command [" + d.getData() + "]" + from);
		}

		String response = reqData.createResponse( d, false);
		if( spy!=null && d.getWritable()!=spy && (d.getWritable().getID().equalsIgnoreCase(spyingOn)|| spyingOn.equalsIgnoreCase("all"))){
			spy.writeLine(TelnetCodes.TEXT_ORANGE+"Cmd: "+d.getData()+TelnetCodes.TEXT_YELLOW);
			spy.writeLine(response);
		}
		String[] split = d.getLabel().split(":");
		if (dt != null) {
			if( d.getData().startsWith("telnet:write")){
				dt.writeString(TelnetCodes.PREV_LINE+TelnetCodes.CLEAR_LINE+response);
			}else{
				dt.writeLine(response);
				dt.writeString((split.length >= 2 ? "<" + split[1] : "") + ">");
			}
		} else {
			Logger.info(response);
			Logger.info((split.length >= 2 ? "<" + split[1] : "") + ">");
		}
		procCount.incrementAndGet();
	}
	public void stop(){
		goOn=false;
	}
	/* ************************************** RUNNABLES ******************************************************/
	@Override
	public void run() {

		if (this.reqData == null) {
			Logger.error("Not starting without proper BaseReq");
			return;
		}
		while (goOn) {
			try {
				Datagram d = dQueue.take();
				readCount++;

				lastOrigin=d.getOriginID();
				String label = d.getLabel();

				if (label == null) {
					Logger.error("Invalid label received along with message :" + d.getData());
					continue;
				}

				if( d.label.contains(":") ){
					String readID = label.substring(label.indexOf(":")+1);
					switch (d.label.split(":")[0]) {
						case "valmap" -> executor.execute(() -> processValmap(d));
						case "read" -> executor.execute(() -> checkRead(d.getOriginID(), d.getWritable(), readID));
						case "telnet" -> executor.execute(() -> checkTelnet(d));
						case "log" -> {
							switch (d.label.split(":")[1]) {
								case "info" -> Logger.info(d.getData());
								case "warn" -> Logger.warn(d.getData());
								case "error" -> Logger.error(d.getData());
							}
						}
						default -> {
							boolean processed = false;
							for (DatagramProcessing dgp : dgProc) {
								if (dgp.processDatagram(d)) {
									processed = true;
									break;
								}
							}
							if (!processed)
								Logger.error("Unknown label: " + label);
						}
					}
				}else {
					switch (label) {
						case "system": case "cmd": case "matrix":
							executor.execute(() -> reqData.createResponse( d, false));
							break;
						case "void":
							break;
						case "readable":
							if(  d.getReadable()!=null)
								readables.put(d.getReadable().getID(),d.getReadable());
							readables.entrySet().removeIf( entry -> entry.getValue().isInvalid()); // cleanup
							break;
						case "test":
							executor.execute(() -> Logger.info(d.getOriginID() + "|" + label + " -> " + d.getData()));
							break;
						case "email":
							executor.execute(() -> reqData.emailResponse(d));
							break;
						case "telnet":
							executor.execute(() -> checkTelnet(d) );
							break;
						default:
							boolean processed=false;
							for( DatagramProcessing dgp : dgProc ){
								if( dgp.processDatagram(d)) {
									processed=true;
									break;
								}
							}
							if( ! processed)
								Logger.error("Unknown label: "+label);
							break;
					}
				}
				int proc = procCount.get();
				if (proc >= 500000) {
					procCount.set(0);
					long millis = Instant.now().toEpochMilli() - procTime;
					procTime = Instant.now().toEpochMilli();
					Logger.info("Processed " + (proc / 1000) + "k lines in " + TimeTools.convertPeriodtoString(millis, TimeUnit.MILLISECONDS));
				}
			} catch( RejectedExecutionException e){
				Logger.error(e.getMessage());
			} catch (Exception e) {
				Logger.error(e);
			}
		}
	}
	public void selfCheck(){
		Logger.info("Read count now "+readCount+", old one "+oldReadCount+ " last message processed from "+lastOrigin+ " buffersize "+dQueue.size());
		Logger.info("Executioner: "+ executor.getCompletedTaskCount()+" completed, "+ executor.getTaskCount()+" submitted, "
				+ executor.getActiveCount()+"/"+ executor.getCorePoolSize()+"("+executor.getMaximumPoolSize()+")"+" active threads, "+ executor.getQueue().size()+" waiting to run");

		oldReadCount = readCount;
		readCount=0;
		lastOrigin="";
	}
	public void processValmap(Datagram d) {
		try {
			String valMapIDs = d.label.split(":")[1];
			String mes = d.getData();

			if (mes.isBlank()) {
				Logger.warn(valMapIDs + " -> Ignoring blank line");
				return;
			}
			for (String valmapID : valMapIDs.split(",")) {
				var map = mappers.get(valmapID);
				if (map != null) {
					map.apply(mes, rtvals);
				}else{
					Logger.error("ValMap requested but unknown id: " + valmapID + " -> Message: " + d.getData());
				}
			}
		} catch (ArrayIndexOutOfBoundsException l) {
			Logger.error("Generic requested (" + d.label + ") but no valid id given.");
		} catch( Exception e){
			Logger.error(e);
		}
		procCount.incrementAndGet();
	}

}
