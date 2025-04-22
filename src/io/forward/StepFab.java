package io.forward;

import io.forward.steps.CmdStep;
import io.forward.steps.MathStep;
import org.tinylog.Logger;
import util.data.RealtimeValues;
import util.evalcore.MathEvaluator;
import util.evalcore.MathFab;
import util.evalcore.ParseTools;
import util.xml.XMLdigger;

import java.util.HashMap;
import java.util.Optional;

public class StepFab {

    public static Optional<MathStep> buildMathStep(XMLdigger dig, String delimiter, RealtimeValues rtvals) {
        return digMathNode(dig, rtvals, delimiter);
    }

    public static Optional<CmdStep> buildCmdStep(XMLdigger dig, String delimiter, RealtimeValues rtvals) {
        return digCmdNode(dig, rtvals, delimiter);
    }
    /* ***************************************** M A T H  ************************************************************ */

    /**
     * Read the settings for a mathForward from the given element
     *
     * @param dig The math child element
     * @return True if this was successful
     */

    public static Optional<MathStep> digMathNode(XMLdigger dig, RealtimeValues rtvals, String delimiter) {
        if (dig == null)
            return Optional.empty();

        var baseId = dig.attr("id", "");

        var suffix = dig.attr("suffix", "");
        delimiter = dig.attr("delimiter", delimiter);

        // Check for other subnodes besides 'op' those will be considered def's to reference in the op
        var defines = digForDefinedConstants(dig);

        // Find all the references to realtime values
        boolean doUpdate = false;
        MathEvaluator eval = null;
        int opCnt = 0;
        for (var op : dig.digOut("op")) {
            var id = baseId + "_" + opCnt;
            opCnt++;

            var expression = op.value("");

            // Replace the defines
            for (var define : defines.entrySet())
                expression = expression.replace(define.getKey(), define.getValue());

            Optional<MathEvaluator> meOpt;
            if (eval == null) {
                meOpt = MathFab.parseExpression(expression, rtvals);
            } else {
                var oldRefs = eval.getLastRefs();
                meOpt = MathFab.parseExpression(expression, rtvals, oldRefs);
            }
            if (meOpt.isEmpty()) {
                Logger.error("(mf) -> Failed to parse " + op.value(""));
                return Optional.empty();
            }
            var mathEval = meOpt.get();
            mathEval.setId(id);
            if (mathEval.updatesRtval()) //meaning an rtval is updated because of it
                doUpdate = true;
            mathEval.setScale(op.attr("scale", -1));
            if (eval == null) {
                eval = mathEval;
            } else {
                eval.moveIn(mathEval);
            }
        }
        var step = new MathStep(eval, suffix, delimiter);
        step.setWantsData(doUpdate);
        return Optional.of(step);
    }

    /**
     * Check the node for references to static values and odd those to the collection
     *
     * @param dig A digger pointing to the MathForward
     */
    private static HashMap<String, String> digForDefinedConstants(XMLdigger dig) {
        HashMap<String, String> defines = new HashMap<>();
        dig.peekOut("*")
                .stream().filter(ele -> !ele.getTagName().equalsIgnoreCase("op"))
                .forEach(def -> {
                    var val = def.getTextContent().replace(",", "."); // unify decimal separator
                    if (def.getTagName().equalsIgnoreCase("def")) {
                        defines.put(def.getAttribute("ref"), val); // <def ref="A1">12.5</def>
                    } else {
                        defines.put(def.getTagName(), val);             // <A1>12.5</A1>
                    }
                });
        return defines;
    }

    /* ************************************** C M D **************************************************************** */
    public static Optional<CmdStep> digCmdNode(XMLdigger dig, RealtimeValues rtvals, String delimiter) {
        // First read the basics (things common to all forwards)
        delimiter = dig.attr("delimiter", delimiter);
        var step = new CmdStep(dig.attr("id", ""), delimiter);
        if (dig.tagName("").equals("cmds")) { // meaning multiple
            dig.peekOut("cmd").forEach(cmd -> processCmd(step, rtvals, cmd.getTextContent()));
        } else {
            processCmd(step, rtvals, dig.value(""));
        }
        return Optional.of(step);
    }

    private static void processCmd(CmdStep step, RealtimeValues rtvals, String cmd) {
        var ori = cmd;

        var refs = ParseTools.extractCurlyContent(cmd, true);
        if (!refs.isEmpty()) {
            for (int a = 0; a < refs.size(); a++) {
                var val = rtvals.getAbstractVal(refs.get(a));
                if (val.isEmpty()) {
                    Logger.error("Didn't find a match for " + refs.get(a) + " as part of " + cmd);
                } else {
                    step.addRtval(val.get());
                    cmd = cmd.replace("{" + refs.get(a) + "}", "{" + a + "}");
                }
            }
        }
        // Find all the i's
        var iss = ParseTools.extractIreferences(cmd);
        step.setHighestI(iss[iss.length - 1]); // keep the highest one
        step.addCmd(ori, cmd);
    }
}
