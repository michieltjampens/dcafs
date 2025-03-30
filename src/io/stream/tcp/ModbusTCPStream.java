package io.stream.tcp;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.xml.XMLdigger;

import java.net.InetSocketAddress;

public class ModbusTCPStream extends TcpStream{

    public ModbusTCPStream(XMLdigger stream) {
        super(stream);

    }
    public String getType(){
        return "modbus";
    }
    @Override
    public boolean readExtraFromXML(XMLdigger stream) {
        // Address
        var address = stream.peekAt("address").value("");
        if (!address.contains(":"))
            address+=":502";

        ipsock = new InetSocketAddress( address.substring(0,address.lastIndexOf(":")),
                NumberUtils.toInt(address.substring(address.lastIndexOf(":") + 1), -1));
        return true;
    }
    @Override
    public boolean connect() {
        ChannelFuture f;

        if( eventLoopGroup==null){
            Logger.error(id+" -> Event loop group still null");
            return false;
        }
        if(LookAndFeel.isNthAttempt(connectionAttempts))
            Logger.info(id+" -> Trying to connect to tcp stream");


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
                    handler = new ModbusTCP( id, ModbusTCPStream.this );
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
