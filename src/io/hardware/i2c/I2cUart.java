package io.hardware.i2c;

import com.diozero.api.DigitalInputEvent;
import com.diozero.api.RuntimeIOException;
import com.diozero.api.function.DeviceEventConsumer;
import das.Core;
import io.Writable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.ArrayList;
import java.util.Arrays;

public class I2cUart extends I2cDevice implements Writable, DeviceEventConsumer<DigitalInputEvent> {

    protected ArrayList<Writable> targets = new ArrayList<>();
    private String eol="\r\n";
    private final byte[] eolBytes;
    private final ByteBuf readBuffer = Unpooled.buffer(256,1024);

    private boolean irqTriggered=false;
    private int eolFound = 0;
    private Writable tempTarget;
    private enum WAITING_FOR {IDLE,STATUS,CONF,UNKNOWN}

    private WAITING_FOR requested = WAITING_FOR.IDLE;

    private int baudrate=38400;
    private boolean baudrateChanged=false;

    private final ArrayList<byte[]> writeData=new ArrayList<>();
    private boolean debug=false;

    public I2cUart( XMLdigger dig, I2cBus bus){
        super(dig,bus);

        eol = Tools.getDelimiterString(dig.peekAt("eol").value(eol));
        eolBytes=eol.getBytes();

        var irq = dig.peekAt("irq").value("");
        if( !irq.isEmpty() ) { // Meaning an irq has been defined for this port
            Logger.info( id+"(i2c) -> Found irq node with "+irq+" requesting watch");
            Core.addToQueue(Datagram.system("isr","watch," + irq).payload(this));
        }
        var serialSettings = dig.peekAt("serialsettings").value("");
        var bd = NumberUtils.toInt(serialSettings.split(",")[0]);
        if( bd != baudrate){
            baudrate=bd;
            baudrateChanged=true;
        }
        //connect();

        if(baudrateChanged)
            bus.requestSlot(this );
    }
    public void setDebug( boolean debug ){
        this.debug=debug;
    }
    public void requestStatus(Writable wr){
        if( device == null){
            wr.writeLine(id, "! Device not connected, can't request status");
            return;
        }

        irqTriggered=true;
        requested=WAITING_FOR.STATUS;
        tempTarget=wr;
        bus.requestSlot(this);
    }
    public void useBus(EventLoopGroup scheduler){
        // Write data to the device
        byte[] data=null;
        if( debug )
            Logger.info(id+" (uart) -> Using bus");
        if( device==null){
            Logger.error(id+"(uart) -> Device still null, can't do anything.");
        }
        // Read data from it.
        if( irqTriggered ) {
            if( debug )
                Logger.info( id+"(uart) -> Trying to read from reg 1" );
            irqTriggered = false;

            var status = (int)device.readWordData(1); // Read status 4bits of status followed 12 bits buffer use
            status = Tools.toUnsignedWord(status);

            // split it
            int size = status % 8192;  // Next 12 bits are buffer used size
            if( debug )
                Logger.info("Status: "+status);
            if (requested == WAITING_FOR.UNKNOWN && size != 0) {
                try {
                    data = new byte[size];
                    device.readNoStop((byte) 5, data, false);
                } catch (RuntimeIOException e) {
                    Logger.error(id + "(uart) -> Runtime exception when trying to read: " + e.getMessage());
                }
            }

            bus.doNext(); // Release the bus

            if( data!=null ) { // If no data read, no need tor process
                if( debug )
                    Logger.info(id + "(uart) -> Read " + size + " bytes for uart.");
                updateTimestamp();
                processRead(data);
            }else if( requested == WAITING_FOR.STATUS){
                if( tempTarget!=null){
                    int state = status / 8192; // First 4 bits are state info
                    Logger.info(id+"(uart) -> Read: "+status+" -> state:"+Integer.toBinaryString(state)+" , size:"+size);
                    tempTarget.writeLine(id, "State: 0x" + Integer.toHexString(state) + " Buffer:" + size);
                    tempTarget=null; // remove it
                }else{
                    Logger.warn(id+"(uart) -> Status requested but no valid writable to write to");
                }
            }
            return;
        }else if( !writeData.isEmpty() ){
            while( !writeData.isEmpty()) {
                device.writeBytes(getData());
                if (debug)
                    Logger.info(id + "(uart) -> Data send: " + TimeTools.formatNow("HH:mm:ss.SSS"));
            }
        }else if( baudrateChanged ){
            byte[] d = {0x04,(byte) (baudrate/2400)};
            device.writeBytes( d );
            baudrateChanged=false;
            Logger.info(id+"(uart) -> Baudrate change requested.");
        }
        bus.doNext(); // Release the bus
    }
    private void processRead( byte[] data ){
        for ( byte datum : data) {
            readBuffer.writeByte(datum);
            if (datum != eolBytes[eolFound]) {
                eolFound = 0;
                continue;
            }
            eolFound++;
            if (eolFound == eolBytes.length) { // Got whole eol
                var rec = new byte[readBuffer.readableBytes() - eolFound];
                readBuffer.readBytes(rec); // Read the bytes, but omit the eol
                readBuffer.clear(); // ignore the eol
                var res = new String(rec);
                Logger.tag("RAW").warn(id() + "\t" + res);
                forwardData(res);
                eolFound = 0;
            }
        }
    }
    public byte[] getData(){
        var size = writeData.get(0).length;
        return ArrayUtils.insert(0,writeData.remove(0),(byte)2,(byte)size);
    }
    @Override
    public boolean writeString(String data) {
        return writeBytes( data.getBytes() );
    }

    @Override
    public boolean writeLine(String origin, String data) {
        return writeString(data+eol);
    }

    @Override
    public boolean writeBytes(byte[] data) {
        if( writeData.size()<100) {
            if( data.length<255){
                writeData.add(data);
            }else{
                writeData.add( Arrays.copyOfRange(data,0,255) );
                writeData.add( Arrays.copyOfRange(data,255,data.length) );
            }
            Logger.info("Added to buffer: "+data.length+" bytes for total now "+writeData.size()+ " slots.");
        }else{
            Logger.error(id+"(uart) -> WriteBuffer overflow");
        }
        bus.requestSlot(this );
        return true;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean isConnectionValid() {
        return true;
    }

    @Override
    public void accept(DigitalInputEvent digitalInputEvent) {
        Logger.info("IRQ triggered: "+digitalInputEvent.toString());
        irqTriggered=true;
        requested=WAITING_FOR.UNKNOWN;
        bus.requestSlot(this);
    }
}
