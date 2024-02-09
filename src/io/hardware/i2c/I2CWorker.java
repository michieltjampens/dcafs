package io.hardware.i2c;

import com.diozero.api.DeviceAlreadyOpenedException;
import com.diozero.api.DeviceBusyException;
import com.diozero.api.I2CDevice;
import com.diozero.api.RuntimeIOException;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import io.telnet.TelnetCodes;
import das.Commandable;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class I2CWorker implements Commandable,I2COpFinished {
    private final HashMap<String, ExtI2CDevice> devices = new HashMap<>();
    private final Path scriptsPath; // Path to the scripts
    private final Path settingsPath; // Path to the settingsfile
    private final EventLoopGroup eventloop; // Executor to run the opsets
    private final RealtimeValues rtvals;
    private final BlockingQueue<Datagram> dQueue;
    private final ArrayList<ExtI2CDevice> workQueue = new ArrayList<>();
    private boolean busy = false;

    public I2CWorker(Path settings, EventLoopGroup eventloop, RealtimeValues rtvals, BlockingQueue<Datagram> dQueue) {
        this.settingsPath=settings;
        this.rtvals=rtvals;
        this.eventloop=eventloop;
        this.dQueue=dQueue;
        scriptsPath = settings.getParent().resolve("i2cscripts");
        readFromXML();
    }
    /* ************************* READ XML SETTINGS *******************************************/
    /**
     * Reads the settings for the worker from the given xml file, this mainly
     * consists of devices with their opsets.
     */
    private void readFromXML() {
        var i2cOpt = XMLtools.getFirstElementByTag( settingsPath, "i2c");
        if( i2cOpt.isPresent() ){
            Logger.info("Found settings for a I2C bus");
            devices.values().forEach(I2CDevice::close);
            devices.clear();
            for( Element i2c_bus : XMLtools.getChildElements( i2cOpt.get(), "bus") ){
                int bus = XMLtools.getIntAttribute(i2c_bus, "controller", -1);
                Logger.info("Reading devices on the I2C bus of controller "+bus);
                if( bus ==-1 ){
                    Logger.error("Invalid controller number given.");
                    continue;
                }
                for( Element device : XMLtools.getChildElements( i2c_bus, "device")){

                    String id = XMLtools.getStringAttribute( device, "id", "").toLowerCase();
                    String script = XMLtools.getStringAttribute( device, "script", "").toLowerCase();

                    int address = Tools.parseInt( XMLtools.getStringAttribute( device , "address", "0x00" ),16);
                    var devopt = addDevice( id, script, bus, address );
                    if( devopt.isPresent() ){
                        // Load the op set
                        loadSet( devopt.get() );
                        Logger.info("Adding "+id+"("+address+") to the device list of controller "+bus);
                    }else{
                        Logger.error("Tried to add "+id+" to the i2c device list, but probe failed");
                    }
                }
            }
        }
    }
    private boolean loadSet( ExtI2CDevice device ){
        var script = device.getScript();
        var xml = scriptsPath.resolve(script+".xml");

        if( Files.notExists(xml)){
            Logger.error(device.id()+" (i2c) -> Couldn't find script at "+xml);
            return false;
        }
        var dig = XMLdigger.goIn(xml,"i2cscript");
        if( dig.isInvalid() ){
            Logger.error(device.id()+" (i2c) -> Syntax error in "+xml);
            return false;
        }
        device.clearOpSets(rtvals);

        var defOut = dig.attr("output","");
        for( var c : dig.digOut("i2cop")){
            var set = new I2COpSet(c,rtvals,dQueue,device.id());
            set.setOutputType(defOut);
            if( set.isInvalid()) {
                Logger.error(device.id() + " (i2c) -> Failed to process " + script + "->" + set.id() + ", check logs.");
                return false;
            }
            device.addOpSet(set);
        }
        return true;
    }
    private String reloadSets(){
        if( devices.isEmpty()) {
            readFromXML();
            return "No devices yet, reloading everything.";
        }else {
            StringJoiner join = new StringJoiner("\r\n");
            for (var device : devices.values()) {
                if (!loadSet(device)) {
                    join.add("! Failed to reload set of " + device.id());
                } else {
                    join.add("Reloaded set of " + device.id());
                }
            }
            return join.toString();
        }
    }
    /* ***************************************************************************************************** */
    /**
     * Adds a device to the hashmap
     * 
     * @param id         The name of the device, will be used to reference it from the TaskManager etc
     * @param controller The controller the device is connected to
     * @param address    The address the device had
     * @return The created device
     */
    public Optional<ExtI2CDevice> addDevice(String id, String script, int controller, int address) {

        if( address==-1) {
            Logger.warn(id+"(i2c) -> Invalid address given");
            return Optional.empty();
        }

        try (I2CDevice device = new I2CDevice(controller, address)) {
            if (!device.probe( I2CDevice.ProbeMode.AUTO )) {
                Logger.warn(id+"(i2c) -> Probing the new device at "+address+" failed.");
                //return Optional.empty();
            }
        } catch ( RuntimeIOException e ){
            Logger.warn(id+"(i2c) -> Probing the new device at "+address+" failed.");
            //return Optional.empty();
        }
        try{
            var device = new ExtI2CDevice(id,controller, address, script);
            device.setI2COpFinished(this);
            devices.put(id, device);
            Logger.info("(i2c) -> Added "+id);
            return Optional.of(devices.get(id));
        }catch( RuntimeIOException e){
            Logger.error(id+"(i2c) -> Probing the new device failed: "+address);
            return Optional.empty();
        }
    }
    /**
     * Get a readable list of all the registered devices
     * @return Comma separated list of registered devices
     */
    public String getDeviceList() {
        if( devices.isEmpty())
            return "! No devices yet.";
        StringJoiner join = new StringJoiner("\r\n");
        Logger.info("i2c list size:"+devices.size());
        devices.forEach((key, device) -> join.add(key+" -> "+device.toString()));
        return join.toString();
    }
    public int getDeviceCount(){
        return devices.size();
    }
    /**
     * Add the writable to the list of targets of the device
     * @param id The id of the device
     * @param wr The writable of the target
     * @return True if the addition was ok
     */
    public boolean addTarget(String id, Writable wr){
        ExtI2CDevice device =  devices.get(id);
        if( device == null )
            return false;
        device.addTarget(wr);
        return true;
    }

    /**
     * Get a list of all the devices with their targets
      * @return The list
     */
    public String getListeners(){
        StringJoiner join = new StringJoiner("\r\n");
        join.setEmptyValue("None yet");
        devices.forEach( (id,device) -> join.add( id+" -> "+device.getWritableIDs()));
        return join.toString();
    }
    /* ***************************************************************************************************** */
    /**
     * Enable or disable extra debug info
     * @param debug True to enable
     * @return The new debug state
     */
    public boolean setDebug( boolean debug ){
        devices.values().forEach( device -> device.setDebug(debug));
        return debug;
    }

    /**
     * Get info on  the current status of the attached devices
     * @param eol The eol to use
     * @return The concatenated status's
     */
    public String getStatus(String eol){
        StringJoiner join = new StringJoiner(eol);
        devices.forEach( (key,val) -> join.add( val.getStatus(key)));
        return join.toString();
    }
    /**
     * Search the bus of the specified controller for responsive devices
     * 
     * @param controller The index of the controller to look at
     * @return A list of found used addresses, prepended with Busy if currently addressed by a driver or Used if by dcafs
     */
    public static String detectI2Cdevices( int controller ){
		StringJoiner b = new StringJoiner("\r\n");
        var gr = TelnetCodes.TEXT_GREEN;
        var ye = TelnetCodes.TEXT_DEFAULT;
        var or = TelnetCodes.TEXT_ORANGE;
        var red = TelnetCodes.TEXT_RED;
		for (int device_address = 0; device_address < 128; device_address++) {
			if (device_address >= 0x03 && device_address <= 0x77) {
				try (I2CDevice device = new I2CDevice(controller, device_address)) {
					if (device.probe( I2CDevice.ProbeMode.AUTO )) {
						b.add( gr+"Free"+ye+" - 0x"+String.format("%02x ", device_address) );
					}
				} catch (DeviceBusyException e) {
					b.add(red+"Busy"+ye+" - 0x"+String.format("%02x ", device_address)+"(in use by another process)");
				} catch( DeviceAlreadyOpenedException e){
				    b.add(or+"Used"+ye+" - 0x"+String.format("%02x ", device_address)+"(in use by dcafs)");
                } catch( RuntimeIOException e ){
                    return "! No such bus "+controller;
                }
			}
		}
		String result = b.toString();
		return result.isBlank()?"! No devices found.\r\n":result;
	}
    /* ******************************* C O M M A N D A B L E ******************************************************** */
    @Override
    public boolean removeWritable( Writable wr ){
        int cnt=0;
        for( ExtI2CDevice device : devices.values() ){
            cnt += device.removeTarget(wr)?1:0;
        }
        return cnt!=0;
    }
    @Override
    public String replyToCommand(String cmd, String args, Writable wr, boolean html) {
        String[] cmds = args.split(",");
        String cyan = html?"":TelnetCodes.TEXT_CYAN;
        String gr=html?"":TelnetCodes.TEXT_GREEN;
        String reg=html?"":TelnetCodes.TEXT_DEFAULT;

        switch (cmds[0]) {
            case "?" -> {
                StringJoiner join = new StringJoiner(html ? "<br>" : "\r\n");
                join.add(cyan + "Create/load devices/scripts" + reg)
                        .add(gr + "  i2c:detect,bus" + reg + " -> Detect the devices connected on the given bus")
                        .add(gr + "  i2c:adddevice,id,bus,address,scriptid" + reg + " -> Add a device on bus at hex address that uses script,"
                                +" create new script if it doesn't exist yet")
                        .add(gr + "  i2c:addscript,scriptid" + reg + " -> Adds a blank i2c script to the default folder")
                        .add(gr + "  i2c:reload" + reg + " -> Reload the i2cscripts")
                        .add("").add(cyan + " Get info" + reg)
                        .add(gr + "  i2c:list" + reg + " -> List all registered devices with i2cscript")
                        .add(gr + "  i2c:listeners" + reg + " -> List all the devices with their listeners")
                        .add("").add(cyan + " Other" + reg)
                        .add(gr + "  i2c:debug,on/off" + reg + " -> Enable or disable extra debug feedback in logs")
                        .add(gr + "  i2c:device,setid" + reg + " -> Use the given opset on the device")
                        .add(gr + "  i2c:id" + reg + " -> Request the data received from the given id (can be regex)");
                return join.toString();
            }
            case "list" -> {
                return getDeviceList();
            }
            case "reload" -> {
                return reloadSets();
            }
            case "listeners" -> {
                return getListeners();
            }
            case "debug" -> {
                if (cmds.length == 2) {
                    if (setDebug(cmds[1].equalsIgnoreCase("on")))
                        return "Debug " + cmds[1];
                    return "! Failed to set debug, maybe no i2cworker yet?";
                } else {
                    return "! Incorrect number of variables: i2c:debug,on/off";
                }
            }
            case "addscript" -> {
                if (cmds.length != 2)
                    return "! Incorrect number of arguments: i2c:addscript,scriptid";
                if (!Files.isDirectory(scriptsPath)) {
                    try {
                        Files.createDirectories(scriptsPath);
                    } catch (IOException e) {
                        Logger.error(e);
                    }
                }
                if( Files.exists(scriptsPath.resolve(cmds[1] + ".xml")))
                    return "! Already a script with that name, try again?";
                XMLfab.withRoot(scriptsPath.resolve(cmds[1] + ".xml"), "i2cscript").attr("id", cmds[1])
                        .addParentToRoot("i2cop", "An empty operation set to start with")
                        .attr("id", "setid").attr("info", "what this does").attr("bits","8")
                        .build();
                return "Script added";
            }
            case "adddevice" -> {
                if (cmds.length != 5)
                    return "! Incorrect number of arguments: i2c:adddevice,id,bus,hexaddress,scriptid";
                if (!Files.isDirectory(scriptsPath)) {
                    try {
                        Files.createDirectories(scriptsPath);
                    } catch (IOException e) {
                        Logger.error(e);
                    }
                }
                var opt = addDevice(cmds[1], cmds[4], NumberUtils.toInt(cmds[2]), Tools.parseInt(cmds[3], -1));
                if (opt.isEmpty())
                    return "! Probing " + cmds[3] + " on bus " + cmds[2] + " failed";
                opt.ifPresent(d -> d.storeInXml(XMLfab.withRoot(settingsPath, "dcafs", "settings").digRoot("i2c")));

                // Check if the script already exists, if not build it
                var p = scriptsPath.resolve(cmds[4] + (cmds[4].endsWith(".xml")?"":".xml"));
                if (!Files.exists(p)) {
                    XMLfab.withRoot(p, "i2cscript").attr("id", cmds[4])
                            .addParentToRoot("i2cop", "An empty operation set to start with")
                            .attr("id", "setid").attr("info", "what this does")
                            .attr("bits","8")
                            .build();
                    readFromXML();
                    return "Device added, created blank script at " + p;
                }
                return "Device added, using existing script";

            }
            case "detect" -> {
                if (cmds.length == 2) {
                    return I2CWorker.detectI2Cdevices(Integer.parseInt(cmds[1]));
                } else {
                    return "! Incorrect number of arguments: i2c:detect,bus";
                }
            }
            default -> {
                if (cmds.length == 1) { // single arguments points to a data request
                    StringBuilder oks = new StringBuilder();
                    for (var dev : devices.entrySet()) {
                        if (dev.getKey().matches(cmds[0])) {
                            dev.getValue().addTarget(wr);
                            if (!oks.isEmpty())
                                oks.append(", ");
                            oks.append(dev.getKey());
                        }
                    }
                    if (!oks.isEmpty())
                        return "Request for i2c:" + cmds[0] + " accepted from " + oks;

                    Logger.error("! No matches for i2c:" + cmds[0] + " requested by " + wr.id());
                    return "! No such subcommand in "+cmd+": "+args;
                }
                var dev = devices.get(cmds[0]);
                if( dev == null)
                    return "! No such device id: "+cmds[0];
                if( cmds[1].equalsIgnoreCase("?"))
                    return dev.getOpsInfo(true);
                return addWork(cmds[0], cmds.length>2?cmds[1].substring(args.indexOf(",")):cmds[1]);
            }
        }
    }
    public String payloadCommand( String cmd, String args, Object payload){
        return "! No such cmds in "+cmd;
    }
    /**
     * Add work to the worker
     *
     * @param id  The device that needs to execute a opset
     * @param opset The opset the device needs to execute
     * @return Result
     */
    private String addWork(String id, String opset) {
        if( devices.isEmpty())
            return "! No devices present yet.";

        ExtI2CDevice device = devices.get(id.toLowerCase());
        if (device == null) {
            Logger.error("Invalid job received, unknown device '" + id + "'");
            return "! Invalid job received, unknown device '" + id + "'";
        }

        if (!device.hasOp(opset.split(",")[0])) {
            Logger.error("Invalid opset received '" + device.getScript()+":"+opset + "'.");
            return "! Invalid opset received '" + device.getScript()+":"+opset + "'.";
        }
        if( busy && !device.isBusy()){ // Bus is busy and it's not this device, queue the work
            device.queueOp(opset);
            if( !workQueue.contains(device))
                workQueue.add(device);
        }else {
            busy=true;
            // If this device is busy the device will queue the opset
            eventloop.submit(() -> doWork(device, opset));
        }
        return "ok";
    }
    private void doWork(ExtI2CDevice device, String opsetId) {

        byte[] args = new byte[0];
        // Strip the arguments from the opsetId
        if( opsetId.contains(",")){
            var arg = opsetId.substring(opsetId.indexOf(",")+1);
            if( opsetId.contains(",0x")) {
                args = Tools.fromBaseToBytes(16, arg.split(","));
            }else{
                args = Tools.fromDecStringToBytes(arg);
            }
            opsetId=opsetId.substring(0,opsetId.indexOf(","));
        }
        // Execute the opset
        device.doOp(opsetId,eventloop);
    }

    @Override
    public void deviceDone() {
        if( workQueue.isEmpty() ) {
            busy = false;
            Logger.info("Device done.");
        }else{
            Logger.info("Device done, doing next");
            var device = workQueue.remove(0);
            device.doNext(eventloop);
        }
    }
}