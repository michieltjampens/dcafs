package util.drawio;

import io.netty.channel.EventLoopGroup;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.vals.IntegerVal;
import util.data.vals.NumericVal;
import util.data.vals.RealVal;
import util.data.vals.Rtvals;
import util.evalcore.MathFab;
import util.evalcore.ParseTools;
import util.tasks.blocks.*;
import util.tools.Tools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;

public class TaskParser {

    public record TaskTools(EventLoopGroup eventLoop, ArrayList<OriginBlock> origins, Rtvals rtvals,
                            HashMap<String, AbstractBlock> blocks, HashMap<String, String> idRef,
                            HashMap<String, String[]> todo) {
    }

    public record OriginCell(OriginBlock origin, Drawio.DrawioCell cell) {
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
    public static AbstractBlock createBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        if (cell == null)
            return null;

        return switch (cell.type) {
            case "basicvalblock" -> doAlterValBlock(cell, tools, id);
            case "clockblock" -> doClockBlock(cell, tools, id);
            case "controlblock" -> doControlBlock(cell, tools, id);
            case "counterblock" -> doCounterBlock(cell, tools, id);
            case "commandblock" -> doCmdBlock(cell, tools, id);
            case "conditionblock" -> doConditionBlock(cell, tools, id);
            case "delayblock" -> doDelayBlock(cell, tools, id);
            case "errorblock", "warnblock", "infoblock" -> doLogBlock(cell, tools, id);
            case "flagblock" -> doFlagBlock(cell, tools, id);
            case "triggergateblock" -> doTriggerGateBlock(cell, tools, id);
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

    private static BasicMathBlock doAlterValBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing AlterValBlock");

        var targets = cell.getParam("target", "").split(",");
        var source = cell.getParam("source", "");
        var op = cell.getParam("action", "");

        for (var target : targets) {
            if (!tools.rtvals().hasBaseVal(target)) {
                Logger.error(blockId + " -> No such target yet: " + target);
                return null;
            }
        }
        if (op.isEmpty()) {
            Logger.error(blockId + " -> No action provided.");
            return null;
        }

        var targetVals = Arrays.stream(targets).map(target -> getRealOrIntVal(tools.rtvals(), target)).toArray(NumericVal[]::new);

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
            } else if (tools.rtvals().hasBaseVal(source)) {
                sourceVal = getRealOrIntVal(tools.rtvals(), source);
            }
        }
        if (sourceVal == null) {
            Logger.error(blockId + " -> No valid source provided.");
            return null;
        }

