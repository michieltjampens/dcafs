package io.stream.tcp;

import das.Paths;
import io.stream.BaseStream;
import io.stream.StreamListener;
import io.Writable;
import das.Commandable;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;

public class TcpServer implements StreamListener, Commandable {

	private BlockingQueue<Datagram> dQueue; // TransHandler can also process other commands if given this queue

	private int serverPort = 5542; // The port the server is active on default is 5542
	private ChannelFuture serverFuture;

	private final HashMap<String,TransDefault> defaults = new HashMap<>();

	private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
	private final EventLoopGroup workerGroup;

	private final ArrayList<TransHandler> clients = new ArrayList<>();
	private final HashMap<String,ArrayList<Writable>> targets = new HashMap<>();

	private boolean active;

	public TcpServer(EventLoopGroup workerGroup) {
		this.workerGroup = workerGroup;
		active = readSettingsFromXML();
	}
	public boolean isActive(){
		return active;
	}

	/**
	 * Change the port of the server
	 * 
	 * @param port The new port to use
	 */
	public boolean setServerPort(int port) {
		if (serverPort != port && port != -1) {
			Logger.info("New port isn't the same as current one. current=" + this.serverPort + " req=" + port);
			serverPort = port;
			alterXML();
			restartServer();
			active=true;
		}
		return active;
	}
	/**
	 * Set the queue worked on by a @see BaseWorker
	 * 
	 * @param dQueue The queue from the BaseWorker
	 */
	public void setDataQueue(BlockingQueue<Datagram> dQueue) {
		this.dQueue = dQueue;
	}

	/**
	 * Read the settings related to the transserver from the settings.xml
	 * @return True if no hiccups
	 */
	private boolean readSettingsFromXML( ) {
		var dig = XMLdigger.goIn(Paths.settings(),"dcafs","transserver");

		if (dig.isValid()) {
			Logger.info("Settings for the TransServer found.");
			serverPort = dig.attr( "port", -1);
			if (serverPort == -1)
				serverPort = dig.peekAt("port").value(5542);
			defaults.clear();
			for( var client : dig.digOut("default")){
				TransDefault td = new TransDefault(client.attr("id",""),client.attr("address",""));
				td.setLabel( client.attr("label","system"));
				dig.peekOut("cmd").forEach(req -> td.addCommand( req.getTextContent() ));
				defaults.put(td.id, td );
			}
			if( !active )
				run();
			return true;
		}
		return false;
	}

	public void alterXML() {
		Logger.warn("Altering the XML");

		XMLfab fab = XMLfab.withRoot(Paths.settings(), "settings", "transserver").clearChildren().attr("port", this.serverPort);

		// Adding the clients
		for (Entry<String,TransDefault> cip : defaults.entrySet()) {
			fab.addChild("default").attr("address", cip.getValue().ip).attr("id", cip.getKey()).down();
			cip.getValue().getCommands().forEach(fab::addChild);
		}
		fab.build();
	}

	/* ********************************************************************************************************** **/
	public void run() {
		run(serverPort);
	}

