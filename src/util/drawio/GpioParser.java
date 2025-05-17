package util.drawio;

import io.hardware.gpio.GpioFab;
import io.hardware.gpio.InputPin;
import io.hardware.gpio.OutputPin;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.data.vals.FlagVal;
import util.data.vals.Rtvals;
import util.tasks.blocks.AbstractBlock;
import util.tools.TimeTools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

public class GpioParser {
    public record ValParserTools(EventLoopGroup eventLoop, Rtvals rtvals,
                                 HashMap<String, AbstractBlock> blocks, ArrayList<RtvalsParser.ValCell> vals,
                                 Path source) {
    }

    public record GpioCell(InputPin val, Drawio.DrawioCell cell) {
    }

    public static HashMap<String, FlagVal> parseDrawIoGpios(Path file, EventLoopGroup eventLoop, Rtvals rtvals) {
        if (!file.getFileName().toString().endsWith(".drawio")) {
            Logger.error("This is not a drawio file: " + file);
            return new HashMap<>();
        }
        var tools = new RtvalsParser.ValParserTools(eventLoop, rtvals, new HashMap<>(), new ArrayList<>(), file);
        var cells = Drawio.parseFile(file);
        return parseGpios(cells, tools, file);
    }

    public static HashMap<String, FlagVal> parseDrawIoGpios(HashMap<String, Drawio.DrawioCell> cells, EventLoopGroup eventLoop, Rtvals rtvals, Path file) {
        var tools = new RtvalsParser.ValParserTools(eventLoop, rtvals, new HashMap<>(), new ArrayList<>(), file);
        return parseGpios(cells, tools, file);
    }

    public static HashMap<String, FlagVal> parseGpios(HashMap<String, Drawio.DrawioCell> cells, RtvalsParser.ValParserTools tools, Path file) {
        var tls = new TaskParser.TaskTools(tools.eventLoop(), null, tools.rtvals(), new HashMap<>(), new HashMap<>());
        var pins = new HashMap<String, FlagVal>();

        for (var entry : cells.entrySet()) {
            var cell = entry.getValue();

            if (cell.type.equals("inputpin")) {
                var name = cell.getParam("gpio", "");
                var pull = cell.getParam("pull", "none");

                var trigger = "";
                if (cell.hasArrow("rising") && cell.hasArrow("falling")) {
                    trigger = "both";
                } else {
                    if (cell.hasArrow("rising"))
                        trigger = "rising";
                    if (cell.hasArrow("falling"))
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

                RtvalsParser.alterFlagVal(ip, cell, tools, tls); // Add all the flag related stuff
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
                RtvalsParser.alterFlagVal(op, cell, tools, tls); // Add all the flag related stuff
                pins.put(op.id(), op);
            }
        }
        DrawioEditor.addIds(tls.idRef(), file);
        return pins;
    }
}
