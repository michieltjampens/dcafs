package util.evalcore;

import org.tinylog.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MathEvaluator extends BaseEvaluator {

    private record MathOperation(Function<BigDecimal[], BigDecimal> func, BigDecimal last) {
    }

    MathOperation[] ops;
    BigDecimal[] scratchpad;
    int resultIndex = -1;
    int scale = -1;

    public MathEvaluator(String ori) {
        this.originalExpression = ori;
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

    void setIntermediateSteps(int steps) {
        ops = new MathOperation[steps];
        scratchpad = new BigDecimal[ops.length + refLookup.length];
    }

    void addOp(int index, Function<BigDecimal[], BigDecimal> func) {
        ops[index] = new MathOperation(func, BigDecimal.ZERO);
    }

    void setResultIndex(int resultIndex) {
        this.resultIndex = resultIndex;
    }


    public boolean updatesRtval() {
        return resultIndex >= 100 && resultIndex < 200;
    }

    public void setScale(int scale) {
        this.scale = scale;
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
            Logger.error("Not enough data in inputs (need " + (highestI + 1) + "), aborting. -> " + ins);
            return false;
        }
        for (int a = 0; a < refLookup.length; a++) {
            var r = refLookup[a];

            BigDecimal val = null;
            if (r < 100 && r < inputs.length - 1) {
                val = inputs[r];
            } else if (refs != null && r - 100 < refs.length - 1) {
                val = refs[r - 100].toBigDecimal();
            } else {
                Logger.error("Scratchpad couldn't be filled for index " + a + " from r" + r + " due to out of bounds");
                return false;
            }
            if (val == null) {
                Logger.error("Scratchpad entry at " + a + " coming from r" + r + " is (still) null.");
                return false;
            }
            scratchpad[a] = val;
        }
        return true;
    }

    private Optional<BigDecimal> solve() {
        for (int a = 0; a < ops.length; a++)
            scratchpad[a + refLookup.length] = ops[a].func().apply(scratchpad);
        return Optional.of(scratchpad[scratchpad.length - 1]);
    }

    public Optional<BigDecimal> eval(BigDecimal... bds) {
        if (bds.length < highestI) {
            Logger.error("Not enough elements in input data, need " + (1 + highestI) + " got " + bds.length);
            return Optional.empty();
        }
        if (!buildScratchpad(bds))
            return Optional.empty();

        return solve().map(bd -> applyResult(bds, bd));
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
            refs[index].updateValue(result.doubleValue());
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
        if (inputs.size() < highestI) {
            Logger.error("Not enough elements in input data, need " + (1 + highestI) + " got " + inputs.size());
            return false;
        }
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

    public BigDecimal[] updateBdArray(BigDecimal[] bds, String data, String delimiter) {
        var inputs = data.split(delimiter);
        if (inputs.length < highestI) {
            Logger.error("Not enough elements in input data, need " + (1 + highestI) + " got " + inputs.length);
            return new BigDecimal[0];
        }
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
        return bds;
    }
}