        var bmb = BasicMathBlock.build(blockId, targetVals, op, sourceVal);
        if (bmb == null) {
            Logger.error(blockId + " -> Failed to build block.");
            return null;
        }
        addNext(cell, bmb, tools, "next", "ok", "done");
        return bmb;
    }
    private static ClockBlock doClockBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing Clockblock");

        var days = cell.getParam("days", "always");
        ClockBlock clockBlock;
        if (cell.hasParam("time")) {
            clockBlock = ClockBlock.create(tools.eventLoop(), cell.getParam("time", ""), days, false);
        } else if (cell.hasParam("localtime")) {
            clockBlock = ClockBlock.create(tools.eventLoop(), cell.getParam("localtime", ""), days, true);
        } else {
            Logger.error(blockId + " -> No time or localtime defined in Clockblock, or still empty.");
            return null;
        }
        clockBlock.id(blockId);
        if (!addNext(cell, clockBlock, tools, "next", "pass", "ok")) {
            if (cell.hasArrows())
                Logger.error(clockBlock.id() + " -> Clockblock without 'next' arrow, but does have at least one other arrow connected: " + cell.getArrowLabels());
        }
        return clockBlock;
    }
    private static CmdBlock doCmdBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing CommandBlock");

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
        cb.id(blockId);
        addNext(cell, cb, tools, "next", "pass", "ok");
        addAlt(cell, cb, tools, "fail");
        return cb;
    }
    private static ConditionBlock doConditionBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing Conditionblock");

        if (!cell.hasParam("expression")) {
            Logger.error(blockId + " -> ConditionBlock without an expression specified.");
            return null;
        }
        var cb = ConditionBlock.build(cell.getParam("expression", ""), tools.rtvals, null);
        cb.ifPresent(block -> {
            block.id(blockId);
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
                addAlt(cell, block, tools, "fail", "no", "false", "failed");
            }
        });
        return cb.orElse(null);
    }

    private static ControlBlock doControlBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing Controlblock");

        if (cell.getArrowTarget("stop", "trigger", "start") == null) {
            Logger.error(blockId + " -> Controlblock without a connection to trigger/start or stop.");
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
        cb.id(blockId);
        addNext(cell, cb, tools, "next", "pass", "ok");
        return cb;
    }

    private static CounterBlock doCounterBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing Counterblock");

        if (!cell.hasParam("count")) {
            Logger.error(blockId + " -> Counter block is missing a count property or it's still empty");
            return null;
        }
        var onZero = cell.getParam("onzero", "alt_fail"); // Options alt_fail,alt_pass,stop
        var altInfinite = !cell.getParam("altcount", "once").equals("once"); // once or infinite
        var counter = new CounterBlock(cell.getParam("count", 0));

        counter.setOnZero(onZero, altInfinite);
        counter.id(blockId);
        addNext(cell, counter, tools, "count>0", "next", "pass", "ok", "retries>0", "retry");
        addAlt(cell, counter, tools, "count==0", "counter==0", "count=0", "retries==0", "retries=0", "no retries left");
        return counter;
    }

    private static DelayBlock doDelayBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing Delayblock");

        if (!cell.hasParam("delay")) {
            Logger.error(blockId + " -> No delay specified for delayblock");
            return null;
        }

        var eventLoop = tools.eventLoop();
        var db = DelayBlock.useDelay(cell.params.get("delay"), cell.getParam("retrigger", "restart"), eventLoop);

        db.id(alterId(id));
        if (!addNext(cell, db, tools, "next", "ok", "done")) {
            if (cell.hasArrows() && !cell.getArrowLabels().equals("?"))
                Logger.error(db.id() + " -> Delay Block without 'next/done/ok' arrow, but does have at least one other arrow connected with label(s) " + cell.getArrowLabels() + " )");
        }
        return db;
    }
    private static FlagBlock doFlagBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing Flag Block: " + blockId);

        if (!cell.hasParam("action")) {
            Logger.error(blockId + " -> Flagblock without action specified.");
            return null;
        }
        if (!cell.hasParam("flag") && !cell.hasParam("flagval")) {
            Logger.error(blockId + " -> Flagblock without flagval id specified.");
            return null;
        }
        var action = cell.getParam("action", "raise");
        var flagId = cell.getParam("flagval", "");
        if (flagId.isEmpty())
            flagId = cell.getParam("flag", "");

        var flagOpt = tools.rtvals().getFlagVal(flagId);
        if (flagOpt.isEmpty()) {
            Logger.error(blockId + " -> No such flagVal yet: " + flagId);
            return null;
        }
        FlagBlock fb = switch (action) {
            case "raise", "set" -> FlagBlock.raises(flagOpt.get());
            case "lower", "clear" -> FlagBlock.lowers(flagOpt.get());
            case "toggle" -> FlagBlock.toggles(flagOpt.get());
            default -> {
                Logger.error(blockId + " -> Unknown action picked for " + flagId + ": " + action);
                yield null;
            }
        };
        if (fb == null)
            return null;

        fb.id(blockId);
        if (!addNext(cell, fb, tools, "next", "pass", "yes", "ok")) {
            if (cell.hasArrows() && !cell.getArrowLabels().equals("?"))
                Logger.error(blockId + " -> Flag Block without 'next/pass/yes/ok' arrow, but does have at least one other arrow connected with label(s) " + cell.getArrowLabels() + " )");
        }
        return fb;
    }

    private static DelayBlock doIntervalBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing Interval block");

        var repeats = cell.getParam("repeats", -1);
        var interval = cell.getParam("interval", "");

        if (repeats == 0) { // Repeats of zero means it's just a single delay, so make it one
            Logger.info(blockId + " -> Replacing interval with delay block because 1 rep");
            var eventLoop = tools.eventLoop();
            var db = DelayBlock.useDelay(interval, "ignore", eventLoop);
            db.id(alterId(id));
            addNext(cell, db, tools, "next");
            return db;
        }

        if (!cell.hasParam("interval")) {
            Logger.error(blockId + " -> No interval specified for intervalblock, or still empty");
            return null;
        }

        var eventLoop = tools.eventLoop();
        var init = cell.getParam("initialdelay", interval);
        var retrigger = cell.getParam("retrigger", "ignore");
        // If repeats is not 0, can have a failure

        var db = DelayBlock.useInterval(eventLoop, init, interval, repeats, retrigger);
        db.id(blockId);

        addNext(cell, db, tools, "next");
        addAlt(cell, db, tools, "stopped", "cancelled");
        return db;
    }

    private static LogBlock doLogBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing Log block");

        if (!cell.hasParam("message")) {
            Logger.error(blockId + " -> Logblock without a message specified");
            return null;
        }
        var message = cell.getParam("message", "");
        var list = ParseTools.extractCurlyContent(message,true);
        var refs = list.stream().filter( l -> l.contains("_"))
                        .map(it-> tools.rtvals().getNumericalVal(it))
                        .flatMap(Optional::stream).toArray(NumericVal[]::new);

        var lb = switch (cell.type) {
            case "errorblock" -> LogBlock.error(message,refs);
            case "warnblock" -> LogBlock.warn(message,refs);
            case "infoblock" -> LogBlock.info(message,refs);
            default -> null;
        };
        if (lb != null) {
            lb.id(blockId);
            // Arrows
            if (!addNext(cell, lb, tools, "next", "pass", "ok")) {
                if (cell.hasArrows() && !cell.getArrowLabels().equals("?"))
                    Logger.error(lb.id() + " -> Log Block without 'next/pass/ok' arrow, but does have at least one other arrow connected with label(s) " + cell.getArrowLabels() + " )");
            }
        }
        return lb;
    }
    private static MathBlock doMathBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing Math Block: " + blockId);

        if (!cell.hasParam("expression")) {
            Logger.error(blockId + " -> Math Block without expression specified.");
            return null;
        }

        var exp = cell.getParam("expression", "");
        var mathEval = MathFab.parseExpression(exp, tools.rtvals());
        var res = MathFab.stripForValIfPossible(mathEval);
        if (res == null)
            return null;

        var mb = MathBlock.build(res);
        mb.id(blockId);
        addNext(cell, mb, tools, "next", "pass", "yes", "ok");

        return mb;
    }

    private static ReadingBlock doReaderBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing Reader block");

        if (!cell.hasArrow("source") && !cell.hasParam("source")) {
            Logger.error(blockId + " -> Reading block without a source.");
            return null;
        }
        if (!cell.hasParam("wants")) {
            Logger.error(blockId + " -> Reading block without a wants specified");
            return null;
        }
        String src = "";
        if (cell.hasArrow("source")) {
            // TODO
        } else if (cell.hasParam("source")) {
            src = cell.getParam("source", "");
            if (!src.contains(":")) {
                var type = cell.getParam("sourcetype", "");
                if (type.isEmpty()) {
                    Logger.warn("No type provided, defaulting to 'raw'");
                    type = "raw";
                }
                src = type + ":" + src;
            }
        }
        var reader = new ReadingBlock(tools.eventLoop);
        reader.setMessage(src, cell.getParam("wants", ""), cell.getParam("timeout", ""));
        reader.id(blockId);
        // Arrows
        addNext(cell, reader, tools, "received", "ok", "next");
        addAlt(cell, reader, tools, "timeout");
        return reader;
    }

    private static SplitBlock doSplitBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id, "[", "]");
        Logger.info(blockId + " -> Processing Split block");

        if (!cell.hasArrows()) {
            Logger.error(blockId + " -> Splitblock/Splitdot without departing arrows");
            return null;
        }
        var sb = new SplitBlock(tools.eventLoop);
        sb.setInterval(cell.getParam("interval", ""));
        sb.id(blockId);
        tools.idRef.put(sb.id(), cell.drawId);

        for (int a = 0; a < 50; a++) {
            var next = cell.getArrowTarget("next_" + a, String.valueOf(a));
            if (next != null) {
                sb.addNext(createBlock(next, tools, sb.id() + "[" + a + "]" + "|"));
            }
        }
        return sb;
    }

    private static TriggerGateBlock doTriggerGateBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        var blockId = alterId(id);
        Logger.info(blockId + " -> Processing TriggerGateBlock");

        if (!cell.hasParam("period")) {
            Logger.error(blockId + " -> No period specified for TriggerGateBlock");
            return null;
        }

        var eventLoop = tools.eventLoop();
        var tgb = TriggerGateBlock.build(eventLoop, cell.params.get("period"), cell.getParam("retrigger", "ignore"));

        tgb.id(alterId(id));
        if (!addNext(cell, tgb, tools, "next", "ok", "done")) {
            if (cell.hasArrows() && !cell.getArrowLabels().equals("?"))
                Logger.error(tgb.id() + " -> TriggerGate Block without 'next/done/ok' arrow, but does have at least one other arrow connected with label(s) " + cell.getArrowLabels() + " )");
        }
        addAlt(cell, tgb, tools, "disarmed", "counting", "alt");
        return tgb;
    }
    private static WritableBlock doWritableBlock(Drawio.DrawioCell cell, TaskTools tools, String id) {
        Logger.info("Processing writer block");
        var blockId = alterId(id);
        if (!cell.hasParam("message")) {
            Logger.error(blockId + " -> Writable block is missing a message property or it's still empty");
            return null;
        }
        String target;
        if (!cell.hasParam("target")) { // TODO use arrow instead?
            Logger.error(blockId + " -> Writable block is missing a target property or it's still empty");
            return null;
        } else {
            target = cell.getParam("target", "");
            if (!target.contains(":")) {
                var type = cell.getParam("targettype", "");
                if (type.isEmpty()) {
                    Logger.warn("No type provided, defaulting to 'raw'");
                    type = "raw";
                }
                target = type + ":" + target;
            }
        }
        var wr = new WritableBlock(target, cell.getParam("message", ""));
        wr.id(blockId);
        addNext(cell, wr, tools, "next", "pass", "ok", "send");
        addAlt(cell, wr, tools, "timeout", "fail", "failed", "failure", "disconnected", "not connected");
        return wr;
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

    private static NumericVal getRealOrIntVal(Rtvals rtvals, String target) {
        var real = rtvals.getRealVal(target);
        if (real.isPresent())
            return real.get();
        return rtvals.getIntegerVal(target).orElse(null);
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
