package util.math;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.NumericVal;
import util.data.RealVal;
import util.data.RealtimeValues;
import util.tools.Tools;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

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

        expression = normalizeExpression(expression);

        var resultTarget = "";
        if (expression.contains("=")) {
            var split = expression.split("=");
            resultTarget = split[0];
            expression = split[1];
        }

        expression = MathUtils.checkBrackets(expression);
        if (expression.isEmpty())
            return null;

        // Extract all the 'i*' from the expression
        var is = determineReqInputs(expression);
        highestI = is[is.length - 1];

        // Rewrite the i's to reflect position in inputs
        var inputs = new ArrayList<Integer>();
        for (var i : is) {
            expression = expression.replace("i" + i, "i" + inputs.size());
            inputs.add(i);
        }

        if (debug)
            Logger.info("Replaced i's: " + expression);

        // Find references to realtime values and replace with indexes
        var refs = new ArrayList<>(oldRefs);
        if (!resultTarget.isEmpty()) {
            resultIndex = determineResultIndex(resultTarget, rtvals, refs);
        }
        expression = replaceRealtimeValues(expression, refs, rtvals, inputs);
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
        expression = MathUtils.checkBrackets(expression);

        if (expression.isEmpty())
            return null;

        var is = determineReqInputs(expression);
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

    private static String normalizeExpression(String expression) {
        expression = MathUtils.normalizeExpression(expression); // Replace stuff like i0++ with i0=i0+1
        // words like sin,cos etc messes up the processing, replace with references
        expression = MathUtils.replaceGeometricTerms(expression);
        expression = expression.replace(" ", ""); // Remove any spaces
        expression = MathUtils.handleCompoundAssignment(expression); // Replace stuff like i0*=5 with i0=i0*5
        return expression;
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

    private static int[] determineReqInputs(String expression) {
        return Pattern.compile("i[0-9]{1,2}")// Extract all the references
                .matcher(expression)
                .results()
                .map(MatchResult::group)
                .distinct()
                .sorted() // so the highest one is at the bottom
                .mapToInt(i -> NumberUtils.toInt(i.substring(1)))
                .toArray();
    }

    private static String replaceRealtimeValues(String expression, ArrayList<NumericVal> refs, RealtimeValues rtvals, ArrayList<Integer> inputs) {
        // Replace the temp ones if they exist
        for (var ref : refs) {
            var id = ref.id();
            if (id.startsWith("dcafs_")) {
                var it = id.substring(6);
                expression = expression.replace(it, "{" + id + "}");
            }
        }
        // Do processing as normal
        var bracketVals = Tools.parseCurlyContent(expression, true);
        int hasOld = refs.size();

        iteratevals:
        for (var val : bracketVals) {
            if (hasOld != 0) {
                for (int a = 0; a < hasOld; a++) { // No use looking beyond old elements
                    if (refs.get(a).id().equals(val)) {
                        Logger.info("Using old val:" + val);
                        inputs.add(100 + a);
                        expression = expression.replace("{" + val + "}", "i" + (inputs.size() - 1));
                        continue iteratevals;
                    }
                }
            }
            var result = rtvals.getAbstractVal(val);
            if (result.isEmpty()) {
                Logger.error("No such rtval yet: " + val);
                return "";
            } else if (result.get() instanceof NumericVal nv) {
                int size = inputs.size();
                expression = expression.replace("{" + val + "}", "i" + size);
                inputs.add(100 + refs.size());
                refs.add(nv);
            } else {
                Logger.error("Can't work with " + result.get() + " NOT a numeric val.");
                return "";
            }
        }
        return expression;
    }

    private static ArrayList<String[]> processBrackets(String expression, boolean debug) {
        var subExpr = new ArrayList<String[]>(); // List to contain all the sub-formulas

        while (expression.contains("(")) { // Look for an opening bracket
            int close = expression.indexOf(")"); // Find the first closing bracket
            int open = expression.substring(0, close).lastIndexOf("(");
            // No need to check if open isn't -1 because brackets were checked in earlier step
            String part = expression.substring(open + 1, close); // get the part between the brackets
            String piece = expression.substring(open, close + 1); // includes the brackets

            var res = MathUtils.splitAndProcessExpression(part, subExpr.size() - 1, debug);
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
                .map(sub -> MathUtils.decodeBigDecimalsOp(sub[0], sub[1], sub[2], subFormulas.size()))
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