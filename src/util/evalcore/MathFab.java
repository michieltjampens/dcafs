package util.evalcore;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.vals.NumericVal;
import util.data.vals.RealVal;
import util.data.vals.Rtvals;
import util.math.MathUtils;

import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;

public class MathFab {

    public static Optional<MathEvaluator> parseExpression(String expression, Rtvals rtvals, NumericVal[] valRefs) {
        return build(expression, rtvals, valRefs);
    }

    public static Optional<MathEvaluator> parseExpression(String expression, Rtvals rtvals) {
        return build(expression, rtvals, null);
    }

    /**
     * Parse the formula to functions
     *
     * @param expression The mathematical expression to parse
     * @return This object or null if failed
     */
    private static Optional<MathEvaluator> build(String expression, Rtvals rtvals, NumericVal[] oldRefs) {

        var mathEval = new MathEvaluator(expression);

        // Fix stuff like i0++, i0*=3, remove spaces ,sin/cos/tan to placeholders
        expression = normalizeExpression(expression);

        // Split the left and right part of the equation
        var resultTarget = "";
        if (expression.contains("=")) {
            var split = expression.split("=");
            resultTarget = split[0];
            expression = split[1];
        }

        expression = ParseTools.checkBrackets(expression, '(', ')', true);
        if (expression.isEmpty())
            return Optional.empty();

        // Extract all the 'i*' from the expression and replace to reflect position in refLookup
        var refLookup = new ArrayList<Integer>();
        var is = ParseTools.extractIreferences(expression); // Searches and returns a sorted list of distinct i indexes
        if (is.length > 0) {
            mathEval.setHighestI(is[is.length - 1]); // Remember the highest I encountered, used for a simple check
            for (var i : is) {
                expression = expression.replace("i" + i, "r" + refLookup.size());
                refLookup.add(i); // This needs to be second to use the refLookup.size() trick for indexing
            }
        }
        // Handle the temporary variables, and create the starter array for rtvals
        var refs = handleTempVariables(expression, oldRefs, refLookup);

        // Find references to realtime values and replace with indexes
        mathEval.setResultIndex(determineResultIndex(resultTarget, rtvals, refs)); // returns -1 if resultTarget is empty
        expression = ParseTools.replaceRealtimeValues(expression, refs, rtvals, refLookup);
        if (expression.isEmpty()) // Errors are logged in the method, so can just quit.
            return Optional.empty();

        // At this point the expression should be ready for actual parsing and refs are final
        mathEval.setNormalized(expression);
        mathEval.setRefs(refs.toArray(NumericVal[]::new));
        mathEval.setRefLookup(refLookup.toArray(Integer[]::new));

        // Next go through the brackets from left to right (inner)
        var subExpressions = processBrackets(expression);
        if (subExpressions.isEmpty())
            return Optional.empty();
        mathEval.setIntermediateSteps(subExpressions.size()); // The amount of ops is known, so use it to size arrays

        // Purely for debugging purposes
        var join = new StringJoiner("\r\n");

        for (int x = 0; x < subExpressions.size(); x++) {
            var sub = subExpressions.get(x);
            join.add("o" + x + "=" + sub[0] + sub[2] + sub[1]);
        }
        mathEval.setParseResult(join.toString());

        for (int a = 0; a < subExpressions.size(); a++) {
            var sub = subExpressions.get(a);
            var op = ParseTools.decodeBigDecimalsOp(sub[0], sub[1], sub[2], refLookup.size());
            if (op == null)
                return Optional.empty();
            mathEval.addOp(a, op);
        }

        return Optional.of(mathEval);
    }

    private static String normalizeExpression(String expression) {
        expression = normalizeDualOperand(expression); // Replace stuff like i0++ with i0=i0+1
        // words like sin,cos etc messes up the processing, replace with references
        expression = replaceGeometricTerms(expression);
        expression = expression.replace(" ", ""); // Remove any spaces
        expression = handleCompoundAssignment(expression); // Replace stuff like i0*=5 with i0=i0*5
        return expression;
    }

    private static String normalizeDualOperand(String expression) {
        // Support ++ and --
        return expression.replace("++", "+=1")
                .replace("--", "-=1")
                .replace(" ", ""); //remove spaces
    }

    private static String replaceGeometricTerms(String formula) {
        // Replace to enable geometric stuff?
        formula = formula.replace("cos(", "1°(");
        formula = formula.replace("cosd(", "1°(");
        formula = formula.replace("cosr(", "2°(");

        formula = formula.replace("sin(", "3°(");
        formula = formula.replace("sind(", "3°(");
        formula = formula.replace("sinr(", "4°(");
        formula = formula.replace("abs(", "5°(");

        // Remove unneeded brackets?
        int dot = formula.indexOf("°(");
        String cleanup;
        while (dot != -1) {
            cleanup = formula.substring(dot + 2); // Get the formula without found °(
            int close = cleanup.indexOf(")"); // find a closing bracket
            String content = cleanup.substring(0, close);// Get te content of the bracket
            if (NumberUtils.isCreatable(content) || content.matches("i\\d+")) { // If it's just a number or index
                formula = formula.replace("°(" + content + ")", "°" + content);
            }
            dot = cleanup.indexOf("°(");
        }
        return formula;
    }

