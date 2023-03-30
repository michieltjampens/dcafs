package io.stream;

import das.Commandable;
import das.IssuePool;
import io.Writable;
import io.collector.CollectorFuture;
import io.collector.ConfirmCollector;
import io.stream.serialport.ModbusStream;
import io.stream.serialport.MultiStream;
import io.stream.serialport.SerialStream;
import io.stream.tcp.ModbusTCPStream;
import io.stream.tcp.TcpStream;
import io.stream.udp.UdpServer;
import io.stream.udp.UdpStream;
import io.telnet.TelnetCodes;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.commons.lang3.StringUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * The class holds all the information required about a datasource to acquire
 * data from it. It uses the internal class StreamDescriptor to hold this
 * information. All connections are made using the Netty 4.x library.
 */
public class StreamManager implements StreamListener, CollectorFuture, Commandable {

	private final BlockingQueue<Datagram> dQueue; // Holds the data for the DataWorker

	// Netty 
	private Bootstrap bootstrapTCP;		// Bootstrap for TCP connections
	private Bootstrap bootstrapUDP;	  	// Bootstrap for UDP connections

	private final EventLoopGroup eventLoopGroup;	// Event loop used by the netty stuff

	private final IssuePool issues;			// Handles the issues/problems that arise
	private int retryDelayMax = 30;			// The minimum time between reconnection attempts
	private int retryDelayIncrement = 5;	// How much the delay increases between attempts

	private final HashMap<String, ConfirmCollector> confirmCollectors = new HashMap<>();

	private final LinkedHashMap<String,BaseStream> streams = new LinkedHashMap<>();

	private Path settingsPath = Path.of("settings.xml"); // Path to the xml file
	private boolean debug = false; // Whether in debug mode, gives more feedback

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(); // scheduler for the connection attempts
	private static final String XML_PARENT_TAG="streams";
	private static final String XML_CHILD_TAG="stream";
	private final RealtimeValues rtvals;

	static String[] WHEN={"open","close","idle","!idle","hello","wakeup","asleep"};
	static String[] SERIAL={"serial","modbus"};
	static String[] NEWSTREAM={"addserial","addmodbus","addtcp","addudpclient","addlocal","addudpserver"};

	public StreamManager(BlockingQueue<Datagram> dQueue, IssuePool issues, EventLoopGroup nettyGroup, RealtimeValues rtvals ) {
		this.dQueue = dQueue;
		this.issues = issues;	
		this.eventLoopGroup = nettyGroup;
		this.rtvals=rtvals;
	}
	public StreamManager(BlockingQueue<Datagram> dQueue, IssuePool issues, RealtimeValues rtvals) {
		this(dQueue,issues, new NioEventLoopGroup(), rtvals);
	}

	/**
	 * Get the optional stream associated with the given id
	 * @param id The id to look for
	 * @return The BaseStream optional or an empty optional of none found
	 */
	public Optional<BaseStream> getStream( String id ){
		return Optional.ofNullable(streams.get(id.toLowerCase()));
	}

	/**
	 * Get the optional stream associated with the given id but only if it is writable with a valid connection
	 * @param id The id to look for
	 * @return The BaseStream optional or an empty optional if none found
	 */
	public Optional<BaseStream> getWritableStream( String id ){
		// Get the stream, check if it's writable if so get the writable and has a valid connection or return an empty optional
		return Optional.ofNullable( streams.get(id.toLowerCase()) ).filter( bs -> bs.isWritable() && bs.isConnectionValid() );
	}
	/**
	 * Get the Writable of the stream associated with the id as an optional
	 * @param id The stream to look for
	 * @return The optional writable or an empty one
	 */
	public Optional<Writable> getWritable(String id ){
		// Get the stream, check if it's writable if so get the writable or return an empty optional if not
		return getStream(id).filter(BaseStream::isWritable).map(bs -> (Writable) bs);
		//TODO Check if this still works
	}

	/* **************************** S T A T U S ************************************************************************/
	/**
	 * Request a string holding info regarding the status of each connection
	 * 
	 * @return A string holding info regarding the status of each connection.
	 */
	public String getStatus() {
		StringJoiner join = new StringJoiner("");

		for( BaseStream stream : streams.values() ){
			long ttl = Instant.now().toEpochMilli() - stream.getLastTimestamp();
			if( !stream.isConnectionValid() ){
				join.add("NC ");
			}else if (ttl > stream.readerIdleSeconds *1000 && stream.readerIdleSeconds != -1) {
				join.add("!! ");
			}

			join.add(stream.getInfo()).add("\t");
			if (stream.getLastTimestamp() == -1) {
				join.add("No data yet!").add("\r\n");
			} else {
				join.add(TimeTools.convertPeriodtoString(ttl, TimeUnit.MILLISECONDS)).add(" [");
				join.add(TimeTools.convertPeriodtoString(stream.readerIdleSeconds, TimeUnit.SECONDS)).add("]").add("\r\n");
			}
		}
		return join.toString();
	}

	/**
	 * Request information regarding the settings of all the connections, this is
	 * very rudimentary.
	 * 
	 * @return The label, id, address and TTL of each stream
	 */
	public String getSettings() {
		StringJoiner join = new StringJoiner("\r\n");
		streams.values().forEach(
			stream -> join.add( stream.getInfo() + " Max. TTL:" + TimeTools.convertPeriodtoString(stream.readerIdleSeconds,TimeUnit.SECONDS))
		);
		return join.toString();
	}


