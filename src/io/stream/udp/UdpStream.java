package io.stream.udp;

import io.Writable;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.util.concurrent.FutureListener;
import io.stream.BaseStream;
import io.stream.tcp.TcpHandler;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.LookAndFeel;
import worker.Datagram;

import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;

public class UdpStream extends BaseStream implements Writable {

    private TcpHandler handler;
    private InetSocketAddress ipsock;
    private Bootstrap bootstrapUDP;		// Bootstrap for TCP connections
    private EventLoopGroup group;		    // Eventloop used by the netty stuff

    public UdpStream( BlockingQueue<Datagram> dQueue, Element stream  ){
        super(dQueue,stream);
    }
    protected String getType(){
        return "udp";
    }

    @Override
    protected void flagIdle() {

    }
    public Bootstrap setBootstrap( Bootstrap strap ){
        if( strap == null ){
            if(group==null){
                Logger.error(id+" -> No eventloopgroup yet");
                return null;
            }
            bootstrapUDP = new Bootstrap();
            bootstrapUDP.group(group)
                         .channel(NioDatagramChannel.class)
                         .option(ChannelOption.SO_BROADCAST, true);
        }else{
            this.bootstrapUDP=strap;
        }
        return bootstrapUDP;
    }
    public void setEventLoopGroup( EventLoopGroup group ){
        this.group=group;
    }

    @Override
    public boolean connect() {

        if( bootstrapUDP == null ){
            bootstrapUDP = new Bootstrap();
            bootstrapUDP.group(group).channel(NioDatagramChannel.class).option(ChannelOption.SO_BROADCAST, true);
        }
        Logger.debug("Port and IP defined for UDP, meaning writing so connecting channel...?");
        bootstrapUDP.option(ChannelOption.SO_REUSEADDR,true);
        bootstrapUDP.handler( new ChannelInitializer<NioDatagramChannel>() {
            @Override
            public void initChannel(NioDatagramChannel ch) {
                ch.pipeline().addLast( "decoder", new ByteArrayDecoder() );
                ch.pipeline().addLast( "encoder", new ByteArrayEncoder() );

                if( handler != null )
                    handler.disconnect();	
                handler = new TcpHandler( id, dQueue, UdpStream.this );
                handler.setTargets(targets);
                handler.setStreamListeners(listeners);
                handler.toggleUDP();
                ch.pipeline().addLast( handler ); 
            }
        });

        ChannelFuture f = bootstrapUDP.connect(ipsock);
        
        f.awaitUninterruptibly();
        f.addListener((FutureListener<Void>) future -> {
            if (f.isSuccess()) {
                Logger.info("Operation complete");
            } else {
                if(LookAndFeel.isNthAttempt(connectionAttempts)) {
                    String cause = String.valueOf(future.cause());
                    Logger.error(id + " -> Failed to connect: " + cause.substring(cause.indexOf(":") + 1));
                }
            }
        });
        return true;
    }

    @Override
    public boolean disconnect() {
        if( handler==null)
            return false;
        return handler.disconnect();
    }

    @Override
    public boolean isConnectionValid() {
        return handler!=null&&handler.isConnectionValid();
    }

    @Override
    protected boolean readExtraFromXML(Element stream) {
        // Process the address
        var opt = readIPsockFromElement(stream);
        if(opt.isEmpty())
            return false;
        ipsock = opt.get();
        
        // Alter eol
        if( eol.isEmpty() ){
            Logger.error("No EOL defined for "+id);
            return false;
        }
        return true;
    }
    @Override
    public long getLastTimestamp() {
        return handler==null?-1:handler.getTimestamp();
    }

    @Override
    public String getInfo() {
        return "UDP writer[" + id + (label.isEmpty() ? "" : "|" + label) + "] " + ipsock.toString();
    }

    @Override
    public boolean writeString(String data) {
        return handler.writeString(data);
    }

    @Override
    public boolean writeLine(String data) {
        return handler.writeLine(data);
    }
    @Override
    public boolean writeLine(String origin, String data) {
        return handler.writeLine(data);
    }
    @Override
    public boolean writeBytes(byte[] data) {
        return handler.writeBytes(data);
    }
    @Override
    public Writable getWritable() {
        return this;
    }
    
}
