package io.stream.tcp;

import io.stream.BaseStream;
import io.Writable;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.util.concurrent.FutureListener;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.Tools;
import util.xml.XMLtools;
import worker.Datagram;

import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;

public class TcpStream extends BaseStream implements Writable {

    TcpHandler handler;
    InetSocketAddress ipsock;
    ByteBuf[] deli;
    Bootstrap bootstrap;        // Bootstrap for TCP connections
    static int bufferSize = 2048;     // How many bytes are stored before a dump

    public TcpStream(BlockingQueue<Datagram> dQueue, Element stream) {
        super(dQueue,stream);
    }
    protected String getType(){
        return "tcp";
    }
    public Bootstrap setBootstrap( Bootstrap strap ){
        if( strap == null ){
            if(eventLoopGroup==null){
                Logger.error(id+" -> No eventloopgroup yet");
                return null;
            }
            bootstrap = new Bootstrap();
            bootstrap.group(eventLoopGroup).channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 6000);
        }else{
            this.bootstrap=strap;
        }
        return bootstrap;
    }
    public void setHandler( TcpHandler handler ){
        this.handler=handler;
    }

    @Override
    public void setLabel(String label) {
        this.label=label;
        handler.label=label;
    }
    @Override
    public boolean connect() {
        ChannelFuture f;

        if( eventLoopGroup==null){
            Logger.error(id+ " -> Event loop group still null, can't connect");
            return false;
        }
        Logger.info(id+" -> Trying to connect");
        if( bootstrap == null ){
            bootstrap = new Bootstrap();
            bootstrap.group(eventLoopGroup).channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 6000);
        }

        connectionAttempts++;

        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) {
                try{
                    if( deli != null ){
                        ch.pipeline().addLast("framer",  new DelimiterBasedFrameDecoder(bufferSize,deli) );

                    }else{
                        Logger.error(id + " -> Deli still null, assuming fixed size...");
                        ch.pipeline().addLast("framer", new FixedLengthFrameDecoder(3) );
                    }
                    ch.pipeline().addLast( "decoder", new ByteArrayDecoder() );
                    ch.pipeline().addLast( "encoder", new ByteArrayEncoder() );
                    boolean idle=false;
                    if( handler != null ) {
                        handler.disconnect();
                        idle= handler.isIdle(); // Keep this so it survives
                    }
                    // For some reason the handler needs to be remade in order to restore the connection...
                    handler = new TcpHandler( id, dQueue, TcpStream.this );
                    handler.setPriority(priority);
                    handler.setTargets(targets);
                    handler.setStreamListeners( listeners );
                    handler.setEventLoopGroup(eventLoopGroup);
                    handler.setValStore(store);
                    if( idle )
                        handler.flagAsIdle();
                    ch.pipeline().addLast( handler );
                }catch( io.netty.channel.ChannelPipelineException e ){
                    Logger.error(id + " -> Issue trying to use handler");
                    Logger.error( e );
                }
            }
        });
        if( ipsock == null ){
            Logger.error(id+" -> No proper ipsock");
            return false;
        }
        f = bootstrap.connect(ipsock).awaitUninterruptibly();
        f.addListener((FutureListener<Void>) future -> {
            if (!f.isSuccess()) {
                String cause = String.valueOf(future.cause());
                Logger.error( id+" -> Failed to connect: "+cause.substring(cause.indexOf(":")+1));
            }
        });
        if (f.isCancelled()) {
            return false;
        }
        return f.isSuccess();
    }
    public void updateHandlerStore(){
        if( handler !=null)
            handler.setValStore(store);
    }
    /**
     * Disconnect the stream
      * @return True if disconnected
     */
    @Override
    public boolean disconnect() {
        if( handler!=null ){
            return handler.disconnect();
        } 
        return true;
    }

    /**
     * Flag this stream as idle
     */
    protected void flagIdle(){
        if( handler!=null)
            handler.flagAsIdle();
    }
    @Override
    public boolean isConnectionValid() {
        return handler!=null && handler.isConnectionValid();
    }

    @Override
    public boolean readExtraFromXML(Element stream) {
        // Address
        String address = XMLtools.getChildStringValueByTag( stream, "address", "");
        if( !address.contains(":") ){
            Logger.error(id+" -> Not proper ip:port -> "+address);
            return false;
        }else{
            ipsock = new InetSocketAddress( address.substring(0,address.lastIndexOf(":")),
                                                Tools.parseInt( address.substring(address.lastIndexOf(":")+1) , -1) );
        }

        // Alter eol
        if( eol.isEmpty() ){
            Logger.error(id + " -> No EOL defined");
            return false;
        }
        deli = new ByteBuf[]{ Unpooled.copiedBuffer( eol.getBytes())};
        return true;
    }

    /**
     * Get the timestamp of when the last data was received
     * @return The time in epoch milles or -1 if invalid handler
     */
    @Override
    public long getLastTimestamp() {
        return handler==null?-1:handler.timeStamp;
    }

    @Override
    public String getInfo() {
        return "TCP ["+id+"] "+ ipsock.toString();
    }

    @Override
    public boolean writeString(String data) {
        if( handler==null || !isConnectionValid())
            return false;
        return handler.writeString(data);
    }

    @Override
    public boolean writeLine(String data) {
        if( handler==null || !isConnectionValid())
            return false;
        return handler.writeLine(data);
    }
    @Override
    public boolean writeLine(String origin, String data) {
        return writeLine(data);
    }
    @Override
    public boolean writeBytes( byte[] data){
        if( handler==null || !isConnectionValid())
            return false;
        return handler.writeBytes(data);
    }
    @Override
    public Writable getWritable(){
        return this;
    }
}
