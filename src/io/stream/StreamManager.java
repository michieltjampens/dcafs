package io.stream;

import das.Commandable;
import das.Core;
import das.Paths;
import io.Writable;
import io.collector.CollectorFuture;
import io.collector.ConfirmCollector;
import io.collector.StoreCollector;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.stream.serialport.ModbusStream;
import io.stream.serialport.MultiStream;
import io.stream.serialport.SerialStream;
import io.stream.tcp.ModbusTCPStream;
import io.stream.tcp.TcpServerStream;
import io.stream.tcp.TcpStream;
import io.stream.udp.UdpServer;
import io.stream.udp.UdpStream;
import org.apache.commons.lang3.StringUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.LookAndFeel;
import util.data.vals.Rtvals;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The class holds all the information required about a datasource to acquire
 * data from it. It uses the internal class StreamDescriptor to hold this
 * information. All connections are made using the Netty 4.x library.
 */
public class StreamManager implements StreamListener, CollectorFuture, Commandable {

	// Netty
	private Bootstrap bootstrapTCP;        // Bootstrap for TCP connections
	private Bootstrap bootstrapUDP;          // Bootstrap for UDP connections

	private int retryDelayMax = 30;            // The minimum time between reconnection attempts
	private int retryDelayIncrement = 5;    // How much the delay increases between attempts

	private final HashMap<String, ConfirmCollector> confirmCollectors = new HashMap<>();

	private final LinkedHashMap<String,BaseStream> streams = new LinkedHashMap<>();

	private final Rtvals rtvals;

	static String[] WHEN={"open","close","idle","!idle","hello","wakeup","asleep"};
	static String[] NEWSTREAM = {"addserial", "addmodbus", "addtcp", "addudpclient", "addudp", "addlocal", "addudpserver", "addtcpserver"};

	private final ArrayList<StoreCollector> stores = new ArrayList<>();
	final EventLoopGroup eventLoopGroup = new NioEventLoopGroup(3, new DefaultThreadFactory("StreamManager-group"));

	public StreamManager(Rtvals rtvals) {
		this.rtvals=rtvals;
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
		return Optional.ofNullable(streams.get(id.toLowerCase()))
				.filter(bs -> bs.isWritable() && bs.isConnectionValid());
	}

	/**
	 * Get the Writable of the stream associated with the id as an optional
	 * @param id The stream to look for
	 * @return The optional writable or an empty one
	 */
	public Optional<Writable> getWritable(String id ){
		// Get the stream, check if it's writable if so get the writable or return an empty optional if not
		return getStream(id).filter(BaseStream::isWritable).map(bs -> (Writable) bs);
	}

	/* **************************** S T A T U S ************************************************************************/

