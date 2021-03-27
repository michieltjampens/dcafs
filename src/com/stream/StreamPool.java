package com.stream;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.stream.forward.TextForward;
import com.stream.serialport.ModbusStream;
import com.stream.serialport.MultiStream;
import com.stream.serialport.SerialStream;
import com.stream.collector.ConfirmCollector;
import com.stream.forward.FilterForward;
import com.stream.forward.MathForward;
import com.stream.collector.CollectorFuture;
import com.stream.tcp.TcpStream;
import com.stream.udp.UdpServer;
import com.stream.udp.UdpStream;

import com.telnet.TelnetCodes;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import das.IssueCollector;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import util.xml.XMLfab;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLtools;
import worker.Datagram;

/**
 * The class holds all the information required about a datasource to acquire
 * data from it. It uses the internal class StreamDescriptor to hold this
 * information. All connections are made using the Netty 4.x library.
 */
public class StreamPool implements StreamListener, CollectorFuture {

	BlockingQueue<Datagram> dQueue; // Holds the data for the DataWorker

	// Netty 
	Bootstrap bootstrapTCP;		// Bootstrap for TCP connections
	Bootstrap bootstrapUDP;	  	// Bootstrap for UDP connections

	EventLoopGroup group;		    // Event loop used by the netty stuff

	IssueCollector issues;			// Handles the issues/problems that arise
	int retryDelayMax = 30;			// The minimum time between reconnection attempts
	int retryDelayIncrement = 5;	// How much the delay increases between attempts

	HashMap<String, ConfirmCollector> confirmCollectors = new HashMap<>();
	HashMap<String, FilterForward> filters = new HashMap<>();
	HashMap<String, TextForward> editors = new HashMap<>();
	HashMap<String, MathForward> maths = new HashMap<>();

	LinkedHashMap<String,BaseStream> streams = new LinkedHashMap<>();

	Path xmlPath = Paths.get("settings.xml"); // Path to the xml file
	Document xml;		   // The settings xml
	boolean debug = false; // Whether or not in debug mode, gives more feedback

	ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(); // scheduler for the connection attempts
	private static final String XML_PARENT_TAG="streams";
	private static final String XML_CHILD_TAG="stream";

	public StreamPool(BlockingQueue<Datagram> dQueue, IssueCollector issues, EventLoopGroup nettyGroup ) {
		this.dQueue = dQueue;
		this.issues = issues;	
		this.group = nettyGroup;	
	}
	public StreamPool(BlockingQueue<Datagram> dQueue, IssueCollector issues) {
		this(dQueue,issues, new NioEventLoopGroup());	
	}

