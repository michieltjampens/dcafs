package worker;

import io.telnet.TelnetCodes;
import io.Writable;
import das.CommandPool;
import org.tinylog.Logger;
import util.tools.TimeTools;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class retrieves @see worker.Datagram s from a @see BlockingQueue. 
 * Next the content of @see Datagram is investigated and processed. 
 *
 * @author Michiel TJampens @vliz
 */
public class LabelWorker implements Runnable {
	private final ArrayList<DatagramProcessing> dgProc = new ArrayList<>();
	private BlockingQueue<Datagram> dQueue;      // The queue holding raw data for processing
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
	public LabelWorker(BlockingQueue<Datagram> dQueue) {
		this.dQueue = dQueue;

		Logger.info("Using " + Math.min(3, Runtime.getRuntime().availableProcessors()) + " threads");
		debug.scheduleAtFixedRate(this::selfCheck,5,30,TimeUnit.MINUTES);
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
				from += dt.id();
			}
			if (!d.getData().isBlank())
				Logger.info("Executing telnet command [" + d.getData() + "]" + from);
		}

		String response = reqData.createResponse( d, false);
		if( spy!=null && d.getWritable()!=spy && (d.getWritable().id().equalsIgnoreCase(spyingOn)|| spyingOn.equalsIgnoreCase("all"))){
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
					switch (d.label.split(":")[0]) {
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
						case "system", "cmd", "matrix" -> executor.execute(() -> reqData.createResponse(d, false));
						case "email" -> executor.execute(() -> reqData.emailResponse(d));
						case "telnet" -> executor.execute(() -> checkTelnet(d));
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
}
