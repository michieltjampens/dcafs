package util.drawio;

import io.netty.channel.EventLoopGroup;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.vals.IntegerVal;
import util.data.vals.NumericVal;
import util.data.vals.RealVal;
import util.data.vals.Rtvals;
import util.evalcore.MathFab;
import util.tasks.blocks.*;
import util.tools.Tools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Collectors;

public class TaskParser {

    public record TaskTools(EventLoopGroup eventLoop, ArrayList<OriginBlock> origins, Rtvals rtvals,
                            HashMap<String, AbstractBlock> blocks, HashMap<String, String> idRef,
                            HashMap<String, String[]> todo) {
    }

    public record OriginCell(OriginBlock origin, Drawio.DrawioCell cell) {
    }

    public static ArrayList<OriginBlock> parseDrawIoTaskFile(Path file, EventLoopGroup eventLoop, Rtvals rtvals) {
        if (!file.getFileName().toString().endsWith(".drawio")) {
            Logger.error("This is not a drawio file: " + file);
            return new ArrayList<>();
        }

        var cells = Drawio.parseFile(file);
        return parseTasks(file, cells, eventLoop, rtvals);
    }

    public static ArrayList<OriginBlock> parseTasks(Path file, HashMap<String, Drawio.DrawioCell> cells, EventLoopGroup eventLoop, Rtvals rtvals) {
        HashMap<String, String> idRef = new HashMap<>();
        ArrayList<OriginBlock> origins = new ArrayList<>();
        var tools = new TaskTools(eventLoop, origins, rtvals, new HashMap<>(), idRef, new HashMap<>());

        // First create all the origins and then populate with the rest because controlblocks need a full list during set up
        ArrayList<OriginCell> oricell = new ArrayList<>();
        for (var entry : cells.entrySet()) {
            var cell = entry.getValue();
            if (cell.type.equals("originblock")) { // Look for originblocks
                var origin = doOriginBlock(cell);
                origins.add(origin);
                oricell.add(new OriginCell(origin, cell));
            }
        }
        for (var oricel : oricell) {
            addNext(oricel.cell(), oricel.origin(), tools, "next");
        }
        // Fill in rest of controlblock if needed
        fixControlBlocks(cells, tools);

        DrawioEditor.addIds(idRef, file);
        oricell.clear();
        return origins;
    }

    public static void fixControlBlocks(HashMap<String, Drawio.DrawioCell> cells, TaskTools tools) {
        if (!tools.todo.isEmpty()) {
            for (var entry : tools.todo().entrySet()) {
                var control = (ControlBlock) tools.blocks.get(entry.getKey()); // Get control block
                var trigger = tools.blocks.get(entry.getValue()[0]); // trigger block
                var stop = tools.blocks.get(entry.getValue()[1]); // stop block

                if (trigger == null) {
                    var triggerCell = cells.get(entry.getValue()[0]);
                    trigger = createBlock(triggerCell, tools, control.id() + "[T]" + "|");
                }
                control.setBlocks(trigger, stop);
            }
        }
    }
    private static void lookup(HashMap<String, String> idRef, ArrayList<OriginBlock> origins) {
        var list = new ArrayList<String>();
        for (var entry : idRef.entrySet()) {
            boolean found = false;
            for (var ori : origins) {
                var match = ori.matchId(entry.getKey());
                if (match != null) {
                    var clSplit = match.getClass().toString().split("\\.");
                    list.add(entry.getKey() + " -> " + clSplit[clSplit.length - 1] + " -> " + entry.getValue());
                    found = true;
                    break;
                }
            }
            if (!found)
                list.add(entry.getKey() + " -> ??? -> " + entry.getValue());
        }
        Logger.info(list.stream().sorted().collect(Collectors.joining("\r\n")));
    }

    public static AbstractBlock createBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        if (cell == null)
            return null;