	public void setIssueCollector( IssueCollector issues ){
		this.issues = issues;
	}
	public void enableDebug(){
		debug=true;
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
	public Optional<Writable> getWritable( String id ){
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
				join.add("NC");
			}else if (ttl > stream.readerIdleSeconds *1000 && stream.readerIdleSeconds != -1) {
				join.add("!!");
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
	 * @return The label, title, address and TTL of each stream
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
	 * @param html Whether or not to use html formatting
	 * @return A String with a line for each StreamDescriptor which looks like
	 *         Sxx=title
	 */
	public String getStreamList(boolean html) {
		StringJoiner join = new StringJoiner(html ? "<br>" :"\r\n");
		join.setEmptyValue("None yet");
		int a = 1;
		for (String title : streams.keySet()) {
			join.add( "S" + (a++) + ":" + title);
		}
		return join.toString();
	}
	/**
	 * Get a list of all the StreamDescriptors available
	 * 
	 * @return A String with a line for each StreamDescriptor which looks like
	 *         Sxx=title
	 */
	public String getStreamList() {
		return getStreamList(false);
	}
	/**
	 * Get a list of all currently active labels
	 * 
	 * @return A list of all currently active labels (fe. nmea,li7000...) separated
	 *         by ','
	 */
	public String getActiveLabels() {
		StringJoiner join = new StringJoiner(", ","Currently used labels: ","\r\n");
		
		streams.values().stream().filter( desc -> !join.toString().contains(desc.getLabel()) )
								  .forEach( desc -> join.add( desc.getLabel() ) );			

		return join.toString();
	}

	/**
	 * Retrieve the contents of the confirm/reply buffer from the various streams
	 * Mainly used for debugging
	 * @return Contents of the confirm/reply buffer from the various streams
	 */
	public String getConfirmBuffers() {
		StringJoiner join = new StringJoiner("\r\n");
		confirmCollectors.forEach( (id, cw) -> join.add(">>"+cw.getID()).add( cw.getStored().length() == 0 ? " empty" : cw.getStored()));
		return join.toString();
	}
	/* *************************************  S E T U P **************************************************************/
	/**
	 * Disconnect all connections
	 */
	public void disconnectAll() {
		streams.forEach((k,v) -> v.disconnect() );
		group.shutdownGracefully();
	}

	/* ********************************** W R I T I N G **************************************************************/
	/**
	 * Send bytes over a specified stream
	 * @param id The name/title of the stream
	 * @param txt The data to transmit
	 * @param addDelimiter True if the default delimiter for that stream needs to be appended
	 * @return True if it was written
	 */
	public String writeBytesToStream(String id, byte[] txt, boolean addDelimiter ) {
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
			var r = new String(txt);
			if( addDelimiter ){
				wr.writeLine( r );
			}else{
				wr.writeString( r );
			}
			return r;
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
		BaseStream stream = this.streams.get( id.toLowerCase() );

		if( stream == null || !stream.isWritable() || !stream.isConnectionValid() ){
			Logger.error( "Stream still null, not writable or no valid connection (looking for "+id+")");
			return false;
		}
		
		ConfirmCollector cw = confirmCollectors.get(ref+"_"+id);
		if( cw==null ){
			cw = new ConfirmCollector( ref+"_"+id,3,3, (Writable)stream, group );
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
			return writeBytesToStream( id, Tools.fromHexStringToBytes(txt),false );
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
					cw = new ConfirmCollector( id,3,3, (Writable)stream, group );
					cw.addListener(this);
					if( !reply.isEmpty()) // No need to get data if we won't use it
						stream.addTarget(cw);
					confirmCollectors.put(stream.getID(),cw);
				}else{
					if( txt.indexOf("\\") < txt.length()-2 ){
						txt = Tools.fromEscapedStringToBytes(txt);
					}
					boolean written = false;
					if( txt.endsWith("\\0")){
						written=((Writable)stream).writeString(StringUtils.removeEnd(txt,"\\0"));
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
	 * Get the title of the stream based on the index in the hashmap
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
	 * Reload the settings of a channel and re-initialize
	 * 
	 * @param id ID of the stream to reload
	 * @return True if reload was successful
	 */
	public String reloadStream( String id ) {
		
		BaseStream stream = this.streams.get(id.toLowerCase());

		Logger.info("Reloading "+id+ " from "+this.xmlPath.toAbsolutePath().toString() );
		Document xmlDoc = XMLtools.readXML(this.xmlPath);
		if( xmlDoc != null ){
			Element streamElement = XMLtools.getFirstElementByTag(xmlDoc, XML_PARENT_TAG);
			var child = XMLfab.withRoot(xmlPath,"das","streams").getChild("stream","id",id);
			var base = getStream(id);
			if( child.isEmpty() )
				return "No stream named "+id+" found.";

			if( base.isPresent() ){ // meaning reloading an existing one
				var str = base.get();
				str.disconnect();
				str.readFromXML(child.get());
				str.reconnectFuture = scheduler.schedule( new DoConnection( str ), 0, TimeUnit.SECONDS );
				return "Reloaded and trying to reconnect";
			}else{
				addStreamFromXML(child.get());
				return "Loading new stream.";
			}

		}else{
			Logger.error("Failed to read xml file at "+this.xmlPath.toAbsolutePath().toString());
			return "Failed to read xml";
		}
	}
	/**
	 * Disconnect a stream
	 * @param id The id of the stream to disconnect
	 */
	private void disconnectStream( String id ){
		getStream(id).ifPresent(BaseStream::disconnect);
	}
	/* ***************************** A D D I N G C H A N N E L S ******************************************/
	/**
	 * Checks whether or not StreamPool info can be found in the settings file.
	 * 
	 * @param xml The settings file
	 * @return True if settings were found
	 */
	public static boolean inXML(Document xml) {
		return XMLfab.hasRoot(xml, "settings",XML_PARENT_TAG);
	}

	/**
	 * Add the streams by reading the settings.xml
	 * 
	 * @param xml The XML document to look in
	 */
	public void readSettingsFromXML( Document xml ) {
		this.xml=xml;
		URI uri;
		
		try {
			uri = new URI( xml.getDocumentURI() );
			String os = System.getProperty("os.name").toLowerCase();
			if( os.startsWith("linux")){		
				xmlPath= Paths.get( uri.getRawPath().replace("%20", " ") );
			}else{
				xmlPath= Paths.get( uri.getRawPath().substring(1).replace("%20", " ") );
			}
			Logger.info("Set XMLPath to "+this.xmlPath.toAbsolutePath().toString() );
		} catch ( URISyntaxException | InvalidPathException | NullPointerException e) {			
			Logger.error(e);
		}

		Element filtersEle = XMLtools.getFirstElementByTag( xml, "filters");
		if( filtersEle != null )
			readFiltersFromXML( XMLtools.getChildElements(filtersEle, "filter"));
		
		Element mathsEle = XMLtools.getFirstElementByTag( xml, "maths");
		if( mathsEle != null )
			readMathsFromXML(XMLtools.getChildElements( mathsEle, "math"));

		Element editorsEle = XMLtools.getFirstElementByTag( xml, "editors");
		if( editorsEle != null )
			readEditorsFromXML(XMLtools.getChildElements( editorsEle, "editor"));

		Element streamsElement = XMLtools.getFirstElementByTag( xml, "streams");
		
		retryDelayIncrement = XMLtools.getChildIntValueByTag(streamsElement, "retrydelayincrement", 5);
		retryDelayMax = XMLtools.getChildIntValueByTag(streamsElement, "retrydelaymax", 30);
		
		streams.clear(); // Clear out before the reread

		for( Element el : XMLtools.getChildElements( streamsElement, XML_CHILD_TAG)){
			BaseStream bs = addStreamFromXML(el);
			if( bs != null ){
				streams.put( bs.getID().toLowerCase(),bs);
			}
		}	
	}
	/**
	 * Add a single channel from an XML element
	 * 
	 * @param stream The element containing the channel information
	 */
	public BaseStream addStreamFromXML( Element stream ){
		
    	switch( stream.getAttribute("type").toLowerCase() ){
			case "tcp": case "tcpclient":
				TcpStream tcp = new TcpStream( dQueue, stream );
				tcp.setEventLoopGroup(group);
				tcp.addListener(this);
				bootstrapTCP = tcp.setBootstrap(bootstrapTCP);
				tcp.reconnectFuture = scheduler.schedule( new DoConnection( tcp ), 0, TimeUnit.SECONDS );
				return tcp;
			case "udp":case "udpclient":
				UdpStream udp = new UdpStream( dQueue, stream );
				udp.setEventLoopGroup(group);
				udp.addListener(this);
				bootstrapUDP = udp.setBootstrap(bootstrapUDP);
				udp.reconnectFuture = scheduler.schedule( new DoConnection( udp ), 0, TimeUnit.SECONDS ); 
				return udp;
			case "udpserver":
				UdpServer serv = new UdpServer( dQueue, stream );
				serv.setEventLoopGroup(group);
				serv.addListener(this);
				serv.reconnectFuture = scheduler.schedule( new DoConnection( serv ), 0, TimeUnit.SECONDS );
				return serv;
			case "serial":
				SerialStream serial = new SerialStream( dQueue, stream );
				if( serial.readerIdleSeconds !=-1 ){
					scheduler.schedule(new ReaderIdleTimeoutTask(serial), serial.readerIdleSeconds, TimeUnit.SECONDS);
				}
				serial.addListener(this);
				serial.reconnectFuture = scheduler.schedule( new DoConnection( serial ), 0, TimeUnit.SECONDS );
				return serial; 
			case "modbus":
				ModbusStream modbus = new ModbusStream( dQueue, stream );
				modbus.addListener(this);
				modbus.reconnectFuture = scheduler.schedule( new DoConnection( modbus ), 0, TimeUnit.SECONDS );
				return modbus;
			case "multiplex":
				MultiStream mStream = new MultiStream( dQueue, stream );
				if( mStream.readerIdleSeconds !=-1 ){
					scheduler.schedule(new ReaderIdleTimeoutTask(mStream), mStream.readerIdleSeconds, TimeUnit.SECONDS);
				}
				mStream.addListener(this);
				mStream.reconnectFuture = scheduler.schedule( new DoConnection( mStream ), 0, TimeUnit.SECONDS );
				return mStream;
			case "local":
				LocalStream local = new LocalStream( dQueue, stream);
				local.addListener(this);
				if( local.readerIdleSeconds !=-1 ){
					scheduler.schedule(new ReaderIdleTimeoutTask(local), local.readerIdleSeconds, TimeUnit.SECONDS);
				}
				local.reconnectFuture = scheduler.schedule( new DoConnection( local ), 0, TimeUnit.SECONDS );
				return local;                  		
    		default: Logger.error( "aNo such type defined" );
		}
		return null;
	}
	/**
	 * Stores the settings of a stream to the settings.xml. Writing isn't done if the address is already in use by a different stream.
	 * If a stream with the name is already present, unless overwrite = true writing is aborted
	 * @param id The title of the stream to write
	 * @param overwrite If true an existing element with that title will be removed
	 * @return True if ok, false if failed or aborted
	 */
	public boolean addStreamToXML( String id, boolean overwrite ){
		BaseStream stream = streams.get(id.toLowerCase());
		if( stream == null){
			Logger.error("No such stream to write to xml "+id);
			return false;
		}

		// Check if it already exists (based on title and address?)
		XMLfab fab = XMLfab.withRoot(xmlPath, "das",XML_PARENT_TAG);
		boolean exists = fab.hasChild(XML_CHILD_TAG, "id", stream.getID() );

		if( exists && !overwrite ){
			Logger.warn("Already such stream ("+id+") in the settings.xml, not overwriting");
			return false;
		}
		stream.writeToXML(fab);
		xml = fab.build();
		return xml != null;		
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
				Logger.error( "Failed to connect to "+base.getID()+", scheduling retry in "+delay+"s. ("+base.connectionAttempts+" attempts)" );				
				String device = base.getID().replace(" ","").toLowerCase();
				if( issues!=null )
					issues.reportIssue(device+".conlost", "Connection lost to "+base.getID(), true);
				base.reconnectFuture = scheduler.schedule( new DoConnection( base ), delay, TimeUnit.SECONDS );
			} catch (Exception ex) {		
				Logger.error( "Connection thread interrupting while trying to connect to "+base.getID());
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
	/**
	 * The streampool can give replies to certain predetermined questions
	 * @param request The question to ask
	 * @param html Whether or not the answer should use html or regular line endings
	 * @return The answer or Unknown Command if the question wasn't understood
	 */
	public String replyToCmd(String request, boolean html ){

		String nl = html?"<br>":"\r\n";

		//if( request.equals("") )
		//	request = "status";

		String[] cmds = request.split(",");		

		String device="";
		if( cmds.length > 1 ){				
			device = cmds[1].replace(" ", "").toLowerCase(); // Remove spaces
		}
		StringJoiner join = new StringJoiner(nl);
		BaseStream stream;
		XMLfab fab;

		switch( cmds[0] ){
			case "?":
				join.add(TelnetCodes.TEXT_RESET+TelnetCodes.TEXT_GREEN+"General info"+TelnetCodes.TEXT_YELLOW)
					.add("-> ss: and streams: do the same thing")
					.add("-> Every stream has at least:")
					.add("   - a unique id which is used to identify it")
					.add("   - a label which is used to determine how the data is processed")
					.add("   - an eol (end of line), the default is crlf")
					.add("   - ...");
				join.add("").add(TelnetCodes.TEXT_GREEN+"Add new streams"+TelnetCodes.TEXT_YELLOW)
					.add(" ss:addtcp,id,ip:port,label -> Add a TCP stream to xml and try to connect")
					.add(" ss:addudp,id,ip:port,label -> Add a UDP stream to xml and connect")
					.add(" ss:addserial,id,port:baudrate,label -> Add a serial stream to xml and try to connect" )
					.add(" ss:addlocal,id,label,source -> Add a internal stream that handles internal data")
				.add("").add(TelnetCodes.TEXT_GREEN+"Info about streams"+TelnetCodes.TEXT_YELLOW)
					.add(" ss:labels -> get active labels.")
					.add(" ss:buffers -> Get confirm buffers.")
					.add(" ss:status -> Get streamlist.")
					.add(" ss:requests -> Get an overview of all the datarequests held by the streams")
				.add("").add(TelnetCodes.TEXT_GREEN+"Interact with stream objects"+TelnetCodes.TEXT_YELLOW)
					.add(" ss:recon,id -> Try reconnecting the stream")
					.add(" ss:reload,id -> Reload the stream or 'all' for all from xml.")
					.add(" ss:store,id -> Update the xml entry for this stream")
					.add(" ss:alter,id,parameter:value -> Alter the given parameter options label,baudrate,ttl")
				.add("").add(TelnetCodes.TEXT_GREEN+"Route data from or to a stream"+TelnetCodes.TEXT_YELLOW)
					.add(" ss:forward,source,id -> Forward the data from a source to the stream, source can be any object that accepts a writable")
					.add(" ss:connect,id1,if2 -> Data is interchanged between the streams with the given id's")
					.add(" ss:echo,id -> Toggles that all the data received on this stream will be returned to sender")
				.add("").add(TelnetCodes.TEXT_GREEN+"Send data to stream via telnet"+TelnetCodes.TEXT_YELLOW)
					.add("Option 1) First get the index of the streams with ss or streams")
					.add("          Then use Sx:data to send data to the given stream (eol will be added)")
					.add("Option 2) ss:send,id,data -> Send the data to the given stream and append eol"+TelnetCodes.TEXT_BRIGHT);
				return join.toString();
			case "send":
				if( cmds.length < 3 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 3 (send,id,data)";
				var s = getStream(cmds[1]);
				if( s.isPresent() ){
					var bs = s.get();
					if( bs instanceof Writable ){
						if( ((Writable)s.get()).writeLine(request.substring(request.indexOf(cmds[1]+",")+cmds[1].length()+1)) ){
							return "Data written";
						}else{
							return "Failed to write data";
						}
					}else{
						return "Can't write to this stream";
					}

				}else{
					return "No such stream: "+cmds[1];
				}
			case "buffers": return getConfirmBuffers();
			case "labels" :case "rios": return this.getActiveLabels();
			case "requests":
				join.setEmptyValue("No requests yet.");
				streams.values().stream().filter( base -> base.getRequestsSize() !=0 )
								.forEach( x -> join.add( x.getID()+" -> "+x.listTargets() ) );
				return join.toString();
			case "recon":
				if( cmds.length != 2 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 2 (recon,id)";
				stream = streams.get(cmds[1].toLowerCase());

				if( stream != null){
					stream.disconnect();
					if(stream.reconnectFuture.getDelay(TimeUnit.SECONDS) < 0 ) {
						Logger.info("Already scheduled to reconnect");
						stream.reconnectFuture = scheduler.schedule(new DoConnection(stream), 5, TimeUnit.SECONDS);
					}
					return "Trying to reconnect to "+cmds[1];
				}else{
					return "Already waiting for reconnect attempt";
				}
			case "reload":
				if( cmds.length != 2 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 2 (reload,id)";
				int index = Tools.parseInt(cmds[1], -999);
				if( index != -999 ){
					cmds[1] = this.getStreamID(index);
				}
				if( cmds[1].equalsIgnoreCase("all")){
					readSettingsFromXML(xml);
					return "Settings reloaded.";
				}else{
					return reloadStream( cmds[1] );
				}
			case "store":
				if( cmds.length != 2 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 2 (store,id)";

				stream = streams.get(cmds[1].toLowerCase());
				if( stream == null )
					return "No such stream: "+cmds[1];
				
				if( this.addStreamToXML(cmds[1],false) )
					return "Updated XML";
				return "Failed to update XML! Entry might exist already";
			case "trigger":
				stream = streams.get(cmds[1].toLowerCase());
				if( cmds.length<4)
					return "Incorrect amount of arguments, expected ms:trigger,id,event,cmd";
				if( stream == null )
					return "No such stream: "+cmds[1];

				String event = cmds[2];
				String cmd = request.substring( request.indexOf(event+",")+event.length()+1);

				fab = XMLfab.withRoot(xml,XML_PARENT_TAG); // get a fab pointing to the streams node

				if( fab.selectParent("stream","id",cmds[1]).isEmpty() )
					return "No such stream  "+cmds[1];

				fab.addChild("cmd",cmd).attr("trigger",event);
				stream.addTriggeredCmd(event,cmd);
				return fab.build()!=null?"Trigger added":"Altering xml failed";
			case "alter":
				stream = streams.get(cmds[1].toLowerCase());
				if( cmds.length != 3)
					return "Bad amount of arguments, should be ss:alter,id,param:value";

				if( stream == null )
					return "No such stream: "+cmds[1];

				String[] alter = cmds[2].split(":");
				if( alter.length==1)
					return "Not enough arguments for altering";

				if( alter.length>2)
					alter[1] = cmds[2].substring(cmds[2].indexOf(":")+1);

				fab = XMLfab.withRoot(xml,XML_PARENT_TAG); // get a fab pointing to the streams node

				if( fab.selectParent("stream","id",cmds[1]).isEmpty() )
					return "No such stream '"+cmds[1]+"'";

				Element f;
				boolean reload=false;
				switch( alter[0] ){
					case "label":
						stream.setLabel(alter[1]);
						fab.alterChild("label",alter[1]);
						break; 
					case "baudrate":
						if( !(stream instanceof SerialStream) )
							return "Not a Serial port, no baudrate to change";
						((SerialStream)stream).setBaudrate(Tools.parseInt(alter[1], -1));
						fab.alterChild("serialsettings",((SerialStream)stream).getSerialSettings());
					break;
					case "ttl": 
						if( !alter[1].equals("-1")){
							stream.setReaderIdleTime( TimeTools.parsePeriodStringToSeconds(alter[1]));
							fab.alterChild("ttl",alter[1]);
						}else{
							fab.removeChild("ttl");
						}
					break;
					default:
						fab.alterChild(alter[0],alter[1]);
						reload=true;
					break;
				}
				if( fab.build()!=null ){
					if( reload)
						this.reloadStream(device);	
					return "Alteration applied";
				}
				return "Failed to alter stream!";
			case "echo":
					if( cmds.length != 2 ) // Make sure we got the correct amount of arguments
						return "Bad amount of arguments, need 2 (echo,id)";
					BaseStream bs0 = streams.get(cmds[1].toLowerCase());	
					if( bs0 == null || !bs0.isWritable() )
						return "No such writable stream: "+cmds[1];
					if( bs0.hasEcho() ){
						bs0.disableEcho();
						return "Echo disabled on "+cmds[1];
					}else{
						bs0.enableEcho();
						return "Echo enabled on "+cmds[1];
					}
			case "tunnel": case "connect":
					if( cmds.length != 3 ) // Make sure we got the correct amount of arguments
						return "Bad amount of arguments, need 3 (tunnel,fromid,toid)";

					int s1Ok = getWritable(cmds[1]).map( wr-> addForwarding(cmds[2], wr)?1:0).orElse(-1);
					if( s1Ok != 1 )
						return s1Ok==-1?"No writable "+cmds[1]:"No such source "+cmds[2];

					int s2Ok = getWritable(cmds[2]).map( wr-> addForwarding(cmds[1], wr)?1:0).orElse(-1);
					if( s2Ok != 1 )
						return s2Ok==-1?"No writable "+cmds[1]:"No such source "+cmds[1];
					
					return "Tunnel established between "+cmds[1]+" and "+cmds[2];
			case "link": case "forward":
				if( cmds.length != 3 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 3 (link,fromid,toid)";
				var wr = getWritable(cmds[2]);
				if(wr.isPresent()){
					dQueue.add(new Datagram(wr.get(),"system",cmds[1]));
					return "Tried enabling the forward from "+cmds[1]+" to "+cmds[2];
				}else{
					return "No such stream: "+cmds[2];
				}
			case "addtcp":
				if( cmds.length < 4 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 4 (addtcp,title,ip:port,label)";

				if( streams.get(cmds[1].toLowerCase()) != null )// Make sure we don't overwrite an existing connection
					return "Connection exists with that title ("+cmds[1]+") not creating it";

				if( cmds.length>4)
					cmds[3]=request.substring( request.indexOf(","+cmds[3])+1);

				cmds[1]=cmds[1].toLowerCase();

				TcpStream tcp = new TcpStream( cmds[1], cmds[2], dQueue, cmds[3], 1 );
				tcp.addListener(this);
				tcp.setEventLoopGroup(group);
				tcp.setBootstrap(bootstrapTCP);
				
				streams.put( cmds[1], tcp );

				if( addStreamToXML(cmds[1],false) ){
					tcp.reconnectFuture = scheduler.schedule( new DoConnection( tcp ), 0, TimeUnit.SECONDS );
					return "Trying to connect...";
				}
				return "Failed to update XML! Entry might exist already";
			case "addudp":
				if( cmds.length < 4 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 4 (addudp,title,ip:port,label)";

				if( streams.get(cmds[1].toLowerCase()) != null )// Make sure we don't overwrite an existing connection
					return "Connection exists with that title ("+cmds[1]+") not creating it";

				if( cmds.length>4)
					cmds[3]=request.substring( request.indexOf(","+cmds[3])+1);
				UdpStream udp = new UdpStream(cmds[1], cmds[2], dQueue, cmds[3], 1 );
				udp.addListener(this);
				udp.setEventLoopGroup(group);
				udp.setBootstrap(bootstrapUDP);

				streams.put( cmds[1], udp );

				if( addStreamToXML(cmds[1],false) ){
					udp.reconnectFuture = scheduler.schedule( new DoConnection( udp ), 0, TimeUnit.SECONDS );
					return "Trying to connect...";
				}
				return "Failed to update XML! Entry might exist already";
			case "udpserver":
				if( cmds.length < 4 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 4 (addudpserver,title,port,label)";

				if( streams.get(cmds[1].toLowerCase()) != null )// Make sure we don't overwrite an existing connection
					return "Connection exists with that title ("+cmds[1]+") not creating it";

				if( cmds.length>4)
					cmds[3]=request.substring( request.indexOf(","+cmds[3])+1);

				UdpServer udpserver = new UdpServer(cmds[1],Integer.parseInt(cmds[2]),dQueue,cmds[3]);

				break;
			case "addserial":
				if( cmds.length < 4 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 4 (addserial,title,portname:baudrate,label)";

				cmds[1]=cmds[1].toLowerCase();

				if( streams.get(cmds[1].toLowerCase()) != null )// Make sure we don't overwrite an existing connection
					return "Connection exists with that title ("+cmds[1]+") not creating it";

				if( cmds.length>4)
					cmds[3]=request.substring( request.indexOf(","+cmds[3])+1);

				String[] portAndBaud = cmds[2].split(":");
				String port = portAndBaud[0];
				String baud = portAndBaud.length==2?portAndBaud[1]:"19200";
				
				if( !SerialStream.portExists(port) && !port.contains("ttyGS"))
					return "No such port on this system. Options: "+ SerialStream.portList();
				
				SerialStream serial = new SerialStream( port, dQueue, cmds[3], 1);
				serial.alterSerialSettings(baud+",8,1,none");

				serial.setID(cmds[1]);
				serial.addListener(this);

				streams.put(cmds[1].toLowerCase(), serial);
				addStreamToXML(cmds[1],true);

				return serial.connect()?"Connected to "+port:"Failed to connect to "+port;	
			case "addlocal":
				if( cmds.length != 4 ) // Make sure we got the correct amount of arguments
					return "Bad amount of arguments, need 4 (addlocal,id,label,source)";
				LocalStream local = new LocalStream( cmds[1],cmds[2],cmds[3],dQueue);
				local.addListener(this);
				streams.put( cmds[1].toLowerCase(), local);
				addStreamToXML(cmds[1],true);
				return "Local stream added";			
			case "":
				return "List of available streams:"+nl+this.getStreamList(html);
			case "status" :
				return getStatus();
			default: return "Unknown command: "+request;
		}
		return "";
	}

	/**
	 * Listener stuff
	 *
	 */
	@Override
	public void notifyIdle( String title ) {
		String device = title.replace(" ", "").toLowerCase(); // Remove spaces
		issues.reportIssue(device+".conidle", "TTL passed for "+title, true);
		getStream(title.toLowerCase()).ifPresent( b -> b.applyTriggeredCmd(BaseStream.TRIGGER.IDLE));
		getStream(title.toLowerCase()).ifPresent( b -> b.applyTriggeredCmd(BaseStream.TRIGGER.WAKEUP));
	}
	@Override
	public void notifyActive( String title ) {
		String device = title.replace(" ", "").toLowerCase(); // Remove spaces
		issues.reportIssue(device+".conidle", "TTL passed for "+title, false);
	}
	@Override
	public void notifyOpened( String title ) {
		String device = title.replace(" ", "").toLowerCase(); // Remove spaces
		issues.reportIssue(device+".conlost", "Connection lost to "+title, false);

		getStream(title.toLowerCase()).ifPresent( b -> b.applyTriggeredCmd(BaseStream.TRIGGER.HELLO));
		getStream(title.toLowerCase()).ifPresent( b -> b.applyTriggeredCmd(BaseStream.TRIGGER.OPEN));

	}
	@Override
	public void notifyClosed( String title ) {
		String device = title.replace(" ", "").toLowerCase(); // Remove spaces
		issues.reportIssue(device+".conlost", "Connection lost to "+title, true);
		getStream(title.toLowerCase()).ifPresent( b -> b.applyTriggeredCmd(BaseStream.TRIGGER.CLOSE));
	}
	@Override
	public boolean requestReconnection( String id ) {
		BaseStream bs = streams.get(id.toLowerCase());
		if( bs == null){
			Logger.error("Bad title given for reconnection request: "+id);
			return false;
		}
		Logger.error("Requesting reconnect for "+bs.getID());
		if( bs.reconnectFuture==null || bs.reconnectFuture.getDelay(TimeUnit.SECONDS) < 0 ){
			bs.reconnectFuture = scheduler.schedule( new DoConnection( bs ), 5, TimeUnit.SECONDS );
			return true;
		}
		return false;
	}
	/*    ------------------------ Math ---------------------------------    */
	public MathForward addMath(String id, String source ){
		var mf = new MathForward( id, source, dQueue);
		maths.put( id, mf);
		return mf;
	}
	public Optional<MathForward> getMath(String id ){
		return Optional.ofNullable( maths.get(id));
	}
	public void readMathsFromXML( List<Element> maths ){
		for( Element ele : maths ){
			MathForward mf = new MathForward( ele,dQueue );
			String id = mf.getID();
			this.maths.put(id.replace("math:", ""), mf);
		}
	}
	public String replyToMathCmd( String cmd, Writable wr, boolean html ){
		if( cmd.isEmpty() )
			cmd = "list";

		String[] cmds = cmd.split(",");

		StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
		switch( cmds[0] ) {
			case "?":
				join.add(TelnetCodes.TEXT_RED+"Purpose"+TelnetCodes.TEXT_YELLOW)
					.add("  MathForwards can be used to alter data received from any source using mathematics.")
					.add("  eg. receive the raw data from a sensor and convert it to engineering values")
					.add("  Furthermore, the altered data is considered a source and can thus be used in further steps.")
					.add("  eg. pass it to a generic that stores it in a database").add("");
				join.add(TelnetCodes.TEXT_GREEN+"Create a MathForward"+TelnetCodes.TEXT_YELLOW)
					.add("  mf:addblank,id,source -> Add a blank mathf to the xml with the given id and optional source")
					.add("  mf:addsource,id,source -> Add the source to the given mathf")
					.add("  mf:addop,id,op -> Add the operation fe. i1=i2+50 to the mathf with the id")
					.add("  mf:alter,id,param:value -> Change a setting, currently delim(eter),label");
				join.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW)
					.add("  mf:debug,on/off -> Turn debug on/off")
					.add("  mf:list -> Get a listing of all the present mathforwards")
					.add("  mf:scratchpad,id,value -> Add the given value to the scratchpad of mathf id (or * for all)")
					.add("  mf:reload,id -> reloads the given id")
					.add("  mf:test,id,variables -> Test the given id with the variables (with the correct delimiter)")
					.add("  math:id -> Receive the data in the telnet window, also the source reference");
				return join.toString();
			case "debug":
				if (cmds[1].equalsIgnoreCase("on")) {
					maths.values().forEach(MathForward::enableDebug);
					return "Debug enabled";
				} else {
					maths.values().forEach(MathForward::disableDebug);
					return "Debug disabled";
				}
			case "addblank":
				if( cmds.length<3)
					return "Incorrect amount of arguments, expected ms:addblank,id,source";
				if( getMath(cmds[1]).isPresent() )
					return "Already math with that id";
				cmds[1]=cmds[1].toLowerCase();
				StringJoiner src = new StringJoiner(",");
				for( int a=2;a<cmds.length;a++){
					src.add(cmds[a]);
				}
				addMath(cmds[1],src.toString()).writeToXML(XMLfab.withRoot(xmlPath, "das"));
				return "Blank math with id "+cmds[1]+ " created.";
			case "alter":
				var mf = maths.get(cmds[1]);
				if( cmds.length < 3)
					return "Bad amount of arguments, should be mf:alter,id,param:value";
				if( mf == null )
					return "No such mathforward: "+cmds[1];

				if( !cmds[2].contains(":"))
					return "No proper param:value pair";

				String param = cmds[2].substring(0,cmds[2].indexOf(":"));

				String value = cmd.substring(cmd.indexOf(param+":")+param.length()+1);

				XMLfab fab = XMLfab.withRoot(xml,"maths"); // get a fab pointing to the maths node

				if( fab.selectParent("math","id",cmds[1]).isEmpty() )
					return "No such math node '"+cmds[1]+"'";

				switch( param ){
					case "delim": case "delimiter": case "split":
						mf.setDelimiter(value);
						fab.attr("delimiter",value);
						return fab.build()!=null?"Delimiter changed":"Delimiter change failed";
					case "label":
						mf.setLabel(value);
						fab.attr("label",value);
						return fab.build()!=null?"Label changed":"Label change failed";
					default:return "No valid alter target: "+param;
				}

			case "reload":
				if( cmds.length!=2)
					return "Incorrect amount of arguments, expected ms:reload,id";
				if( getMath(cmds[1]).isEmpty() )
					return "No such math";
				getMath(cmds[1]).ifPresent( m ->
									m.readFromXML(
											XMLfab.withRoot(xmlPath,"das","maths").getChild("math","id",cmds[1]).get()
											));
				return "Math reloaded";
			case "list":
				join.setEmptyValue("No maths yet");
				maths.values().forEach(m -> join.add(m.toString()));
				return join.toString();
			case "scratchpad":
				if( cmds.length < 3)
					return "Bad amount of arguments, should be maths:scratchpad,id,value";
				if( cmds[1].equalsIgnoreCase("*")) {
					maths.forEach((id, m) -> m.setScratchpad(NumberUtils.createDouble(cmds[2])));
				}else{
					getMath(cmds[1]).ifPresent( m -> m.setScratchpad(NumberUtils.createDouble(cmds[2])));
				}
				return "Scratchpad value ("+cmds[2]+") given to "+cmds[1];
			case "addsource": case "addsrc":
				String source = cmds[2].startsWith("i2c:")?cmds[2]+","+cmds[3]:cmds[2];
				if( getMath(cmds[1]).map( m -> m.addSource(source) ).orElse(false) )
					return "Source added";
				return "Failed to add source, no such math.";
			case "addop":
				if( cmds.length < 2)
					return "Bad amount of arguments, should be maths:addop,id,inputIndex(fe. i1)=formula";

				cmds[1]=cmds[1].toLowerCase();

				Logger.info("Math "+cmds[1]+" exists?"+getMath(cmds[1]).isPresent());
				String op = cmds.length==3?cmds[2]:"i1=i1";
				String[] split = op.split("=");
				if( split.length!=2){
					return "Op not in correct format, needs to be ix=formula (x is the index)";
				}
				int index = Tools.parseInt(split[0].substring(1),-1);
				if( index == -1 ){
					return "No valid index given: "+split[0];
				}
				if( getMath(cmds[1]).isEmpty())
					return "No such math yet ("+cmds[1]+")";

				if( getMath(cmds[1]).map( f -> f.addOperation(index, MathForward.OP_TYPE.COMPLEX,"",split[1]) ).orElse(false) ){
					return "Operation added and written to xml";
				}
				return "Operation added, failed to write to xml";
			case "test":
				if( cmds.length < 3)
					return "Bad amount of arguments, should be maths:test,id,variables";
				if( getMath(cmds[1].toLowerCase()).isEmpty() )
					return "No such math yet ("+cmds[1]+")";
				getMath(cmds[1].toLowerCase()).ifPresent(MathForward::enableDebug);
				String[] var = ArrayUtils.subarray(cmds,2,cmds.length);
				return getMath(cmds[1].toLowerCase()).map( m -> m.solveFor(String.join(",",var))).orElse("Failed");
			default: return "unknown command "+cmds[0];
		}
	}
	/*    ------------------------ Editor ---------------------------------    */
	public TextForward addEditor(String id, String source ){
		var tf = new TextForward( id, source, dQueue);
		editors.put( id, tf);
		return tf;
	}
	public Optional<TextForward> getEditor(String id ){
		return Optional.ofNullable( editors.get(id));
	}
	public void readEditorsFromXML( List<Element> editors ){
		Logger.info("Reading TextForwards from xml");
		for( Element ele : editors ){
			var tf = new TextForward( ele,dQueue );
			this.editors.put(tf.getID().replace("editor:", ""), tf);
		}
	}
	/*    ------------------------ Filter ---------------------------------    */
	public FilterForward addFilter(String id, String source, String rule ){
		var ff = new FilterForward( id, source, dQueue);
		ff.addRule(rule);
		filters.put( id, ff);
		return ff;
	}
	public Optional<FilterForward> getFilter(String id ){
		return Optional.ofNullable( filters.get(id));
	}
	public void readFiltersFromXML( List<Element> filters ){
		Logger.info("Reading filterforwards from xml");
		for( Element ele : filters ){
			FilterForward ff = new FilterForward( ele,dQueue );
			this.filters.put(ff.getID().replace("filter:", ""), ff);
		}
	}
	public String replyToFilterCmd( String cmd, Writable wr, boolean html ){

		if( cmd.isEmpty() )
			cmd = "list";

		String[] cmds = cmd.split(",");	

		StringJoiner join = new StringJoiner(html?"<br>":"\r\n");

		switch( cmds[0] ){
			case "?":
				join.add(TelnetCodes.TEXT_RED+"Purpose"+TelnetCodes.TEXT_YELLOW)
					.add("  If a next step in the processing doesn't want to receive some of the data, a filterforward can")
					.add("  be used to remove this data from the source.");
				join.add(TelnetCodes.TEXT_BLUE+"Notes"+TelnetCodes.TEXT_YELLOW)
					.add("  - Filter works based on exclusion, meaning no rules = all data goes through")
					.add("  - Filter doesn't do anything if it doesn't have a target (label counts as target)")
					.add("  - ...");
				join.add("").add(TelnetCodes.TEXT_GREEN+"Create a FilterForward"+TelnetCodes.TEXT_YELLOW);
				join.add( "  ff:addblank,id<,source> -> Add a blank filter with an optional source, is stored in xml.");
				join.add( "  ff:rules -> Get a list of all the possible rules with a short explanation");
				join.add( "  ff:addshort,id,src,rule:value -> Adds a filter with the given source and rule (type:value)");
				join.add( "  ff:addtemp,id<,source> -> Add a temp filter with an optional source with the issuer as target. Not stored in xml.");
				join.add( "  ff:addsource,id,source -> Add a source to the given filter");
				join.add( "  ff:addrule,id,rule:value -> Add a rule to the given filter");

				join.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW);
				join.add( "  ff:remove,id -> Remove the filter with the given id");
				join.add( "  ff:list or fs -> Get a list of all the currently existing filters.");
				join.add( "  ff:delrule,id,index -> Remove a rule from the filter based on the index given in fs:list");
				join.add( "  filter:id -> Receive the data in the telnet window, also the source reference");

				return join.toString();
			case "debug":
				if( cmds[1].equalsIgnoreCase("on")){
					filters.values().forEach( FilterForward::enableDebug );
					return "Debug enabled";
				}else{
					filters.values().forEach( FilterForward::disableDebug );
					return "Debug disabled";
				}
			case "list":
				join.setEmptyValue("No filters yet");
				filters.values().forEach( f -> join.add(f.toString()).add("") );
				return join.toString();
			case "rules":
				join.add("start   -> Which text the message should start with" );
				join.add("nostart -> Which text the message can't start with");
				join.add("end     -> Which text the message should end with");
				join.add("contain -> Which text the message should contain");
				join.add("c_start -> Which character should be found on position c from the start (0=first)");
				join.add("c_end   -> Which character should be found on position c from the end (0=last)");
				return join.toString();
			case "remove":
				if( cmds.length < 2 )
					return "Not enough arguments: fs:remove,id";
				if( filters.remove(cmds[1]) != null )
					return "Filter removed";
				return "No such filter";
			case "addrule":
				if( cmds.length < 3)
					return "Bad amount of arguments, should be filters:addrule,id,type:value";					
				String step = cmds.length==4?cmds[2]+","+cmds[3]:cmds[2]; // step might contain a ,
				Logger.info("Filter exists?"+getFilter(cmds[1]).isPresent());
				switch( getFilter(cmds[1].toLowerCase()).map( f -> f.addRule(step) ).orElse(0) ){
					case 1:  return "Rule added to "+cmds[1];
					case 0:  return "Failed to add rule, no such filter called "+cmds[1];
					case -1: return "Unknown type in "+step+", try fs:types for a list";
					case -2: return "Bad rule syntax, should be type:value";
					default: return "Wrong response from getFilter";
				}									
			case "delrule":
				if( cmds.length < 3)
					return "Bad amount of arguments, should be filters:delrule,id,index";
					int index = Tools.parseInt( cmds[2], -1);
					if( getFilter(cmds[1]).map( f -> f.removeRule(index) ).orElse(false) )
						return "Rule removed";
					return "Failed to remove rule, no such filter or rule.";
			case "addsource": case "addsrc":
				String source = cmds[2].startsWith("i2c:")?cmds[2]+","+cmds[3]:cmds[2];
				if( getFilter(cmds[1]).map( f -> f.addSource(source) ).orElse(false) )
					return "Source added";
				return "Failed to add source, no such filter.";
			case "addblank":
				if( cmds.length<2)
					return "Not enough arguments, needs to be fs:addblank,id<,src,>";
				if( getFilter(cmds[1]).isPresent() )
					return "Already filter with that id";

				StringJoiner src = new StringJoiner(",");
				for( int a=2;a<cmds.length;a++){
					src.add(cmds[a]);
				}

				addFilter(cmds[1].toLowerCase(),src.toString(),"")
						.writeToXML( XMLfab.withRoot(xmlPath, "das"),false );
				return "Blank filter with id "+cmds[1]+ " created"+(cmds.length>2?", with source "+cmds[2]:"")+".";
			case "addshort":
				if( cmds.length<4)
					return "Not enough arguments, needs to be fs:addshort,id,src,type:value";
				if( getFilter(cmds[1]).isPresent() )
					return "Already filter with that id";
				addFilter(cmds[1].toLowerCase(),cmds[2],cmds[3])
						.writeToXML( XMLfab.withRoot(xmlPath, "das"),true );
				return "Filter with id "+cmds[1]+ " created, with source "+cmds[2]+" and rule "+cmds[3];
			case "addtemp":
				if( getFilter(cmds[1]).isPresent() ){
					return "Already filter with that id";
				}
				filters.put(cmds[1], new FilterForward(cmds[1],cmds.length>2?cmds[2]:"",dQueue));
				getFilter(cmds[1]).ifPresent( f -> f.addTarget(wr) );
				getFilter(cmds[1]).ifPresent( f -> f.addTarget(wr) );
				return "Temp filter with id "+cmds[1]+ " created"+(cmds.length>2?", with source"+cmds[2]:"")+".";
			case "reload":
				if( cmds.length != 2)
					return "Bad amount of arguments, should be filters:reload,id";
					Optional<Element> x = XMLfab.withRoot(xmlPath, "das","filters").getChild("filter", "id", cmds[1]);
					if( x.isPresent()){
						getFilter(cmds[1]).ifPresent( f -> f.readFromXML(x.get()));
					}else{
						return "No such filter, "+cmds[1];
					}
				return "Filter reloaded.";
			default: return "No such command";
		}
	}
	/* 	--------------------------------------------------------------------	*/
	public boolean addForwarding(String cmd, Writable writable) {

		if( writable != null ){
			Logger.info("Received data request from "+writable.getID()+" for "+cmd);
		}else if( cmd.startsWith("email") ){
			Logger.info("Received request through email for "+cmd);
		}
		String[] items = cmd.split(":");
		String type = items.length==2?items[0]:"id";
		String search = items.length==2?items[1]:items[0];

		switch( type ){
			case "label": case "ll":
				return getStream( search ).filter( bs -> bs.getLabel().startsWith(search) ).map( bs -> bs.addTarget(writable) ).orElse(false);
			case "generic": case "gen":
				for( String item : items[1].split(",")){
					streams.values().stream().filter( x -> x.getLabel().startsWith("generic:")||x.getLabel().startsWith("gen:")) // Label must start with generic
							.filter( x -> x.getLabel().contains(item))	  	   // Label must contain the word
							.forEach( x -> x.addTarget(writable) ); // add it, don't care about duplicates
				}
				break;
			case "title": case "id":
				return getStream(search).map( bs -> bs.addTarget(writable) ).orElse(false);
			case "filter":
				return getFilter(search).map( ff -> {ff.addTarget(writable);return true;} ).orElse(false);
			case "math":
				return getMath(search).map( mf -> { mf.addTarget(writable); return true;} ).orElse(false);
			case "editor":
				return getEditor(search).map( tf -> { tf.addTarget(writable); return true;} ).orElse(false);
			case "start":
				// Connect to all streams... but remove some? How to inform...
				break;
			default:
				Logger.warn("Unknown type: "+type+ " possible ones: title/id, label/ll, generic/gen, filter, math");
				return false;
		}

		return true;
	}

	/**
	 * Remove the given writable from the various sources
	 * @param wr The writable to remove
	 * @return True if any were removed
	 */
	public boolean removeForwarding(Writable wr) {
		int cnt =0;
		for( BaseStream bs : streams.values() ){
			if( bs.removeTarget(wr) )
				cnt++;
		}
		for( MathForward mf : maths.values() ){
			if( mf.removeTarget(wr) )
				cnt++;
		}
		for( FilterForward ff : filters.values() ){
			if( ff.removeTarget(wr) )
				cnt++;
		}
		for( var ef : editors.values() ){
			if( ef.removeTarget(wr) )
				cnt++;
		}
		return cnt > 0;
	}
	@Override
	public void collectorFinished(String id, String message, Object result) {
		String[] ids = id.split(":");
		switch( ids[0] ){
			case "confirm":
				confirmCollectors.remove(ids[1]);
				if (confirmCollectors.isEmpty())
					Logger.info("Confirm tasks are empty");
				break;
			case "math":
				dQueue.add( new Datagram( "store:"+message+","+result,1,"system") );
				break;
			default:
				Logger.error("Unknown Collector type: "+id);
				break;
		}
	}
	public void removeConfirm(String id){
		if( confirmCollectors.values().removeIf( cc -> cc.getID().equalsIgnoreCase(id)) ){
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
				Logger.warn(stream.getID()+" -> Connection invalid, waiting for reconnect");
				requestReconnection(stream.getID());
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
					Logger.warn(stream.getID()+" is idle for "+stream.readerIdleSeconds+"s");
					notifyIdle(stream.getID());
				}
			} else {
				// Read occurred before the timeout - set a new timeout with shorter delay.
				scheduler.schedule(this, nextDelay, TimeUnit.MILLISECONDS);
			}
		}
	}
}