    private static String handleCompoundAssignment(String exp) {
        if (!exp.contains("="))
            return exp;
        // The expression might be a simple i0 *= 2, so replace such with i0=i0*2 because of the way it's processed
        // A way to know this is the case, is that normally the summed length of the split items is one lower than
        // the length of the original expression (because the = ), if not that means an operand was in front of '='
        var split = exp.split("[+-/*^]?=");
        int lengthAfterSplit = split[0].length() + split[1].length();
        if (lengthAfterSplit + 1 != exp.length()) { // Support += -= *= and /= fe. i0+=1
            String[] spl = exp.split("="); //[0]:i0+ [1]:1
            split[1] = spl[0] + split[1]; // split[1]=i0+1
        }
        return split[0] + "=" + split[1];
    }

    /**
     * Check the left part of the equation if exists and determine if it's referring to input data, a val or temp
     *
     * @param equation The expression to check
     * @param rtvals   The realtimevalues already existing
     * @param refs     The subset of the realtimevalues already used
     * @return The index or -1 if the expression is empty or something wrong with it
     */
    private static int determineResultIndex(String equation, Rtvals rtvals, ArrayList<NumericVal> refs) {
        if (equation.isEmpty()) // Nothing to work with
            return -1;

        // References to received data
        if (equation.startsWith("i"))
            return NumberUtils.toInt(equation.substring(1));

        // References to rtvals
        if (equation.contains("{")) {
            equation = equation.substring(1, equation.length() - 1);
            var valOpt = rtvals.getBaseVal(equation);
            if (valOpt.isEmpty()) {
                Logger.error("No such val yet: " + equation);
                return -1;
            }
            var val = valOpt.get();
            if (val instanceof NumericVal nv) {
                int index = refs.indexOf(val);
                if (index != -1) {
                    return index + 100;
                } else {
                    refs.add(nv);
                    return refs.size() - 1 + 100; // -1 to get index and +100 for the offset
                }
            } else {
                Logger.error(equation + " not a numeric value, can't use it in math");
                return -1;
            }
        }
        // References to temp data
        if (equation.startsWith("t")) {
            var temp = RealVal.newVal("dcafs", equation);
            refs.add(temp);
            return refs.size() - 1 + 200;
        }
        return -1;
    }

    private static ArrayList<String[]> processBrackets(String expression) {
        var subExpr = new ArrayList<String[]>(); // List to contain all the sub-formulas

        while (expression.contains("(")) { // Look for an opening bracket
            int close = expression.indexOf(")"); // Find the first closing bracket
            int open = expression.substring(0, close).lastIndexOf("(");
            // No need to check if open isn't -1 because brackets were checked in earlier step
            String part = expression.substring(open + 1, close); // get the part between the brackets
            String piece = expression.substring(open, close + 1); // includes the brackets

            var res = MathUtils.splitAndProcessMathExpression(part, subExpr.size() - 1, false);
            if (res.isEmpty()) {
                Logger.error("Failed to build because of issues during " + part);
                return subExpr;
            } else if (res.size() == 1 && res.get(0)[1].equals("0") && res.get(0)[2].equals("+")) { // Check if it's just simplification
                expression = expression.replace(piece, res.get(0)[0]); // if so, directly replace it
            } else {
                subExpr.addAll(res);    // split that part in the sub-formulas
                // replace the sub part in the original formula with a reference to the last sub-formula
                expression = expression.replace(piece, "o" + (subExpr.size() - 1));
            }
        }
        return subExpr;
    }

    private static ArrayList<NumericVal> handleTempVariables(String expression, NumericVal[] oldRefs, ArrayList<Integer> refLookup) {
        // Add temporary references of linked evals if any
        var refs = new ArrayList<NumericVal>();
        if (oldRefs != null) {
            for (var old : oldRefs) {
                if (old.id().startsWith("dcafs_"))
                    refs.add(old);
            }
        }
        var ts = ParseTools.extractTreferences(expression);
        for (var t : ts) {
            expression = expression.replace("t" + t, "r" + refLookup.size());

            var found = false;
            for (int a = 0; a < refs.size(); a++) {
                if (refs.get(a).id().equals("dcafs_t" + t)) {
                    refLookup.add(200 + a); // Match found, so refer to it
                    found = true;
                    break;
                }
            }
            if (!found) {
                refLookup.add(200 + refs.size()); // Match found, so refer to it
                refs.add(RealVal.newVal("dcafs", "t" + t));
            }
        }
        return refs;
    }
}