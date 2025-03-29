package io.stream.tcp;

import das.Core;
import io.Writable;
import io.netty.channel.*;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.stream.StreamListener;
import org.tinylog.Logger;
import worker.Datagram;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class TcpHandler extends SimpleChannelInboundHandler<byte[]>{

    protected boolean idle=false;

    protected String id;
    protected String label="";
    protected int priority = 1;

    protected List<StreamListener> listeners;

    protected Channel channel;
    protected boolean log=true;

    protected Long timeStamp=-1L;

    protected InetSocketAddress remote;
    protected Writable writable;
    protected CopyOnWriteArrayList<Writable> targets;

    protected EventLoopGroup eventLoopGroup;

    String eol="\r\n";
    boolean udp=false;

    public TcpHandler( String id ){
        this.id=id;
    }

    public TcpHandler(String id, Writable writable) {
        this.id=id;
        this.writable=writable;
    }

    public void setTargets(CopyOnWriteArrayList<Writable> targets) {
        this.targets = targets;
    }
    public long getTimestamp(){
        return timeStamp;
    }
    public void setPriority( int priority){this.priority=priority;}
    public void toggleUDP(){
        udp=!udp;
    }
    /* StreamListener */
    public void setStreamListeners( List<StreamListener> listeners ){
        this.listeners=listeners;
    }
    public void addStreamListener( StreamListener listener){
        if( listeners==null)
            listeners = new ArrayList<>();
        listeners.add(listener);
    }
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
    	if (evt instanceof IdleStateEvent e) {
            if (e.state() == IdleState.READER_IDLE ) {
                Logger.error( "READER IDLE for "+id);
            }else if (e.state() == IdleState.WRITER_IDLE) {
            	Logger.error( "WRITER IDLE for "+id);
            }else {
            	Logger.error( "Something went Wrong");
            }
        }else{
    	    Logger.info(id+" -> Unknown user event... "+evt);
        }
    }
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised, but don't send messages if it's related to remote ignore	
		String address = ctx.channel().remoteAddress().toString();

        if (cause instanceof TooLongFrameException) {
			Logger.warn(id+" -> Unexpected exception caught: "+cause.getMessage(), true);
			ctx.flush();
		}else if( cause instanceof java.net.PortUnreachableException){
			if( !udp ){
				Logger.error("Device/Port unreachable, probably offline: "+address);
				ctx.flush();
				ctx.close();							// Close the channel
			}		
		}else{
		    Logger.error(cause);
			Logger.error( id+" -> Unexpected exception caught: " + cause.getMessage() );
			ctx.close();							// Close the channel
		}
	}
    @Override
    public void channelActive(ChannelHandlerContext ctx) {

		channel = ctx.channel();			// Store the channel for future use
		
		if( channel.remoteAddress() != null){					// Incase the remote address is not null
			remote = (InetSocketAddress)ctx.channel().remoteAddress();	// Store this as remote address
		}else{
			Logger.error( "Channel.remoteAddress is null in channelActive method");
		}
	
        Logger.info("Channel Opened: "+ctx.channel().remoteAddress() +" ("+(label.isEmpty()?"No label":label)+")");
        if( !label.equals("telnet")&&!label.equals("trans") && !id.isBlank()){
            listeners.forEach( l-> l.notifyOpened(id) );
        }     
		
        ChannelFuture closeFuture = channel.closeFuture();           
        closeFuture.addListener((ChannelFutureListener) future -> {
             future.cancel(true);

             Logger.info( "Channel Closed! "+ remote.toString() +" ("+label +")");

             if( channel!=null)
                 channel.close();

             listeners.forEach( l-> l.requestReconnection(id));
             listeners.forEach( l-> l.notifyClosed(id));

         });
	}    
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {   
        // Don't care about this  
    }
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) { 
        // Don't care about this     
    }
    @Override
    public void channelRead0(ChannelHandlerContext ctx, byte[] data) throws Exception {
       
       String msg = new String( data );	// Convert the raw data to a readable string
	   
	   if( idle ){
		    idle=false;
		    listeners.forEach( l-> l.notifyActive(id));
       }
        if (msg.isBlank())//make sure that the received data is not 'null' or an empty string
            return;

        msg = msg.replace("\n", "");   // Remove newline characters
        msg = msg.replace("\r", "");   // Remove carriage return characters
        msg = msg.replace("\0", "");    // Remove null characters

        // Log anything and everything (except empty strings)
        // If the message isn't an empty string and logging is enabled, store the data with logback
        if (!msg.isBlank() && log)
            Logger.tag("RAW").warn(id + "\t" + msg);

        // Implement the use of labels
        if (!label.isEmpty()) { // No use adding to queue without label
            Core.addToQueue(Datagram.build(msg)
                    .label(label)
                    .origin(id)
                    .priority(priority)
                    .writable(writable)
            );
        }

        // Forward data to targets
        if (targets.isEmpty())
            return;
        String tosend = new String(data);
        try {
            targets.parallelStream().forEach(wr -> wr.writeLine(id, tosend));// Concurrent sending to multiple writables
            targets.removeIf(wr -> !wr.isConnectionValid()); // Clear inactive
        } catch (ConcurrentModificationException e) {
            Logger.error(e);
        }

        // Keep the timestamp of the last message
        timeStamp = Instant.now().toEpochMilli();            // Store the timestamp of the received message
	}
    public boolean writeString(String data) {
        if( channel==null || !channel.isActive() )
            return false;
        channel.writeAndFlush(data.getBytes());
        return true;
    }
    public boolean writeLine(String data) {
        if( channel==null || !channel.isActive() )
            return false;
        channel.writeAndFlush((data+eol).getBytes()); 
        return true;
    }
    public boolean writeBytes(byte[] data) {
        if( channel==null || !channel.isActive() )
            return false;
        channel.writeAndFlush(data);
        return true;
    }
    public boolean disconnect(){
        if( channel != null ){
            channel.disconnect();
            return true;
        }
        return false;
    }
    public void setLabel( String label){
        this.label=label;
    }
    public void flagAsIdle(){
        idle=true;
    }
    public boolean isIdle(){
        return idle;
    }
    public boolean isConnectionValid(){
        return channel!=null&&channel.isActive();
    }

    public void setEventLoopGroup(EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup=eventLoopGroup;
    }
}
