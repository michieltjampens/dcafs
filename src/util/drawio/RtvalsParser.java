package util.drawio;

import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.data.procs.Builtin;
import util.data.procs.MathEvalForVal;
import util.data.procs.Reducer;
import util.data.vals.*;
import util.evalcore.MathFab;
import util.tasks.blocks.AbstractBlock;
import util.tasks.blocks.ConditionBlock;
import util.tasks.blocks.OriginBlock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

public class RtvalsParser {
    public record ParserTools(EventLoopGroup eventLoop, Rtvals rtvals,
                              HashMap<String, AbstractBlock> blocks, ArrayList<ValCell> vals) {
    }

    public record ValCell(NumericVal val, Drawio.DrawioCell cell) {
    }

    public static void parseDrawIoRtvals(Path file, EventLoopGroup eventLoop, Rtvals rtvals) {
        if (!file.getFileName().toString().endsWith(".drawio")) {
            Logger.error("This is not a drawio file: " + file);
            return;
        }
        var tools = new ParserTools(eventLoop, rtvals, new HashMap<>(), new ArrayList<>());
        var cells = Drawio.parseFile(file);
        parseRtvals(cells, tools);
    }

    public static void parseDrawIoRtvals(HashMap<String, Drawio.DrawioCell> cells, EventLoopGroup eventLoop, Rtvals rtvals) {
        var tools = new ParserTools(eventLoop, rtvals, new HashMap<>(), new ArrayList<>());
        parseRtvals(cells, tools);
    }
    public static ConditionBlock parseRtvals(HashMap<String, Drawio.DrawioCell> cells, ParserTools tools) {
        // First create all the origins and then populate with the rest because controlblocks need a full list during set up
        ArrayList<Drawio.DrawioCell> starts = new ArrayList<>();
        for (var entry : cells.entrySet()) {
            var cell = entry.getValue();
            switch (cell.type) {
                case "realval":
                    var rv = doRealVal(cell, tools);
                    if (rv != null && isNewId(rv.id(), tools.vals)) {
                        tools.vals.add(new ValCell(rv, cell));
                    }
                    break;
                case "integerval":
                    var iv = doIntegerVal(cell, tools);
                    if (iv != null && isNewId(iv.id(), tools.vals))
                        tools.vals.add(new ValCell(iv, cell));
                    break;
                case "valupdater":
                    starts.add(cell);
                default:
                    break;
            }
        }
        parseTasks(starts, tools);
        return null;
    }

    private static boolean isNewId(String id, ArrayList<ValCell> vcs) {
        for (var vc : vcs) {
            if (vc.val().id().equals(id)) {
                Logger.error("Tried to use an existing id twice in the same diagram");
                return false;
            }
        }
        return true;
    }
    public static RealVal doRealVal(Drawio.DrawioCell cell, ParserTools tools) {
        var idArray = getId(cell);
        if (idArray.length == 0)
            return null;
        String group = idArray[0], name = idArray[1];
        if (tools.rtvals().hasReal(group + "_" + name))
            return tools.rtvals().getRealVal(group + "_" + name).get(); //get is fine because of earlier has
        int window = cell.getParam("window", 0);
        var unit = cell.getParam("unit", "");

        RealVal rv;
        if (window == 0) {
            rv = RealVal.newVal(group, name);
            rv.unit(unit);
            rv.defValue(cell.getParam("def", rv.defValue()));
        } else {
            var reducer = Reducer.getDoubleReducer(cell.getParam("reducer", "avg"), cell.getParam("def", 0), window);
            var ra = new RealValAggregator(group, name, unit, reducer, window);
            ra.setScale(cell.getParam("scale", -1));
            Logger.info("Building RealValAggregator " + ra.id());
            rv = ra;
        }
        return rv;
    }

    public static IntegerVal doIntegerVal(Drawio.DrawioCell cell, ParserTools tools) {

        var idArray = getId(cell);
        if (idArray.length == 0)
            return null;
        String group = idArray[0], name = idArray[1];

        if (tools.rtvals().hasInteger(group + "_" + name))
            return tools.rtvals().getIntegerVal(group + "_" + name).get(); //get is fine because of earlier has
        int window = cell.getParam("window", 0);
        var unit = cell.getParam("unit", "");

        IntegerVal iv;
        if (window == 0) {
            iv = IntegerVal.newVal(group, name);
            iv.unit(cell.getParam("unit", ""));
            iv.defValue(cell.getParam("def", iv.defValue()));
        } else {
            var reducer = Reducer.getIntegerReducer(cell.getParam("reducer", "sum"), cell.getParam("def", 0), window);
            var ri = new IntegerValAggregator(group, name, unit, reducer, window);

            Logger.info("Building IntegerValAggregator " + ri.id());
            iv = ri;
        }
        return iv;
    }

