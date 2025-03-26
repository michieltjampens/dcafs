package io.stream.tcp;

import io.Writable;
import io.netty.channel.ChannelHandlerContext;
import org.tinylog.Logger;
import util.tools.Tools;

import java.time.Instant;
import java.util.Arrays;
import java.util.StringJoiner;

public class ModbusTCP extends TcpHandler{
    private int index=0;
    private final byte[] rec = new byte[512];
    private final byte[] header=new byte[]{0,1,0,0,0,0,0};
    private final String[] origin = new String[]{"","","","reg","AI",""};
    private Long passed;

    public ModbusTCP(String id) {
        super(id);
    }
    public ModbusTCP( String id, Writable writable ){
        this(id);
        this.writable=writable;
    }
    @Override
    public void channelRead0(ChannelHandlerContext ctx, byte[] data) {

        if( idle ){
            idle=false;
            listeners.forEach( l-> l.notifyActive(id));
        }

        long p = Instant.now().toEpochMilli() - timeStamp;	// Calculate the time between 'now' and when the previous message was received
        if( p >= 0 ){	// If this time is valid
            passed = p; // Store it
        }
        if( passed > 10 ){
            index=0;
        }
        timeStamp = Instant.now().toEpochMilli();    		    // Store the timestamp of the received message

        for( byte b : data ){

            if( index >= rec.length){
                Logger.info("Out of bounds...?"+rec.length);
                break;
            }else{
                rec[index] = b;
                index++;
            }
        }

        if( index < 6 || index < rec[4]*256+rec[5]+6) // Wait for length info and length content
            return;

        switch( rec[7] ){
            case 0x03: // Register read
            case 0x04: // Analog read?
            case 0x06: // reply?
                    processRegisters( Arrays.copyOfRange(rec,0,index) );
                    break;
            case 0x10:

                break;
            default: Logger.warn(id+"(mb) -> Received unknown type");
                Logger.info(Tools.fromBytesToHexString(rec,0,index));
                break;
        }
    }

    private void processRegisters( byte[] data ){
        index=0;
        // Log anything and everything (except empty strings)
        if( log )		// If the message isn't an empty string and logging is enabled, store the data with logback
            Logger.tag("RAW").warn( id + "\t[hex] " + Tools.fromBytesToHexString(data,0,data.length) );

        int reg = data[8];

        StringJoiner join = new StringJoiner(",");

        for( int a=9;a<data.length;a+=2){
            int i0= data[a]<0?-1*((data[a]^0xFF) + 1):data[a];
            int i1= data[a+1]<0?-1*((data[a+1]^0xFF) + 1):data[a+1];
            join.add(origin[data[7]]+reg+":"+(i0*256+i1));
            reg++;
        }
        if( !targets.isEmpty() ){
            targets.forEach(dt -> eventLoopGroup.submit(() -> dt.writeLine(id, join.toString())));
            targets.removeIf(wr -> !wr.isConnectionValid() ); // Clear inactive
        }
    }

    /**
     * Writes the given bytes with the default header prepended (00 01 00 00 00 xx 01, x is data length)
     * Header followed with
     *  1B -> function code (0x03=AI, 0x04=Reg etc)
     *  2B -> Address
     *  2B -> Addresses to read (each contain 2B)
     * @param data The data to append to the header
     * @return True if written
     */
    public boolean writeBytes(byte[] data) {
        if( channel==null || !channel.isActive() )
            return false;
        header[5] = (byte) (data.length+1);
        channel.write(header);
        channel.writeAndFlush(data);
        return true;
    }
    public boolean writeLine(String data) {
       return writeBytes(data.getBytes());
    }
    public byte[] getHeader(){
        return header;
    }
}
