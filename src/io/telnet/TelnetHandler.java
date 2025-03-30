package io.telnet;

import das.Core;
import das.Paths;
import io.Writable;
import io.netty.channel.*;
import io.netty.handler.codec.TooLongFrameException;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class TelnetHandler extends SimpleChannelInboundHandler<byte[]> implements Writable {

	/* Pretty much the local descriptor */
	protected static final String LABEL = "cmd";			// The label that determines what needs to be done with a message
	protected Channel channel;	// The channel that is handled
	protected String remoteIP = "";		// The ip of the handler
	private InetSocketAddress remote;

	protected String newLine = "\r\n";			// The string to end the messages send with		
	protected String lastSendMessage="";			// The last message that was send

	/* OTHER */
	protected ArrayList<String> ignoreIP= new ArrayList<>();	// List of IP's to ignore, not relevant for StreamHandler, but is for the telnet implementation
	protected boolean log=true;	// Flag that determines if raw data needs to be logged

	private final HashMap<String,String> macros = new HashMap<>();
	private ArrayList<String> hist;
 	String repeat = "";
	String title = "dcafs";
	String id="telnet";
	String start="";
	CommandLineInterface cli;
	ArrayList<String> onetime = new ArrayList<>();
	ArrayList<String> ids = new ArrayList<>();
	private boolean prefix=false,ts=false,ds=false,es=false;
	private long elapsed=-1;
	private String format="HH:mm:ss.SSS";
	private String default_text_color=TelnetCodes.TEXT_LIGHT_GRAY;
	boolean bootOk;
	/* ****************************************** C O N S T R U C T O R S ******************************************* */
	/**
	 * Constructor that requires both the BaseWorker queue and the TransServer queue
	 *
	 * @param ignoreIPlist list of ip's to ignore (meaning no logging)
	 */
    public TelnetHandler(String ignoreIPlist, boolean bootOk){
		this.bootOk=bootOk;
		ignoreIP.addAll(Arrays.asList(ignoreIPlist.split(";")));
		ignoreIP.trimToSize();
	}
	public void addOneTime(String mess){
		onetime.add(mess);
	}
	/* ************************************** N E T T Y  O V E R R I D E S ********************************************/

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
		Logger.info("Not implemented yet - user event triggered");
    }
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
    	
		channel = ctx.channel();			// Store the channel for future use
		
		if( channel.remoteAddress() != null){					// Incase the remote address is not null
			remote = (InetSocketAddress)ctx.channel().remoteAddress();	// Store this as remote address
			remoteIP = remote.getAddress().getHostAddress();
			if( remote.getAddress() instanceof Inet4Address){
				Logger.debug("IPv4: "+ remote.getAddress());
			}else{
				Logger.debug("IPv6: "+ remote.getAddress());
			}
			readXMLsettings();
		}else{
			Logger.error( "Channel.remoteAddress is null in channelActive method");
		}

		cli = new CommandLineInterface(channel); // Start the cli
		cli.setHistory(hist);
		if( bootOk ) {
			showWelcomeMessage();

			if (!start.isEmpty()) { // Send the init cmds
				Core.addToQueue(Datagram.build(start).label(LABEL).writable(this).origin("telnet:" + channel.remoteAddress().toString()).toggleSilent());
			}
		}else{
			writeLine(TelnetCodes.TEXT_RED + "Issue in settings.xml, can't start up properly! Please fix! " + TelnetCodes.TEXT_ORANGE);
			writeLine( ">>> LAST 15ish lines of the errors log<<<<");
			var data = FileTools.readLastLines(Paths.storage()
									.resolve("logs")
									.resolve("errors_"+ TimeTools.formatUTCNow("yyMMdd") +".log"),15);
			boolean wait = true;
			for( String d : data){
				if (d.startsWith("20"))
					wait = false;
				if (!wait)
					writeLine(d);
			}
			writeLine("Press <enter> to shut down dcafs...");
		}
	}
	private void readXMLsettings( ){
		var dig = XMLdigger.goIn( Paths.settings(),"dcafs","settings","telnet");
		if( dig.hasPeek("client","host",remote.getHostName()) ){
			dig.usePeek(); // Because we want to go down the level
			id = dig.attr("id",id); // fetch the id attribute
			if( dig.hasPeek("start")) // Check if there's a start node
				start = dig.value(""); // If so fetch the content

			for( var macro: dig.digOut("macro")){
				var ref = macro.attr("ref","");
				if( !ref.isEmpty())
					macros.put( ref,macro.value(""));
			}
		}
	}
	private void showWelcomeMessage(){
		writeString(TelnetCodes.TEXT_RED + "Welcome to " + title + "!\r\n" + TelnetCodes.TEXT_RESET);
		writeString(TelnetCodes.TEXT_GREEN + "It is " + new Date() + " now.\r\n" + TelnetCodes.TEXT_RESET);
		writeString(TelnetCodes.TEXT_BRIGHT_BLUE + "> Common Commands: [h]elp,[st]atus, rtvals, exit...\r\n");
		if( !onetime.isEmpty() ) {
			writeLine(TelnetCodes.TEXT_RED);
			writeLine("");
			writeLine("ERRORS DETECTED DURING STARTUP");
			onetime.forEach(this::writeLine);
			onetime.clear();
		}
		writeString(TelnetCodes.TEXT_DEFAULT + ">");
		channel.flush();
	}
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {   
		Logger.debug("Not implemented yet - channelRegistered");
    }
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) { 
		Logger.debug("Not implemented yet - channelUnregistered");
		Core.addToQueue( Datagram.system("nb").writable(this)); // Remove this from the writables when closed
    }
    @Override
    public void channelRead0(ChannelHandlerContext ctx, byte[] data) {

		var recOpt = cli.receiveData(data);

		if( recOpt.isEmpty())
			return;
		var rec = recOpt.get();

		writeString(newLine); // Without this, the reply will overwrite the data

		if (!bootOk)
			System.exit(0);

		distributeMessage(
				Datagram.build(rec)
						.label(LABEL)
						.writable(this)
						.origin("telnet:"+channel.remoteAddress().toString())
		);
	}

	public void distributeMessage( Datagram d ){
		d.label( LABEL+":"+repeat );
		d.setData(d.getData().stripLeading()); // Remove leading empty spaces if any

		if( d.getData().endsWith("!!") ) {
			if( d.getData().length()>2) {
				repeat += d.getData().replace("!!", "");
				writeString("Prefix changed to '"+repeat+"'\r\n"+TelnetCodes.TEXT_YELLOW+repeat+">"+default_text_color);
			}else {
				d.label(LABEL);
				repeat="";
				writeString("Prefix cleared!\r\n>");
			}
			return;
		}else if( d.getData().startsWith(">>")) {
			doCmds(d);
			return;
		}else{
			d.setData(repeat+d.getData());
		}
		var macro = macros.get(d.getData());
		if( macro!=null)
			d.setData(macro);
		if ( d.getData().equalsIgnoreCase("bye")||d.getData().equalsIgnoreCase("exit")) {
			// Close the connection after sending 'Have a good day!' if the client has sent 'bye' or 'exit'.
			ChannelFuture future = channel.writeAndFlush( "Have a good day!\r\n");   			
			future.addListener(ChannelFutureListener.CLOSE);
        } else {
			Core.addToQueue( d );
        }
	}
	private void doChangeIdCmd( String id ){
		var dig = XMLdigger.goIn(Paths.settings(),"dcafs","settings","telnet");
		if( dig.isInvalid() ) {
			writeString("! No telnet node yet");
			return;
		}
		if( dig.peekAt("client","id",id).hasValidPeek()){
			writeLine("ID already in use");
		}else{
			var fabOpt = XMLfab.alterDigger(dig);
			fabOpt.ifPresent(fab -> {
				fab.addChild("client").attr("id", id).attr("host", remote.getHostName());
				fab.build();
			});
			writeLine("ID set to "+id);
		}
		this.id = id;
	}
	private void doCmds( Datagram d ){
		var split = new String[2];
		String[] cmds = {"prefix", "ts", "ds", "id?", "es", "prefixid", "?"}; // Cmds that don't require arguments

		String cmd = d.getData().substring(2); // remove >>
		if(cmd.startsWith(">")) // If three are used
			cmd=cmd.substring(1);

		if( !d.getData().contains(":") && !Arrays.asList(cmds).contains(cmd) ){
			writeLine("Missing ':'");
			return;
		}

		if( cmd.contains(":")) {
			split[0] = cmd.substring(0, cmd.indexOf(":"));
			split[1] = cmd.substring(split[0].length() + 1);
		}else{
			split[0]=cmd;
			split[1]="";
		}

		switch (split[0]) {
			case "?" -> writeLine(doHelpCmd());
			case "id?" -> writeLine( id.equalsIgnoreCase("telnet")?"No id set yet":"Current id: "+id);
			case "id" -> doChangeIdCmd(split[1]);
			case "talkto" -> {
				writeString("Talking to " + split[1] + ", send !! to stop\r\n>");
				repeat = "telnet:write," + split[1] + ",";
			}
			case "start" -> doStartCmd(split[1]);
			case "color" -> {
				default_text_color = TelnetCodes.colorToCode(split[1],default_text_color);
				writeString(default_text_color+"Color changed...?\r\n>");
			}
			case "macro" -> doMacroCmd(split[1]);
			case "ts" -> {
				ts = !ts;
				format = split[1].isEmpty()?"HH:mm:ss.SSS":split[1];
				writeLine("Time stamping " + (ts ? "enabled" : "disabled"));
			}
			case "ds" -> {
				ds = !ds;
				writeLine("Date stamping " + (ds ? "enabled" : "disabled"));
			}
			case "es" -> {
				es = !es;
				writeLine("Elapsed time stamping " + (es ? "enabled" : "disabled"));
			}
			case "prefix", "prefixid" -> {
				prefix = !prefix;
				writeLine("Prefix " + (prefix ? "enabled" : "disabled"));
			}
			case "clearhistory","clrh" -> cli.clearHistory();
			default -> 	writeLine("Unknown telnet command: " + d.getData());
		}
	}

	private static String doHelpCmd() {
		var join = new StringJoiner("\r\n");
		join.add("Commands available in a Telnet session");
		join.add(">>>id? -> Returns the current id if set")
				.add(">>>id:newid -> Change the id to the new id")
				.add(">>>color:newcolor -> Change the default text color")
				.add(">>>macro:shortcut->cmd -> Adds a macro that is called with shortcut and send cmd.")
				.add(">>>clearhistory or >>>clrh -> Clear the history of issued commands");
		join.add("Prefixes")
				.add(">>>ts -> Show the time data was received")
				.add(">>>ds -> Show the full timestamp of when the data was received")
				.add(">>>es -> Show the time since the previous data was received")
				.add(">>>prefixid -> Prefix the id in front of the received data");
		return LookAndFeel.formatCmdHelp(join.toString(), false);
	}
	private void doStartCmd(String cmd){
		if (id.isEmpty() || id.equalsIgnoreCase("telnet")) {
			writeLine("Please set an id first with >>id:newid");
		}else {
			start = cmd;
			writeLine("Startup command has been set to '" + start + "'");
			writeLine( XMLfab.withRoot(Paths.settings(), "dcafs", "settings", "telnet").selectChildAsParent("client", "id", id)
					.map(f -> {
						f.addChild("start", cmd);
						f.build();
						return "Result of " + cmd + " will be shown on log in.\r\n>";
					}).orElse("Couldn't find the node?"));
		}
	}
	private void doMacroCmd( String macro ){
		if (!macro.contains("->")) {
			writeLine("Missing ->");
		}else {
			var ma = macro.split("->");
			writeString(XMLfab.withRoot(Paths.settings(), "dcafs", "settings", "telnet").selectChildAsParent("client", "id", id)
					.map(f -> {
						f.addChild("macro", ma[1]).attr("ref", ma[0]).build();
						macros.put(ma[0], ma[1]);
						return "Macro " + ma[0] + " gets replaced with '" + ma[1] + "'\r\n>";
					}).orElse("Couldn't find the node\r\n>"));
		}
	}
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
       ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised, but don't send messages if it's related to remote ignore	
		String addr = ctx.channel().remoteAddress().toString();
		Core.addToQueue(Datagram.system("nb"));
		if (cause instanceof TooLongFrameException){	
			Logger.warn("Unexpected exception caught"+cause.getMessage()+" "+addr, true); 
			ctx.flush();
		}
	}
	public void setDefaultColor( String color ){
		default_text_color=color;
	}
	/* *************************************** W R I T A B L E  ******************************************************/
	/**
	 * Sending data that will be appended by the default newline string.
	 * @param message The data to send.
	 * @return True If nothing was wrong with the connection
	 */
	public synchronized boolean writeLine( String message ){
		if( message.split("\r\n").length>25 && !message.contains("[0;")){
			boolean toggle=false;
			for( var m : message.split("\r\n")) {
				var color = toggle?TelnetCodes.TEXT_DEFAULT:TelnetCodes.TEXT_YELLOW;
				writeString(color + m + newLine);
				toggle=!toggle;
			}
			writeString(TelnetCodes.TEXT_DEFAULT);
			return true;
		}else {
			return writeString(message + newLine);
		}
	}

	@Override
	public boolean writeLine(String origin, String data) {
		if( data.equalsIgnoreCase("Clearing requests")) {
			ids.clear();
		} else if (!ids.contains(origin) && origin != null) {
			ids.add(origin);
		}
		String time = "", elapsedPeriod = "";

		if( ts || ds)
			time = TelnetCodes.TEXT_ORANGE + TimeTools.formatUTCNow(ts ? format : "yyyy-MM-dd HH:mm:ss.SSS") + "  " + TelnetCodes.TEXT_DEFAULT;
		if( es ) {
			if (this.elapsed != -1) {
				var period = String.format("%8s", TimeTools.convertPeriodToString(Instant.now().toEpochMilli() - this.elapsed, TimeUnit.MILLISECONDS));
				elapsedPeriod = TelnetCodes.TEXT_CYAN + period + "  " + TelnetCodes.TEXT_DEFAULT;
			} else {
				elapsedPeriod = TelnetCodes.TEXT_CYAN + "  0ms  " + TelnetCodes.TEXT_DEFAULT;
			}
			this.elapsed = Instant.now().toEpochMilli();
		}
		if(prefix) {
			var end = ids.get(ids.size()-1).equals(origin)?newLine+"------------- ("+ids.size()+")":"";
			if( ids.size()==1)
				end="";
			var length = ids.stream().mapToInt(String::length).max().orElse(0);
			origin = Tools.addTrailingSpaces(origin,length);
			return writeLine(time + elapsedPeriod + TelnetCodes.TEXT_MAGENTA + origin + TelnetCodes.TEXT_DEFAULT + "  " + data + end);
		}
		return writeLine(time + elapsedPeriod + data);
	}

	/**
	 * Sending data that won't be appended with anything
	 * @param message The data to send.
	 * @return True If nothing was wrong with the connection
	 */
	public synchronized boolean writeString( String message ){					
		if( channel != null && channel.isActive()){
			message = message.replace(TelnetCodes.TEXT_DEFAULT,default_text_color);
			channel.writeAndFlush(message.getBytes());
			lastSendMessage = message;	// Store the message for future reference		
			return true;
		}
		return false;
	}
	public synchronized boolean writeBytes( byte[] data ){
		if( channel != null && channel.isActive()){
			channel.writeAndFlush(data);
			lastSendMessage = new String(data);	// Store the message for future reference
			return true;
		}
		return false;
	}

	@Override
	public boolean isConnectionValid() {
		if( channel==null)
			return false;
		return channel.isActive();
	}

	/* ***********************************************************************************************************/
	/**
	 * Change the title of the handler, title is used for telnet client etc representation
	 * @param title The new title
	 */
	public void setTitle( String title ) {
    	this.title=title;
	}
	/**
	 * Get the title of the handler
	 * @return The title
	 */
	public String getTitle( ){
		return title;
	}

	/* ************************************* S T A T U S *********************************************************/
	/**
	 * Get the channel object
	 * @return The channel
	 */
	public Channel getChannel(){
		return channel;
	}
	@Override
	public String id() {
		return "telnet:"+(id.isEmpty()?LABEL:id);
	}

	public void setCmdHistory(ArrayList<String> history) {
		hist=history;
		Logger.info("Giving history");
		for( var h:history)
			Logger.info("->"+h);
	}
}