    private static String[] getId(Drawio.DrawioCell cell) {
        if (cell.hasParam("dcafsid")) {
            var id = cell.getParam("dcafsid", "");
            return id.split("_", 2);
        } else if (cell.hasParam("name") && cell.hasParam("group")) {
            return new String[]{cell.getParam("group", ""), cell.getParam("name", "")};
        } else {
            Logger.error("No id found in the cell: " + cell.drawId);
            return new String[0];
        }
    }

    private static void parseTasks(ArrayList<Drawio.DrawioCell> starters, ParserTools tools) {
        HashMap<String, String> idRef = new HashMap<>();
        ArrayList<OriginBlock> origins = new ArrayList<>();

        for (var cell : starters) {
            var label = cell.getParam("arrowlabel", "");
            var target = cell.getArrowTarget(label);

            var res = buildValCel(target, label, tools, origins, idRef);
            if (res == null) {
                Logger.error("Failed to make val");
                continue;
            }
            if (res.val() instanceof RealVal rv) {
                tools.rtvals().addRealVal(rv);
            } else if (res.val() instanceof IntegerVal iv) {
                tools.rtvals().addIntegerVal(iv);
            }
        }
    }

    private static String normalizeExpression(String exp, String label) {
        exp = exp.replace(label, "i0"); // alter so it get i0
        exp = exp.replace("new", "i0");
        exp = exp.replace("old", "i1"); // alter so it get i1 instead of old
        return exp.replace("math", "i2"); // alter so it get i2 instead of math
    }

