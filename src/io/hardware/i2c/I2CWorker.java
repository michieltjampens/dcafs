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
import util.data.RealtimeValues;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;

public class I2CWorker implements Commandable {
    private final HashMap<String, I2cDevice> devices = new HashMap<>();
    private final Path scriptsPath; // Path to the scripts
    private final Path settingsPath; // Path to the settingsfile
    private final EventLoopGroup eventloop; // Executor to run the opsets
    private final RealtimeValues rtvals;
    private final BlockingQueue<Datagram> dQueue;
    private final ArrayList<I2cBus> busses = new ArrayList<>();

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
    private String readFromXML() {
        devices.values().forEach( x-> x.getDevice().close() ); // First close them before removing
        devices.clear(); // Remove any existing ones

        int cnt=0;
        var dig = XMLdigger.goIn(settingsPath,"dcafs","i2c");

        if( dig.isValid() ){
            Logger.info("Found settings for a I2C bus");
            var debug = dig.attr("debug",false);

            for( var i2c_bus : dig.digOut("bus") ){
                int controller = i2c_bus.attr("controller", -1);
                Logger.info("(i2c) -> Reading devices on the I2C bus of controller "+controller);
                if( controller ==-1 ){
                    Logger.error("(i2c) -> Invalid controller number given.");
                    continue;
                }
                var bus = getBus(controller);
                if(i2c_bus.hasPeek("device")) {
                    for (var device : i2c_bus.digOut("device")) {
                        cnt++;
                        var dev = new I2cOpper(device, bus, dQueue);
                        loadSet(dev);
                        devices.put(dev.id(), dev);
                    }
                    i2c_bus.goUp();
                }
                for( var device : i2c_bus.digOut("uart")){
                    cnt++;
                    var dev = new I2cUart( device,bus,dQueue);
                    devices.put(dev.id,dev);
                }
            }
            if( devices.size()==cnt)
                return "Found all devices";
            devices.values().forEach(dev->dev.setDebug(debug));
        }
        return "Found "+devices+" on checked busses, while looking for "+cnt+" devices";
    }
    private I2cBus getBus( int controller ){
        while(busses.size() <= controller)
            busses.add(null);
        if( busses.get(controller)==null)
            busses.set( controller, new I2cBus(controller,eventloop));
        return busses.get(controller);
    }
    private void loadSet(I2cOpper device ){
        var script = device.getScript();
        var xml = scriptsPath.resolve(script+".xml");

        if( Files.notExists(xml)){
            Logger.error(device.id()+" (i2c) -> Couldn't find script at "+xml);
            return;
        }
        var dig = XMLdigger.goIn(xml,"i2cscript");
        if( dig.isInvalid() ){
            Logger.error(device.id()+" (i2c) -> Syntax error in "+xml);
            return;
        }
        device.clearOpSets(rtvals);

        var defOut = dig.attr("output","");
        for( var c : dig.digOut("i2cop")){
            var set = new I2COpSet(c,rtvals,dQueue,device.id());
            set.setOutputType(defOut);
            if( set.isInvalid()) {
                Logger.error(device.id() + " (i2c) -> Failed to process " + script + "->" + set.id() + ", check logs.");
                return;
            }
            device.addOpSet(set);
        }
        Logger.info(device.id()+"(i2c) -> Read "+device.opsetCount()+" op sets.");
    }
    /* ***************************************************************************************************** */
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
        var device =  devices.get(id);
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
     *
     * @param debug True to enable
     */
    public void setDebug(boolean debug ){
        devices.values().forEach( device -> device.setDebug(debug));
    }

