package util.evalcore;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.NumericVal;
import util.data.RealVal;
import util.data.RealtimeValues;
import util.math.MathUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

public class MathOpFab {

    ArrayList<Function<BigDecimal[], BigDecimal>> steps = new ArrayList<>();
    Integer[] referenced;
    NumericVal[] valRefs;
    int highestI = -1;
    boolean debug = true;

    String ori = "";
    boolean valid;
    int resultIndex = -1;

    public MathOpFab(String expression, RealtimeValues rtvals, List<NumericVal> valRefs) {
        ori = expression;
        valid = build(expression, rtvals, valRefs) != null;
    }

    public MathOpFab(String expression) {
        ori = expression;
        valid = build(expression) != null;
    }

    public static MathOpFab withExpression(String expression, RealtimeValues rtvals, NumericVal[] valRefs) {
        var refs = Arrays.stream(Objects.requireNonNullElse(valRefs, new NumericVal[0])).toList();
        return new MathOpFab(expression, rtvals, refs);
    }

    public static MathOpFab withExpression(String formula, RealtimeValues rtvals) {
        return new MathOpFab(formula, rtvals, new ArrayList<>());
    }

    public static MathOpFab withExpression(String expression) {
        return new MathOpFab(expression);
    }

    /**
     * Enable or disable extra debug information
     *
     * @param debug New value for debug
     */
    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public String getOri() {
        return ori;
    }

    /**
     * Check if this MathFab is valid or failed the formula parsing
     *
     * @return True if valid
     */
    public boolean isValid() {
        return valid;
    }

    /**
     * Parse the formula to functions
     *
     * @param expression The mathematical expression to parse
     * @return This object or null if failed
     */
    private MathOpFab build(String expression, RealtimeValues rtvals, List<NumericVal> oldRefs) {
        if (debug)
            Logger.info("MathFab building for " + expression);
        // Fix stuff like i0++, i0*=3, remove spaces ,sin/cos/tan to placeholders
        expression = normalizeExpression(expression);

        var resultTarget = "";
        if (expression.contains("=")) {
            var split = expression.split("=");
            resultTarget = split[0];
            expression = split[1];
        }

        expression = ParseTools.checkBrackets(expression, '(', ')', true);
        if (expression.isEmpty())
            return null;

        // Extract all the 'i*' from the expression
        var is = ParseTools.extractIreferences(expression);
        highestI = is[is.length - 1];

        // Rewrite the i's to reflect position in inputs
        var inputs = new ArrayList<Integer>();
        for (var i : is) {
            expression = expression.replace("i" + i, "r" + inputs.size());
            inputs.add(i);
        }

        if (debug)
            Logger.info("Replaced i's: " + expression);

        // Find references to realtime values and replace with indexes
        var refs = new ArrayList<>(oldRefs);
        if (!resultTarget.isEmpty()) {
            resultIndex = determineResultIndex(resultTarget, rtvals, refs);
        }
        expression = ParseTools.replaceRealtimeValues(expression, refs, rtvals, inputs);
        valRefs = refs.toArray(NumericVal[]::new);
        referenced = inputs.toArray(Integer[]::new);

        if (expression.isEmpty())
            return null;

        // Next go through the brackets from left to right (inner)
        return parseToMathOps(expression);
    }

    private MathOpFab build(String expression) {
        if (debug)
            Logger.info("MathOpFab building for " + expression);

        expression = normalizeExpression(expression);
        expression = ParseTools.checkBrackets(expression, '(', ')', true);

        if (expression.isEmpty())
            return null;

        var is = ParseTools.extractIreferences(expression);
        highestI = is[is.length - 1];

        // Rewrite the i's to reflect position in inputs
        var inputs = new ArrayList<Integer>();
        for (var i : is) {
            expression = expression.replace("i" + i, "i" + inputs.size());
            inputs.add(i);
        }
        referenced = inputs.toArray(Integer[]::new);

        if (debug)
            Logger.info("Replaced i's: " + expression);

        if (expression.isEmpty())
            return null;

        // Next go through the brackets from left to right (inner)
        return parseToMathOps(expression);
    }
    public static String normalizeExpression(String expression) {
        expression = normalizeDualOperand(expression); // Replace stuff like i0++ with i0=i0+1
        // words like sin,cos etc messes up the processing, replace with references
        expression = replaceGeometricTerms(expression);
        expression = expression.replace(" ", ""); // Remove any spaces
        expression = handleCompoundAssignment(expression); // Replace stuff like i0*=5 with i0=i0*5
        return expression;
    }
    public static String normalizeDualOperand(String expression) {
        // Support ++ and --
        return expression.replace("++", "+=1")
                .replace("--", "-=1")
                .replace(" ", ""); //remove spaces
    }
    public static String replaceGeometricTerms(String formula) {
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
    public static String handleCompoundAssignment(String exp) {
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
     * @return The index
     */
    private static int determineResultIndex(String equation, RealtimeValues rtvals, ArrayList<NumericVal> refs) {

        // References to received data
        if (equation.startsWith("i"))
            return NumberUtils.toInt(equation.substring(1));

        // References to rtvals
        if (equation.contains("{")) {
            equation = equation.substring(1, equation.length() - 1);
            var valOpt = rtvals.getAbstractVal(equation);
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



    private static ArrayList<String[]> processBrackets(String expression, boolean debug) {
        var subExpr = new ArrayList<String[]>(); // List to contain all the sub-formulas

        while (expression.contains("(")) { // Look for an opening bracket
            int close = expression.indexOf(")"); // Find the first closing bracket
            int open = expression.substring(0, close).lastIndexOf("(");
            // No need to check if open isn't -1 because brackets were checked in earlier step
            String part = expression.substring(open + 1, close); // get the part between the brackets
            String piece = expression.substring(open, close + 1); // includes the brackets

            var res = MathUtils.splitAndProcessMathExpression(part, subExpr.size() - 1, debug);
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

    private MathOpFab parseToMathOps(String expression) {
        var subFormulas = processBrackets(expression, debug);
        if (subFormulas.isEmpty())
            return null;

        if (debug)
            subFormulas.stream().map(x -> x[0] + x[2] + x[1]).forEach(Logger::info);

        var failed = subFormulas.stream()
                .map(sub -> ParseTools.decodeBigDecimalsOp(sub[0], sub[1], sub[2], subFormulas.size()))
                .peek(x -> steps.add(x))
                .anyMatch(Objects::isNull);
        if (failed) {
            Logger.error("Failed to convert " + expression);
            steps.clear();
            return null;
        }
        return this;
    }

    public Optional<MathOperation> getMathOp() {
        if (!valid)
            return Optional.empty();

        var mop = new MathOperation(ori);
        mop.referenced = referenced;
        mop.valRefs = valRefs;
        mop.steps = steps;
        mop.highestI = highestI;
        mop.scratchpad = new BigDecimal[referenced.length + steps.size()];
        mop.resultIndex = resultIndex;

        return Optional.of(mop);
    }
}