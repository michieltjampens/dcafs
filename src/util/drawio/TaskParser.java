package util.drawio;

import io.netty.channel.EventLoopGroup;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.vals.Rtvals;
import util.tasks.blocks.*;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

public class TaskParser {

    public record Tools(EventLoopGroup eventLoop, ArrayList<OriginBlock> origins, Rtvals rtvals,
                        HashMap<String, AbstractBlock> blocks) {
    }

    public static ArrayList<OriginBlock> parseDrawIoTaskFile(Path file, EventLoopGroup eventLoop, Rtvals rtvals) {
        if (!file.getFileName().toString().endsWith(".drawio")) {
            Logger.error("This is not a drawio file: " + file);
            return new ArrayList<>();
        }

        var cells = Drawio.parseFile(file);
        return parseTasks(cells, eventLoop, rtvals);
    }

    private static ArrayList<OriginBlock> parseTasks(HashMap<String, Drawio.DrawioCell> cells, EventLoopGroup eventLoop, Rtvals rtvals) {
        ArrayList<OriginBlock> origins = new ArrayList<>();
        var tools = new Tools(eventLoop, origins, rtvals, new HashMap<>());

        for (var entry : cells.entrySet()) {
            var cell = entry.getValue();
            if (cell.type.equals("originblock")) { // Look for originblocks
                var origin = (OriginBlock) createBlock(cell, tools);
                if (origin != null) {
                    Logger.info(origin.getInfo());
                }
            }
        }
        return origins;
    }

    private static AbstractBlock createBlock(Drawio.DrawioCell cell, Tools tools) {
        if (cell == null)
            return null;

        return switch (cell.type) {
            case "originblock" -> doOriginBlock(cell, tools);
            case "delayblock" -> doDelayBlock(cell, tools);
            case "intervalblock" -> doIntervalBlock(cell, tools);
            case "timeblock" -> doTimeBlock(cell, tools);
            case "writerblock" -> doWritableBlock(cell, tools);
            case "counterblock" -> doCounterBlock(cell, tools);
            case "controlblock" -> doControlBlock(cell, tools);
            case "readerblock" -> doReaderBlock(cell, tools);
            case "splitblock" -> doSplitBlock(cell, tools);
            case "commandblock" -> doCmdBlock(cell, tools);
            case "conditionblock" -> doConditionBlock(cell, tools);
            default -> null;
        };
    }

    private static OriginBlock doOriginBlock(Drawio.DrawioCell cell, Tools tools) {
        var origin = new OriginBlock(cell.dasId);
        tools.origins.add(origin);
        addNext(cell, origin, tools, "next");
        return origin;
    }

    private static DelayBlock doDelayBlock(Drawio.DrawioCell cell, Tools tools) {
        Logger.info("Processing delay block");
        if (cell.hasParam("delay")) {
            var eventLoop = tools.eventLoop();
            var db = DelayBlock.useDelay(cell.params.get("delay"), eventLoop);
            addNext(cell, db, tools, "next");
            return db;
        }
        Logger.error("No delay specified for delayblock");
        return null;
    }

    private static DelayBlock doIntervalBlock(Drawio.DrawioCell cell, Tools tools) {
        Logger.info("Processing interval block");
        if (cell.hasParam("interval")) {
            var eventLoop = tools.eventLoop();
            var interval = cell.getParam("interval", "");
            var init = cell.getParam("initialdelay", interval);
            // If repeats is not 0, can have a failure
            var repeats = NumberUtils.toInt(cell.getParam("repeats", "-1"), -1);
            var db = DelayBlock.useInterval(eventLoop, init, interval, repeats);

            addNext(cell, db, tools, "next");
            addNext(cell, db, tools, "repeats>0");

            if (repeats != -1) {
                if (!addFail("repeats==0", cell, db, tools))
                    Logger.warn("Interval task with repeats but failure link not used.");
            }
            return db;
        }
        Logger.error("No interval specified for intervalblock, or still empty");
        return null;
    }

    private static DelayBlock doTimeBlock(Drawio.DrawioCell cell, Tools tools) {
        Logger.info("Processing time block");
        var delayBlock = new DelayBlock(tools.eventLoop);

        if (cell.hasParam("time")) {
            delayBlock.useClock(cell.getParam("time", ""), false);
        } else if (cell.hasParam("localtime")) {
            delayBlock.useClock(cell.getParam("localtime", ""), true);
        } else {
            Logger.error("No time or localtime defined in Timeblock, or still empty.");
            return null;
        }
        addNext(cell, delayBlock, tools, "next");
        return delayBlock;
    }

    private static WritableBlock doWritableBlock(Drawio.DrawioCell cell, Tools tools) {
        Logger.info("Processing writer block");
        if (!cell.hasParam("message")) {
            Logger.error("Writable block is missing a message property or it's still empty");
            return null;
        }
        if (!cell.hasParam("target")) { // TODO use arrow instead
            Logger.error("Writable block is missing a target property or it's still empty");
            return null;
        }
        var wr = new WritableBlock(cell.getParam("target", ""), cell.getParam("message", ""));
        addNext(cell, wr, tools, "next");
        addFail("timeout", cell, wr, tools);
        return wr;
    }