        return switch (cell.type) {
            case "basicvalblock" -> doBasicValBlock(cell, tools, id);
            case "clockblock" -> doClockBlock(cell, tools, id);
            case "controlblock" -> doControlBlock(cell, tools, id);
            case "counterblock" -> doCounterBlock(cell, tools, id);
            case "commandblock" -> doCmdBlock(cell, tools, id);
            case "conditionblock" -> doConditionBlock(cell, tools, id);
            case "delayblock" -> doDelayBlock(cell, tools, id);
            case "errorblock", "warnblock", "infoblock" -> doLogBlock(cell, tools, id);
            case "flagblock" -> doFlagBlock(cell, tools, id);
            case "intervalblock" -> doIntervalBlock(cell, tools, id);
            case "mathblock" -> doMathBlock(cell, tools, id);
            case "originblock" -> doOriginBlock(cell);
            case "readerblock" -> doReaderBlock(cell, tools, id);
            case "splitblock" -> doSplitBlock(cell, tools, id);
            case "writerblock" -> doWritableBlock(cell, tools, id);

            default -> null;
        };
    }

    private static OriginBlock doOriginBlock(Drawio.DrawioCell cell) {
        var ob = new OriginBlock(cell.dasId);

        var auto = cell.getParam("autostart", "no");
        var shut = cell.getParam("shutdownhook", "no");

        ob.setAutostart(Tools.parseBool(auto, false));
        ob.setShutdownhook(Tools.parseBool(shut, false));

        if (!cell.hasArrow("next")) {
            if (cell.hasArrows() && !cell.getArrowLabels().equals("?"))
                Logger.error(ob.id() + " -> Origin Block without 'next' arrow, but does have at least one other arrow connected with label(s) " + cell.getArrowLabels() + " )");
        }
        return ob;
    }

    private static BasicMathBlock doBasicValBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var target = cell.getParam("target", "");
        var source = cell.getParam("source", "");
        var op = cell.getParam("action", "");

        var newId = alterId(id);
        if (!tools.rtvals().hasBaseVal(target)) {
            Logger.error(newId + " -> No such target yet: " + target);
            return null;
        }
        if (op.isEmpty()) {
            Logger.error(newId + " -> No action provided.");
            return null;
        }

        var targetVal = getRealOrIntVal(tools.rtvals(), source);
        if (targetVal == null) {
            Logger.error(newId + " -> No valid source provided");
            return null;
        }

        NumericVal sourceVal = null;
        if (!op.equals("reset")) {
            if (NumberUtils.isParsable(source)) {
                if (source.contains(".")) {
                    sourceVal = RealVal.newVal("dcafs", "temp");
                    sourceVal.update(NumberUtils.toDouble(source));
                } else {
                    sourceVal = IntegerVal.newVal("dcafs", "temp");
                    sourceVal.update(NumberUtils.toInt(source));
                }
            } else if (tools.rtvals().hasBaseVal(target)) {
                sourceVal = getRealOrIntVal(tools.rtvals(), source);
            }
        }
        var bmb = BasicMathBlock.build(newId, targetVal, op, sourceVal);
        if (bmb == null) {
            Logger.error(newId + " -> Failed to build block.");
        }
        addNext(cell, bmb, tools, "next", "ok", "done");
        return bmb;
    }

    private static NumericVal getRealOrIntVal(Rtvals rtvals, String target) {
        var real = rtvals.getRealVal(target);
        if (real.isPresent())
            return real.get();
        return rtvals.getIntegerVal(target).orElse(null);
    }
    private static DelayBlock doDelayBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        Logger.info("Processing delay block");
        if (cell.hasParam("delay")) {
            var eventLoop = tools.eventLoop();
            var db = DelayBlock.useDelay(cell.params.get("delay"), cell.getParam("retrigger", "restart"), eventLoop);

            db.id(alterId(id));
            if (!addNext(cell, db, tools, "next", "ok", "done")) {
                if (cell.hasArrows() && !cell.getArrowLabels().equals("?"))
                    Logger.error(db.id() + " -> Delay Block without 'next/done/ok' arrow, but does have at least one other arrow connected with label(s) " + cell.getArrowLabels() + " )");
            }
            return db;
        }
        Logger.error("No delay specified for delayblock");
        return null;
    }
    private static DelayBlock doIntervalBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var repeats = cell.getParam("repeats", -1);
        var interval = cell.getParam("interval", "");

        if (repeats == 0) { // Repeats of zero means it's just a single delay, so make it one
            var eventLoop = tools.eventLoop();
            var db = DelayBlock.useDelay(interval, "ignore", eventLoop);
            db.id(alterId(id));
            addNext(cell, db, tools, "next");
            return db;
        }

        Logger.info("Processing interval block");
        if (cell.hasParam("interval")) {
            var eventLoop = tools.eventLoop();
            var init = cell.getParam("initialdelay", interval);
            // If repeats is not 0, can have a failure

            var db = DelayBlock.useInterval(eventLoop, init, interval, repeats);
            db.id(alterId(id));

            addNext(cell, db, tools, "next", "repeats>0");
            addAlt(cell, db, tools, "repeat==0");

            if (repeats != -1) {
                if (!addAlt(cell, db, tools, "repeats==0"))
                    Logger.warn("Interval task with repeats but failure link not used.");
            }
            return db;
        }
        Logger.error("No interval specified for intervalblock, or still empty");
        return null;
    }

    private static ClockBlock doClockBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        Logger.info("Processing time block");

        var days = cell.getParam("days", "always");
        ClockBlock clockBlock;
        if (cell.hasParam("time")) {
            clockBlock = ClockBlock.create(tools.eventLoop(), cell.getParam("time", ""), days, false);
        } else if (cell.hasParam("localtime")) {
            clockBlock = ClockBlock.create(tools.eventLoop(), cell.getParam("localtime", ""), days, true);
        } else {
            Logger.error("No time or localtime defined in Timeblock, or still empty.");
            return null;
        }
        clockBlock.id(alterId(id));
        if (!addNext(cell, clockBlock, tools, "next", "pass", "ok")) {
            if (cell.hasArrows())
                Logger.error(clockBlock.id() + " -> clock block without 'next' arrow, but does have at least one other arrow connected.");
        }
        return clockBlock;
    }

    private static WritableBlock doWritableBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
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
        wr.id(alterId(id));
        addNext(cell, wr, tools, "next", "pass", "ok");
        addAlt(cell, wr, tools, "timeout");
        return wr;
    }

    private static CounterBlock doCounterBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        Logger.info("Processing counter block");
        if (!cell.hasParam("count")) {
            Logger.error("Counter block is missing a count property or it's still empty");
            return null;
        }
        var onzero = cell.getParam("onzero", "alt_fail"); // Options alt_fail,alt_pass,stop
        var altInfinite = !cell.getParam("altcount", "once").equals("once"); // once or infinite
        var counter = new CounterBlock(cell.getParam("count", 0));

        counter.setOnZero(onzero, altInfinite);
        counter.id(alterId(id));
        addNext(cell, counter, tools, "count>0", "next", "pass", "ok", "retries>0", "retry");
        addAlt(cell, counter, tools, "count==0", "count=0", "retries==0", "retries=0", "no retries left");
        return counter;
    }

    private static ControlBlock doControlBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        Logger.info("Processing control block");
        if (cell.getArrowTarget("stop", "trigger", "start") == null) {
            Logger.error("Controlblock without a connection to trigger/start or stop.");
            return null;
        }

        AbstractBlock trigger = null, stop = null;

        var triggerCell = cell.getArrowTarget("trigger");
        if (triggerCell == null)
            triggerCell = cell.getArrowTarget("start");
        var stopCell = cell.getArrowTarget("stop");


        if (stopCell != null || triggerCell != null) {
            var stopDas = stopCell == null ? "" : stopCell.dasId;
            var triggerDas = triggerCell == null ? "" : triggerCell.dasId;

            for (var ori : tools.origins) {
                if (ori.id().equals(stopDas))
                    trigger = ori;
                if (ori.id().equals(triggerDas))
                    stop = ori;
            }
            if (trigger == null && triggerCell != null) {
                trigger = tools.blocks.get(triggerCell.drawId);
                if (trigger == null) // Not created yet
                    tools.todo.put(cell.drawId, new String[]{triggerCell.drawId, ""});
            }
            if (stop == null && stopCell != null) {
                stop = tools.blocks.get(stopCell.drawId);
                if (stop == null) { // Not created yet
                    var old = tools.todo.putIfAbsent(cell.drawId, new String[]{"", stopCell.drawId});
                    if (old != null) {
                        old[1] = stopCell.drawId;
                        tools.todo.put(cell.drawId, old);
                    }
                }
            }
        }
        var cb = new ControlBlock(tools.eventLoop, trigger, stop);
        cb.id(alterId(id));
        addNext(cell, cb, tools, "next", "pass", "ok");
        return cb;
    }
    private static ReadingBlock doReaderBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {

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
        reader.id(alterId(id));
        // Arrows
        addNext(cell, reader, tools, "received", "ok", "next");
        addAlt(cell, reader, tools, "timeout");
        return reader;
    }

    private static SplitBlock doSplitBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        if (!cell.hasArrows()) {
            Logger.error("Splitblock without any next blocks specified");
            return null;
        }
        var sb = new SplitBlock(tools.eventLoop);
        sb.setInterval(cell.getParam("interval", ""));
        sb.id(alterId(id, "[", "]"));
        tools.idRef.put(sb.id(), cell.drawId);

        for (int a = 0; a < 50; a++) {
            var next = cell.getArrowTarget("next_" + a);
            if (next != null) {
                sb.addNext(createBlock(next, tools, sb.id() + "[" + a + "]" + "|"));
            }
        }
        return sb;
    }

    private static LogBlock doLogBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        if (!cell.hasParam("message")) {
            Logger.error("Logblock without a message specified");
            return null;
        }
        var message = cell.getParam("message", "");
        var lb = switch (cell.type) {
            case "errorblock" -> LogBlock.error(message);
            case "warnblock" -> LogBlock.warn(message);
            case "infoblock" -> LogBlock.info(message);
            default -> null;
        };
        if (lb != null) {
            lb.id(alterId(id));
            // Arrows
            if (!addNext(cell, lb, tools, "next", "pass", "ok")) {
                if (cell.hasArrows() && !cell.getArrowLabels().equals("?"))
                    Logger.error(lb.id() + " -> Log Block without 'next/pass/ok' arrow, but does have at least one other arrow connected with label(s) " + cell.getArrowLabels() + " )");
            }
        }
        return lb;
    }

    private static CmdBlock doCmdBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        if (!cell.hasParam("cmd") && !cell.hasParam("message")) {
            Logger.error("Commandblock without a cmd/message specified");
            return null;
        }
        var cmd = cell.getParam("cmd", cell.getParam("message", ""));
        cmd = switch (cell.type) {
            case "errorblock" -> "log:error," + cmd;
            case "warnblock" -> "log:warn," + cmd;
            case "infoblock" -> "log:info," + cmd;
            default -> cmd;
        };
        var cb = new CmdBlock(Datagram.system(cmd));
        cb.id(alterId(id));
        addNext(cell, cb, tools, "next", "pass", "ok");
        addAlt(cell, cb, tools, "fail");
        return cb;
    }

    private static ConditionBlock doConditionBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        if (!cell.hasParam("expression")) {
            Logger.error("ConditionBlock without an expression specified.");
            return null;
        }
        var cb = ConditionBlock.build(cell.getParam("expression", ""), tools.rtvals, null);
        cb.ifPresent(block -> {
            block.id(alterId(id));
            var target = cell.getArrowTarget("update");
            if (target != null && target.type.equals("flagval")) {
                var flagId = target.getParam("group", "") + "_" + target.getParam("name", "");
                tools.rtvals().getFlagVal(flagId).ifPresent(block::setFlag);

                // Grab the next of the flagval and make it the next of the condition
                if (target.hasArrow("next")) {
                    tools.blocks.put(cell.drawId, block);
                    if (block.id().isEmpty())
                        Logger.info("Id still empty?");
                    tools.idRef.put(block.id(), cell.drawId);
                    if (target.hasArrow("next")) {
                        var targetCell = target.getArrowTarget("next");
                        var targetId = targetCell.drawId;
                        var existing = tools.blocks.get(targetId);
                        if (existing == null) { // not created yet
                            block.addNext(createBlock(targetCell, tools, block.id()));
                        } else {
                            block.addNext(existing);
                        }
                    }
                }
            } else {
                addNext(cell, block, tools, "next", "pass", "yes", "ok", "true");
                addAlt(cell, block, tools, "fail", "no", "false");
            }
        });
        return cb.orElse(null);
    }

    private static FlagBlock doFlagBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        if (!cell.hasParam("action")) {
            Logger.error("Flagblock without action specified.");
            return null;
        }
        if (!cell.hasParam("flag") && !cell.hasParam("flagval")) {
            Logger.error("Flagblock without flagval id specified.");
            return null;
        }
        var action = cell.getParam("action", "raise");
        var flagId = cell.getParam("flagval", "");
        if (flagId.isEmpty())
            flagId = cell.getParam("flag", "");

        var flagOpt = tools.rtvals().getFlagVal(flagId);
        if (flagOpt.isEmpty()) {
            Logger.error("No such flagVal yet: " + flagId);
            return null;
        }
        FlagBlock fb = switch (action) {
            case "raise", "set" -> FlagBlock.raises(flagOpt.get());
            case "lower", "clear" -> FlagBlock.lowers(flagOpt.get());
            case "toggle" -> FlagBlock.toggles(flagOpt.get());
            default -> {
                Logger.error("Unknown action picked for " + flagId + ": " + flagId);
                yield null;
            }
        };
        if (fb == null)
            return null;

        fb.id(alterId(id));
        if (!addNext(cell, fb, tools, "next", "pass", "yes", "ok")) {
            if (cell.hasArrows() && !cell.getArrowLabels().equals("?"))
                Logger.error(fb.id() + " -> Flag Block without 'next/pass/yes/ok' arrow, but does have at least one other arrow connected with label(s) " + cell.getArrowLabels() + " )");
        }
        return fb;
    }

    private static MathBlock doMathBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        if (!cell.hasParam("expression")) {
            Logger.error("Mathblock without expression specified.");
            return null;
        }

        var exp = cell.getParam("expression", "");
        var mathEval = MathFab.parseExpression(exp, tools.rtvals());
        var res = MathFab.stripForValIfPossible(mathEval);
        if (res == null)
            return null;

        var mb = MathBlock.build(res);
        mb.id(alterId(id));
        addNext(cell, mb, tools, "next", "pass", "yes", "ok");

        return mb;
    }

    /* ************************************************* H E L P E R S ********************************************** */
    private static String alterId(String id) {
        return alterId(id, "", "");
    }

    private static String alterId(String id, String prefix, String suffix) {
        if (!id.contains("@"))
            return id + "@" + prefix + "0" + suffix;

        if (id.endsWith("|"))
            return id + prefix + "0" + suffix;

        var splitter = id.contains("|") ? "|" : "@";
        var split = Tools.endSplit(id, splitter);
        return split[0] + splitter + prefix + (NumberUtils.toInt(split[1]) + 1) + suffix;
    }
    private static boolean addNext(Drawio.DrawioCell cell, AbstractBlock block, TaskTools tools, String... nexts) {

        tools.blocks.put(cell.drawId, block);
        if (block.id().isEmpty())
            Logger.info("Id still empty?");
        tools.idRef.put(block.id(), cell.drawId);
        for (var next : nexts) {
            if (cell.hasArrow(next)) {
                var targetCell = cell.getArrowTarget(next);
                var targetId = targetCell.drawId;
                var existing = tools.blocks.get(targetId);
                if (existing == null) { // not created yet
                    block.addNext(createBlock(targetCell, tools, block.id()));
                } else {
                    block.addNext(existing);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean addAlt(Drawio.DrawioCell cell, AbstractBlock block, TaskTools tools, String... labels) {
        tools.blocks.put(cell.drawId, block);
        for (var label : labels) {
            if (cell.hasArrow(label)) {
                var id = block.id();
                id = id + "|";
                tools.idRef.put(block.id(), cell.drawId);

                var targetCell = cell.getArrowTarget(label);
                var targetId = cell.getArrowTarget(label).drawId;
                var existing = tools.blocks.get(targetId);
                if (existing == null) {
                    block.setAltRouteBlock(createBlock(targetCell, tools, id));
                } else {
                    block.setAltRouteBlock(existing);
                }
                return true;
            }
        }
        return false;
    }
}
