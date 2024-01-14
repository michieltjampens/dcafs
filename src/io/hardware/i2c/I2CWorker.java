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
import java.util.stream.Stream;

public class I2CWorker implements Commandable {
    private final HashMap<String, ExtI2CDevice> devices = new HashMap<>();
    private final LinkedHashMap<String, I2COpSet> opSets = new LinkedHashMap<>();

    private boolean debug = false;

    private final Path scriptsPath; // Path to the scripts
    private final Path settingsPath; // Path to the settingsfile
    private final EventLoopGroup eventloop; // Executor to run the commands
    private final RealtimeValues rtvals;
    private final BlockingQueue<Datagram> dQueue;

    public I2CWorker(Path settings, EventLoopGroup eventloop, RealtimeValues rtvals, BlockingQueue<Datagram> dQueue) {
        this.settingsPath=settings;
        this.rtvals=rtvals;
        this.eventloop=eventloop;
        this.dQueue=dQueue;
        scriptsPath = settings.getParent().resolve("i2cscripts");
        readFromXML();
    }

    /**
     * Enable or disable extra debug info
     * @param debug True to enable
     * @return The new debug state
     */
    public boolean setDebug( boolean debug ){
        this.debug=debug;
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
            Logger.warn(id+" -> Invalid address given");
            return Optional.empty();
        }

