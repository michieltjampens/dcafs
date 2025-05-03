package util.drawio;

import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.data.procs.MathEvalForVal;
import util.data.vals.*;
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

    public static ArrayList<ConditionBlock> parseDrawIoRtvals(Path file, EventLoopGroup eventLoop, Rtvals rtvals) {
        if (!file.getFileName().toString().endsWith(".drawio")) {
            Logger.error("This is not a drawio file: " + file);
            return new ArrayList<>();
        }
        var tools = new ParserTools(eventLoop, rtvals, new HashMap<>(), new ArrayList<>());
        var cells = Drawio.parseFile(file);
        parseRtvals(file, cells, tools);
        return null;
    }

    public static ConditionBlock parseRtvals(Path file, HashMap<String, Drawio.DrawioCell> cells, ParserTools tools) {
        // First create all the origins and then populate with the rest because controlblocks need a full list during set up
        ArrayList<Drawio.DrawioCell> starts = new ArrayList<>();
        for (var entry : cells.entrySet()) {
            var cell = entry.getValue();
            switch (cell.type) {
                case "realval":
                    tools.vals.add(new ValCell(doRealVal(cell, tools), cell));
                    break;
                case "integerval":
                    tools.vals.add(new ValCell(doIntegerVal(cell, tools), cell));
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

    public static RealVal doRealVal(Drawio.DrawioCell cell, ParserTools tools) {
        var idArray = getId(cell);
        if (idArray.length == 0)
            return null;
        String group = idArray[0], name = idArray[1];
        if (tools.rtvals().hasReal(group + "_" + name))
            return tools.rtvals().getRealVal(group + "_" + name).get(); //get is fine because of earlier has

        var rv = RealVal.newVal(group, name);
        rv.unit(cell.getParam("unit", ""));
        return rv;
    }

    public static IntegerVal doIntegerVal(Drawio.DrawioCell cell, ParserTools tools) {

        var idArray = getId(cell);
        if (idArray.length == 0)
            return null;
        String group = idArray[0], name = idArray[1];

        if (tools.rtvals().hasInteger(group + "_" + name))
            return tools.rtvals().getIntegerVal(group + "_" + name).get(); //get is fine because of earlier has

        var rv = IntegerVal.newVal(group, name);
        rv.unit(cell.getParam("unit", ""));
        return rv;
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

            ValCell valcell = null;
            ConditionBlock pre = null;
            MathEvalForVal math = null;

            // Do everything up to the rtval
            while (valcell == null) {
                switch (target.type) {
                    case "realval", "integerval" -> {
                        for (var vc : tools.vals()) {
                            if (vc.cell().equals(target)) {
                                valcell = vc;
                                if (pre != null) {
                                    valcell.val().setPreCheck(pre);
                                }
                                if (math != null) {
                                    valcell.val().setMath(math);
                                }
                                break;
                            }
                        }
                    }
                    case "conditionblock" -> {
                        var exp = target.getParam("expression", "");
                        exp = exp.replace(label, "i0"); // alter so it get i0
                        exp = exp.replace("old", "i1"); // alter so it get i1 instead of old
                        target.addParam("expression", exp);
                        pre = (ConditionBlock) TaskParser.createBlock(target, new TaskParser.TaskTools(tools.eventLoop(), origins, tools.rtvals, new HashMap<>(), idRef), "id");

                        // that's precheck?
                        if (target.hasArrow(label)) { // Goes to val
                            target = target.getArrowTarget(label);
                        }
                    }
                    case "math" -> {
                        var mexp = target.getParam("expression", "");
                    }
                    default -> {
                    }
                }
            }
            // Post check
            if (valcell.cell().hasArrow("next")) { // Find the post check if any
                var post = valcell.cell().getArrowTarget("next");
                var exp = post.getParam("expression", "");
                exp = exp.replace(label, "i0"); // alter so it get i0
                exp = exp.replace("new", "i0");
                exp = exp.replace("old", "i1"); // alter so it get i1 instead of old
                exp = exp.replace("math", "i2"); // alter so it get i2 instead of math
                post.addParam("expression", exp);
                var block = TaskParser.createBlock(post, new TaskParser.TaskTools(tools.eventLoop(), origins, tools.rtvals, new HashMap<>(), idRef), valcell.val().id() + "_post");
                valcell.val().setPostCheck((ConditionBlock) block);
            }
            // At this post the precondition should be taken care off... now it's the difficult stuff like symbiote etc
            if (valcell.cell().hasArrow("derive")) {
                // val becomes a symbiote...
                // And now it's just recursion?
            } // Just find the next block

        }
    }
}
