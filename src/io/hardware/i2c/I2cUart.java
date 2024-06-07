package io.hardware.i2c;

import com.diozero.api.*;
import com.diozero.api.function.DeviceEventConsumer;
import io.Writable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.tinylog.Logger;
import util.tools.Tools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;

public class I2cUart extends I2cDevice implements Writable, DeviceEventConsumer<DigitalInputEvent> {

    protected ArrayList<Writable> targets = new ArrayList<>();
    private I2CDevice device;
    private String eol="\r\n";
    private byte[] eolBytes;
    private final ByteBuf writeBuffer = Unpooled.buffer(256,1024);
    private final ByteBuf readBuffer = Unpooled.buffer(256,1024);

    private boolean irqTriggered=false;
    private int eolFound = 0;

    public I2cUart( XMLdigger dig, I2cBus bus, BlockingQueue<Datagram>  dQueue){
        super(dig,bus,dQueue);

        eol = Tools.fromEscapedStringToBytes(dig.peekAt("eol").value(eol));
        eolBytes=eol.getBytes();

        var irq = dig.peekAt("irq").value("");
        if( !irq.isEmpty() ) { // Meaning an irq has been defined for this port
            Logger.info( id+"(i2c) -> Found irq node with "+irq+" requesting watch");
            dQueue.add(Datagram.system("isr:watch," + irq).payload(this));
        }
        connect();
    }
    public void useBus(){
        // Write data to the device
        if( writeBuffer.readableBytes() != 0 ){
            device.writeBytes( getData() );
        }
        byte[] data=null;
        // Read data from it.
        if( irqTriggered ) {
            var size = device.readWordData(2);
            if (size != 0) {
                data = device.readBytes(size);
                readBuffer.writeBytes(data);
                updateTimestamp();
               // process the data?
            }
            irqTriggered = false;
        }
        bus.doNext(); // Release the bus
        if( data!=null ) { // If no data read, no need tor process
            processRead(data);
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
        var data = new byte[writeBuffer.readableBytes()];
        writeBuffer.getBytes(writeBuffer.readableBytes(),data);
        return data;
    }
    @Override
    public boolean writeString(String data) {
        return writeBytes( Tools.fromStringToBytes(data) );
    }

    @Override
    public boolean writeLine(String data) {
        return writeString(data+eol);
    }

    @Override
    public boolean writeLine(String origin, String data) {
        return writeString(data+eol);
    }

    @Override
    public boolean writeBytes(byte[] data) {
        writeBuffer.writeBytes(data);
        bus.addSlot(this );
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
    }
}