	/**
	 * Get a list of all the StreamDescriptors available
	 * @param html Whether to use html formatting
	 * @return A String with a line for each StreamDescriptor which looks like
	 *         Sxx=id
	 */
	public String getStreamList(boolean html) {
		StringJoiner join = new StringJoiner(html ? "<br>" :"\r\n");
		join.setEmptyValue("None yet");
		int a = 1;
		for (String id : streams.keySet()) {
			join.add( "S" + (a++) + ":" + id);
		}
		return join.toString();
	}
	/**
	 * Retrieve the contents of the confirm/reply buffer from the various streams
	 * Mainly used for debugging
	 * @return Contents of the confirm/reply buffer from the various streams
	 */
	public String getConfirmBuffers() {
		StringJoiner join = new StringJoiner("\r\n");
		confirmCollectors.forEach( (id, cw) -> join.add(">>"+cw.id()).add( cw.getStored().length() == 0 ? " empty" : cw.getStored()));
		return join.toString();
	}
	/* *************************************  S E T U P **************************************************************/
	/**
	 * Disconnect all connections
	 */
	public void disconnectAll() {
		streams.forEach((k,v) -> v.disconnect() );
		eventLoopGroup.shutdownGracefully();
	}

	/* ********************************** W R I T I N G **************************************************************/
	/**
	 * Send bytes over a specified stream
	 * @param id The name/title of the stream
	 * @param txt The data to transmit
	 * @return True if it was written
	 */
	public String writeBytesToStream( String id, byte[] txt ) {
		Optional<BaseStream> streamOpt = getStream(id.toLowerCase());
		if ( streamOpt.isPresent() ) {
			BaseStream stream = streamOpt.get();
			if( !stream.isWritable() ){
				Logger.error("The stream " + id + " is readonly.");
				return "";
			}
			if( !stream.isConnectionValid() ){
				Logger.error("No connection to stream named " + stream);
				reloadStream(id);
				return "";
			}
			if( debug )
				Logger.info("Sending '"+Tools.fromBytesToHexString(txt) + "' to " + stream );

			Writable wr = (Writable)stream;
			wr.writeBytes( txt );
			return new String(txt);
		}else{
			Logger.error("Didn't find stream named " + id);
			return "";
		}
	}

