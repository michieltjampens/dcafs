package io.stream.tcp;

import das.Core;
import io.Writable;
import io.netty.channel.*;
import io.stream.StreamListener;
import org.tinylog.Logger;
import worker.Datagram;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public class TransHandler extends SimpleChannelInboundHandler<byte[]> implements Writable {

	private String id="";
	private String label;
	private StreamListener listener;
	private Channel channel;
	private InetSocketAddress remote;
	private final List<Writable> targets = new ArrayList<>();
	private static final String eol="\r\n";
	private final ArrayList<String> history = new ArrayList<>();
	String repeat="";
	boolean keepHistory=false;
	private EventLoopGroup eventLoopGroup;

    public TransHandler( String label ){
        this.label=label;
    }

	/**
	 * Set the id of this handler
	 * @param id The id to set
	 */
	public void setID(String id){
		this.id=id;
	}
	public void setLabel(String label){
		this.label=label;
	}
	public String getLabel(){
		return label;
	}
	public void addTarget( Writable wr){
		targets.removeIf( w -> w.equals(wr) || w.id().equalsIgnoreCase(wr.id()));
		targets.add(wr);
	}
	public void setEventLoopGroup(EventLoopGroup eventLoopGroup) {
		this.eventLoopGroup=eventLoopGroup;
	}
	/**
	 * Set the listener of the server here
	 * @param listener The listener aka server
	 */
	public void setListener( StreamListener listener){
        this.listener=listener;
    }
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
			Logger.info(id+ " triggered "+evt);
    }
    @Override
    public void channelActive(ChannelHandlerContext ctx) {

		channel = ctx.channel();			// Store the channel for future use
		
		if( channel.remoteAddress() != null){					// If the remote address is not null
			remote = (InetSocketAddress)ctx.channel().remoteAddress();	// Store this as remote address
			Logger.info("Connection established to "+remote.getHostName());
		}else{
			Logger.error( "Channel.remoteAddress is null in channelActive method");
		}

		if (channel == null) {      // If the channel is invalid, return
			Logger.error("Channel is null. (handler:" + id() + ")");
			return;
		}
		channel.flush();

		if (listener != null && !listener.notifyActive(id()))
			channel.writeAndFlush("Hello?\r\n".getBytes());

		ChannelFuture closeFuture = channel.closeFuture();
		closeFuture.addListener((ChannelFutureListener) future -> {
			future.cancel(true);
			if (channel != null) channel.close();
			if (listener != null) listener.notifyClosed(id());
		});
	}    
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {    
		// Not used 
    }
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) { 
		//Not used
    }
    @Override
    public void channelRead0(ChannelHandlerContext ctx, byte[] data) throws Exception {

		String tempLabel=label;
		String msg = new String( data );	// Convert the raw data to a readable string

		if( msg.startsWith(">>>") ) {
			msg=handleCmds(msg);
			if(msg.isEmpty())
				return;
		}else if(keepHistory){
			history.add(msg);
		}
		if(msg.endsWith("!!")){
			repeat = msg.length()==2?"":msg.substring(0,msg.length()-2);
			return;
		}

		Core.addToQueue(Datagram.build(repeat + msg).label(tempLabel).writable(this));

		if( !targets.isEmpty() && !tempLabel.equals("system")){
			String tosend = repeat+new String(data);
			targets.forEach(dt -> eventLoopGroup.submit(() -> dt.writeLine("", tosend)));
			targets.removeIf(wr -> !wr.isConnectionValid() ); // Clear inactive
		}
   }
	private String handleCmds( String msg ){
		msg = msg.substring(3);

		String[] cmds = msg.split(":");
		if (msg.startsWith("label:")) {
			label = msg.substring(6);
			writeLine("Altered label to "+label);
			return "ts:alter," + id() + ",label:" + label;
		}

		if (msg.startsWith("id:")) {
			this.id = msg.split(":")[1];
			writeLine("Altered id to " + id);
			return "";
		}
		if (msg.startsWith("store")) {
			// Somehow save to xml?
			writeLine("Stored setup to xml (and no longer recording history)");
			keepHistory=false;
			if( cmds.length==2)
				return "ts:store," + id()+","+cmds[1];
			return "ts:store," + id();
		}
		if (msg.startsWith("record")) {
			keepHistory=true;
			writeLine("Recording history");
		}else if (msg.startsWith("forget")) {
			history.clear();
		}else{
			switch (msg) {
				case "?" -> writeLine( doHelpCmd() );
				case "id?" -> writeLine("id is " + id);
				case "label?" -> writeLine("label is " + label);
				default -> {
					Logger.warn("Unknown message " + msg + " from " + id);
					writeLine("Unknown command, try >>>? for a list");
				}
			}
		}
		return "";
	}

	private static String doHelpCmd() {
		var help = new StringJoiner("\r\n");
		help.add(">>>? -> Returns this message");
		help.add(">>>label:newlabel  -> Change the label to the new label");
		help.add(">>>id:newid  -> Change the id to the new id");
		help.add(">>>store(:newid)   -> Store the session as default, including recorded history");
		help.add(">>>record -> Commands send are stored in the history, which will be default on store");
		help.add(">>>forget -> Clears the current history");
		help.add(">>>id? -> returns the current id");
		help.add(">>>label? -> returns the current label");
		return help.toString();
	}

	/**
	 * Write the given data without changing it
	 * @param data The data to write
	 * @return True if the channel was ok
	 */
    public boolean writeString(String data) {
        if( channel==null)
            return false;
        channel.writeAndFlush(data.getBytes());
        return true;
    }
	/**
	 * Write the given data and eol
	 * @param data The data to write
	 * @return True if the channel was ok
	 */
    public boolean writeLine(String data) {
        if( channel==null)
            return false;
        channel.writeAndFlush((data+eol).getBytes());
        return true;
    }
	public boolean writeLine(String origin, String data) {
		return writeLine(data);
	}
	/**
	 * Write the given binary data
	 * @param data The data to write
	 * @return True if the channel was ok
	 */
	public boolean writeBytes(byte[] data) {
		if( channel==null)
			return false;
		channel.writeAndFlush(data);
		return true;
	}
	/**
	 * Disconnect the handler
	 * @return True if disconnected
	 */
	public boolean disconnect(){
		if (channel == null)
			return false;
		channel.close();
        return true;
    }

	/**
	 * Check if the connection is valid
	 * @return True if it's still active
	 */
	public boolean isConnectionValid(){
        return channel!=null && channel.isActive();
    }

	/**
	 * Get the IP where this connection originates from
	 * @return The ip
	 */
	public String getIP(){
		return remote.getAddress().getHostAddress();
	}

	/**
	 * Add a command to the history of this handler and execute it
	 * @param cmd The command to add
	 */
	public void addHistory( String cmd ){
		history.add(cmd);
		Logger.info(id+" -> Adding history: "+cmd);
		useQueue(cmd);
	}

	/**
	 * Add a list of commands to the history of this handler and execute them
	 * @param data The List containing the commands
	 */
	public void writeHistory( List<String> data ){
		data.forEach( this::addHistory );
	}
	@Override
	public String id() {
		if (id.isEmpty())
			return remote.getHostName();
		return id;
	}
	public void clearRequests(){
		useQueue("nothing");
	}

	/**
	 * Send a command to be processed
	 * @param cmd The command to execute
	 */
	private void useQueue(String cmd){
		Core.addToQueue( Datagram.system(cmd).writable(this).toggleSilent() );
	}

	/**
	 * Get a list of this handlers history
	 * @param delimiter The delimiter to use between elements
	 * @return Delimited listing of the handlers history
	 */
	public String getHistory(String delimiter){
		return String.join(delimiter, history);
	}

	/**
	 * This handlers history
	 * @return The history List
	 */
	public List<String> getHistory(){
		return history;
	}
}