	private void run(int port) {
		serverPort = port;
		// Netty
		try {
			ServerBootstrap b = new ServerBootstrap();
			b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 50)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						public void initChannel(SocketChannel ch){
							ch.pipeline().addLast("framer",
									new DelimiterBasedFrameDecoder(512, true, Delimiters.lineDelimiter()));
							ch.pipeline().addLast("decoder", new ByteArrayDecoder());
							ch.pipeline().addLast("encoder", new ByteArrayEncoder());

							TransHandler handler = new TransHandler("system", dQueue);
							handler.setListener(TcpServer.this);
							handler.setEventLoopGroup(workerGroup);
							clients.add(handler);

							ch.pipeline().addLast(handler);
						}
					});

			// Start the server.
			Logger.info("Starting TransServer on port " + port + " ...");
			serverFuture = b.bind(port);
			serverFuture.addListener((ChannelFutureListener) future -> {
				if (!future.isSuccess()) {
					Logger.error("Failed to start the TransServer (bind issue?)");
					System.exit(0);
				} else if (future.isSuccess()) {
					Logger.info("Started the TransServer.");
				}
			});
			serverFuture.sync();
		} catch (InterruptedException e) {
			Logger.error(e);
			// Restore interrupted state...
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Restart the server
	 */
	private void restartServer() {
		if (serverFuture != null) {
			serverFuture.cancel(true);
			run();
		}
	}

	/* ******************** * D E F A U L T  */
	@Override
	public void notifyIdle(BaseStream stream) {
		// Not used for server
	}

	@Override
	public boolean notifyActive(String title) {
		Logger.info(title+" is active");
		for( TransHandler th : clients ){
			if( th.id().equalsIgnoreCase(title) ){
				Logger.info("Found matching id "+title);
				for( TransDefault td : defaults.values() ){
					if( td.ip.equalsIgnoreCase(th.getIP())){
						th.writeHistory(td.commands);
						th.setID(td.id);
						th.setLabel(td.label);
						th.writeLine("Welcome back "+th.id()+"!");
						break;
					}
				}
				if(targets.containsKey(th.id())){
					targets.get(th.id()).forEach( th::addTarget );
				}
				return true;
			}else{
				Logger.info("No matching ID "+title+" vs "+th.id() );
			}
		}
		return false;
	}

	@Override
	public void notifyOpened(String id) {
		Logger.info(id+" is opened");
	}

	/**
	 * Used for the handler to inform the listener that its connection is closed
	 * @param id The id of the handler
	 */
	@Override
	public void notifyClosed(String id) {
		Logger.info(id+" is closed");
		
		if( clients.removeIf( client -> client.id().equalsIgnoreCase(id) ) ){
			Logger.info("Removed client handler for "+id);
		}else{
			Logger.info("Couldn't find handler for "+id);
		}
	}

	/**
	 * Handler can use this request to have the server reconnect its connection
	 * @param id The id of the handler
	 * @return True if successful
	 */
	@Override
	public boolean requestReconnection(String id) {
		return false;
	}

	private Optional<TransHandler> getHandler( String id ){
		int index = Tools.parseInt(id, -1);
		if( index != -1 ){
			return Optional.ofNullable(clients.get(index));
		}
		return clients.stream().filter( h->h.id().equalsIgnoreCase(id)).findFirst();
	}

	/**
	 * Store the TransHandler information in the xml file as a default
	 * @param handler The handler to store
	 * @param wr The writable that issued the command
	 */
	public void storeHandler( TransHandler handler, Writable wr ){
		XMLfab fab = XMLfab.withRoot(Paths.settings(), "settings", "transserver");

		fab.selectOrAddChildAsParent("default","id",handler.id());
		fab.attr("address",handler.getIP());
		if( handler.getLabel().equalsIgnoreCase("system")){
			fab.removeAttr("label");
		}else{
			fab.attr("label",handler.getLabel() );
		}
		fab.clearChildren(); // start from scratch
		for( String h : handler.getHistory() ){
			fab.addChild("cmd",h);
		}
		if( fab.build() ){
			wr.writeLine(handler.id()+" Stored!");
		}else{
			wr.writeLine("Storing "+handler.id()+" failed");
		}
	}

	/**
	 * Get a list of all the clients currently connected to the server
	 * @return the list with format index -> id -> history
	 */
	public String getClientList(){
		StringJoiner join = new StringJoiner("\r\n");
		join.setEmptyValue("No connections yet.");
		int index=0;

		for( TransHandler th : clients ){
			join.add( index +" -> "+th.id()+" --> "+th.getHistory(" "));
			index++;
		}
		return join.toString();
	}
	public Optional<Writable> getClientWritable( String id){
		for( var h:clients){
			if(h.id().equalsIgnoreCase(id))
				return Optional.of(h.getWritable());
		}
		return Optional.empty();
	}
	/**
	 * Execute and reply to commands given in as a readable string
	 * @param d The datagram containing all info needed to process the command
	 * @return the reply
	 */
	@Override
	public String replyToCommand(Datagram d) {
		String[] args = d.argList();
		var wr = d.getWritable();

		if( !active ) {
			if (args[0].equalsIgnoreCase("start") && args.length == 2) {
				if (setServerPort(NumberUtils.toInt(args[1], -1)))
					return "Server started on " + args[1];
				return "Invalid port number given.";
			}else{
				return "No server active yet, use ts:start,port to start one";
			}
		}
		if (args[0].equals("create"))
			return "! Server already exists";

		Optional<TransHandler> hOpt = args.length > 1 ? getHandler(args[1]) : Optional.empty();

		switch (args[0]) {
			case "?":
				return doCmdHelp(d.asHtml());
			case "store":
				if( hOpt.isEmpty() )
					return "Invalid id";
				var handler = hOpt.get();
				handler.setID(args.length == 3 ? args[2] : handler.id());
				storeHandler(handler,wr);
				return "Stored";
			case "add":
				return doAddCmd(args);
			case "clear":
				return getHandler(args[1]).map(h -> {
					h.clearRequests();
					return args[1] + "  cleared.";
				}).orElse("No such client: " + args[1]);
			case "defaults":
				StringJoiner lines = new StringJoiner("\r\n");
				defaults.forEach( (id,val) -> lines.add(id+" -> "+val.ip+" => "+String.join(",",val.commands)));
				return lines.toString();
			case "reload":
				if( readSettingsFromXML() )
					return "Defaults reloaded";
				return "Reload failed";
			case "alter":
				return doAlterCmd(args, hOpt.orElse(null));
			case "trans" :
				if (args.length != 2)
					return "Not enough arguments, need trans:id";
				args[1] = "forward" + args[1];
			case "forward":
				return doForwardCmd(args, hOpt.orElse(null), wr);
			case "": case "list": 
				return "Server running on port "+serverPort+"\r\n"+getClientList();
			default:
				return "! No such subcommand in " + d.getData();
		}
	}
	private String doCmdHelp( boolean html ){
		StringJoiner j = new StringJoiner("\r\n");
		j.add( "TCP server to act as alternative to the telnet interface");
		j.add("General")
				.add( "ts:store,id/index<,newid> -> Store the session in the xml, optional id")
				.add( "ts:add,id/index,cmd -> Add the cmd to the id/index")
				.add( "ts:clear,id/index -> Clear all cmds from the client id/index")
				.add( "ts:list -> List of all the connected clients")
				.add( "ts:defaults -> List of all the defaults")
				.add( "ts:alter,id,ref:value -> Alter some settings")
				.add( "ts:forward,id -> Forward data received on the trans to the issuer of the command")
				.add( "ts:reload -> Reload the xml settings.");
		return LookAndFeel.formatCmdHelp(j.toString(),html);
	}
	private String doAddCmd( String[] cmds ){
		return getHandler(cmds[1]).map( h -> {
				StringJoiner join = new StringJoiner(",");
				for( int a=2;a<cmds.length;a++ )
					join.add(cmds[a]);

				h.addHistory(join.toString());
				return cmds[1]+"  added "+join;
			}).orElse("No such client: "+cmds[1]);
	}
	private String doAlterCmd( String[] cmds, TransHandler th){
		if( th==null )
			return "Invalid id";
		if( cmds.length<3)
			return "Not enough arguments: trans:alter,id,ref:value";
		String ref = cmds[2].substring(0,cmds[2].indexOf(":"));
		String value =  cmds[2].substring(cmds[2].indexOf(":")+1);
		switch (ref) {
			case "label" -> {
				th.setLabel(value);
				return "Altered label to " + value;
			}
			case "id" -> {
				th.setID(value);
				return "Altered id to " + value;
			}
			default -> {
				return "Nothing called " + ref;
			}
		}
	}
	private String doForwardCmd(String[] cmds, TransHandler th, Writable wr){
		if( cmds.length==1)
			return "No enough parameters given, needs ts:forward,id";

		if( defaults.containsKey(cmds[1]) || th!=null ) {
			if (!targets.containsKey(cmds[1])) {
				targets.put(cmds[1], new ArrayList<>());
			}
			var list = targets.get(cmds[1]);

			if( !list.contains(wr)){// no exact match
				list.removeIf(Objects::isNull); // Remove invalid ones
				list.removeIf( w -> w.id().equalsIgnoreCase(wr.id()));// Remove id match
				list.add(wr);
			}
		}
		return Optional.ofNullable(th).map( h -> {
			h.addTarget(wr);
			return "Added to target for "+cmds[1];
		}).orElse(cmds[1]+" not active yet, but recorded request");
	}
	public String payloadCommand( String cmd, String args, Object payload){
		return "! No such cmds in "+cmd;
	}
	@Override
	public boolean removeWritable(Writable wr) {
		boolean removed=false;
		for( var list : targets.values()) {
			if( list.removeIf(w -> w.equals(wr)))
				removed=true;
		}
		return removed;
	}

	/**
	 * Class to hold the info regarding a default, this will allow history of a connection to be reproduced.
	 * Eg. if the default is stored with calc:clock, this will be applied if the that client connects again
	 */
	public static class TransDefault{
		String ip;
		String id;
		String label="system";
		ArrayList<String> commands = new ArrayList<>();

		public TransDefault( String id, String ip ){
			this.ip=ip;
			this.id=id;
		}
		public void setLabel(String label){
			this.label=label;
		}
		public void addCommand( String cmd ){
			if( !commands.contains(cmd))
				commands.add(cmd);
		}
		public List<String> getCommands(){return commands;}
	}
}