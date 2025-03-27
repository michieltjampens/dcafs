package io.hardware.gpio;

import com.diozero.api.DeviceMode;
import com.diozero.api.DigitalInputDevice;
import com.diozero.internal.board.GenericLinuxArmBoardInfo;
import com.diozero.sbc.LocalSystemInfo;
import org.tinylog.Logger;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.StringJoiner;
import java.util.stream.Collectors;


public class CustomBoard extends GenericLinuxArmBoardInfo {
    Path settingsFile;
    String id="custom";

    public CustomBoard(LocalSystemInfo systemInfo) {
        super(systemInfo, systemInfo.getMake());
        populateBoardPinInfo();
    }
    public CustomBoard(LocalSystemInfo systemInfo, Path settings) {
        super(systemInfo, systemInfo.getMake());
        this.settingsFile=settings;

        populateBoardPinInfo();
        if( gpioCount()==0) {
            Logger.info("No builtin boarddef, reading from xml");
            readFromXML();
            Logger.info("Found: "+gpioCount()+" pin definitions");
        }
    }
    public int gpioCount(){
        return getGpios().size();
    }
    public void readFromXML(){
        var dig = XMLdigger.goIn(settingsFile,"dcafs","gpios");
        id = dig.attr("id",id); // read the id
        int chipLines = dig.attr("chiplines",32);

        for( var gpio : dig.digOut("gpio")){
            var name =  gpio.attr("name","");

            var chip = gpio.attr("chip",-1);
            var line = gpio.attr("line",-1);

            if( line == -1 && chip==-1 ){
                var nr = gpio.attr("nr",-1);
                if( nr==-1 || chipLines==-1) {
                    Logger.warn("gpios -> Not enough info to create gpio "+name);
                    continue;
                }
                chip = nr/chipLines;
                line = nr%chipLines;
            }

            var headerName = gpio.peekAt("physical").attr("header","");
            int headerPin = gpio.peekAt("physical").attr("pin",-1);
            var modes = parseModes( gpio.peekAt("modes").value("digital_input"));

            addGpioPinInfo(headerName,(chip*chipLines)+line,name,headerPin,modes,chip,line);
        }
    }
    protected static Collection<DeviceMode> parseModes(String modeValues) {
        if (modeValues.isEmpty())
            return EnumSet.noneOf(DeviceMode.class);

        return Arrays.stream(Tools.splitList(modeValues)).map(mode -> DeviceMode.valueOf(mode.trim().toUpperCase()))
                .collect(Collectors.toSet());
    }
    public String checkGPIOs(){

        var join = new StringJoiner("\r\n");
        join.add("Amount of mapped pins: "+getGpios().size() );
        for( var map :  getGpios().entrySet() ){
            var pin = getByName(map.getValue().getName());
            if (pin == null) {
                join.add("Not found: " + map.getKey() + " -> " + map.getValue().getName());
                continue;
            }

            try (var device = DigitalInputDevice.Builder.builder(pin).build()) {
                var name = map.getValue().getName();
                if (name.isEmpty())
                    name = device.getName();
                join.add("Nr: " + device.getGpio() + " -> Name:" + name + " State:" + (device.getValue() ? "high" : "low"));
            } catch (com.diozero.api.NoSuchDeviceException e) {
                Logger.error(e);
                join.add("Build failed: " + map.getKey() + " -> " + map.getValue().getName());
            } catch (com.diozero.api.DeviceAlreadyOpenedException d) {
                join.add("In use: " + map.getKey() + " -> " + map.getValue().getName());
            }
        }
        return join.toString();
    }
}
