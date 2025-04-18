package util.tasks;

import org.tinylog.Logger;
import util.data.NumericVal;
import util.data.RealtimeValues;
import util.data.ValTools;
import util.math.MathUtils;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class LogicFab {
    public static Optional<ConditionBlock> updateConditionBlock(ConditionBlock block, RealtimeValues rtvals, ArrayList<NumericVal> sharedMem) {
        if (fillInBlock(block, rtvals, sharedMem))
            return Optional.of(block);
        return Optional.empty();
    }

    public static Optional<ConditionBlock> buildConditionBlock(String condition, RealtimeValues rtvals, ArrayList<NumericVal> sharedMem) {

        if (condition.isEmpty())
            return Optional.empty();

        var block = new ConditionBlock(condition);
        if (fillInBlock(block, rtvals, sharedMem))
            return Optional.of(block);
        return Optional.empty();
    }

    private static boolean fillInBlock(ConditionBlock block, RealtimeValues rtvals, ArrayList<NumericVal> sharedMem) {
        block.sharedMem = Objects.requireNonNullElseGet(sharedMem, ArrayList::new);

        var exp = cleanExpression(block.ori);
        // Figure out the realtime stuff and populate the sharedMemory with it
        exp = populateSharedMemory(exp, rtvals, sharedMem);
        if (exp.isEmpty()) {
            return false;
        }

        // Figure out the brackets?
        var subOp = splitInSubExpressions(exp);
        if (subOp.isEmpty()) {
            return false;
        }
        var subFormulas = subOp.get();
        if (subFormulas.isEmpty())
            return false;

        block.resultIndex = subFormulas.size() - 1;
        ;

        // Convert the sub formulas to functions
        subFormulas.forEach(sub -> {
            sub = sub.startsWith("!") ? sub.substring(1) + "==0" : sub;
            var parts = MathUtils.extractParts(sub);
            if (parts != null) {
                try {
                    block.steps.add(MathUtils.decodeDoublesOp(parts.get(0), parts.size() == 3 ? parts.get(2) : "", parts.get(1), subFormulas.size()));
                } catch (IndexOutOfBoundsException e) {
                    Logger.error("CheckBox error during steps adding: " + e.getMessage());
                }
            }
        });
        return true;
    }

    private static String cleanExpression(String ori) {
        if (ori.isEmpty()) {
            Logger.error("No expression to process.");
            return "";
        }
        // Fix the flag/issue negation and diff?
        var exp = fixFlagAndIssueNegation(ori);

        // Replace the words used for the comparisons with the math equivalent
        // e.g. below becomes < and so on
        return MathUtils.mapExpressionToSymbols(exp); // rewrite to math symbols
    }

    private static String fixFlagAndIssueNegation(String ori) {
        // Find comparisons optionally surrounded with curly brackets
        // Legacy or alternative notation for a flag is flag:value without a comparison (e.g. ==1)
        Pattern words = Pattern.compile("\\{?[!a-zA-Z:_]+[0-9]*[a-zA-Z]+\\d*}?");

        var foundComparisons = words.matcher(ori).results().map(MatchResult::group).toList();
        String expression = ori;
        for (var compare : foundComparisons) {
            // Fixes flag:, !flag and issue:/!issue:
            if (compare.contains("flag:") || compare.startsWith("issue:")) {
                expression = expandFlagOrIssueState(expression, compare);
            } else if (compare.toLowerCase().startsWith("d:") || compare.toLowerCase().startsWith("f:")) { // not surrounded with brackets
                expression = expression.replace(compare, "{" + compare + "}"); // So add them
            }
        }
        return expression;
    }

    private static String expandFlagOrIssueState(String exp, String compare) {
        String name = compare.split(":")[1];
        var offset = compare.startsWith("!") ? 1 : 0;
        String type = compare.substring(offset, 1 + offset);
        return exp.replace(compare, "{" + type + ":" + name + "}==" + (compare.startsWith("!") ? "0" : "1"));
    }

    private static String populateSharedMemory(String exp, RealtimeValues rtvals, ArrayList<NumericVal> sharedMem) {
        if (exp.isEmpty()) {
            Logger.error("No expression to process.");
            return "";
        }
        if (rtvals != null) {
            exp = ValTools.buildNumericalMem(rtvals, exp, sharedMem, 0);
            if (exp.matches("i0"))
                exp += "==1";
        } else {
            Logger.warn("No rtvals, skipping numerical mem");
        }
        if (exp.isEmpty()) {
            Logger.error("Couldn't process " + exp + ", vals missing");
            return "";
        }
        return exp;
    }

    private static Optional<ArrayList<String>> splitInSubExpressions(String exp) {
        exp = MathUtils.checkBrackets(exp, '(', ')', true);
        if (exp.isEmpty()) {
            return Optional.empty();
        }

        var subFormulas = new ArrayList<String>(); // List to contain all the sub-formulas
        // Fill the list by going through the brackets from left to right (inner)
        while (exp.contains("(")) { // Look for an opening bracket
            int close = exp.indexOf(")"); // Find the first closing bracket
            // Find the index of the matching opening bracket by looking for the last occurrence before the closing one
            int open = exp.substring(0, close - 1).lastIndexOf("(");
            String part = exp.substring(open + 1, close); // get the part between the brackets

            exp = exp.replace(exp.substring(open, close + 1), "$$"); // Replace the sub with the placeholder

            // Split part on && and ||
            var and_ors = part.split("[&|!]{2}", 0);
            for (var and_or : and_ors) {
                var comps = and_or.split("[><=!]=?"); // Split on the compare ops
                for (var c : comps) { // Go through the elements
                    if (!(c.matches("[io]+\\d+") || c.matches("\\d*[.]?\\d*")) && !c.isEmpty()) {
                        // If NOT ix,ox or a number
                        int index = subFormulas.indexOf(c);
                        if (index == -1) {
                            subFormulas.add(c);    // split that part in the sub-formulas
                            index = subFormulas.size() - 1;
                        }
                        and_or = and_or.replace(c, "o" + index);
                        part = part.replace(c, "o" + index);
                    }
                }
                if (!(and_or.matches("[io]+\\d+") || and_or.matches("\\d*"))) {
                    subFormulas.add(and_or);
                    part = part.replace(and_or, "o" + (subFormulas.size() - 1));
                }
            }
            part = part.replace("&&", "*")
                    .replace("||", "+");

            if (part.contains("!|"))
                part = "(" + part.replace("!|", "+") + ")%2";

            exp = exp.replace("$$", part);
        }
        if (exp.length() != 2)
            subFormulas.add(exp);
        return Optional.of(subFormulas);
    }
}
