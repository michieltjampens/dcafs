package util.evalcore;

import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import util.data.procs.MathEvalForVal;
import util.data.vals.NumericVal;
import util.tools.TimeTools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MathEvaluator extends BaseEvaluator implements MathEvalForVal, Evaluator {


    Function<BigDecimal[], BigDecimal>[] ops;
    BigDecimal[] scratchpad;
    int resultIndex = -1;
    int scale = -1;
    MathEvaluator next = null;

    public MathEvaluator(String ori) {
        this.originalExpression = ori;
    }

    public void moveIn(MathEvaluator eval) {
        if (next == null) {
            next = eval;
        } else {
            next.moveIn(eval);
        }
    }
    /* ***************************** Set up the class ************************************************* */
    void setNormalized(String normalized) {
        this.normalizedExpression = normalized;
    }

    void setParseResult(String parseResult) {
        this.parseResult = parseResult;
    }

    void setRefLookup(Integer[] refLookup) {
        this.refLookup = refLookup;
    }

    @SuppressWarnings("unchecked")
    void setIntermediateSteps(int steps) {
        ops = new Function[steps];
        scratchpad = new BigDecimal[steps + refLookup.length];
    }

    void addOp(int index, Function<BigDecimal[], BigDecimal> func) {
        ops[index] = func;
    }

    public
    void setResultIndex(int resultIndex) {
        this.resultIndex = resultIndex;
    }
    public boolean updatesRtval() {
        return resultIndex >= 100 && resultIndex < 200;
    }

    public void setScale(int scale) {
        this.scale = scale;
    }

    public NumericVal[] getLastRefs() {
        if (next == null)
            return refs;
        return next.getLastRefs();
    }

    public MathEvaluator makeValid() {
        valid = true;
        return this;
    }
    /* *********************************** Debug information ************************************************ */
    @Override
    public String getInfo(String id) {
        if (!(id.equals(this.id) || id.equals("*")))
            return next == null ? "No match found" : next.getInfo(id);

        var info = new StringJoiner("\r\n");

        info.add(TelnetCodes.TEXT_CYAN + "Debugging information for ID: " + this.id + TelnetCodes.TEXT_DEFAULT);
        info.add("Original: " + originalExpression);
        info.add("Normalized: " + normalizedExpression);
        info.add("Input data needs at least " + (highestI + 1) + " items");
        info.add("------------------------------------------");
        info.add(getCurrentScratchpad(parseResult.split("\r\n")));
        info.add("");

        if (id.equals(this.id) || next == null)
            return info.toString();
        return info + "\r\n" + next.getInfo(id);
    }

    @Override
    public String getInfo() {
        return getInfo("*");
    }
    /**
     * Provides an overview of the current state of the scratchpad register.
     * Includes details about the origin of the values stored.
     *
     * @return A string representation of the current scratchpad state.
     */
    public String getCurrentScratchpad(String[] parse) {
        var info = new StringJoiner("\r\n");
        info.add(TelnetCodes.TEXT_CYAN + "Scratchpad state (" + TimeTools.formatLongNow() + ")" + TelnetCodes.TEXT_DEFAULT);
        info.add("-Inputs & rtvals-");
        for (int a = 0; a < scratchpad.length; a++) {
            var extra = "";
            var val = scratchpad[a] == null ? "NaN" : scratchpad[a].toPlainString();
            if (a < refLookup.length) {
                if (refLookup[a] >= 100) {
                    extra = " (" + refs[refLookup[a] - 100].id() + ")";
                } else {
                    extra = " (i" + refLookup[a] + ")";
                }
                info.add("r" + a + "= " + val + extra);
            } else {
                if (a - refLookup.length == 0)
                    info.add("").add("-Intermediate steps-");
                info.add(parse[a - refLookup.length] + "=" + val);
            }
        }
        return info.toString();
    }
    /* *********************************** Do evaluation ************************************************ */

    /**
     * Fills the scratchpad with data from the inputs and rtvals according to the lookup table.
     *
     * @param inputs The inputs received for evaluation.
     * @return True if the scratchpad was successfully filled, false otherwise.
     */
    private boolean buildScratchpad(BigDecimal[] inputs) {
        if (highestI >= inputs.length) {
            var ins = Arrays.stream(inputs).map(BigDecimal::toPlainString).collect(Collectors.joining(","));
            Logger.error(id + " (me) -> Not enough data in inputs (need " + (highestI + 1) + "), aborting. -> " + ins);
            return false;
        }
        for (int a = 0; a < refLookup.length; a++) {
            var r = refLookup[a];

            BigDecimal val;
            if (r < 100 && r < inputs.length) { // second check redundant because highestI earlier?
                val = inputs[r];
            } else if (refs != null && r - 100 < refs.length ) {
                val = refs[r - 100].asBigDecimal();
            } else {
                Logger.error(id + " (me) -> Scratchpad couldn't be filled for index " + a + " from r" + r + " due to out of bounds");
                return false;
            }
            if (val == null) {
                Logger.error(id + " (me) -> Scratchpad entry at " + a + " coming from r" + r + " is (still) null.");
                return false;
            }
            scratchpad[a] = val;
        }
        return true;
    }

    private Optional<BigDecimal> solve() {
        try {
            for (int a = 0; a < ops.length; a++)
                scratchpad[a + refLookup.length] = ops[a].apply(scratchpad);
            return Optional.of(scratchpad[scratchpad.length - 1]);
        } catch (NullPointerException np) {
            Logger.error(id + " (me) -> Nullpointer while evaluating: " + originalExpression, np);
        } catch (ArithmeticException ae) {
            Logger.error(id + " (me) -> ArithmeticException:" + ae.getMessage());
        }
        return Optional.empty();
    }

    public Optional<BigDecimal> eval(BigDecimal... bds) {
        if (badInputCount(bds.length, ""))
            return Optional.empty();

        if (!buildScratchpad(bds))
            return Optional.empty();

        var res = solve().map(bd -> applyResult(bds, bd));
        if (next != null)
            next.eval(bds);
        return res;
        //return solve().map(bd -> applyResult(bds, bd));
    }

    private BigDecimal applyResult(BigDecimal[] bds, BigDecimal result) {
        if (resultIndex == -1) // Feature not used, just return the result
            return result;

        if (scale != -1)
            result = result.setScale(scale, RoundingMode.HALF_UP);
        if (resultIndex < 100 && resultIndex < bds.length) { // Either referring to the input data
            bds[resultIndex] = result;
        } else if (resultIndex % 100 < refs.length) { // Or a val ref, either permanent (1xx) or temp (2xx)
            var index = resultIndex % 100;
            refs[index].update(result.doubleValue());
        } else {
            return null;
        }
        return result;
    }

    /* ************************* Alternative variants of eval for specific use cases ****************************** */

    /**
     * Applies an array of doubles as the input for the expression and returns the result.
     *
     * @param inputs The inputs to calculate with
     * @return The result if successful or NaN if something went wrong
     */
    public boolean eval(ArrayList<Double> inputs) {
        if (badInputCount(inputs.size(), Stream.of(inputs).map(String::valueOf).collect(Collectors.joining(","))))
            return false;

        var bds = new BigDecimal[inputs.size()];
        for (Integer ref : refLookup) {
            if (ref < 100) { // meaning from input and don't overwrite
                try {
                    bds[ref] = BigDecimal.valueOf(inputs.get(ref));
                } catch (NumberFormatException e) {
                    Logger.error(e);
                }
            } else {
                break;
            }
        }
        var res = eval(bds).map(BigDecimal::doubleValue).orElse(Double.NaN);
        if (!Double.isNaN(res) && resultIndex > 0 && resultIndex < 100)
            inputs.set(resultIndex, res);
        return !Double.isNaN(res);
    }

    /**
     * Applies an array of doubles as the input for the expression and returns the result.
     *
     * @return The result if successful or NaN if something went wrong
     */
    public double eval(double d0, double d1, double d2) {
        if (highestI > 1)
            return Double.NaN;

        var bd0 = Double.isNaN(d0) ? null : BigDecimal.valueOf(d0);
        var bd1 = Double.isNaN(d1) ? null : BigDecimal.valueOf(d1);
        var bd2 = Double.isNaN(d2) ? null : BigDecimal.valueOf(d2);

        var bds = new BigDecimal[]{bd0, bd1, bd2};
        return eval(bds).map(bd -> {
            if (scale != -1)
                bd = bd.setScale(scale, RoundingMode.HALF_UP);
            return bd.doubleValue();
        }).orElse(Double.NaN);
    }

    @Override
    public boolean logicEval(double... value) {

        var bds = Arrays.stream(value).mapToObj(BigDecimal::valueOf).toArray(BigDecimal[]::new);
        var result = eval(bds);
        return result.map(bd -> bd.compareTo(BigDecimal.ONE) == 0).orElse(false);
    }
    @Override
    public int eval(int i0, int i1, int i2) {
        if (highestI > 1)
            return Integer.MAX_VALUE;

        var bds = new BigDecimal[]{BigDecimal.valueOf(i0), BigDecimal.valueOf(i1), BigDecimal.valueOf(i2)};
        return eval(bds).map(BigDecimal::intValue).orElse(Integer.MAX_VALUE);
    }

    public BigDecimal[] prepareBdArray(BigDecimal[] bds, String data, String delimiter) {
        var inputs = data.split(delimiter);

        if (badInputCount(inputs.length, data))
            return new BigDecimal[0];

        // Insert the inputs that are actually used
        if (bds == null)
            bds = new BigDecimal[inputs.length];

        for (Integer ref : refLookup) {
            if (ref < 100 && bds[ref] == null) { // meaning from input and don't overwrite
                try {
                    bds[ref] = new BigDecimal(inputs[ref]);
                } catch (NumberFormatException e) {
                    Logger.error(e);
                }
            }
        }
        if (next != null)
            next.prepareBdArray(bds, inputs);
        return bds;
    }

    private void prepareBdArray(BigDecimal[] bds, String[] inputs) {
        for (Integer ref : refLookup) {
            if (ref < 100 && bds[ref] == null) { // meaning from input and don't overwrite
                try {
                    bds[ref] = new BigDecimal(inputs[ref]);
                } catch (NumberFormatException e) {
                    Logger.error(e);
                }
            }
        }
        if (next != null)
            next.prepareBdArray(bds, inputs);
    }

    @Override
    public String getOriExpr() {
        return super.getOriginalExpression();
    }

}
