package util.data.vals;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.evalcore.Evaluator;
import util.evalcore.LogEvaluatorDummy;

import java.math.BigDecimal;
import java.util.function.DoubleBinaryOperator;

public class RealVal extends BaseVal implements NumericVal {
    double value = Double.NaN, defValue = Double.NaN;

    // DoubleBinaryOperator to apply a logic to update the value
    DoubleBinaryOperator updaterPass = (oldV, newV) -> newV; // Replace with more logic if needed
    DoubleBinaryOperator updaterFail = (oldV, newV) -> newV; // Replace with more logic if needed
    Evaluator logEval = new LogEvaluatorDummy();

    public RealVal(String group, String name, String unit) {
        super(group, name, unit);
    }

    public static RealVal newVal(String group, String name) {
        return new RealVal(group, name, "");
    }
    public void update(double value) {
        var result = logEval.eval(this.value, value);
        if (result.isEmpty()) {
            // logic eval failed
            return;
        }

        if (result.get()) {
            this.value = updaterPass.applyAsDouble(this.value, value);
        } else {
            this.value = updaterFail.applyAsDouble(this.value, value);
        }
    }

    public double value() {
        return value;
    }

    @Override
    public void resetValue() {
        value = defValue;
    }

    @Override
    public boolean parseValue(String value) {
        var v = NumberUtils.createDouble(value);
        if (v == null)
            return false;
        this.value = v;
        return true;
    }

    public void defValue(double defValue) {
        this.defValue = defValue;
    }

    @Override
    public double asDouble() {
        return value;
    }

    @Override
    public int asInteger() {
        return (int) Math.round(value);
    }

    @Override
    public BigDecimal asBigDecimal() {
        try {
            return BigDecimal.valueOf(value);
        } catch (NumberFormatException e) {
            Logger.warn(id() + " hasn't got a valid value yet to convert to BigDecimal");
            return null;
        }
    }

    public String asString() {
        return String.valueOf(value);
    }
}
