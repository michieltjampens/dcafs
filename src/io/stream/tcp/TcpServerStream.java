package io.stream.tcp;

import io.Writable;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.stream.BaseStream;
import io.stream.StreamListener;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.Tools;
import util.xml.XMLtools;
import worker.Datagram;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;

public class TcpServerStream extends BaseStream implements Writable, StreamListener {

    private InetSocketAddress ipsock;
    private ChannelFuture serverFuture;
    private final ArrayList<TcpHandler> clients = new ArrayList<>();
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private int nr=0;
    private boolean serverOk=false;
    public TcpServerStream(BlockingQueue<Datagram> dQueue, Element stream) {
        super(dQueue,stream);
    }
    protected String getType(){
        return "tcpserver";
    }
    @Override
    public boolean connect() {

        // Netty
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, eventLoopGroup).channel(NioServerSocketChannel.class).option(ChannelOption.SO_BACKLOG, 50)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch){
                            ch.pipeline().addLast("framer",
                                    new DelimiterBasedFrameDecoder(512, true, Delimiters.lineDelimiter())); // Max 512
                            // char,
                            // strip
                            // delimiter
                            ch.pipeline().addLast("decoder", new ByteArrayDecoder());
                            ch.pipeline().addLast("encoder", new ByteArrayEncoder());

                            if( clients.size() > 5 ){
                                Logger.info(id+" -> Maximum amount of clients reached, close another one first");
                            }
                            // For some reason the handler needs to be remade in order to restore the connection...
                            var handler = new TcpHandler( id+"client"+nr++, dQueue, TcpServerStream.this );
                            handler.setPriority(priority);
                            handler.setLabel(label);
                            handler.setTargets(targets);
                            handler.addStreamListener( TcpServerStream.this );
                            handler.setEventLoopGroup(eventLoopGroup);
                            handler.setValStore(store);
                            clients.add(handler);
                            ch.pipeline().addLast(handler);
                        }
                    });

            // Start the server.
            Logger.info(id+" -> Starting TcpServer on port " + ipsock.getPort() + " ...");
            serverFuture = b.bind(ipsock.getPort());
            serverFuture.addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    Logger.error(id+" -> Failed to start the TcpServer (bind issue?)");
                    System.exit(0);
                } else if (future.isSuccess()) {
                    Logger.info(id+" -> Started the TcpServer.");
                    serverOk=true;
                }
            });
            serverFuture.sync();
        } catch (InterruptedException e) {
            Logger.error(e);
            // Restore interrupted state...
            Thread.currentThread().interrupt();
            serverOk=false;
        }
        return false;
    }
    public void updateHandlerStore(){
        clients.forEach( x->x.setValStore(store));
    }
    /**
     * Disconnect the stream
      * @return True if disconnected
     */
    @Override
    public boolean disconnect() {
        clients.forEach(TcpHandler::disconnect);
        clients.clear();
        return true;
    }

    /**
     * Flag this stream as idle
     */
    protected void flagIdle(){
        clients.forEach(TcpHandler::flagAsIdle);
    }
    @Override
    public boolean isConnectionValid() {
        return serverOk;//clients.stream().anyMatch(TcpHandler::isConnectionValid);
    }

    @Override
    public boolean readExtraFromXML(Element stream) {

        // Alter eol
        if( eol.isEmpty() ){
            Logger.error(id + " -> No EOL defined");
            return false;
        }

        // Address
        String address = XMLtools.getChildStringValueByTag( stream, "address", "");
        if( address.isEmpty()){
            int port = XMLtools.getChildIntValueByTag(stream,"port",-1);
            if( port==-1) {
                Logger.error(id+" -> Not a valid port number:"+address);
                return false;
            }
            ipsock = new InetSocketAddress( "localhost",port);
            return true;
        }
        if( !address.contains(":") ) {
            if( Tools.parseInt(address,-999)==-999) {
                Logger.error(id+" -> Not a valid port number:"+address);
                return false;
            }
            address = "localhost:" + address;
        }
        ipsock = new InetSocketAddress(address.substring(0, address.lastIndexOf(":")),
                    Tools.parseInt(address.substring(address.lastIndexOf(":") + 1), -1));

        return true;
    }

    /**
     * Get the timestamp of when the last data was received
     * @return The time in epoch milles or -1 if invalid handler
     */
    @Override
    public long getLastTimestamp() {
        return -1;
    }

    @Override
    public String getInfo() {
        return "TCPSERVER ["+id+"] Port "+ ipsock.getPort();
    }

    @Override
    public boolean writeString(String data) {
        clients.forEach(x->x.writeString(data));
        return serverOk;
    }

    @Override
    public boolean writeLine(String data) {
        clients.forEach(x->x.writeLine(data));
        return serverOk;
    }
    @Override
    public boolean writeLine(String origin, String data) {
        var client = clients.stream().filter(x->x.id.equalsIgnoreCase(origin)).findFirst();
        if( client.isPresent() ){
            client.get().writeLine(data);
        }else{
            clients.forEach( x->x.writeLine(data));
        }
        return serverOk;
    }
    @Override
    public boolean writeBytes( byte[] data){
        clients.forEach(x->x.writeBytes(data));
        return serverOk;
    }
    @Override
    public Writable getWritable(){
        return this;
    }

    @Override
    public void notifyIdle(BaseStream stream) {

    }

    @Override
    public boolean notifyActive(String id) {
        return false;
    }

    @Override
    public void notifyOpened(String id) {
        applyTriggeredAction(TRIGGER.HELLO,id);
        if( clients.size() == 1){
            applyTriggeredAction(TRIGGER.OPEN);
        }
    }

    @Override
    public void notifyClosed(String id) {
        Logger.info( id+" -> Client closed");
        var i = clients.size();
        clients.removeIf( x->x.id.equalsIgnoreCase(id));
        if( i == clients.size())
            Logger.info(id+" -> Failed to remove "+id);
        Logger.info(id+" -> Clients left: "+clients.size());
    }

    @Override
    public boolean requestReconnection(String id) {
        return false;
    }
    public void applyTriggeredAction(TRIGGER trigger, String origin ){
        for( TriggerAction cmd : triggeredActions){
            if( cmd.trigger!=trigger) // Check if the trigger presented matched this actions trigger
                continue; // If not, check the next one

            if( cmd.trigger==TRIGGER.HELLO || cmd.trigger==TRIGGER.WAKEUP ){ // These trigger involves writing to remote
                Logger.info(id+" -> "+cmd.trigger+" => "+cmd.data());
                ((Writable) this).writeLine(origin,cmd.data());
                continue;
            }
            Logger.info(id+" -> "+cmd.trigger+" => "+cmd.data());
            dQueue.add( Datagram.system(cmd.data()).writable(this) );
        }
    }
    public int getClientCount(){
        return clients.size();
    }
}