	/**
	 * Write something to a stream that expects a reply
	 * @param wf Future for this write
	 * @param ref Reference to this action
	 * @param id The id of the stream to write to
	 * @param txt The text to write
	 * @param reply The reply to expect
	 * @return False if stream doesn't exist or doesn't allow being written to otherwise true
	 */
	public boolean writeWithReply(CollectorFuture wf, String ref, String id, String txt, String reply){
		return writeWithReply(wf,ref,id,txt,reply,3,3);
	}
	public boolean writeWithReply(CollectorFuture wf, String ref, String id, String txt, String reply,long replyWait,int replyTries){
		BaseStream stream = this.streams.get( id.toLowerCase() );

		if( stream == null || !stream.isWritable() || !stream.isConnectionValid() ){
			Logger.error( "Stream still null, not writable or no valid connection (looking for "+id+")");
			return false;
		}
		
		ConfirmCollector cw = confirmCollectors.get(ref+"_"+id);
		if( cw==null ){
			cw = new ConfirmCollector( ref+"_"+id,replyTries,(int)replyWait, (Writable)stream, eventLoopGroup);
			cw.addListener(wf);
			stream.addTarget(cw);
			confirmCollectors.put(ref+"_"+id, cw );
		}
		cw.addConfirm(txt.split(";"), reply);
		return true;
	}
	/**
	 * Standard way of writing ascii data to a channel, with or without requesting a certain reply
	 * @param id The id of the stream to write to
	 * @param txt The ascii data to transmit
	 * @param reply The expected reply to the transmit
	 * @return The string that was written or an empty string if failed
	 */
	public String writeToStream(String id, String txt, String reply) {

		if( txt.startsWith("\\h(") ){
			txt = txt.substring( 3, txt.indexOf(")") ); //remove the \h(...)
			return writeBytesToStream( id, Tools.fromHexStringToBytes(txt) );
		}

		Optional<BaseStream> streamOptional = getWritableStream(id);
		if( streamOptional.isPresent() ){
			BaseStream stream = streamOptional.get();
			ConfirmCollector cw = confirmCollectors.get(id);

			if( cw!=null && cw.isEmpty() ) {
				confirmCollectors.remove(id);
				Logger.info("Removed empty ConfirmCollector "+id);
				cw=null;
			}

			if( cw==null ){// If none exists yet
				if( txt.contains(";") || !reply.isEmpty() ){
					cw = new ConfirmCollector( id,3,3, (Writable)stream, scheduler );
					cw.addListener(this);
					if( !reply.isEmpty()) // No need to get data if we won't use it
						stream.addTarget(cw);
					confirmCollectors.put(stream.id(),cw);
				}else{
					if( txt.indexOf("\\") < txt.length()-2 ){
						txt = Tools.fromEscapedStringToBytes(txt);
					}
					boolean written;
					boolean nullEnded = Tools.isNullEnded(txt);
					if( txt.endsWith("\\0") || nullEnded){
						if( nullEnded )
							txt = txt.substring(0,txt.length()-1);
						txt=StringUtils.removeEnd(txt,"\\0");
						written=((Writable)stream).writeString(txt);
					}else{
						written=((Writable)stream).writeLine(txt);
					}
					if( !written )
						Logger.error("writeString/writeLine failed to "+id+" for "+txt);
					return written?txt:"";
				}
			}
			cw.addConfirm(txt.split(";"), reply);
			return txt;
		}else{
			var bs = streams.get(id);
			if( bs==null){
				Logger.error("No such stream "+id);
			}else if( !bs.isWritable() ){
				Logger.error("Found the stream "+id+", but not writable");
			}else if( !bs.isConnectionValid()){
				Logger.error("Found the writable stream "+id+", but no valid connection");
			}
			return "";
		}
	}
	/**
	 * Request the amount of registered streams
	 * 
	 * @return Amount of registered streams
	 */
	public int getStreamCount() {
		return streams.size();
	}
	/**
	 * Get the id of the stream based on the index in the hashmap
	 * 
	 * @param index The index in the hashmap to retrieve
	 * @return The ID of the stream on the index position or empty of bad index
	 */
	public String getStreamID( int index ) {
		if( index ==-1 || index >= streams.size() )
			return "";
		return (String)streams.keySet().toArray()[index];
	}
	/* ************************************************************************************************* */
	/**
	 * Reload the settings of a stream and re-initialize
	 * 
	 * @param id ID of the stream to reload
	 * @return True if reload was successful
	 */
	public String reloadStream( String id ) {

		Logger.info("Reloading "+id+ " from "+ settingsPath.toAbsolutePath());
		if(Files.notExists(settingsPath)){
			Logger.error("Failed to read xml file at "+ settingsPath.toAbsolutePath());
			return "! Failed to read xml";
		}

		var childOpt = XMLfab.withRoot(settingsPath,"dcafs","streams").getChild("stream","id",id);
		var baseOpt = getStream(id);
		if( childOpt.isEmpty() )
			return "! No stream named "+id+" found.";

		if( baseOpt.isPresent() ){ // meaning reloading an existing one
			var str = baseOpt.get();
			str.disconnect();
			str.getValStore().ifPresent( store -> store.removeRealtimeValues(rtvals));
			str.readFromXML(childOpt.get());
			str.getValStore().ifPresent( store -> store.shareRealtimeValues(rtvals));
			str.reconnectFuture = scheduler.schedule( new DoConnection( str ), 0, TimeUnit.SECONDS );
			return "Reloaded and trying to reconnect";
		}else{
			var str = addStreamFromXML(childOpt.get());
			str.getValStore().ifPresent( store -> store.shareRealtimeValues(rtvals));
			return "Loading new stream.";
		}
	}
	public boolean reloadStore( String id ){
		var baseOpt = getStream(id);
		// meaning reloading an existing one
		return baseOpt.map(baseStream -> baseStream.reloadStore(settingsPath, rtvals)).orElse(false);
	}
	/* ***************************** A D D I N G C H A N N E L S ******************************************/
	/**
	 * Add the streams by reading the settings.xml
	 * 
	 * @param settingsPath The path to the settings.xml
	 */
	public void readSettingsFromXML( Path settingsPath ) {

		if( XMLtools.readXML(settingsPath).isEmpty())
			return;

		this.settingsPath=settingsPath;

		if( !streams.isEmpty()){
			streams.values().forEach(BaseStream::disconnect);
		}
		streams.values().forEach( s -> s.getValStore().ifPresent( store -> store.removeRealtimeValues(rtvals)));
		streams.clear(); // Clear out before the reread

		XMLtools.getFirstElementByTag( settingsPath, "streams").ifPresent( ele -> {
			retryDelayIncrement = XMLtools.getChildIntValueByTag(ele, "retrydelayincrement", 5);
			retryDelayMax = XMLtools.getChildIntValueByTag(ele, "retrydelaymax", 60);

			for( Element el : XMLtools.getChildElements( ele, XML_CHILD_TAG)){
				BaseStream bs = addStreamFromXML(el);
				bs.getValStore().ifPresent( store -> store.shareRealtimeValues(rtvals));
				streams.put(bs.id().toLowerCase(), bs);
			}
		});
	}
	/**
	 * Add a single channel from an XML element
	 * 
	 * @param stream The element containing the channel information
	 */
	public BaseStream addStreamFromXML( Element stream ){

		switch (stream.getAttribute("type").toLowerCase()) {
			case "tcp", "tcpclient" -> {
				TcpStream tcp = new TcpStream(dQueue, stream);
				tcp.setEventLoopGroup(eventLoopGroup);
				tcp.addListener(this);
				bootstrapTCP = tcp.setBootstrap(bootstrapTCP);
				tcp.reconnectFuture = scheduler.schedule(new DoConnection(tcp), 0, TimeUnit.SECONDS);
				return tcp;
			}
			case "udp", "udpclient" -> {
				UdpStream udp = new UdpStream(dQueue, stream);
				udp.setEventLoopGroup(eventLoopGroup);
				udp.addListener(this);
				bootstrapUDP = udp.setBootstrap(bootstrapUDP);
				udp.reconnectFuture = scheduler.schedule(new DoConnection(udp), 0, TimeUnit.SECONDS);
				return udp;
			}
			case "udpserver" -> {
				UdpServer serv = new UdpServer(dQueue, stream);
				serv.setEventLoopGroup(eventLoopGroup);
				serv.addListener(this);
				serv.reconnectFuture = scheduler.schedule(new DoConnection(serv), 0, TimeUnit.SECONDS);
				return serv;
			}
			case "serial" -> {
				SerialStream serial = new SerialStream(dQueue, stream);
				serial.setEventLoopGroup(eventLoopGroup);
				if (serial.readerIdleSeconds != -1) {
					scheduler.schedule(new ReaderIdleTimeoutTask(serial), serial.readerIdleSeconds, TimeUnit.SECONDS);
				}
				serial.addListener(this);
				serial.reconnectFuture = scheduler.schedule(new DoConnection(serial), 0, TimeUnit.SECONDS);
				return serial;
			}
			case "modbus" -> {
				if (XMLtools.hasChildByTag(stream, "address")) { // Address means tcp
					ModbusTCPStream mbtcp = new ModbusTCPStream(dQueue, stream);
					mbtcp.setEventLoopGroup(eventLoopGroup);
					mbtcp.addListener(this);
					bootstrapTCP = mbtcp.setBootstrap(bootstrapTCP);
					mbtcp.reconnectFuture = scheduler.schedule(new DoConnection(mbtcp), 0, TimeUnit.SECONDS);
					return mbtcp;
				} else {
					ModbusStream modbus = new ModbusStream(dQueue, stream);
					modbus.setEventLoopGroup(eventLoopGroup);
					modbus.addListener(this);
					modbus.reconnectFuture = scheduler.schedule(new DoConnection(modbus), 0, TimeUnit.SECONDS);
					return modbus;
				}
			}
			case "multiplex" -> {
				MultiStream mStream = new MultiStream(dQueue, stream);
				mStream.setEventLoopGroup(eventLoopGroup);
				if (mStream.readerIdleSeconds != -1) {
					scheduler.schedule(new ReaderIdleTimeoutTask(mStream), mStream.readerIdleSeconds, TimeUnit.SECONDS);
				}
				mStream.addListener(this);
				mStream.reconnectFuture = scheduler.schedule(new DoConnection(mStream), 0, TimeUnit.SECONDS);
				return mStream;
			}
			case "local" -> {
				LocalStream local = new LocalStream(dQueue, stream);
				local.setEventLoopGroup(eventLoopGroup);
				local.addListener(this);
				if (local.readerIdleSeconds != -1) {
					scheduler.schedule(new ReaderIdleTimeoutTask(local), local.readerIdleSeconds, TimeUnit.SECONDS);
				}
				local.reconnectFuture = scheduler.schedule(new DoConnection(local), 0, TimeUnit.SECONDS);
				return local;
			}
			default -> Logger.error("No such type defined");
		}
		return null;
	}
	/**
	 * Stores the settings of a stream to the settings.xml. Writing isn't done if the address is already in use by a different stream.
	 * If a stream with the name is already present, unless overwrite = true writing is aborted
	 * @param id The id of the stream to write
	 * @param overwrite If true an existing element with that id will be removed
	 * @return True if ok, false if failed or aborted
	 */
	public boolean addStreamToXML( String id, boolean overwrite ){
		BaseStream stream = streams.get(id.toLowerCase());
		if( stream == null){
			Logger.warn("No such stream to write to xml "+id);
			return false;
		}

		// Check if it already exists (based on id and address?)
		XMLfab fab = XMLfab.withRoot(settingsPath, "dcafs",XML_PARENT_TAG);
		boolean exists = fab.hasChild(XML_CHILD_TAG, "id", stream.id() ).isPresent();

		if( exists && !overwrite ){
			Logger.warn("Already such stream ("+id+") in the settings.xml, not overwriting");
			return false;
		}
		stream.writeToXML(fab);
		return fab.build();
	}
	/* ************************************************************************************************* **/
	/**
	 * Class that handles making a connection to a channel
	 */
	public class DoConnection implements Runnable {
		
