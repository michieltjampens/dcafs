package util.drawio;

import io.hardware.gpio.GpioFab;
import io.hardware.gpio.InputPin;
import io.hardware.gpio.OutputPin;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.data.vals.FlagVal;
import util.data.vals.Rtvals;
import util.tools.TimeTools;
import util.tools.Tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

public class GpioParser {

    public static HashMap<String, FlagVal> parseDrawIoGpios(Path file, EventLoopGroup eventLoop, Rtvals rtvals) {
        if (!file.getFileName().toString().endsWith(".drawio")) {
            Logger.error("This is not a drawio file: " + file);
            return new HashMap<>();
        }
        var tls = new TaskParser.TaskTools(eventLoop, null, rtvals, new HashMap<>(), new HashMap<>(), new HashMap<>());
        ArrayList<RtvalsParser.ValCell> vals = new ArrayList<>();

        var cells = Drawio.parseFile(file);
        return parseGpios(cells, tls, vals, file);
    }

    public static HashMap<String, FlagVal> parseDrawIoGpios(HashMap<String, Drawio.DrawioCell> cells, EventLoopGroup eventLoop, Rtvals rtvals, Path file) {
        var tls = new TaskParser.TaskTools(eventLoop, null, rtvals, new HashMap<>(), new HashMap<>(), new HashMap<>());
        ArrayList<RtvalsParser.ValCell> vals = new ArrayList<>();
        return parseGpios(cells, tls, vals, file);
    }

    public static HashMap<String, FlagVal> parseGpios(HashMap<String, Drawio.DrawioCell> cells, TaskParser.TaskTools tls, ArrayList<RtvalsParser.ValCell> vals, Path file) {

        var pins = new HashMap<String, FlagVal>();

        for (var entry : cells.entrySet()) {
            var cell = entry.getValue();

            if (cell.type.equals("inputpin")) {
                var name = cell.getParam("gpio", "");
                var pullPlace = cell.getParam("pulllogic", "none");
                var pull = "none";

                var idle = Tools.parseBool(cell.getParam("idle", "low"), false);
                if (pullPlace.startsWith("int")) {
                    pull = idle ? "up" : "down";
                }

                var trigger = "";
                // rising might not be pressed, but doesn't matter, just need to check presence of both
                if (cell.getArrowTarget("rising", "pressed") != null && cell.getArrowTarget("falling", "released") != null) {
                    trigger = "both";
                } else {
                    if (cell.hasArrow("rising") || cell.hasArrow(idle ? "released" : "pressed"))
                        trigger = "rising";
                    if (cell.hasArrow("falling") || cell.hasArrow(idle ? "pressed" : "released"))
                        trigger = "falling";
                }

                Logger.info("gpio-> Building input at " + name + " ->  " + pull + "&" + trigger);

                var input = GpioFab.buildInput(name, pull, trigger);
                if (input == null) {
                    Logger.error("Failed to build input: " + name);
                    continue;
                }
                var ip = new InputPin(cell.getParam("group", ""), cell.getParam("name", ""), cell.getParam("unit", ""), input);
                var period = cell.getParam("debounce", "0s");
                var ms = TimeTools.parsePeriodStringToMillis(period);
                ip.setDebounceMs(ms);

                RtvalsParser.alterFlagVal(ip, cell, tls); // Add all the flag related stuff
                pins.put(ip.id(), ip);
            } else if (cell.type.equals("outputpin")) {
                var name = cell.getParam("gpio", "");
                var activeHigh = cell.getParam("activehigh", true);
                Logger.info("gpio-> Building output at " + name + " ->  activeHigh?" + activeHigh);

                var output = GpioFab.buildOutput(name, activeHigh);
                if (output == null) {
                    Logger.error("Failed to build output: " + name);
                    continue;
                }
                var op = new OutputPin(cell.getParam("group", ""), cell.getParam("name", ""), cell.getParam("unit", ""), output);
                RtvalsParser.alterFlagVal(op, cell, tls); // Add all the flag related stuff
                pins.put(op.id(), op);
            }
        }
        TaskParser.fixControlBlocks(cells, tls);
        DrawioEditor.addIds(tls.idRef(), file);
        return pins;
    }
}
