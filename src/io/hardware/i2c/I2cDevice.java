package io.hardware.i2c;

import com.diozero.api.*;
import io.Writable;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class I2cDevice{
    protected final String id;
    protected int address;
    protected Instant timestamp;
    protected final ArrayList<Writable> targets = new ArrayList<>();
    protected boolean debug=false;
    protected I2CDevice device;
    protected final I2cBus bus;
    protected BlockingQueue<Datagram> dQueue;
    protected boolean valid = true;

    public I2cDevice(XMLdigger dev, I2cBus bus, BlockingQueue<Datagram> dQueue){
        this.dQueue=dQueue;
        this.bus=bus;

        id = dev.attr("id","");
        // Get the address, might be an attribute or a node
        if( dev.hasAttr("address") ) {
            address = NumberUtils.createInteger(dev.attr("address", "0x00"));
        }else if( dev.hasPeek("address") ) {
            address = NumberUtils.createInteger(dev.peekAt("address").value("0x00"));
        }
        if( address==0) {
            Logger.warn(id+" (i2c) -> Invalid address given.");
        }
        if( id.isEmpty() || address==0 ) {
            Logger.error("Tried to create i2c-uart, but no id nor address.");
            valid=false;
        }else{
            Logger.info("(i2c) -> Created "+id+" ("+Integer.toHexString(address)+") for controller "+bus);
        }
        if( valid )
            device = I2CDevice.builder(address).setController(bus.id()).build();
    }
    public I2cDevice( String id, int address, I2cBus bus, BlockingQueue<Datagram> dQueue){
        this.id=id;
        this.address=address;
        this.bus=bus;
        this.dQueue=dQueue;

        device = I2CDevice.builder(address).setController(bus.id()).build();
    }
    public boolean connect(){
        if( !valid ) {
            Logger.warn(id+" (i2c) -> Not connecting because invalid");
            return false;
        }
        if( device != null)
            device.close();
        device = I2CDevice.builder(address).setController(bus.id()).build();
        if( !probeIt() )
            device = null;
        return device != null;
    }
    public String id(){
        return id;
    }
    public String getAddr(){
        return "0x"+String.format("%02x", address)+"@"+bus;
    }
    public void useBus(){
        Logger.warn("Not implemented!");
    }
    public I2CDevice getDevice(){
        return device;
    }
    public boolean probeIt(){
        if( !valid || device==null) {
            Logger.warn(id + " (i2c) -> Probe failed because invalid or device null");
            return false;
        }
        try{
            if (device.probe()) {
                Logger.info("(i2c) -> Probe OK for "+id+" at "+getAddr() );
            }else{
                Logger.warn("(i2c) -> Probe failed for "+id+" at "+getAddr() );
                return false;
            }
        } catch (DeviceBusyException e) {
            Logger.info(id+"(i2c) -> Device busy for at "+getAddr() );
        } catch( DeviceAlreadyOpenedException e){
            Logger.info(id+"(i2c) -> Device already opened at "+getAddr() );
        } catch( RuntimeIOException e ){
            Logger.warn(id+"(i2c) -> Runtime error during probe at "+getAddr() );
            return false;
        }
        return true;
    }
    public void setDebug( boolean state){
        debug=state;
    }
    public boolean isDebug(){
        return debug;
    }
    public String getStatus(String id){
        String age = getAge()==-1?"Not used yet": TimeTools.convertPeriodtoString(getAge(), TimeUnit.SECONDS);
        return (probeIt()?"":"!!")+"I2C ["+id+"] "+getAddr()+"\t"+age+" [-1]";
    }
    /**
     * Add a @Writable to which data received from this device is sent
     * @param wr Where the data will be sent to
     */
    public void addTarget(Writable wr){
        if( wr!=null&&!targets.contains(wr))
            targets.add(wr);
    }
    public boolean removeTarget(Writable wr ){
        return targets.remove(wr);
    }
    /**
     * Get the list containing the writables
     * @return The list of writables
     */
    public List<Writable> getTargets(){
        return targets;
    }
    public String getWritableIDs(){
        StringJoiner join = new StringJoiner(", ");
        join.setEmptyValue("None yet.");
        targets.forEach(wr -> join.add(wr.id()));
        return join.toString();
    }
    public void updateTimestamp(){
        timestamp = Instant.now();
    }
    public long getAge(){
        if( timestamp==null)
            return -1;
        return Duration.between(timestamp,Instant.now()).getSeconds();
    }
    protected void forwardData( String message){
        if( !targets.isEmpty() ){
            try {
                targets.parallelStream().forEach(dt -> {
                    try {
                        dt.writeLine(id,message);
                    } catch (Exception e) {
                        Logger.error(id + "(i2c) -> Something bad while writeLine to " + dt.id());
                        Logger.error(e);
                    }
                });
                targets.removeIf(wr -> !wr.isConnectionValid()); // Clear inactive
            }catch(Exception e){
                Logger.error(id+" -> Something bad in i2c port");
                Logger.error(e);
            }
        }
    }
}