		BaseStream base;

		public DoConnection( BaseStream base ){
			this.base = base;
			base.reconnecting=true;
		}		
		@Override
		public void run() {	
			try{
				if( base==null) {
					Logger.error("Can't reconnect if the object isn't valid");
					return;
				}
				base.disconnect();
				if( base.connect() ){
					base.reconnecting=false;
					return; //if Ok, nothing else to do?
				} 
			
				int delay = retryDelayIncrement*(base.connectionAttempts+1);
				if( delay > retryDelayMax )
					delay = retryDelayMax;
				Logger.error( "Failed to connect to "+base.id()+", scheduling retry in "+delay+"s. ("+base.connectionAttempts+" attempts)" );
				String device = base.id().replace(" ","").toLowerCase();
				if( issues!=null )
					issues.addIfNewAndStart(device+".conlost", "Connection lost to "+base.id());
				base.reconnectFuture = scheduler.schedule( new DoConnection( base ), delay, TimeUnit.SECONDS );
			} catch (Exception ex) {		
				Logger.error( "Connection thread interrupting while trying to connect to "+base.id());
				Logger.error( ex );
			}
		}
	}
	/* ************************** * C H E C K I N G   S T R E A M S  ************************************/
	/**
	 * Check if the stream is still ok/connected and maybe reconnect
	 * @param id The stream to check
	 * @param reconnect If true and not connected, a reconnect attempt will be made
	 * @return True if ok 
	 */
	public boolean isStreamOk( String id, boolean reconnect ){
		BaseStream base = streams.get(id.toLowerCase());
		if( base == null){
			Logger.warn("Couldn't find stream: "+id+" in list of "+streams.size());
			return false;
		}
		boolean alive = base.isConnectionValid();
		if( !alive && reconnect )
			this.reloadStream(id);
		return alive;
	}
	/**
	 * Check if the stream is still ok/connected
	 * @param id The stream to check
	 * @return True if the stream is ok/connected
	 */
	public boolean isStreamOk( String id ){
		return isStreamOk(id,false);
	}