    private static CounterBlock doCounterBlock(Drawio.DrawioCell cell, Tools tools) {
        Logger.info("Processing counter block");
        if (!cell.hasParam("count")) {
            Logger.error("Counter block is missing a count property or it's still empty");
            return null;
        }
        var counter = new CounterBlock(cell.getParam("count", 0));
        addNext(cell, counter, tools, "count>0", "next");
        addFail("count==0", cell, counter, tools);
        return counter;
    }

    private static ControlBlock doControlBlock(Drawio.DrawioCell cell, Tools tools) {
        Logger.info("Processing control block");
        if (!cell.hasArrow("trigger") && !cell.hasArrow("stop")) {
            Logger.error("Controlblock without a connection to trigger or stop.");
            return null;
        }

        OriginBlock trigger = null, stop = null;

        var triggerId = cell.hasArrow("trigger") ? cell.getArrowTarget("trigger").dasId : "";
        var stopId = cell.hasArrow("stop") ? cell.getArrowTarget("stop").dasId : "";

        for (var ori : tools.origins) {
            if (ori.id().equals(triggerId))
                trigger = ori;
            if (ori.id().equals(stopId))
                stop = ori;
        }
        var cb = new ControlBlock(tools.eventLoop, trigger, stop);
        addNext(cell, cb, tools, "next");
        return cb;
    }

    private static ReadingBlock doReaderBlock(Drawio.DrawioCell cell, Tools tools) {

        if (!cell.hasArrow("source") && !cell.hasParam("source")) {
            Logger.error("Reading block without a source.");
            return null;
        }
        if (!cell.hasParam("wants")) {
            Logger.error("Reading block without a wants specified");
            return null;
        }
        String src = "";
        if (cell.hasArrow("source")) {
            // TODO
        } else if (cell.hasParam("source")) {
            src = cell.getParam("source", "");
        }
        var reader = new ReadingBlock(tools.eventLoop);
        reader.setMessage(src, cell.getParam("wants", ""), cell.getParam("timeout", ""));

        addNext(cell, reader, tools, "received");
        addFail("timeout", cell, reader, tools);
        return reader;
    }

    private static SplitBlock doSplitBlock(Drawio.DrawioCell cell, Tools tools) {
        if (!cell.hasArrows()) {
            Logger.error("Splitblock without any next blocks specified");
            return null;
        }
        var sb = new SplitBlock(tools.eventLoop);
        sb.setInterval(cell.getParam("interval", ""));

        for (int a = 0; a < 50; a++) {
            var next = cell.getArrowTarget("next_" + a);
            if (next != null)
                sb.addNext(createBlock(next, tools));
        }
        return sb;
    }

    private static CmdBlock doCmdBlock(Drawio.DrawioCell cell, Tools tools) {
        if (!cell.hasParam("cmd")) {
            Logger.error("Commandblock without a cmd specified");
            return null;
        }
        var cb = new CmdBlock(Datagram.system(cell.getParam("cmd", "")));
        addNext(cell, cb, tools, "next", "pass");
        addFail("fail", cell, cb, tools);
        return cb;
    }

    private static ConditionBlock doConditionBlock(Drawio.DrawioCell cell, Tools tools) {
        if (!cell.hasParam("expression")) {
            Logger.error("Conditionblock without an expression specified.");
            return null;
        }
        var cb = ConditionBlock.build(cell.getParam("expression", ""), tools.rtvals, null);
        cb.ifPresent(block -> {
            addNext(cell, block, tools, "next", "pass");
            addFail("fail", cell, block, tools);
        });
        return cb.orElse(null);
    }

    private static void addNext(Drawio.DrawioCell cell, AbstractBlock block, Tools tools, String... nexts) {
        boolean match = false;
        tools.blocks.put(cell.drawId, block);
        for (var next : nexts) {
            if (cell.hasArrow(next)) {
                var target = cell.getArrowTarget(next).drawId;
                if (tools.blocks.get(target) == null) {
                    block.addNext(createBlock(cell.getArrowTarget(next), tools));
                } else {
                    block.addNext(tools.blocks.get(target));
                }
                match = true;
                break;
            }
        }
        if (!match)
            Logger.info("Final block in chain is " + block.getClass());
    }

    private static boolean addFail(String label, Drawio.DrawioCell cell, AbstractBlock block, Tools tools) {
        tools.blocks.put(cell.drawId, block);
        if (cell.hasArrow(label)) {
            block.setFailureBlock(createBlock(cell.getArrowTarget(label), tools));
            return true;
        }
        Logger.warn("No fail connection found with label " + label);
        return false;
    }
}
