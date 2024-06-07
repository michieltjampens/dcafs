package io.hardware.i2c;

import com.diozero.api.*;
import com.diozero.api.function.DeviceEventConsumer;
import io.Writable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.commons.lang3.ArrayUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;

public class I2cUart extends I2cDevice implements Writable, DeviceEventConsumer<DigitalInputEvent> {

    protected ArrayList<Writable> targets = new ArrayList<>();
    private String eol="\r\n";
    private byte[] eolBytes;
    private final ByteBuf writeBuffer = Unpooled.buffer(256,1024);
    private final ByteBuf readBuffer = Unpooled.buffer(256,1024);

    private boolean irqTriggered=false;
    private int eolFound = 0;
    private Writable tempTarget;
    private enum WAITING_FOR {IDLE,STATUS,UNKNOWN};
    private WAITING_FOR requested = WAITING_FOR.IDLE;

    public I2cUart( XMLdigger dig, I2cBus bus, BlockingQueue<Datagram>  dQueue){
        super(dig,bus,dQueue);

        eol = Tools.getDelimiterString(dig.peekAt("eol").value(eol));
        eolBytes=eol.getBytes();

        var irq = dig.peekAt("irq").value("");
        if( !irq.isEmpty() ) { // Meaning an irq has been defined for this port
            Logger.info( id+"(i2c) -> Found irq node with "+irq+" requesting watch");
            dQueue.add(Datagram.system("isr:watch," + irq).payload(this));
        }
        connect();
    }
    public void requestStatus(Writable wr){
        if( device == null){
            wr.writeLine("! Device not connected, can't request status");
            return;
        }

        irqTriggered=true;
        requested=WAITING_FOR.STATUS;
        tempTarget=wr;
        bus.requestSlot(this);
    }
    public void useBus(){
        // Write data to the device
        byte[] data=null;
        if( device==null){
            Logger.error(id+"(uart) -> Device still null, can't do anything.");
        }
        // Read data from it.
        if( irqTriggered ) {
            Logger.info( "Trying to read from reg 1" );

            var status = (int)device.readWordSwapped(1); // Read status 4bits of status followed 12 bits buffer use
            status = Tools.toUnsignedWord(status);

            // split it
            Logger.info("Read: "+status+" -> "+Integer.toBinaryString(status)+" -> "+Integer.toHexString(status));

            int state = status / 8192; // First 4 bits are state info
            int size = status % 8192;  // Next 12 bits are buffer used size

            switch (requested){
                case IDLE -> Logger.info("Requested idle...?");
                case STATUS -> {
                    if( tempTarget!=null){
                        tempTarget.writeLine("State: 0x"+Integer.toHexString(state)+" Buffer:"+size);
                        tempTarget=null; // remove it
                    }else{
                        Logger.warn(id+"(uart) -> Status requested but no valid writable to write to");
                    }
                }
                case UNKNOWN -> {
                    Logger.info("Requested unknown...?");
                    if (size != 0) {
                        data = device.readBytes(size);
                        readBuffer.writeBytes(data);
                        updateTimestamp();
                        // process the data?
                    }
                }
            }
            bus.doNext(); // Release the bus
            irqTriggered = false;
            if( data!=null ) { // If no data read, no need tor process
                processRead(data);
            }
        }else if( writeBuffer.readableBytes() != 0 ){
            device.writeBytes( getData() );
            Logger.info("Data send: "+ TimeTools.formatNow("HH:mm:ss.SSS"));
            bus.doNext(); // Release the bus
        }
    }
    private void processRead( byte[] data ){
        for ( byte datum : data) {
            readBuffer.writeByte(datum);
            if (datum == eolBytes[eolFound]) {
                eolFound++;
                if (eolFound == eolBytes.length) { // Got whole eol
                    var rec = new byte[readBuffer.readableBytes()-eolFound];
                    readBuffer.readBytes(rec); // Read the bytes, but omit the eol
                    readBuffer.clear(); // ignore the eol
                    var res = new String(rec);
                    Logger.tag("RAW").warn( id() + "\t" + res );
                    forwardData( res );
                    eolFound = 0;
                }
            } else {
                eolFound = 0;
            }
        }
    }
    public byte[] getData(){
        var size = writeBuffer.readableBytes();
        if( size > 255 )
            size=255;
        var data = new byte[size];
        writeBuffer.readBytes(size).readBytes(data);
        return ArrayUtils.insert(0,data,(byte)2,(byte)size);
    }
    @Override
    public boolean writeString(String data) {
        return writeBytes( data.getBytes() );
    }

    @Override
    public boolean writeLine(String data) {
        return writeBytes( ArrayUtils.addAll(data.getBytes(),eolBytes) );
    }

    @Override
    public boolean writeLine(String origin, String data) {;
        return writeString(data+eol);
    }

    @Override
    public boolean writeBytes(byte[] data) {
        writeBuffer.writeBytes(data);
        bus.requestSlot(this );
        Logger.info("Added to buffer: "+Tools.fromBytesToHexString(data));
        return true;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean isConnectionValid() {
        return false;
    }

    @Override
    public Writable getWritable() {
        return this;
    }

    @Override
    public void accept(DigitalInputEvent digitalInputEvent) {
        Logger.info("IRQ triggered: "+digitalInputEvent.toString());
        irqTriggered=true;
        requested=WAITING_FOR.UNKNOWN;
        bus.requestSlot(this);
    }
}