	/* ***************************************************************************************************** */
	@Override
	public String replyToCommand(String cmd, String args, Writable wr, boolean html) {
		String find = cmd.toLowerCase().replaceAll("\\d+","_");
		return switch( find ) {
			case "ss", "streams" -> replyToStreamCommand(args, html);
			case "raw","stream" -> {
				var res = addForwarding(args,wr);
				yield (res?"":"! ")+"Request for "+cmd+":"+args+" "+(res?"ok":"failed");
			}
			case "s_","h_" -> doSorH( cmd,args );
			case "","stop" -> removeWritable(wr)?"Ok.":"";
			default -> "Unknown Command";
		};
	}
	private static void addBaseToXML( XMLfab fab, String id, String type ){
		fab.addChild("stream").attr("id", id).attr("type", type).down();
		fab.addChild("eol", "crlf");
	}
	/**
	 * The streampool can give replies to certain predetermined questions
	 * @param request The question to ask
	 * @param html Whether the answer should use html or regular line endings
	 * @return The answer or Unknown Command if the question wasn't understood
	 */
	public String replyToStreamCommand(String request, boolean html ){

		String nl = html?"<br>":"\r\n";

		String[] cmds = request.split(",");

		StringJoiner join = new StringJoiner(nl);
		BaseStream stream;
		XMLfab fab;

		var dig = XMLdigger.goIn(settingsPath,"dcafs").peekAt("streams");
		if( !dig.hasValidPeek()) {
			XMLfab.alterDigger(dig).ifPresent( x->x.addChild("streams").build());
		}
		dig.goDown("streams");
		if( cmds.length==1){
			switch (cmds[0]) {
				case "?" -> {
					String cyan = html ? "" : TelnetCodes.TEXT_CYAN;
					String green = html ? "" : TelnetCodes.TEXT_GREEN;
					String ora = html ? "" : TelnetCodes.TEXT_ORANGE;
					String reg = html ? "" : TelnetCodes.TEXT_YELLOW + TelnetCodes.UNDERLINE_OFF;
					join.add(TelnetCodes.TEXT_RESET + ora + "Notes" + reg)
							.add("-> ss: and streams: do the same thing")
							.add("-> Every stream has at least:")
							.add("   - a unique id which is used to identify it, lowercase is enforced")
							.add("   - an eol (end of line), the default is crlf")
							.add("   - ...");
					join.add("").add(cyan + "Add new streams" + reg)
							.add(green + " ss:addtcp,id,ip:port " + reg + "-> Add a TCP stream to xml and try to connect")
							.add(green + " ss:addudp,id,ip:port " + reg + "-> Add a UDP stream to xml and connect")
							.add(green + " ss:addserial,id,port,baudrate" + reg + " -> Add a serial stream to xml and try to connect")
							.add(green + " ss:addlocal,id,source " + reg + "-> Add a internal stream that handles internal data");
					join.add("").add(cyan + "Info about all streams" + reg)
							.add(green + " ss:buffers " + reg + "-> Get confirm buffers.")
							.add(green + " ss:status " + reg + "-> Get streamlist.")
							.add(green + " ss:requests " + reg + "-> Get an overview of all the datarequests held by the streams");
					join.add("").add(cyan + "Alter the stream settings" + reg)
							.add(green + " ss:id,ttl,value " + reg + "-> Alter the ttl")
							.add(green + " ss:id,eol,value " + reg + "-> Alter the eol string")
							.add(green + " ss:id,baudrate,value " + reg + "-> Alter the baudrate of a serial/modbus stream")
							.add(green + " ss:id,addwrite,when,data " + reg + "-> Add a triggered write, possible when are hello (stream opened) and wakeup (stream idle)")
							.add(green + " ss:id,addcmd,when,data " + reg + "-> Add a triggered cmd, options for 'when' are open,idle,!idle,close")
							.add(green + " ss:id,echo,on/off " + reg + "-> Sets if the data received on this stream will be returned to sender");
					join.add("").add(cyan + "Route data from or to a stream" + reg)
							.add(green + " ss:forward,source,id " + reg + "-> Forward the data from a source to the stream, source can be any object that accepts a writable")
							.add(green + " ss:connect,id1,if2 " + reg + "-> Data is interchanged between the streams with the given id's")
							.add(green + " ss:id,send,data(,reply) " + reg + "-> Send the given data to the id with optional reply");
					return join.toString();
				}
				case "reload" -> {
					readSettingsFromXML(settingsPath);
					return "Settings reloaded.";
				}
				case "buffers" -> {
					return getConfirmBuffers();
				}
				case "requests" -> {
					join.setEmptyValue("! No requests yet.");
					streams.values().stream().filter(base -> base.getRequestsSize() != 0)
							.forEach(x -> join.add(x.id() + " -> " + x.listTargets()));
					return join.toString();
				}
				case "status" -> {
					return getStatus();
				}
				default -> {
					return "! No such cmd in ss: " + cmds[0];
				}
			}
		}else if( Arrays.asList(NEWSTREAM).contains(cmds[0]) ){ // Meaning it's an add cmd
			cmds[1]=cmds[1].toLowerCase();
			dig.peekAt("stream","id",cmds[1]);
			if( dig.hasValidPeek())
				return "! Already a stream with that id, try something else?";

			var fabOpt = XMLfab.alterDigger(dig);
			if( fabOpt.isEmpty())
				return "! Failed to create fab";

			fab = fabOpt.get();
			var type = cmds[0].substring(3);

			BaseStream base;
			switch (type) {
				case "tcp", "udpclient" -> {
					if (cmds.length != 3)
						return "! Not enough arguments: ss:" + cmds[0] + ",id,ip:port";
					addBaseToXML(fab, cmds[1], type);
					fab.addChild("address", cmds[2])
							.build();

					base = addStreamFromXML(fab.getCurrentElement());
				}
				case "serial", "modbus" -> {
					if (cmds.length < 3)
						return "! Not enough arguments: ss:" + cmds[0] + ",id,port(,baudrate)";
					addBaseToXML(fab, cmds[1], type);
					fab.addChild("port", cmds[2]);
					fab.addChild("serialsettings", (cmds.length == 4 ? cmds[3] : "19200") + ",8,1,none")
							.build();
					base = addStreamFromXML(fab.getCurrentElement());
				}
				case "udpserver" -> {
					if (cmds.length != 3) // Make sure we got the correct amount of arguments
						return "! Wrong amount of arguments -> ss:addudpserver,id,port";
					new UdpServer(cmds[1], Integer.parseInt(cmds[2]), dQueue);
					return "Started udp server (probably)";
				}
				case "local"->{
					if (cmds.length != 3) // Make sure we got the correct amount of arguments
						return "! Wrong amount of arguments -> ss:addlocal,id,source";
					LocalStream local = new LocalStream(cmds[1], cmds[2], dQueue);
					local.addListener(this);
					streams.put(cmds[1], local);
					addStreamToXML(cmds[1], true);
					return "Local stream added";
				}
				default -> {
					return "! Invalid option";
				}
			}
			if( base != null){
				try{
					base.reconnectFuture.get(2,TimeUnit.SECONDS);
					streams.put( cmds[1], base );
					addStreamToXML(cmds[1],true);
				}catch(CancellationException | ExecutionException | InterruptedException | TimeoutException e){
					return "! Failed to connect.";
				}
				return "Connected to "+base.id()+", use 'raw:"+base.id()+"' to see incoming data.";
			}
			return "! Failed to read stream from XML";
		}else{ // Meaning it's an alter/use cmd
			cmds[0] = cmds[0].toLowerCase();

			dig.peekAt("stream","id",cmds[0]);
			if( !dig.hasValidPeek())
				return "! No such stream yet.";
			dig.goDown("stream","id",cmds[0]);

			var fabOpt = XMLfab.alterDigger(dig);
			if( fabOpt.isEmpty() )
				return "! Failed to find in xml";
			fab = fabOpt.get();

			stream = streams.get(cmds[0]);

			switch (cmds[1]) {
				case "send" -> {
					if (cmds.length < 3)
						return "! Not enough arguments given: ss:id,send,data";
					String written = writeToStream(cmds[1], cmds[2], cmds.length > 3 ? cmds[3] : "");
					if (written.isEmpty())
						return "! Failed to write data";
					return "Data written: " + written;
				}
				case "recon" -> {
					if (stream != null) {
						stream.disconnect();
						if (stream.reconnectFuture.getDelay(TimeUnit.SECONDS) < 0) {
							Logger.info("Already scheduled to reconnect");
							stream.reconnectFuture = scheduler.schedule(new DoConnection(stream), 5, TimeUnit.SECONDS);
						}
						try {
							stream.reconnectFuture.get(2, TimeUnit.SECONDS);
						} catch (CancellationException | ExecutionException | InterruptedException |
								 TimeoutException e) {
							return "! Failed to connect.";
						}
						return "Reconnected to " + stream.id() + ", use 'raw:" + stream.id() + "' to see incoming data.";
					}
					return "! Couldn't find that stream";
				}
				case "reload" -> {
					return reloadStream(cmds[0]);
				}
				case "clearrequests" -> {
					return "Targets cleared:" + stream.clearTargets();
				}
				case "baudrate" -> {
					if (!(stream instanceof SerialStream))
						return "! Not a Serial port, no baudrate to change";
					((SerialStream) stream).setBaudrate(Tools.parseInt(cmds[2], -1));
					fab.alterChild("serialsettings", ((SerialStream) stream).getSerialSettings()).build();
					return "Altered the baudrate";
				}
				case "label" -> {
					if( cmds[2].isEmpty()||cmds[2].equalsIgnoreCase("void")){
						fab.removeChild("label").build();
						return "Label removed";
					}
					fab.alterChild("label", cmds[2]).build();
					stream.setLabel(cmds[2]);
					return "Label altered to " + cmds[2];
				}
				case "ttl" -> {
					if (!cmds[2].equals("-1")) {
						stream.setReaderIdleTime(TimeTools.parsePeriodStringToSeconds(cmds[2]));
						fab.alterChild("ttl", cmds[2]);
					} else {
						fab.removeChild("ttl");
					}
					fab.build();
					reloadStream(cmds[0]);
					return "TTL altered";
				}
				case "eol" -> {
					fab.alterChild("eol", cmds[2]);
					fab.build();
					reloadStream(cmds[0]);
					return "EOL altered";
				}
				case "addwrite", "addcmd" -> {
					if (cmds.length < 3) {
						return "! Wrong amount of arguments -> ss:id," + cmds[1] + ",when:cmd";
					}
					if (cmds[2].split(":").length == 1)
						return "! Doesn't contain a proper when:data pair";
					var data = request.substring(request.indexOf(":") + 1);
					var when = cmds[2].substring(0, cmds[2].indexOf(":"));
					if (Arrays.asList(WHEN).contains(when)) {
						fab.addChild(cmds[1].substring(3), data).attr("when", when).build();
						stream.addTriggeredAction(when, data);
						return "Added triggered " + cmds[1].substring(3) + " to " + cmds[0];
					}
					return "! Failed to add, invalid when";
				}
				case "echo" -> {
					if (cmds.length != 3) // Make sure we got the correct amount of arguments
						return "! Wrong amount of arguments -> ss:id,echo,on/off";
					var state = Tools.parseBool(cmds[2], false);
					if (state) {
						fab.alterChild("echo", "on");
						stream.enableEcho();
					} else {
						fab.removeChild("echo");
						stream.disableEcho();
					}
					fab.build();
					return "Echo altered";
				}
				case "reloadstore"-> {
					if (reloadStore(cmds[0]))
						return "Reloaded the store of " + cmds[0];
					return "! Failed to reload the store of " + cmds[0];
				}
				case "tunnel" -> {
					if (cmds.length != 3) // Make sure we got the correct amount of arguments
						return "! Wrong amount of arguments -> ss:fromid,tunnel,toid";

					int s1_ok = getWritable(cmds[1]).map(wr -> addForwarding(cmds[2], wr) ? 1 : 0).orElse(-1);
					if (s1_ok != 1)
						return s1_ok == -1 ? "! No writable " + cmds[1] : "! No such source " + cmds[2];

					int s2_ok = getWritable(cmds[2]).map(wr -> addForwarding(cmds[0], wr) ? 1 : 0).orElse(-1);
					if (s2_ok != 1)
						return s2_ok == -1 ? "! No writable " + cmds[1] : "! No such source " + cmds[1];

					return "Tunnel established between " + cmds[1] + " and " + cmds[2];
				}
				case "link" -> {
					if (cmds.length != 3) // Make sure we got the correct amount of arguments
						return "! Wrong amount of arguments -> ss:id,link,toid";
					dQueue.add(Datagram.system(cmds[2]).writable(getWritable(cmds[0]).get()));
					return "Tried enabling the forward from " + cmds[0] + " to " + cmds[2];
				}
				default -> {
					return "! No such subcommand for ss:id :"+cmds[1];
				}
			}
		}
	}