	/**
	 * Request a string holding info regarding the status of each connection
	 *
	 * @return A string holding info regarding the status of each connection.
	 */
	public String getStatus() {

		if( streams.isEmpty())
			return "No streams defined (yet)";

		StringJoiner join = new StringJoiner("");

		int infoLength = 0;
		for( BaseStream stream : streams.values() ){
			infoLength = Math.max(stream.getInfo().length(),infoLength);
		}
		infoLength += 3;
		for( BaseStream stream : streams.values() ){
			long ttl = Instant.now().toEpochMilli() - stream.getLastTimestamp();
			if( !stream.isConnectionValid() ){
				join.add("!! NC ");
			}else if (ttl > stream.readerIdleSeconds *1000 && stream.readerIdleSeconds != -1) {
				join.add("!! ");
			}

			join.add(stream.getInfo()).add(" ".repeat(infoLength-stream.getInfo().length()));
			if( stream instanceof TcpServerStream tss) {
				join.add(tss.getClientCount() + " client(s)").add("\r\n");
			} else if (stream instanceof UdpStream) {
				join.add("(send only)").add("\r\n");
			}else if (stream.getLastTimestamp() == -1) {
				join.add("No data yet! ");
				join.add(" [").add(TimeTools.convertPeriodToString(stream.readerIdleSeconds, TimeUnit.SECONDS)).add("]").add("\r\n");
			} else {
				join.add(TimeTools.convertPeriodToString(ttl, TimeUnit.MILLISECONDS)).add(" [");
				join.add(TimeTools.convertPeriodToString(stream.readerIdleSeconds, TimeUnit.SECONDS)).add("]").add("\r\n");
			}
		}
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
		for (String id : streams.keySet())
			join.add( "S" + (a++) + ":" + id);

		return join.toString();
	}
	public Stream<String> getStreamIDs(){
		return streams.keySet().stream();
	}
	/**
	 * Retrieve the contents of the confirm/reply buffer from the various streams
	 * @return Contents of the confirm/reply buffer from the various streams
	 */
	public String getConfirmBuffers() {
		StringJoiner join = new StringJoiner("\r\n");
		join.setEmptyValue("No buffers used yet.");
		confirmCollectors.forEach( (id, cw) -> join.add(">>"+cw.id()).add(cw.getStored().isEmpty() ? " empty" : cw.getStored()));
		return join.toString();
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
	/* *************************************  S E T U P **************************************************************/

	/**
	 * Disconnect all streams
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
		if (streamOpt.isEmpty()) {
			Logger.error("Didn't find stream named " + id);
			return "";
		}

		BaseStream stream = streamOpt.get();
		if (!stream.isWritable()) {
			Logger.error("The stream " + id + " is readonly.");
			return "";
		}
		if (!stream.isConnectionValid()) {
			Logger.error("No connection to stream named " + stream);
			return "";
		}
		Writable wr = (Writable) stream;
		wr.writeBytes(txt);
		return new String(txt);
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

	private static String convertHexes(String data) {
		Pattern pattern = Pattern.compile("\\\\h\\([a-fA-F0-9Xx ,]+\\)");
		Matcher matcher = pattern.matcher(data);

		while (matcher.find()) {
			var found = matcher.group();
			var sub = found.substring(3, found.length() - 1);
			var rep = new String(Tools.fromHexStringToBytes(sub));
			data = data.replace(found, rep);
		}
		return data;
	}
	/**
	 * Standard way of writing ascii data to a channel, with or without requesting a certain reply
	 * @param id The id of the stream to write to
	 * @param txt The ascii data to transmit
	 * @param reply The expected reply to transmit
	 * @return The string that was written or an empty string if failed
	 */
	public String writeToStream(String id, String txt, String reply) {

		if (txt.contains("\\h(")) {
			txt = convertHexes(txt);
			return writeBytesToStream(id, txt.getBytes());//Tools.fromHexStringToBytes(txt) );
		}

		Optional<BaseStream> streamOptional = getWritableStream(id);

		if (streamOptional.isEmpty()) {
			var bs = streams.get(id);
			if (bs == null) {
				Logger.error("No such stream " + id);
			} else if (!bs.isWritable()) {
				Logger.error("Found the stream " + id + ", but not writable");
			} else if (!bs.isConnectionValid()) {
				Logger.error("Found the writable stream " + id + ", but no valid connection");
			}
			return "";
		}

		BaseStream stream = streamOptional.get();
		ConfirmCollector confirmCollector = confirmCollectors.get(id);

		if (confirmCollector != null && confirmCollector.isEmpty()) {
			confirmCollectors.remove(id);
			Logger.info("Removed empty ConfirmCollector " + id);
			confirmCollector = null;
		}

		if (confirmCollector == null) {// If none exists yet
			confirmCollector = withoutConfirmCollector(txt, stream, reply, id);
			if (confirmCollector == null)
				return noNeedConfirmCollector(txt, id, stream);
		}
		confirmCollector.addConfirm(txt.split(";"), reply);
		confirmCollectors.put(id, confirmCollector);
		return txt;
	}

	private ConfirmCollector withoutConfirmCollector(String txt, BaseStream stream, String reply, String id) {
		ConfirmCollector confirmCollector = null;
		if (txt.contains(";") || !reply.isEmpty()) {
			confirmCollector = new ConfirmCollector(id, 3, 3, (Writable) stream, eventLoopGroup);
			confirmCollector.addListener(this);
			if (!reply.isEmpty()) // No need to get data if we won't use it
				stream.addTarget(confirmCollector);
			confirmCollectors.put(stream.id(), confirmCollector);
		}
		return confirmCollector;
	}

	private String noNeedConfirmCollector(String txt, String id, BaseStream stream) {
		if (txt.indexOf("\\") < txt.length() - 2) {
			txt = Tools.fromEscapedStringToBytes(txt);
		}
		boolean written;
		boolean nullEnded = Tools.isNullEnded(txt);
		if (txt.endsWith("\\0") || nullEnded) {
			if (nullEnded)
				txt = txt.substring(0, txt.length() - 1);
			txt = StringUtils.removeEnd(txt, "\\0");
			written = ((Writable) stream).writeString(txt);
		} else {
			written = ((Writable) stream).writeLine("", txt);
		}
		if (!written)
			Logger.error("writeString/writeLine failed to " + id + " for " + txt);
		if (txt.getBytes()[0] == 27)
			txt = "ESC";
		return written ? txt : "";
	}
	/* ************************************************************************************************* */

	/**
	 * Reload the settings of a stream and re-initialize
	 *
	 * @param id ID of the stream to reload
	 * @return True if reload was successful
	 */
	public String reloadStream( String id ) {

		Logger.info("Reloading "+id+ " from "+ Paths.settings().toAbsolutePath());
		if(Files.notExists(Paths.settings())){
			Logger.error("Failed to read xml file at "+ Paths.settings().toAbsolutePath());
			return "! Failed to read xml";
		}

		var streamDig = XMLdigger.goIn(Paths.settings(),"dcafs","streams");
		streamDig.peekAt("stream","id",id);
		if( !streamDig.hasValidPeek())
			return "! No stream named "+id+" found, so can't reload.";

		streamDig.usePeek(); // now point to the stream node

		var baseOpt = getStream(id);
		if (baseOpt.isEmpty()) {
			addStreamFromXML(streamDig);
			return "Loading new stream.";
		}
		// meaning reloading an existing one
		var str = baseOpt.get();
		str.disconnect();
		str.readFromXML(streamDig);
		if (streamDig.hasPeek("store")) {
			streamDig.usePeek();
			addStore(streamDig.currentTrusted(), str);
		}
		if (!str.getType().contains("server"))
			eventLoopGroup.submit(() -> new DoConnection(str));
		return "Reloaded and trying to reconnect";
	}

	/**
	 * Reload the store of the specified stream, if it exists
	 * @param id The id of the stream that might contain a store
	 * @return True if reloaded
	 */
	public boolean reloadStore( String id ){
		// meaning reloading an existing one
		var streamDig = XMLdigger.goIn(Paths.settings(),"dcafs","streams");
		streamDig.digDown("stream","id",id);
		streamDig.digDown("store");
		if( streamDig.isValid() && getStream(id).isPresent()){
			return addStore(streamDig.currentTrusted(),getStream(id).get());
		}
		return false;
	}
	/* ***************************** A D D I N G  S T R E A M S  ******************************************/

	/**
	 * Add the streams by reading the settings.xml
	 */
	public void readSettingsFromXML( ) {

		if( XMLtools.readXML(Paths.settings()).isEmpty())
			return;

		if( !streams.isEmpty()){
			streams.values().forEach(BaseStream::disconnect);
		}
		// Make sure the rtvals created earlier are deleted in case they don't exist anymore
		stores.forEach( st -> st.getStore().ifPresent( s -> s.removeRealtimeValues(rtvals)));
		streams.clear(); // Clear out before the reread

		XMLdigger dig = XMLdigger.goIn(Paths.settings(),"dcafs","streams");
		if( !dig.isValid())
			return;

		retryDelayIncrement = dig.attr("retrydelayincrement", 5);
		retryDelayMax = dig.attr( "retrydelaymax", 90);

		if( !dig.hasPeek("stream"))
			return;

		for( var stream : dig.digOut("stream")){
			BaseStream bs = addStreamFromXML(stream);
			streams.put(bs.id().toLowerCase(), bs);
			if (stream.hasPeek("store")) {
				stream.usePeek();
				addStore(stream.currentTrusted(), bs);
			}
		}
	}

	/**
	 * Adds a store linked to a stream
	 *
	 * @param st The element of the store
	 * @param bs The stream to link it to
	 * @return True if ok
	 */
	private boolean addStore( Element st, BaseStream bs){
		if( !st.hasAttribute("group"))
			st.setAttribute("group",bs.id());
		if( !st.hasAttribute("id"))
			st.setAttribute("id",bs.id());
		// First make sure it doesn't exist yet
		StoreCollector sc;
		var stOpt = stores.stream().filter( s -> s.id().equals(bs.id())).findFirst();
		if( stOpt.isPresent() ){ // Already has a store for that stream
			sc = stOpt.get();
			bs.removeTarget(sc);
			if (sc.reload(XMLdigger.goIn(st), rtvals)) { // Rebuild store
				Logger.info( bs.id() +" -> Reloaded store");
			}else{
				Logger.error( bs.id() +" -> Failed to reload store");
				return false;
			}
		}else{
			sc = new StoreCollector(XMLdigger.goIn(st), rtvals);
			if (sc.getStore().isEmpty()) {
				Logger.error(bs.id()+" -> Failed to load store");
			}else {
				stores.add(sc); // Add it to the stores
			}
		}
		bs.addTarget(sc); // Make sure the forward is a target of the stream
		if (sc.needsDB()) { // If it contains db writing, ask for a link
			for (var dbInsert : sc.dbInsertSets()) { // Request the table insert object
				Core.addToQueue(Datagram.system("dbm:" + dbInsert[0] + ",tableinsert," + dbInsert[1]).payload(sc));
			}
		}
		return true;
	}
	/**
	 * Add a single stream from an XML element
	 *
	 * @param stream The element containing the stream information
	 */
	public BaseStream addStreamFromXML(XMLdigger stream) {

		var type = stream.attr("type", "").toLowerCase();
		return switch (type) {
			case "tcp", "tcpclient" -> addTcpFromStream(stream);
			case "tcpserver" -> addTcpServerFromStream(stream);
			case "udp", "udpclient" -> addUdpClientFromStream(stream);
			case "udpserver" -> addUdpServerFromStream(stream);
			case "serial" -> addSerialFromStream(stream);
			case "modbus" -> addModbusFromStream(stream);
			case "multiplex" -> addMultiplexFromStream(stream);
			case "local" -> addLocalFromStream(stream);
			default -> {
				Logger.error("No such type defined: " + type);
				yield null;
			}
		};
	}

	private TcpServerStream addTcpServerFromStream(XMLdigger stream) {
		TcpServerStream tcp = new TcpServerStream(stream);
		tcp.setEventLoopGroup(eventLoopGroup);
		tcp.connect();
		return tcp;
	}

	private UdpStream addUdpClientFromStream(XMLdigger stream) {
		UdpStream udp = new UdpStream(stream);
		udp.setEventLoopGroup(eventLoopGroup);
		udp.addListener(this);
		bootstrapUDP = udp.setBootstrap(bootstrapUDP);
		udp.reconnectFuture = eventLoopGroup.schedule(new DoConnection(udp), 0, TimeUnit.SECONDS);
		return udp;
	}

	private UdpServer addUdpServerFromStream(XMLdigger stream) {
		UdpServer serv = new UdpServer(stream);
		serv.setEventLoopGroup(eventLoopGroup);
		serv.addListener(this);
		serv.connect();
		return serv;
	}

	private MultiStream addMultiplexFromStream(XMLdigger stream) {
		MultiStream mStream = new MultiStream(stream);
		mStream.setEventLoopGroup(eventLoopGroup);
		if (mStream.readerIdleSeconds != -1) {
			eventLoopGroup.schedule(new ReaderIdleTimeoutTask(mStream), mStream.readerIdleSeconds, TimeUnit.SECONDS);
		}
		mStream.addListener(this);
		mStream.reconnectFuture = eventLoopGroup.schedule(new DoConnection(mStream), 0, TimeUnit.SECONDS);
		return mStream;
	}

	private BaseStream addTcpFromStream(XMLdigger stream) {
		TcpStream tcp = new TcpStream(stream);
		tcp.setEventLoopGroup(eventLoopGroup);
		tcp.addListener(this);
		bootstrapTCP = tcp.setBootstrap(bootstrapTCP);
		if (tcp.getReaderIdleTime() != -1) {
			eventLoopGroup.schedule(new ReaderIdleTimeoutTask(tcp), tcp.getReaderIdleTime(), TimeUnit.SECONDS);
		}
		tcp.reconnectFuture = eventLoopGroup.schedule(new DoConnection(tcp), 0, TimeUnit.SECONDS);
		return tcp;
	}

	private BaseStream addModbusFromStream(XMLdigger stream) {
		if (stream.hasPeek("address")) { // Address means tcp
			ModbusTCPStream mbtcp = new ModbusTCPStream(stream);
			mbtcp.setEventLoopGroup(eventLoopGroup);
			mbtcp.addListener(this);
			bootstrapTCP = mbtcp.setBootstrap(bootstrapTCP);
			mbtcp.reconnectFuture = eventLoopGroup.schedule(new DoConnection(mbtcp), 0, TimeUnit.SECONDS);
			return mbtcp;
		} else {
			ModbusStream modbus = new ModbusStream(stream);
			modbus.setEventLoopGroup(eventLoopGroup);
			modbus.addListener(this);
			modbus.reconnectFuture = eventLoopGroup.schedule(new DoConnection(modbus), 0, TimeUnit.SECONDS);
			return modbus;
		}
	}

	private BaseStream addSerialFromStream(XMLdigger stream) {
		SerialStream serial = new SerialStream(stream);
		serial.setEventLoopGroup(eventLoopGroup);
		if (serial.getReaderIdleTime() != -1) {
			eventLoopGroup.schedule(new ReaderIdleTimeoutTask(serial), serial.getReaderIdleTime(), TimeUnit.SECONDS);
		}
		serial.addListener(this);
		serial.reconnectFuture = eventLoopGroup.schedule(new DoConnection(serial), 0, TimeUnit.SECONDS);
		return serial;
	}

	private BaseStream addLocalFromStream(XMLdigger stream) {
		LocalStream local = new LocalStream(stream);
		local.setEventLoopGroup(eventLoopGroup);
		local.addListener(this);
		if (local.readerIdleSeconds != -1) {
			eventLoopGroup.schedule(new ReaderIdleTimeoutTask(local), local.readerIdleSeconds, TimeUnit.SECONDS);
		}
		local.reconnectFuture = eventLoopGroup.schedule(new DoConnection(local), 0, TimeUnit.SECONDS);
		return local;
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
					base.openedStamp = Instant.now().toEpochMilli();
					return; //if Ok, nothing else to do?
				}

				int delay = retryDelayIncrement*(base.connectionAttempts+1);
				if( delay > retryDelayMax )
					delay = retryDelayMax;

				if (LookAndFeel.isNthAttempt(base.connectionAttempts))
					Logger.error( "Failed to connect to "+base.id()+", scheduling retry in "+delay+"s. ("+base.connectionAttempts+" attempts)" );

				base.reconnectFuture = eventLoopGroup.schedule(new DoConnection(base), delay, TimeUnit.SECONDS);
			} catch (Exception ex) {
				Logger.error( "Connection thread interrupting while trying to connect to "+base.id());
				Logger.error( ex );
			}
		}
	}

	@Override
	public String replyToCommand(Datagram d) {
		var wr = d.getWritable();
		var cmd = d.cmd();
		var args = d.args();

		String find = d.cmd().toLowerCase().replaceAll("\\d+", "_");
		return switch( find ) {
			case "ss", "streams" -> replyToStreamCommand(d);
			case "raw","stream" -> {
				var res = addTargetRequest(args,wr);
				yield (res?"":"! ")+"Request for "+cmd+":"+args+" "+(res?"ok":"failed");
			}
			case "s_","h_" -> doSorH( cmd,args );
			case "","stop" -> removeWritable(wr)?"Ok.":"";
			default -> {
				int a=1;
				for( String id:streams.keySet() ){
					if( id.equalsIgnoreCase(cmd)){
						yield doSorH("S"+a,args);
					}
					a++;
				}
				yield "Unknown Command";
			}
		};
	}
	private static void addBaseToXML( XMLfab fab, String id, String type ){
		type = type.replace("client", "");
		fab.addChild("stream").attr("id", id).attr("type", type).down();
		fab.addChild("eol", "crlf"); //build done later
	}

	/**
	 * The streampool can give replies to certain predetermined questions
	 * @param d Original datagram with all info
	 * @return The answer or Unknown Command if the question wasn't understood
	 */
	public String replyToStreamCommand(Datagram d) {
		// Prepare the digger
		var dig = XMLdigger.goIn(Paths.settings(),"dcafs");
		if (!dig.hasPeek("streams"))
			XMLfab.alterDigger(dig).ifPresent( x->x.addChild("streams").build());

		dig.digDown("streams");

		String[] cmds = d.argList();
		if (cmds.length == 1)
			return doSingleCmd(cmds[0], d.asHtml());
		if (Arrays.asList(NEWSTREAM).contains(cmds[0])) // Meaning it's an add cmd
			return doAddStreamCmd(cmds,dig);
		// Meaning it's an alter/use cmd
		return doAlterUseCmd(cmds, d.getWritable(), dig);
	}
	private String doSingleCmd( String cmd, boolean html){
		return switch (cmd) {
			case "?" -> doHelpCmd(html);
			case "reload" -> {
				readSettingsFromXML();
				yield "Settings reloaded.";
			}
			case "buffers" ->  getConfirmBuffers();
			case "requests" -> {
				var join = new StringJoiner(html?"<br>":"\r\n");
				join.setEmptyValue("! No requests yet.");
				streams.values().stream().filter(base -> base.getRequestsSize() != 0)
						.forEach(x -> join.add(x.id() + " -> " + x.listTargets()));
				yield join.toString();
			}
			case "status" -> getStatus();
			case "" -> getStreamList(html);
			default ->  "! No such cmd in ss: " + cmd;
		};
	}

	private static String doHelpCmd(boolean html) {
		var help = new StringJoiner("\r\n");

		help.add("Manages all the streams: adding, checking, writing etc.");
		help.add("Add new streams")
				.add( "ss:addtcp,id,ip:port -> Add a TCP stream to xml and try to connect")
				.add("ss:addtcpserver,id,port -> Start TCP server and add to xml")
				.add("ss:addudp,id,ip:port -> Add a UDP stream to xml and try to connect")
				.add("ss:addudpserver,id,port -> Start a UDP server and add to xml")
				.add( "ss:addserial,id,port,baudrate -> Add a serial stream to xml and try to connect")
				.add( "ss:addlocal,id,source -> Add a internal stream that handles internal data");
		help.add("Info about all streams")
				.add( "ss -> Get a list of all streams with indexes for sending data")
				.add( "ss:buffers -> Get confirm buffers.")
				.add( "ss:status -> Get streamlist.")
				.add( "ss:requests -> Get an overview of all the datarequests held by the streams");
		help.add("Alter the stream settings")
				.add( "ss:id,ttl,value -> Alter the ttl")
				.add( "ss:id,eol,value -> Alter the eol string")
				.add( "ss:id,baudrate,value -> Alter the baudrate of a serial/modbus stream")
				.add("ss:id,addwrite,when,data -> Add a triggered write, possible when are hello (stream opened) and wakeup (stream idle)")
				.add("ss:id,addcmd,when,data -> Add a triggered cmd, options for 'when' are open,idle,!idle,close")
				.add( "ss:id,echo,on/off -> Sets if the data received on this stream will be returned to sender");
		help.add("Route data from or to a stream")
				.add("ss:streamid,request,requestcmd -> Requestcmd is the cmd you'd use in telnet to request the data, do this in name of the stream.")
				.add("ss:id1,tunnel,id2 -> Data is interchanged between the streams with the given id's")
				.add( "ss:id,send,data(,reply) -> Send the given data to the id with optional reply");
		return LookAndFeel.formatHelpCmd(help.toString(), html);
	}
	private String doAddStreamCmd( String[] cmds, XMLdigger dig){
		cmds[1]=cmds[1].toLowerCase();
		dig.hasPeek("stream","id",cmds[1]);
		if( dig.hasValidPeek())
			return "! Already a stream with that id, try something else?";

		if (dig.hasPeek("stream")) {
			for (var d : dig.digOut("stream")) {
				if (d.peekAtContent("address", cmds[2]))
					return "! " + dig.attr("id", "") + " already connected to " + cmds[2];
				if (d.peekAtContent("port", cmds[2]))
					return "! " + dig.attr("id", "") + " already using " + cmds[2];
			}
		}
		var fabOpt = XMLfab.alterDigger(dig);
		if( fabOpt.isEmpty())
			return "! Failed to create fab";

		var fab = fabOpt.get();
		var result = addStreamToFab(cmds, fab);
		if (!result.isEmpty())
			return result;

		var base = addStreamFromXML(dig.digDown("stream", "id", cmds[1]));
		if (base != null) {
			Core.addToQueue(Datagram.system("commandable:" + base.id()).payload(this));
			try {
				if (base.reconnectFuture != null)
					base.reconnectFuture.get(2, TimeUnit.SECONDS);
				streams.put(cmds[1], base);
			} catch (CancellationException | ExecutionException | InterruptedException | TimeoutException e) {
				return "! Failed to connect.";
			}
			var type = cmds[0].substring(3);
			if (type.endsWith("server"))
				return "Started " + base.id() + ", use 'raw:" + base.id() + "' to see incoming data.";
			return "Connected to " + base.id() + ", use 'raw:" + base.id() + "' to see incoming data.";
		}
		return "! Failed to read stream from XML";
	}

	private String addStreamToFab(String[] cmds, XMLfab fab) {
		var type = cmds[0].substring(3);

		switch (type) {
			case "tcp", "udpclient", "udp" -> {
				if (cmds.length != 3)
					return "! Not enough arguments: ss:" + cmds[0] + ",id,ip:port";
				addBaseToXML(fab, cmds[1], type);
				fab.addChild("address", cmds[2]).build();
			}
			case "tcpserver" -> {
				if (cmds.length != 3)
					return "! Not enough arguments: ss:addtcpserver,id,port";
				addBaseToXML(fab, cmds[1], type);
				fab.addChild("port", cmds[2]).build();
			}
			case "serial", "modbus" -> {
				if (cmds.length < 3)
					return "! Not enough arguments: ss:" + cmds[0] + ",id,port(,baudrate)";
				addBaseToXML(fab, cmds[1], type);
				fab.addChild("port", cmds[2]);
				fab.addChild("serialsettings", (cmds.length == 4 ? cmds[3] : "19200") + ",8,1,none")
						.build();
			}
			case "local"->{
				addBaseToXML(fab, cmds[1], type);
				if( cmds.length==3)
					fab.attr("src",cmds[2]);
				fab.build();
			}
			case "udpserver" -> {
				if (cmds.length != 3) // Make sure we got the correct amount of arguments
					return "! Wrong amount of arguments -> ss:addudpserver,id,port";
				new UdpServer(cmds[1], Integer.parseInt(cmds[2]));
				addBaseToXML(fab, cmds[1], type);
				fab.addChild("port", cmds[2]).build();
			}
			default -> {
				return "! Invalid option";
			}
		}
		return "";
	}
	private String doAlterUseCmd(String[] cmds, Writable wr, XMLdigger dig) {
		cmds[0] = cmds[0].toLowerCase();

		dig.hasPeek("stream","id",cmds[0]);
		if( !dig.hasValidPeek())
			return "! No such stream yet.";
		dig.digDown("stream","id",cmds[0]);
		var type = dig.attr("type","");
		var fabOpt = XMLfab.alterDigger(dig);
		if( fabOpt.isEmpty() )
			return "! Failed to find in xml";
		var fab = fabOpt.get();

		var stream = streams.get(cmds[0]);

		return switch (cmds[1]) {
			case "send" -> doSendCmd(cmds);
			case "recon" -> doReconCmd(stream);
			case "reload" -> reloadStream(cmds[0]);
			case "clearrequests" -> "Targets cleared:" + stream.clearTargets();
			case "baudrate" -> doBaudrateCmd(cmds, stream, fab);
			case "label" -> doLabelCmd(cmds, fab, stream);
			case "ttl" -> doTtlCmd(cmds, stream, fab);
			case "port" -> doPortCmd(cmds, dig, type, fab);
			case "eol" -> doEolCmd(cmds, fab);
			case "addwrite", "addcmd" -> doAddWriteCmd(cmds, fab, stream);
			case "echo" -> doEchoCmd(cmds, fab, stream);
			case "reloadstore" -> reloadStore(cmds[0])
					? "Reloaded the store of " + cmds[0]
					: "! Failed to reload the store of " + cmds[0];
			case "tunnel" -> doTunnelCmd(cmds);
			case "request" -> doRequestCmd(cmds);
			case "reqwritable" -> {
				wr.giveObject("writable", getWritable(cmds[0]).orElse(null));
				yield "Writable given";
			}
			default -> "! No such subcommand for ss:id :" + cmds[1];
		};
	}

	private String doRequestCmd(String[] cmds) {
		if (cmds.length != 3) // Make sure we got the correct amount of arguments
			return "! Wrong amount of arguments -> ss:id,request,requestcmd";
		getWritable(cmds[0]).ifPresent(wri -> Core.addToQueue(Datagram.system(cmds[2]).writable(wri)));
		Core.addToQueue(Datagram.system("ss", cmds[0] + ",addcmd,open," + cmds[2]));
		return "Tried requesting data from " + cmds[2] + " for " + cmds[2];
	}

	private static String doBaudrateCmd(String[] cmds, BaseStream stream, XMLfab fab) {
		if (!(stream instanceof SerialStream))
			return "! Not a Serial port, no baudrate to change";
		((SerialStream) stream).setBaudrate(Tools.parseInt(cmds[2], -1));
		fab.alterChild("serialsettings", ((SerialStream) stream).getSerialSettings()).build();
		return "Altered the baudrate";
	}

	private String doEolCmd(String[] cmds, XMLfab fab) {
		fab.alterChild("eol", cmds.length == 3 ? cmds[2] : "");
		fab.build();
		reloadStream(cmds[0]);
		return "EOL altered";
	}

	private String doSendCmd(String[] cmds) {
		if (cmds.length < 3)
			return "! Not enough arguments given: ss:id,send,data";
		String written = writeToStream(cmds[1], cmds[2], cmds.length > 3 ? cmds[3] : "");
		if (written.isEmpty())
			return "! Failed to write data";
		return "Data written: " + written;
	}

	private static String doLabelCmd(String[] cmds, XMLfab fab, BaseStream stream) {
		if (cmds[2].isEmpty() || cmds[2].equalsIgnoreCase("void")) {
			fab.removeChild("label").build();
			return "Label removed";
		}
		fab.alterChild("label", cmds[2]).build();
		stream.setLabel(cmds[2]);
		return "Label altered to " + cmds[2];
	}

	private String doTtlCmd(String[] cmds, BaseStream stream, XMLfab fab) {
		if (!cmds[2].equals("-1")) {
			stream.setReaderIdleTime(TimeTools.parsePeriodStringToSeconds(cmds[2]));
			fab.alterChild("ttl", cmds[2]);
		} else {
			fab.removeChild("ttl");
		}
		fab.build();
		eventLoopGroup.schedule(new ReaderIdleTimeoutTask(stream), stream.getReaderIdleTime(), TimeUnit.SECONDS);
		return "TTL altered";
	}

	private String doReconCmd(BaseStream stream) {
		if (stream != null) {
			stream.disconnect();
			if (stream.reconnectFuture.getDelay(TimeUnit.SECONDS) < 0) {
				Logger.info("Already scheduled to reconnect");
				stream.reconnectFuture = eventLoopGroup.schedule(new DoConnection(stream), 5, TimeUnit.SECONDS);
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

	private String doPortCmd(String[] cmds, XMLdigger dig, String type, XMLfab fab) {
		switch (type) {
			case "serial" -> {
				if (Tools.getSerialPorts(false).contains(cmds[2])) {
					fab.alterChild("port", cmds[2]);
				} else {
					return "! No such serial port available";
				}
			}
			case "type" -> {
				var addr = dig.attr("address", "");
				if (!addr.contains(":"))
					return "! No valid/empty address node?";
				var ip = addr.split(":")[0];
				fab.alterChild("address", ip + ":" + cmds[2]);
			}
			default -> {
				return "! Command not supported for this type (yet)";
			}
		}
		fab.build();
		reloadStream(cmds[0]);
		return "Port altered to " + cmds[2];
	}

	private static String doAddWriteCmd(String[] cmds, XMLfab fab, BaseStream stream) {
		if (cmds.length < 3) {
			return "! Wrong amount of arguments -> ss:id," + cmds[1] + ",when,cmd";
		}
		if (!Arrays.asList(WHEN).contains(cmds[2]))
			return "! Not a valid when.";

		// Last bit could contain a ',' so combine everything from there onwards
		var from = Arrays.copyOfRange(cmds, 3, cmds.length);
		var cmd = String.join(",", from);
		fab.selectOrAddChildAsParent("triggered");
		fab.addChild(cmds[1].substring(3), cmd).attr("when", cmds[2]).build();
		stream.addTriggeredAction(cmds[2], cmd);
		return "Added triggered " + cmds[1].substring(3) + " to " + cmds[0];
	}

	private static String doEchoCmd(String[] cmds, XMLfab fab, BaseStream stream) {
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

	private String doTunnelCmd(String[] cmds) {
		if (cmds.length != 3) // Make sure we got the correct amount of arguments
			return "! Wrong amount of arguments -> ss:fromid,tunnel,toid";

		int s1_ok = getWritable(cmds[0]).map(wri -> addTargetRequest(cmds[2], wri) ? 1 : 0).orElse(-1);
		if (s1_ok != 1)
			return s1_ok == -1 ? "! No writable " + cmds[0] : "! No such source " + cmds[2];

		int s2_ok = getWritable(cmds[2]).map(wri -> addTargetRequest(cmds[0], wri) ? 1 : 0).orElse(-1);
		if (s2_ok != 1)
			return s2_ok == -1 ? "! No writable " + cmds[2] : "! No such source " + cmds[2];
		// Tunneling is essentially asking the data from the other one
		Core.addToQueue(Datagram.system("ss", cmds[0] + ",addcmd,open,raw:" + cmds[2]));
		Core.addToQueue(Datagram.system("ss", cmds[2] + ",addcmd,open,raw:" + cmds[0]));
		return "Tunnel established between " + cmds[0] + " and " + cmds[2];
	}
	/* ************************************************************************************************************** */
	/**
	 * Checks for a stream that starts with id and adds the given writable to its targets
	 * @param id The (start of) the stream id
	 * @param writable The target to write to
	 * @return True if ok
	 */
	public boolean addTargetRequest(String id, Writable writable) {
		var remove = id.startsWith("!"); // Remove a writable instead of adding it
		if( writable==null){
			Logger.error("Received request for "+id+" but writable is null");
			return false;
		}

		Logger.info("Received "+(remove?"removal":"data request")+" from "+writable.id()+" for "+id);
		if(writable.id().startsWith("email") ){
			Logger.info("Received request through email for "+id);
		}

		if( remove ) {
			getStream(id.substring(1)).ifPresent( bs -> bs.removeTarget(writable));
		}else{
			if( !getStream(id).map( bs -> bs.addTarget(writable) ).orElse(false) ) {
				var stream = streams.entrySet().stream().filter(set -> set.getKey().startsWith(id))
						.map(Map.Entry::getValue).findFirst();
				if(stream.isEmpty())
					return false;
				stream.get().addTarget(writable);
			}
		}
		return true;
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



	@Override
	public void collectorFinished(String id, String message, Object result) {
		String[] ids = id.split(":");
		switch (ids[0]) {
			case "confirm" -> {
				confirmCollectors.remove(ids[1]);
				if (confirmCollectors.isEmpty())
					Logger.info("All confirm requests are finished");
			}
			case "math" -> Core.addToQueue(Datagram.system("store", message + "," + result));
			default -> Logger.error("Unknown Collector type: " + id);
		}
	}

	/**
	 * Remove the confirmCollector with the given id from the hashmap
	 * @param id The id to look for
	 */
	public void removeConfirm(String id){
		if( confirmCollectors.values().removeIf( cc -> cc.id().equalsIgnoreCase(id)) ){
			Logger.debug("ConfirmCollector removed: "+id);
		}else{
			Logger.warn("ConfirmCollector not found: "+id);
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
			long currentTime = Instant.now().toEpochMilli();
			long lastReadTime = stream.getLastTimestamp();
			long nextDelay = stream.getReaderIdleTime()*1000 - (currentTime - lastReadTime);

			if (nextDelay <= 0 ) {// If the next delay is less than longer ago than a previous idle
				notifyIdle(stream);// Reader is idle notify the callback.
			}else { // Don't schedule check when idle
				eventLoopGroup.schedule(this, nextDelay, TimeUnit.MILLISECONDS);
			}
		}
	}

	/**
	 * Listener stuff
	 *
	 */
	@Override
	public void notifyIdle( BaseStream stream ) {
		Logger.warn(stream.id() + " is idle for " + stream.getReaderIdleTime() + "s");
		if( stream instanceof SerialStream ){
			((SerialStream)stream).flushBuffer();
		}
		stream.flagAsIdle();
		stores.forEach(StoreCollector::doIdle);
	}

	@Override
	public boolean notifyActive(String id ) {
		getStream(id.toLowerCase()).ifPresent( s -> {
			s.flagAsActive();
			eventLoopGroup.schedule(new ReaderIdleTimeoutTask(s), s.getReaderIdleTime(), TimeUnit.SECONDS);
		});
		return true;
	}

	@Override
	public void notifyOpened( String id ) {
		getStream(id.toLowerCase()).ifPresent( b -> {
			b.applyTriggeredAction(BaseStream.TRIGGER.HELLO);
			b.applyTriggeredAction(BaseStream.TRIGGER.OPEN);
		});
	}

	@Override
	public void notifyClosed( String id ) {
		getStream(id.toLowerCase()).ifPresent( b -> b.applyTriggeredAction(BaseStream.TRIGGER.CLOSE));
	}

	@Override
	public boolean requestReconnection( String id ) {
		BaseStream bs = streams.get(id.toLowerCase());
		if( bs == null){
			Logger.error("Bad id given for reconnection request: "+id);
			return false;
		}
		if (bs.reconnectFuture == null || bs.reconnectFuture.getDelay(TimeUnit.SECONDS) < 0) {
			Logger.error("Requesting reconnect for "+bs.id());
			bs.reconnectFuture = eventLoopGroup.schedule(new DoConnection(bs), 5, TimeUnit.SECONDS);
			return true;
		}
		return false;
	}

	/* 	--------------------------------------------------------------------	*/
}
