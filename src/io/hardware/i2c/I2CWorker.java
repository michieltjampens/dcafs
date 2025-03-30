package io.hardware.i2c;

import com.diozero.api.DeviceAlreadyOpenedException;
import com.diozero.api.DeviceBusyException;
import com.diozero.api.I2CDevice;
import com.diozero.api.RuntimeIOException;
import das.Commandable;
import das.Paths;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.RealtimeValues;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;

public class I2CWorker implements Commandable {
    private final HashMap<String, I2cDevice> devices = new HashMap<>();
    private final Path scriptsPath = Paths.storage().resolve("i2cscripts"); // Path to the scripts
    private final EventLoopGroup eventloop; // Executor to run the opsets
    private final RealtimeValues rtvals;
    private final ArrayList<I2cBus> busses = new ArrayList<>();

    public I2CWorker( EventLoopGroup eventloop, RealtimeValues rtvals) {
        this.rtvals=rtvals;
        this.eventloop=eventloop;
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
        var dig = Paths.digInSettings("i2c");
        if (dig.isInvalid())
            return "! Didn't find i2c node in settings file";

        Logger.info("Found settings for a I2C bus");
        var debug = dig.attr("debug", false);

        for (var i2c_bus : dig.digOut("bus")) {
            int controller = i2c_bus.attr("controller", -1);
            Logger.info("(i2c) -> Reading devices on the I2C bus of controller " + controller);
            if (controller == -1) {
                Logger.error("(i2c) -> Invalid controller number given.");
                continue;
            }
            var bus = getBus(controller);
            if (i2c_bus.hasPeek("device")) {
                for (var device : i2c_bus.digOut("device")) {
                    cnt++;
                    var dev = new I2cOpper(device, bus);
                    loadSet(dev);
                    devices.put(dev.id(), dev);
                }
                i2c_bus.goUp();
            }
            for (var device : i2c_bus.digOut("uart")) {
                cnt++;
                var dev = new I2cUart(device, bus);
                devices.put(dev.id, dev);
            }
        }
        if (devices.size() == cnt)
            return "Found all devices";

        setDebug(debug);
        return "! Found " + devices + " on checked busses, while looking for " + cnt + " devices";
    }
    private I2cBus getBus( int controller ){
        while(busses.size() <= controller)
            busses.add(null);
        if( busses.get(controller)==null)
            busses.set(controller, new I2cBus(controller, eventloop));
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
            var set = new I2COpSet(c,rtvals,device.id());
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

        Logger.info("i2c list size:"+devices.size());
        return devices.entrySet().stream()
                .map(set -> set.getKey() + " -> " + set.getValue())
                .collect(Collectors.joining("\r\n"));
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
        join.setEmptyValue("! No devices yet");
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
        busses.stream().filter(Objects::nonNull).forEach(bus -> bus.setDebug(debug));
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
    public String replyToCommand(Datagram d) {

        String[] args = d.argList();

        var dev = devices.get(d.cmd());
        if( dev instanceof I2cUart uart){
            uart.writeLine("", d.args());
            return "Written '" + d.args() + "' to " + d.cmd();
        }

        return switch (args[0]) {
            case "?" -> doHelpCmd(d.asHtml());
            case "list" -> getDeviceList();
            case "reload" -> readFromXML();
            case "listeners" -> getListeners();

            case "debug" -> {
                if (args.length == 2) {
                    setDebug(args[1].equalsIgnoreCase("on"));
                    yield "Debug " + args[1];
                }
                yield "! Incorrect number of variables: i2c:debug,on/off";
            }
            case "addscript" -> doAddScriptCmd(args);
            case "adddevice" -> doAddDeviceCmd(args);
            case "detect" -> {
                if (args.length == 2)
                    yield I2CWorker.detectI2Cdevices(Integer.parseInt(args[1]));
                yield "! Incorrect number of arguments: i2c:detect,bus";

            }
            case "businfo" -> busses.stream().filter(Objects::nonNull).map(I2cBus::getInfo)
                    .collect(Collectors.joining("\r\b"));
            case "busreset" -> {
                if (args.length != 2)
                    yield "! Incorrect number of arguments: i2c:busreset,bus";
                var bus = busses.get(Integer.parseInt(args[1]));
                if (bus == null)
                    yield "! Bus not in use.";
                bus.reset();
                yield "Bus " + args[1] + " object reset, this didn't do anything to the bus itself!";
            }
            case "" -> removeWritable(d.getWritable()) ? "ok" : "failed";
            default -> doDefaultCmd(d);
        };
    }

    private static String doHelpCmd(boolean html) {
        var help = new StringJoiner("\r\n");
        help.add("Create/load devices/scripts")
                .add("i2c:detect,bus -> Detect the devices connected on the given bus")
                .add("i2c:adddevice,id,bus,address,scriptid -> Add a device on bus at hex address that uses script,"
                        + " create new script if it doesn't exist yet")
                .add("i2c:addscript,scriptid -> Adds a blank i2c script to the default folder")
                .add("i2c:reload -> Check for devices and reload the scripts")
                .add(" Get info")
                .add("i2c:list -> List all registered devices with i2cscript")
                .add("i2c:listeners -> List all the devices with their listeners")
                .add(" Other")
                .add("i2c:debug,on/off -> Enable or disable extra debug feedback in logs")
                .add("i2c:device,setid -> Use the given i2cop on the device")
                .add("i2c:id -> Request the data received from the given id (can be regex)");
        return LookAndFeel.formatHelpCmd(help.toString(), html);
    }

    private String doAddScriptCmd(String[] args) {
        if (args.length != 2)
            return "! Incorrect number of arguments: i2c:addscript,scriptid";
        if (!Files.isDirectory(scriptsPath)) {
            try {
                Files.createDirectories(scriptsPath);
            } catch (IOException e) {
                Logger.error(e);
            }
        }
        if (Files.exists(scriptsPath.resolve(args[1] + ".xml")))
            return "! Already a script with that name, try again?";
        XMLfab.withRoot(scriptsPath.resolve(args[1] + ".xml"), "i2cscript").attr("id", args[1])
                .addParentToRoot("i2cop", "An empty operation set to start with")
                .attr("id", "setid").attr("info", "what this does").attr("bits", "8")
                .build();
        return "Script added";
    }

    private String doAddDeviceCmd(String[] args) {
        if (args.length != 5)
            return "! Incorrect number of arguments: i2c:adddevice,id,bus,hexaddress,scriptid";
        if (!Files.isDirectory(scriptsPath)) {
            try {
                Files.createDirectories(scriptsPath);
            } catch (IOException e) {
                Logger.error(e);
            }
        }
        var bus = getBus(NumberUtils.toInt(args[2]));
        var opper = new I2cOpper(args[1], bus, NumberUtils.createInteger(args[3]), args[4]);
        if (!opper.probeIt())
            return "! Probing " + args[3] + " on bus " + args[2] + " failed";

        devices.put(args[1], opper);

        var fab = Paths.fabInSettings("i2c");
        fab.selectOrAddChildAsParent("bus", "controller", args[2]);
        fab.selectOrAddChildAsParent("device").attr("id", args[1]);
        fab.addChild("address").content(args[3]);
        fab.addChild("script").content(args[4]);
        fab.build();

        // Check if the script already exists, if not build it
        var p = scriptsPath.resolve(args[4] + (args[4].endsWith(".xml") ? "" : ".xml"));
        if (!Files.exists(p)) {
            XMLfab.withRoot(p, "i2cscript").attr("id", args[4])
                    .addParentToRoot("i2cop", "An empty operation set to start with")
                    .attr("id", "setid").attr("info", "what this does")
                    .attr("bits", "8")
                    .build();
            readFromXML(); // Reload to enable changes
            return "Device added, created blank script at " + p;
        }
        readFromXML();  // Reload to enable changes
        return "Device added, using existing script";
    }

    private String doDefaultCmd(Datagram d) {
        var args = d.argList();

        if (args.length == 1) // single arguments points to a data request
            return doSingleArgCmd(d);

        var device = devices.get(args[0]);
        if (device == null)
            return "! No such device id: " + args[0];

        if (args[1].equalsIgnoreCase("?") && device instanceof I2cOpper opper)
            return opper.getOpsInfo(true);

        if (device instanceof I2cUart uart) {
            return switch (args[1]) {
                case "status" -> {
                    uart.requestStatus(d.getWritable());
                    yield "Status requested for " + args[0] + " (result should follow...)";
                }
                case "clock" -> {
                    uart.writeLine("", TimeTools.formatNow("HH:mm:ss.SSS"));
                    yield "Sending current time";
                }
                default -> {
                    Logger.warn("No such subcmd yet: " + args[1]);
                    yield "! No such subcmd yet: " + args[1];
                }
            };
        }
        return queueWork(args[0], ArrayUtils.remove(args, 0));
    }

    private String doSingleArgCmd(Datagram d) {
        var args = d.argList();
        var oks = new StringBuilder();
        devices.entrySet().stream()
                .filter(device -> device.getKey().matches(args[0]))
                .forEach(dev -> {
                    dev.getValue().addTarget(d.getWritable());
                    if (!oks.isEmpty())
                        oks.append(", ");
                    oks.append(dev.getKey());
                });
        if (!oks.isEmpty())
            return "Request from " + d.originID() + " accepted for i2c:" + oks;

        Logger.error("! No matches for i2c:" + args[0] + " requested by " + d.originID());
        return "! No such subcommand in " + d.getData();
    }
    /**
     * Add work to the worker
     *
     * @param id    The device that needs to execute an setId
     * @param setIdParams The setId the device needs to execute and any parameters
     * @return Result
     */
    private String queueWork(String id, String[] setIdParams) {
        if( devices.isEmpty())
            return "! No devices present yet.";

        var dev = devices.get(id.toLowerCase());
        if( dev == null ){
            Logger.error("(i2c) -> Invalid job received, unknown device '" + id + "'");
            return "! Invalid because unknown device '" + id + "'";
        }
        var setId = setIdParams[0];
        if (dev instanceof I2cOpper opper)
            return opper.queueSet(setIdParams)?"Set "+setId+" on "+opper.id() +" queued.":("! No such setId '"+setId+"'");

        return "! Device is an uart, not running op sets.";
    }

    public ArrayList<String> getUartIds(){
        var list = new ArrayList<String>();
        devices.values().stream().filter( dev -> dev instanceof I2cUart).map( uart -> uart.id).forEach(list::add);
        return list;
    }
}