	/**
	 * Listener stuff
	 *
	 */
	@Override
	public void notifyIdle( String id ) {
		String device = id.replace(" ", "").toLowerCase(); // Remove spaces
		issues.addIfNewAndStart(device+".conidle", "TTL passed for "+id);
		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.IDLE));
		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.WAKEUP));
	}
	@Override
	public boolean notifyActive(String id ) {
		String device = id.replace(" ", "").toLowerCase(); // Remove spaces
		issues.addIfNewAndStop(device+".conidle", "TTL passed for "+id);
		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.IDLE_END));
		return true;
	}
	@Override
	public void notifyOpened( String id ) {
		String device = id.replace(" ", "").toLowerCase(); // Remove spaces
		issues.addIfNewAndStop(device+".conlost", "Connection lost to "+id);

		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.HELLO));
		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.OPEN));

	}
	@Override
	public void notifyClosed( String id ) {
		String device = id.replace(" ", "").toLowerCase(); // Remove spaces
		issues.addIfNewAndStart(device+".conlost", "Connection lost to "+id);
		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.CLOSE));
	}
	@Override
	public boolean requestReconnection( String id ) {
		BaseStream bs = streams.get(id.toLowerCase());
		if( bs == null){
			Logger.error("Bad id given for reconnection request: "+id);
			return false;
		}
		Logger.error("Requesting reconnect for "+bs.id());
		if( bs.reconnectFuture==null || bs.reconnectFuture.getDelay(TimeUnit.SECONDS) < 0 ){
			bs.reconnectFuture = scheduler.schedule( new DoConnection( bs ), 5, TimeUnit.SECONDS );
			return true;
		}
		return false;
	}

	/* 	--------------------------------------------------------------------	*/
	public boolean addForwarding(String cmd, Writable writable) {
		var remove = cmd.startsWith("!");
		if( writable != null ){
			Logger.info("Received "+(remove?"removal":"data request")+" from "+writable.id()+" for "+cmd);
		}else if( cmd.startsWith("email") ){
			Logger.info("Received request through email for "+cmd);
		}

		if( remove ) {
			getStream(cmd.substring(1)).ifPresent( bs -> bs.removeTarget(writable));
		}else{
			if( !getStream(cmd).map( bs -> bs.addTarget(writable) ).orElse(false) ) {
				var stream = streams.entrySet().stream().filter(set -> set.getKey().startsWith(cmd))
						.map(Map.Entry::getValue).findFirst();
				if(stream.isEmpty())
					return false;
				stream.get().addTarget(writable);
			}
		}
		return true;
	}


	private String doSorH( String cmd, String args ){
		return switch( args ){
			case "??" -> "Sx:y -> Send the string y to stream x";
			case "" -> "No use sending an empty string";
			default -> {
				var stream = getStreamID( Tools.parseInt( cmd.substring(1), 0 ) -1);
				if( !stream.isEmpty()){
					args = args.replace("<cr>", "\r").replace("<lf>", "\n"); // Normally the delimiters are used that are chosen in settings file, extra can be added

					var written = "";
					if( cmd.startsWith("h")){
						written = writeBytesToStream(stream, Tools.fromHexStringToBytes(args) );
					}else{
						written = writeToStream(stream, args, "" );
					}
					if( !written.isEmpty() )
						yield "Sending '"+written+"' to "+stream;
					yield "! Failed to send "+args+" to "+stream;

				}else{
					yield switch( getStreamCount() ){
						case 0 -> "! No streams active to send data to.";
						case 1 ->"! Only one stream active. S1:"+getStreamID(0);
						default -> "! Invalid number chosen! Must be between 1 and "+getStreamCount();
					};
				}
			}
		};
	}

	/**
	 * Remove the given writable from the various sources
	 * @param wr The writable to remove
	 * @return True if any were removed
	 */
	public boolean removeWritable(Writable wr) {
		if( wr==null)
			return false;

		boolean removed=false;
		for( BaseStream bs : streams.values() ){
			if( bs.removeTarget(wr) )
				removed=true;
		}
		return removed;
	}
	@Override
	public void collectorFinished(String id, String message, Object result) {
		String[] ids = id.split(":");
		switch (ids[0]) {
			case "confirm" -> {
				confirmCollectors.remove(ids[1]);
				if (confirmCollectors.isEmpty())
					Logger.info("All confirm requests are finished");
			}
			case "math" -> dQueue.add(Datagram.system("store:" + message + "," + result));
			default -> Logger.error("Unknown Collector type: " + id);
		}
	}

	/**
	 * Remove the confirmCollector with the given id from the hashmap
	 * @param id The id to look for
	 */
	public void removeConfirm(String id){
		if( confirmCollectors.values().removeIf( cc -> cc.id().equalsIgnoreCase(id)) ){
			Logger.info("ConfirmCollector removed: "+id);
		}else{
			Logger.info("ConfirmCollector not found: "+id);
		}
	}
	/**
	 * Class that checks if age of last data is higher than ttl, if so issue the idle cmds.
	 * Reschedule the task for either ttl time or difference between ttl and time since last data
	 * Code copied from Netty and altered
	 */
	private final class ReaderIdleTimeoutTask implements Runnable {

		private final BaseStream stream;

		ReaderIdleTimeoutTask(BaseStream stream) {
			this.stream = stream;
		}

		@Override
		public void run() {

			if( stream==null) // No use scheduling timeout checks if the stream isn' valid
				return;

			if (!stream.isConnectionValid()) { // No use scheduling timeout if there's no connection
				Logger.warn(stream.id()+" -> Connection invalid, waiting for reconnect");
				requestReconnection(stream.id());
				scheduler.schedule(this, stream.readerIdleSeconds, TimeUnit.SECONDS);
				return;
			}
			long currentTime = Instant.now().toEpochMilli();
			long lastReadTime = stream.getLastTimestamp();
			long nextDelay = stream.readerIdleSeconds *1000 - (currentTime - lastReadTime);

			// If the next delay is less than longer ago than a previous idle
			if (nextDelay <= 0 ){
				// Reader is idle - set a new timeout and notify the callback.
				scheduler.schedule(this, stream.readerIdleSeconds, TimeUnit.SECONDS);
				if( nextDelay > -1000*stream.readerIdleSeconds) { // only apply this the first time
					Logger.warn(stream.id()+" is idle for "+stream.readerIdleSeconds+"s");
					notifyIdle(stream.id());
				}
			} else {
				// Read occurred before the timeout - set a new timeout with shorter delay.
				scheduler.schedule(this, nextDelay, TimeUnit.MILLISECONDS);
			}
		}
	}
}
