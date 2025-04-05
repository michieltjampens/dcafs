package util.math;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.NumericVal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.function.Function;

public class MathOperation {
    Integer[] referenced;
    NumericVal[] valRefs;
    ArrayList<Function<BigDecimal[], BigDecimal>> steps = new ArrayList<>();
    String expression;
    int highestI = -1;
    BigDecimal[] scratchpad;
    int resultIndex = -1; // if it's an equation and not an expression

    public MathOperation(String formula) {
        this.expression = formula;
    }

    public int getResultIndex() {
        return resultIndex;
    }

    public NumericVal[] getValRefs() {
        return valRefs;
    }

    public String getExpression() {
        return expression;
    }

    public BigDecimal[] solveRaw(String data, String delimiter) {
        return workWithString(data, delimiter, null);
    }

    public void continueRaw(String data, String delimiter, BigDecimal[] bds) {
        workWithString(data, delimiter, bds);
    }

    public BigDecimal[] workWithString(String data, String delimiter, BigDecimal[] bds) {
        var inputs = data.split(delimiter);
        if (inputs.length < highestI) {
            Logger.error("Not enough items in received data");
            return new BigDecimal[0];
        }
        if (bds == null)
            bds = new BigDecimal[inputs.length];
        // First fill in all the now know values
        for (Integer ref : referenced) {
            if (ref < 100 && bds[ref] == null) { // meaning from input and don't overwrite
                try {
                    bds[ref] = NumberUtils.toScaledBigDecimal(inputs[ref]);
                } catch (NumberFormatException e) {
                    Logger.error(e);
                }
            }
        }
        return applyResult(bds);
    }

    public BigDecimal[] solveDoubles(ArrayList<Double> data) {
        return workWithDoubles(data, null);
    }

    public void continueDoubles(ArrayList<Double> data, BigDecimal[] bds) {
        workWithDoubles(data, bds);
    }

    private BigDecimal[] workWithDoubles(ArrayList<Double> data, BigDecimal[] bds) {
        if (bds == null)
            bds = new BigDecimal[data.size()];
        // Only convert the values we will actually use
        for (Integer ref : referenced) {
            if (ref < 100 && bds[ref] == null) { // meaning from input and don't overwrite
                try {
                    bds[ref] = BigDecimal.valueOf(data.get(ref));
                } catch (NumberFormatException e) {
                    Logger.error(e);
                }
            }
        }
        return applyResult(bds);
    }

    public BigDecimal[] applyResult(BigDecimal[] bds) {
        // First fill in all the now know values
        var result = solve(bds);
        if (resultIndex == -1)
            return bds;
        if (resultIndex < 100) { // Either referring to the input data
            bds[resultIndex] = result;
        } else { // Or a val ref, either permanent (1xx) or temp (2xx)
            var index = resultIndex % 100;
            valRefs[index].updateValue(result.doubleValue());
        }
        return bds;
    }

    public double solveSimple(BigDecimal input) {
        // First fill in all the now know values
        return solve(new BigDecimal[]{input}).doubleValue();
    }

    private BigDecimal solve(BigDecimal[] bds) {
        // First fill in all the now know values
        int refLength = steps.size();
        for (int index = 0; index < referenced.length; index++) {
            var ref = referenced[index];
            if (ref < 100) { // meaning from input
                try {
                    scratchpad[index + refLength] = bds[ref];
                } catch (NumberFormatException e) {
                    Logger.error(e);
                }
            } else {
                ref %= 100;
                scratchpad[index + refLength] = valRefs[ref].toBigDecimal();
            }
        }

        // Do the calculations
        int index = 0;
        for (var step : steps) {
            scratchpad[index] = step.apply(scratchpad);
            index++;
        }
        return scratchpad[index - 1];
    }

}
