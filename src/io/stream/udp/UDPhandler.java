package io.stream.udp;

import io.Writable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;
import org.tinylog.Logger;

import java.time.Instant;
import java.util.List;

public class UDPhandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private Channel channel;
    private String id;
    private final ByteBuf buf = Unpooled.buffer(128);
    private ByteBuf delim =  Unpooled.copiedBuffer(new byte[]{13,10});
    private boolean debug = false;
    private long timestamp=-1L;
    
    protected List<Writable> targets;
    private boolean readerIdle=false;

    public void setDebug( boolean debug ){
        this.debug = debug;
    }
    public void setID( String id){
        this.id=id;
    }
    public void setTargets( List<Writable> targets ){
        this.targets=targets;
    }
    /**
     *
     * @param delim the delim to set
     */
    public void setDelim(ByteBuf delim) {
        this.delim = delim;
    }
    @Override
    public void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
              
        if( debug ){
            DatagramPacket p = packet.duplicate();  // Create a copy because asking content = removing it
            Logger.info("REC UDP_"+id+": "+p.content().toString(CharsetUtil.UTF_8)+"<EOM");
            Logger.info("FULL UDP_"+id+": "+buf.toString(CharsetUtil.UTF_8).substring(0,buf.writerIndex())+"<EOM");
        }
        if (readerIdle)
            readerIdle=false;

        timestamp = Instant.now().toEpochMilli();
        int l = buf.writerIndex();              // No need to look in the bytes that were already in there
        buf.writeBytes(packet.content());       // Add the received data to the buffer, this removes it from the packet...
       
        if( l > 0 )                             // If the delimiter is longer than a byte and the first byte is received previously, this might be missed...
            l--;
        int pos = indexOf(l,buf,delim);         // Check the buffer for the delimiter

        while( pos != -1 ){                    // Delimiter found
            ByteBuf process = Unpooled.buffer(buf.readableBytes()); // Create a buffer to contain that part
            buf.readBytes( process, pos);                           // Read the bytes till the delimiter
            buf.setIndex(buf.readerIndex()+delim.capacity(), buf.writerIndex() );   // Alter the readerindex to clear delimiter
            buf.discardReadBytes();                                 // Remove read bytes
            
            String chunk = process.toString(CharsetUtil.UTF_8);     // Convert the binary data to readable ascii

            // Early return if chunk is blank
            if (chunk.isBlank()) break;

            // Handle targets if there actually are any
            if( !targets.isEmpty() ){
                targets.forEach(dt -> dt.writeLine(id, chunk));
                targets.removeIf( wr -> !wr.isConnectionValid() ); // Clear inactive
            }

            if(debug)
                Logger.info( id + " -> " + chunk);
               
            // Log anything and everything
            Logger.tag("RAW").warn( id + "\t" + chunk );
                
            pos = indexOf(l,buf,delim); // Get the position of the delimiter, if there are multiple
        }
        if( channel == null )           // If the channel is still null, 
            channel = ctx.channel();    // Get the channel from the latest data (to send a reply if needed etc)
    }
    /**
     * Find the delimiter in the chunk of received data
     * @param startOffset Where in the source to start looking (so we don't look in the same place twice)
     * @param source The chunk of received data
     * @param needle The characters to find, up to two
     * @return The index of the found characters or -1 if not found
     */
    public int indexOf( int startOffset, ByteBuf source, ByteBuf needle ){
        for( int i=buf.readerIndex()+startOffset;i<source.writerIndex();i++ ){
            if (source.getByte(i) != needle.getByte(0))  // Compare the source byte with the delimiter
                continue;
            if (needle.capacity() == 1) // If only looking for single characters
                return i;
            if (source.getByte(i + 1) == needle.getByte(1)) // If looking for two fe \r\n
                return i;
        }
        return -1;
    }
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }
    public void writeData(String data){
        channel.write(data.getBytes());
    }
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // We don't close the channel because we can keep serving requests.
       Logger.error( cause );
    }
    public long getTimestamp(){
        return timestamp;
    }
}