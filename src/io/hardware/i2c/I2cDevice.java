package io.hardware.i2c;

import com.diozero.api.DeviceAlreadyOpenedException;
import com.diozero.api.DeviceBusyException;
import com.diozero.api.I2CDevice;
import com.diozero.api.RuntimeIOException;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.xml.XMLdigger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

public class I2cDevice{
    protected final String id;
    protected int address;
    protected Instant timestamp;
    protected Instant lastOkProbe;
    protected final ArrayList<Writable> targets = new ArrayList<>();
    protected boolean debug=false;
    protected I2CDevice device;
    protected final I2cBus bus;
    protected boolean valid = true;

    public I2cDevice(XMLdigger dev, I2cBus bus){

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
            Logger.error("(i2c) -> Tried to create i2c-uart, but no id nor address.");
            valid=false;
        }else{
            Logger.info("(i2c) -> Created "+id+" ("+Integer.toHexString(address)+") for controller "+bus.id());
        }
        if( valid ) {
            device = I2CDevice.builder(address).setController(bus.id()).build();
            if( device!=null ) {
                Logger.info("(i2c) -> Build device for " + id);
            }else{
                Logger.error("(i2c) -> Failed to build device for "+id);
                valid=false;
            }
        }
    }
    public I2cDevice( String id, int address, I2cBus bus){
        this.id=id;
        this.address=address;
        this.bus=bus;

        try {
            device = I2CDevice.builder(address).setController(bus.id()).build();
        }catch( DeviceAlreadyOpenedException e){
            Logger.warn(id+"(i2c) -> Device already opened.");
        }
    }
    public boolean connect(){
        Logger.info( id+"(i2c) -> Trying to connect to 0x"+Integer.toHexString(address).toUpperCase() );
        if( !valid ) {
            Logger.warn( id+" (i2c) -> Not connecting because invalid." );
            return false;
        }
        if( device != null)
            device.close();
        device = I2CDevice.builder(address).setController(bus.id()).build();
        if( probeIt() ) {
            Logger.info( id + "(i2c) -> Connected to 0x" + Integer.toHexString(address).toUpperCase() + " and probed." );
        }else{
            Logger.error( id + "(i2c) -> Probe of 0x"+Integer.toHexString(address).toUpperCase()+" failed." );
            device = null;
        }
        return device != null;
    }
    public String id(){
        return id;
    }
    public String getAddr(){
        return "0x"+String.format("%02x", address)+"@"+bus.id();
    }
    public void useBus(EventLoopGroup scheduler){
        Logger.warn(id+" (i2c) Not implemented!");
    }
    public I2CDevice getDevice(){
        return device;
    }
    public boolean probeIt(){
        if( !valid || device==null) {
            Logger.warn(id + " (i2c) -> Probe failed because invalid or device null.");
            return false;
        }
        try{
            if (device.probe()) {
                lastOkProbe = Instant.now();
                Logger.info("(i2c) -> Probe OK for "+id+" at "+getAddr() );
            }else{
                Logger.warn("(i2c) -> Probe failed for "+id+" at "+getAddr() );
                return false;
            }
        } catch (DeviceBusyException e) {
            lastOkProbe = Instant.now();
            Logger.info(id+"(i2c) -> Device busy for at "+getAddr() );
        } catch( DeviceAlreadyOpenedException e){
            lastOkProbe = Instant.now();
            Logger.info(id+"(i2c) -> Device already opened at "+getAddr() );
        } catch( RuntimeIOException e ){
            Logger.warn(id+"(i2c) -> Runtime error during probe at "+getAddr() );
            valid=false;
            return false;
        }
        return true;
    }
    public void setDebug( boolean state){
        debug=state;
    }
    public boolean inDebug(){
        return debug;
    }

    public String getStatus(){
        String age = getAge() == -1 ? "Not used yet" : TimeTools.convertPeriodToString(getAge(), TimeUnit.SECONDS);
        return (valid?"":"!!")+"I2C ["+id+"] "+getAddr()+"\t"+age+" [-1]";
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
        Logger.tag("RAW").warn(id() + "\t" + message);
        if (targets.isEmpty())
            return;

        try {
            targets.parallelStream().forEach(dt -> {
                try {
                    dt.writeLine(id, message);
                } catch (Exception e) {
                    Logger.error(id + "(i2c) -> Something bad while writeLine to " + dt.id());
                    Logger.error(e);
                }
            });
            targets.removeIf(wr -> !wr.isConnectionValid()); // Clear inactive
        } catch (Exception e) {
            Logger.error(id + " -> Something bad in i2c port");
            Logger.error(e);
        }
    }
}