    /**
     * Get info on  the current status of the attached devices
     * @param eol The eol to use
     * @return The concatenated status's
     */
    public String getStatus(String eol){
        StringJoiner join = new StringJoiner(eol);
        devices.forEach( (key,val) -> join.add( val.getStatus()));
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
					if (device.probe()) {
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
        for( var device : devices.values() ){
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

        var dev = devices.get(cmd);
        if( dev instanceof I2cUart uart){
            uart.writeLine(args);
            return "Written '"+args+"' to "+cmd;
        }

        switch (cmds[0]) {
            case "?" -> {
                StringJoiner join = new StringJoiner(html ? "<br>" : "\r\n");
                join.add(cyan + "Create/load devices/scripts" + reg)
                        .add(gr + "  i2c:detect,bus" + reg + " -> Detect the devices connected on the given bus")
                        .add(gr + "  i2c:adddevice,id,bus,address,scriptid" + reg + " -> Add a device on bus at hex address that uses script,"
                                +" create new script if it doesn't exist yet")
                        .add(gr + "  i2c:addscript,scriptid" + reg + " -> Adds a blank i2c script to the default folder")
                        .add(gr + "  i2c:reload" + reg + " -> Check for devices and reload the scripts")
                        .add("").add(cyan + " Get info" + reg)
                        .add(gr + "  i2c:list" + reg + " -> List all registered devices with i2cscript")
                        .add(gr + "  i2c:listeners" + reg + " -> List all the devices with their listeners")
                        .add("").add(cyan + " Other" + reg)
                        .add(gr + "  i2c:debug,on/off" + reg + " -> Enable or disable extra debug feedback in logs")
                        .add(gr + "  i2c:device,setid" + reg + " -> Use the given i2cop on the device")
                        .add(gr + "  i2c:id" + reg + " -> Request the data received from the given id (can be regex)");
                return join.toString();
            }
            case "list" -> {
                return getDeviceList();
            }
            case "reload" -> {
                return readFromXML();
            }
            case "listeners" -> {
                return getListeners();
            }
            case "debug" -> {
                if (cmds.length == 2) {
                    setDebug(cmds[1].equalsIgnoreCase("on"));
                    return "Debug " + cmds[1];
                }
                return "! Incorrect number of variables: i2c:debug,on/off";
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
                var bus = getBus( NumberUtils.toInt(cmds[2]) );
                var opper = new I2cOpper(cmds[1],bus,NumberUtils.createInteger(cmds[3]),cmds[4],dQueue);
                if( !opper.probeIt() )
                    return "! Probing " + cmds[3] + " on bus " + cmds[2] + " failed";

                devices.put(cmds[1],opper);

                var fab = XMLfab.withRoot(settingsPath, "dcafs").digRoot("i2c");
                fab.selectOrAddChildAsParent("bus","controller",cmds[2]);
                fab.selectOrAddChildAsParent("device").attr("id",cmds[1]);
                fab.addChild("address").content(cmds[3]);
                fab.addChild("script").content(cmds[4]);
                fab.build();

                // Check if the script already exists, if not build it
                var p = scriptsPath.resolve(cmds[4] + (cmds[4].endsWith(".xml")?"":".xml"));
                if (!Files.exists(p)) {
                    XMLfab.withRoot(p, "i2cscript").attr("id", cmds[4])
                            .addParentToRoot("i2cop", "An empty operation set to start with")
                            .attr("id", "setid").attr("info", "what this does")
                            .attr("bits","8")
                            .build();
                    readFromXML(); // Reload to enable changes
                    return "Device added, created blank script at " + p;
                }
                readFromXML();  // Reload to enable changes
                return "Device added, using existing script";
            }
            case "detect" -> {
                if (cmds.length == 2) {
                    return I2CWorker.detectI2Cdevices(Integer.parseInt(cmds[1]));
                } else {
                    return "! Incorrect number of arguments: i2c:detect,bus";
                }
            }
            case "" -> {
                return removeWritable(wr)?"ok":"failed";
            }
            default -> {
                if (cmds.length == 1) { // single arguments points to a data request
                    StringBuilder oks = new StringBuilder();
                    for (var device : devices.entrySet()) {
                        if (device.getKey().matches(cmds[0])) {
                            device.getValue().addTarget(wr);
                            if (!oks.isEmpty())
                                oks.append(", ");
                            oks.append(device.getKey());
                        }
                    }
                    if (!oks.isEmpty())
                        return "Request from " + wr.id() + " accepted for i2c:" + oks;

                    Logger.error("! No matches for i2c:" + cmds[0] + " requested by " + wr.id());
                    return "! No such subcommand in "+cmd+": "+args;
                }
                var device = devices.get(cmds[0]);
                if( device == null)
                    return "! No such device id: "+cmds[0];
                if( cmds[1].equalsIgnoreCase("?") && device instanceof I2cOpper opper ) {
                    return opper.getOpsInfo(true);
                }else if( device instanceof I2cUart uart ){
                    return switch( cmds[1] ){
                        case "status" ->{
                            uart.requestStatus(wr);
                            yield "Status requested for "+cmds[0]+" (result should follow...)";
                        }
                        case "clock" -> {
                            uart.writeLine(TimeTools.formatNow("HH:mm:ss.SSS"));
                            yield "Sending current time";
                        }
                        default -> {
                            Logger.warn("No such subcmd yet: "+cmds[1]);
                            yield "! No such subcmd yet: "+cmds[1];
                        }
                    };
                }
                return queueWork(cmds[0], cmds.length>2?cmds[1].substring(args.indexOf(",")):cmds[1]);
            }
        }
    }
    public String payloadCommand( String cmd, String args, Object payload){
        return "! No such payload cmd in "+cmd;
    }
    /**
     * Add work to the worker
     *
     * @param id    The device that needs to execute an setId
     * @param setId The setId the device needs to execute
     * @return Result
     */
    private String queueWork(String id, String setId) {
        if( devices.isEmpty())
            return "! No devices present yet.";

        var dev = devices.get(id.toLowerCase());
        if( dev == null ){
            Logger.error("(i2c) -> Invalid job received, unknown device '" + id + "'");
            return "! Invalid because unknown device '" + id + "'";
        }
        if ( dev instanceof I2cOpper opper) {
            return opper.queueSet(setId)?"Set "+setId+" on "+opper.id() +" queued.":"! No such setId";
        }
        return "! Device is an uart, not running op sets.";
    }

    public ArrayList<String> getUartIds(){
        var list = new ArrayList<String>();
        devices.values().stream().filter( dev -> dev instanceof I2cUart).map( uart -> uart.id).forEach(list::add);
        return list;
    }
}