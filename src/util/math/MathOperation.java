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
    int resultIndex = -1;

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
        var bds = solveFor(data, delimiter);
        if (bds.length == 0)
            return bds;
        return solveDirect(bds);
    }

    public BigDecimal[] solveFor(String data, String delimiter) {
        var inputs = data.split(delimiter);
        if (inputs.length < highestI) {
            Logger.error("Not enough items in received data");
            return new BigDecimal[0];
        }
        BigDecimal[] bds = new BigDecimal[inputs.length];
        // First fill in all the now know values
        for (int index = 0; index < referenced.length; index++) {
            var ref = referenced[index];
            if (ref < 100 && bds[index] == null) { // meaning from input and don't overwrite
                try {
                    bds[index] = NumberUtils.toScaledBigDecimal(inputs[ref]);
                } catch (NumberFormatException e) {
                    Logger.error(e);
                }
            }
        }
        return bds;
    }

    public BigDecimal[] solveDirect(BigDecimal[] bds) {
        // First fill in all the now know values
        var result = solve(bds);
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
                    scratchpad[index + refLength] = bds[index];
                } catch (NumberFormatException e) {
                    Logger.error(e);
                }
            } else {
                ref -= 100;
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
