package io.stream.serialport;

import org.apache.commons.lang3.ArrayUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.tools.Tools;

import java.time.Instant;

public class ModbusStream extends SerialStream{

    private final byte[] rec = new byte[128];
    private int index = 0;
    private boolean readyForWorker=false;

    public ModbusStream(Element stream) {
        super(stream);
        eol="";
    }
    @Override
    public String getType(){
        return "modbus";
    }
    @Override
    public String getInfo() {
        return "MODBUS [" + id + "] " + serialPort + " | " + getSerialSettings();
    }
    @Override
    protected void processListenerEvent(byte[] data){

        long p = Instant.now().toEpochMilli() - timestamp;	// Calculate the time between 'now' and when the previous message was received
        if (p >= 0)    // If this time is valid
            passed = p; // Store it

        if (passed > 10)  // Maximum allowed time is 3.5 characters which is 5ms at 9600
            index=0;

        timestamp = Instant.now().toEpochMilli();    		    // Store the timestamp of the received message
        
        for( byte b : data ){
            rec[index] = b;
            index++;   
        }
       
        if( index < 4) // can't do anything with it yet anyway
            return;

        switch( rec[1] ){
            case 0x03: // Register read
                if( index == 5+rec[2] ) // Received all the data
                    readyForWorker=true;
            break;
            case 0x06: // reply?
                if(index == 8 )
                    readyForWorker=true;
            break;
            case 0x10:
            break;
            default: Logger.warn(id+"(mb) -> Received unknown type");
                Logger.info(Tools.fromBytesToHexString(rec));
            break;
        }
        
        if( readyForWorker ){
            // Log anything and everything (except empty strings)
            if( log )		// If the message isn't an empty string and logging is enabled, store the data with logback
                Logger.tag("RAW").warn( id + "\t[hex] " + Tools.fromBytesToHexString(rec,0,index) );

            if( verifyCRC( rec, index ) ){
                forwardData(Tools.fromBytesToHexString(rec,0,index-2));
                readyForWorker=false;
            }else{
                Logger.error(id+"(mb) -> Message failed CRC check: "+Tools.fromBytesToHexString(rec,0,index));
            }
            index=0;
        }
    }
    @Override
    public synchronized boolean writeBytes(byte[] data) {
        return write(MathUtils.calcCRC16_modbus(data, true));
    }
    private boolean verifyCRC( byte[] data,int length){
        byte[] crc = MathUtils.calcCRC16_modbus( ArrayUtils.subarray(data,0,length-2), false);
        return crc[0]==data[length-2] && crc[1]==data[length-1];
    }
}