        try (I2CDevice device = new I2CDevice(controller, address)) {
            if (!device.probe( I2CDevice.ProbeMode.AUTO )) {
                Logger.error("Probing the new device failed: "+address);
                return Optional.empty();
            }
        } catch ( RuntimeIOException e ){
            Logger.error("Probing the new device failed: "+address);
            return Optional.empty();
        }
        try{
            devices.put(id, new ExtI2CDevice(id,controller, address, script));
            return Optional.of(devices.get(id));
        }catch( RuntimeIOException e){
            Logger.error("Probing the new device failed: "+address);
            return Optional.empty();
        }
    }
    /**
     * Get a readable list of all the registered devices
     * @param full Add the complete listing of the commands and not just id/info
     * @return Comma separated list of registered devices
     */
    public String getDeviceList(boolean full) {
        StringJoiner join = new StringJoiner("\r\n");
        devices.forEach((key, device) -> join.add(key+" -> "+device.toString()));
        join.add("\r\n-Stored scripts-");
        String last="";

        for( var entry : opSets.entrySet() ){
            String[] split = entry.getKey().split(":");
            var cmd = entry.getValue();
            if( !last.equalsIgnoreCase(split[0])){
                if( !last.isEmpty())
                    join.add("");
                join.add(TelnetCodes.TEXT_GREEN+split[0]+TelnetCodes.TEXT_DEFAULT);
                last = split[0];
            }
            join.add(cmd.getOpsInfo("\t   ",full));
        }
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
    /* ************************* READ XML SETTINGS *******************************************/
    /**
     * Reads the settings for the worker from the given xml file, this mainly
     * consists of devices with their commands.
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

                    if( addDevice( id, script, bus, address ).isPresent() ){
                        Logger.info("Adding "+id+"("+address+") to the device list of controller "+bus);
                    }else{
                        Logger.error("Tried to add "+id+" to the i2c device list, but probe failed");
                    }
                }           
            }
        }else{
            Logger.info("No settings found for I2C, no use reading the commandsets.");
            return;
        }
        reloadSets();
    }

    private String reloadSets( ){
        List<Path> xmls;
        try (Stream<Path> files = Files.list(scriptsPath)){
            xmls = files.filter(p -> p.toString().endsWith(".xml")).toList();
        }catch (IOException e) {            
            Logger.error("Something went wrong trying to read the commandset files");
            return "Failed to read files in i2cscripts folder";
        }
        Logger.info("Reading I2C scripts from: "+scriptsPath);

        if( !opSets.isEmpty() ){ // Meaning it's a reload
            opSets.values().forEach( set -> set.removeRtvals(rtvals)); // Remove all the related rtvals
            opSets.clear();
        }

        for( Path p : xmls ){
            var dig = XMLdigger.goIn(p,"commandset");
            if( dig.isInvalid() ){
                return "Syntax error in "+p.getFileName().toString();
            }
            var script = dig.attr("script","");
            var defOut = dig.attr("output","dec");
            dig.digOut("command").forEach( c -> { // dig out can invalidate, but it's last use anyway
                opSets.put( script+":"+c.attr("id",""),new I2COpSet(c,rtvals,dQueue));
            });
        }
        return "All files ("+xmls.size()+") read ok.";
    }
    /* ***************************************************************************************************** */
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
                    return "No such bus "+controller;
                }
			}
		}
		String result = b.toString();
		return result.isBlank()?"No devices found.\r\n":result;
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
                        .add(gr + "  i2c:adddevice,id,bus,address,script" + reg + " -> Add a device on bus at hex address that uses script")
                        .add(gr + "  i2c:addblank,scriptname" + reg + " -> Adds a blank i2c script to the default folder")
                        .add(gr + "  i2c:reload" + reg + " -> Reload the commandset file(s)")
                        .add("").add(cyan + " Get info" + reg)
                        .add(gr + "  i2c:list" + reg + " -> List all registered devices with commandsets")
                        .add(gr + "  i2c:listeners" + reg + " -> List all the devices with their listeners")
                        .add("").add(cyan + " Other" + reg)
                        .add(gr + "  i2c:debug,on/off" + reg + " -> Enable or disable extra debug feedback in logs")
                        .add(gr + "  i2c:device,commandset" + reg + " -> Use the given command on the device")
                        .add(gr + "  i2c:id" + reg + " -> Request the data received from the given id (can be regex)");
                return join.toString();
            }
            case "list" -> {
                return getDeviceList(true);
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
                    return "Incorrect number of variables: i2c:debug,on/off";
                }
            }
            case "addblank" -> {
                if (cmds.length != 2)
                    return "! Incorrect number of arguments: i2c:addblank,scriptname";
                if (!Files.isDirectory(scriptsPath)) {
                    try {
                        Files.createDirectories(scriptsPath);
                    } catch (IOException e) {
                        Logger.error(e);
                    }
                }
                XMLfab.withRoot(scriptsPath.resolve(cmds[1] + ".xml"), "commandset").attr("script", cmds[1])
                        .addParentToRoot("command", "An empty command to start with")
                        .attr("id", "cmdname").attr("info", "what this does")
                        .build();
                return "Blank added";
            }
            case "adddevice" -> {
                if (cmds.length != 5)
                    return "! Incorrect number of arguments: i2c:adddevice,id,bus,hexaddress,script";
                if (!Files.isDirectory(scriptsPath)) {
                    try {
                        Files.createDirectories(scriptsPath);
                    } catch (IOException e) {
                        Logger.error(e);
                    }
                }
                var opt = addDevice(cmds[1], cmds[4], NumberUtils.toInt(cmds[2]), Tools.parseInt(cmds[3], -1));
                if (opt.isEmpty())
                    return "Probing " + cmds[3] + " on bus " + cmds[2] + " failed";
                opt.ifPresent(d -> d.storeInXml(XMLfab.withRoot(settingsPath, "dcafs", "settings").digRoot("i2c")));

                // Check if the script already exists, if not build it
                var p = scriptsPath.resolve(cmds[4] + (cmds[4].endsWith(".xml")?"":".xml"));
                if (!Files.exists(p)) {
                    XMLfab.withRoot(p, "commandset").attr("script", cmds[4])
                            .addParentToRoot("command", "An empty command to start with")
                            .attr("id", "cmdname").attr("info", "what this does")
                            .build();
                    readFromXML();
                    return "Device added, created blank script at " + p;
                } else {
                    return "Device added, using existing script";
                }
            }
            case "detect" -> {
                if (cmds.length == 2) {
                    return I2CWorker.detectI2Cdevices(Integer.parseInt(cmds[1]));
                } else {
                    return "! Incorrect number of arguments: i2c:detect,bus";
                }
            }
            default -> {
                if (cmds.length == 1) {
                    StringBuilder oks = new StringBuilder();
                    for (var dev : devices.entrySet()) {
                        if (dev.getKey().matches(cmds[0])) {
                            dev.getValue().addTarget(wr);
                            if (!oks.isEmpty())
                                oks.append(", ");
                            oks.append(dev.getKey());
                        }
                    }
                    if (!oks.isEmpty()) {
                        return "Request for i2c:" + cmds[0] + " accepted from " + wr.id();
                    } else {
                        Logger.error("! No matches for i2c:" + cmds[0] + " requested by " + wr.id());
                        return "! No such subcommand in "+cmd+": "+args;
                    }
                }
                if (wr != null && wr.id().equalsIgnoreCase("telnet")) {
                    if (cmds[0].isEmpty()) {
                        removeWritable(wr);
                    } else {
                        addTarget(cmds[0], wr);
                    }
                }
                var arg = cmds[1];
                if( cmds.length>2) // if there are args added to the cmd
                    arg = args.substring(args.indexOf(","));

                return addWork(cmds[0], arg);
            }
        }
    }
    public String payloadCommand( String cmd, String args, Object payload){
        return "! No such cmds in "+cmd;
    }
    /**
     * Add work to the worker
     *
     * @param id  The device that needs to execute a command
     * @param command The command the device needs to execute
     * @return Result
     */
    private String addWork(String id, String command) {
        if( devices.isEmpty())
            return "! No devices present yet.";

        ExtI2CDevice device = devices.get(id.toLowerCase());
        if (device == null) {
            Logger.error("Invalid job received, unknown device '" + id + "'");
            return "! Invalid job received, unknown device '" + id + "'";
        }
        if (!opSets.containsKey(device.getScript()+":"+command)) {
            Logger.error("Invalid command received '" + device.getScript()+":"+command + "'.");
            return "! Invalid command received '" + device.getScript()+":"+command + "'.";
        }
        eventloop.submit(()->doWork(device,command));
        return "ok";
    }
    public void doWork(ExtI2CDevice device, String cmdID) {

        byte[] args = new byte[0];
        // Strip the arguments from the cmdID
        if( cmdID.contains(",")){
            var arg = cmdID.substring(cmdID.indexOf(",")+1);
            if( cmdID.contains(",0x")) {
                args = Tools.fromBaseToBytes(16, arg.split(","));
            }else{
                args = Tools.fromDecStringToBytes(arg);
            }
            cmdID=cmdID.substring(0,cmdID.indexOf(","));
        }

        // Execute the command
        var ops = opSets.get(device.getScript()+":"+cmdID);
        try {
            Logger.debug("Probing device...");
            device.probeIt(); // First check if the device is actually there?
            device.updateTimestamp(); // Update last used timestamp

            ops.startOp(device,eventloop);
            device.updateTimestamp();
        }catch( RuntimeIOException e ){
            Logger.error("Failed to run command for "+device.getAddr()+":"+e.getMessage());
        }
    }
}