    private static ValCell buildValCel(Drawio.DrawioCell target, String label, ParserTools tools, ArrayList<OriginBlock> origins, HashMap<String, String> idRef) {

        ValCell valcell = null;
        ConditionBlock pre = null;
        ConditionBlock post = null;
        MathEvalForVal math = null;
        String builtin = "";
        int scale = 6;

        // Do everything up to the rtval
        while (valcell == null) {
            switch (target.type) {
                case "realval", "integerval" -> {
                    for (var vc : tools.vals()) {
                        if (vc.cell().equals(target)) {
                            valcell = vc;
                            if (pre != null) {
                                pre.id(valcell.val().id() + "_pre");
                                valcell.val().setPreCheck(pre);
                            }
                            if (post != null) {
                                post.id(valcell.val().id() + "_post");
                                valcell.val().setPostCheck(post, false);
                            }
                            if (math != null) {
                                valcell.val().setMath(math);
                            } else if (!builtin.isEmpty()) {
                                if (target.type.startsWith("real")) {
                                    if (Builtin.isValidDoubleProc(builtin)) {
                                        valcell.val().setMath(Builtin.getDoubleFunction(builtin, scale));
                                    } else {
                                        Logger.error("Builtin " + builtin + " not recognized as a valid function.");
                                        return null;
                                    }
                                } else {
                                    if (Builtin.isValidIntProc(builtin)) {
                                        valcell.val().setMath(Builtin.getIntFunction(builtin));
                                    } else {
                                        Logger.error("Builtin " + builtin + " not recognized as a valid function.");
                                        return null;
                                    }
                                }
                            }
                            break;
                        }
                    }
                    if (valcell == null) {
                        Logger.error("Couldn't match shape with a val");
                    }
                }
                case "conditionblock" -> {
                    var exp = target.getParam("expression", "");
                    target.addParam("expression", normalizeExpression(exp, label));

                    if (pre == null) {
                        pre = (ConditionBlock) TaskParser.createBlock(target, new TaskParser.TaskTools(tools.eventLoop(), origins, tools.rtvals, new HashMap<>(), idRef), "id");
                    } else {
                        post = (ConditionBlock) TaskParser.createBlock(target, new TaskParser.TaskTools(tools.eventLoop(), origins, tools.rtvals, new HashMap<>(), idRef), "id");
                    }

                    // that's precheck?
                    target = getNext(target, label, "ok", "next");
                    if (target == null) {
                        Logger.error("Not found a block after the conditionblock with exp: " + exp);
                        return null;
                    }
                }
                case "mathblock" -> {
                    var mexp = target.getParam("expression", "");
                    if (mexp.isEmpty()) { // Not an expression, so a builtin function
                        builtin = target.getParam("builtin", ""); // Depends on the val, so store,don't build yet
                        scale = target.getParam("scale", scale);
                        if (builtin.isEmpty()) {
                            Logger.error("Mathblock without expression or builtin attribute filled in.");
                            return null;
                        }
                    } else {
                        math = MathFab.parseExpression(normalizeExpression(mexp, label), tools.rtvals, null);
                        if (math == null) {
                            Logger.error("Failed to parse " + mexp);
                            return null;
                        }
                    }
                    var t = target.getArrowTarget(label);
                    if (t == null)
                        t = target.getArrowTarget("next");
                    if (t == target) {
                        Logger.error("Prevented endless loop with next block after mathblock");
                        return null;
                    }
                    if (t != null) {
                        target = t;
                    } else {
                        Logger.error("Not found a block after the mathblock with exp: " + mexp + " or builtin:" + builtin);
                        return null;
                    }
                }
                default -> {
                }
            }
        }
        // Post check
        if (valcell.cell().hasArrow("next")) { // Find the post check if any
            var postCheck = valcell.cell().getArrowTarget("next");
            var exp = postCheck.getParam("expression", "");
            postCheck.addParam("expression", normalizeExpression(exp, label));
            var block = TaskParser.createBlock(postCheck, new TaskParser.TaskTools(tools.eventLoop(), origins, tools.rtvals, new HashMap<>(), idRef), valcell.val().id() + "_post");
            valcell.val().setPostCheck((ConditionBlock) block, true);
        }
        // At this post the precondition should be taken care off... now it's the difficult stuff like symbiote etc
        if (valcell.cell().hasArrow("derive")) {
            // val becomes a symbiote...
            if (valcell.val() instanceof RealVal) {
                RealValSymbiote symb = new RealValSymbiote(0, (RealVal) valcell.val()); // First create the symbiote
                tools.rtvals().addRealVal(symb); // And add it to the collection
                try {
                    processDerives(valcell, label, tools, origins, idRef).forEach(val -> symb.addUnderling((RealVal) val)); // Then process because it might be referred to
                } catch (ClassCastException e) {
                    Logger.error("Tried to add an integer to a realval symbiote...? Symbiote:" + symb.id());
                }
                valcell = new ValCell(symb, valcell.cell()); // Overwrite the cell?
            } else if (valcell.val() instanceof IntegerVal iv) {
                IntegerValSymbiote symb = new IntegerValSymbiote(0, iv, new NumericVal[1]);
                tools.rtvals().addIntegerVal(symb);
                processDerives(valcell, label, tools, origins, idRef).forEach(symb::addUnderling);
                valcell = new ValCell(symb, valcell.cell());
            }
        } else { // Just find the next block
            if (valcell.val() instanceof RealVal rv) {
                tools.rtvals().addRealVal(rv);
            } else if (valcell.val() instanceof IntegerVal iv) {
                tools.rtvals().addIntegerVal(iv);
            }
        }
        return valcell;
    }

    private static ArrayList<NumericVal> processDerives(ValCell valcell, String label, ParserTools tools, ArrayList<OriginBlock> origins, HashMap<String, String> idRef) {
        StringBuilder derive = new StringBuilder("derive");
        var target = valcell.cell().getArrowTarget(derive.toString());
        ArrayList<NumericVal> unders = new ArrayList<>();
        while (target != null) {
            var result = buildValCel(target, label, tools, origins, idRef);
            if (result == null) {
                Logger.error("Failed to parse derive of type " + valcell.cell().type);
                return unders;
            }
            unders.add(result.val());
            derive.append("+");
            target = valcell.cell().getArrowTarget(derive.toString());
        }
        return unders;
    }

    private static Drawio.DrawioCell getNext(Drawio.DrawioCell current, String... labels) {
        for (var label : labels) {
            var res = current.getArrowTarget(label);
            if (res != null)
                return res;
        }
        return null;
    }
}
