package io.stream.tcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
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

public class ModbusTCPStream extends TcpStream{

    public ModbusTCPStream(BlockingQueue<Datagram> dQueue, Element stream) {
        super(dQueue,stream);

    }
    public String getType(){
        return "modbus";
    }
    @Override
    public boolean readExtraFromXML(Element stream) {
        // Address
        String address = XMLtools.getChildStringValueByTag( stream, "address", "");
        if( !address.contains(":") ){
            address+=":502";
        }
        ipsock = new InetSocketAddress( address.substring(0,address.lastIndexOf(":")),
                    Tools.parseInt( address.substring(address.lastIndexOf(":")+1) , -1) );
        return true;
    }
    @Override
    public boolean connect() {
        ChannelFuture f;
        Logger.info(id+" -> Trying to connect to tcp stream");

        if( eventLoopGroup==null){
            Logger.error(id+" -> Event loop group still null");
            return false;
        }
        if( bootstrap == null )
            bootstrap = createBootstrap();

        connectionAttempts++;

        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch){
                try{
                    ch.pipeline().addLast("framer", new FixedLengthFrameDecoder(1) );
                    ch.pipeline().addLast( "decoder", new ByteArrayDecoder() );
                    ch.pipeline().addLast( "encoder", new ByteArrayEncoder() );

                    if( handler != null )
                        handler.disconnect();
                    handler = new ModbusTCP( id, dQueue, ModbusTCPStream.this );
                    handler.setPriority(priority);
                    handler.setTargets(targets);
                    handler.setStreamListeners( listeners );
                    handler.setEventLoopGroup(eventLoopGroup);
                    ch.pipeline().addLast( handler );
                }catch( io.netty.channel.ChannelPipelineException e ){
                    Logger.error(id+" -> Issue trying to use handler for "+id);
                    Logger.error( e );
                }
            }
        });
        return connectIPSock(bootstrap,ipsock);
    }

}
