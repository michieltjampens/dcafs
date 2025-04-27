package util.data.vals;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.procs.MathEvalForVal;
import util.evalcore.LogicEvalTrigger;
import util.evalcore.MathEvaluatorDummy;

import java.math.BigDecimal;

public class RealVal extends BaseVal implements NumericVal {
    double value = Double.NaN, defValue = Double.NaN;

    LogicEvalTrigger preCheck = new LogicEvalTrigger();
    LogicEvalTrigger postCheck = new LogicEvalTrigger();
    MathEvalForVal math = new MathEvaluatorDummy();

    public RealVal(String group, String name, String unit) {
        super(group, name, unit);
        preCheck.setId(id() + "_PRE");
        postCheck.setId(id() + "_POST");
    }

    public static RealVal newVal(String group, String name) {
        return new RealVal(group, name, "");
    }

    public boolean update(double value) {
        if (preCheck.eval(value, this.value, 0.0)) {
            var res = math.eval(value, this.value, 0.0);
            if (postCheck.eval(res, value, this.value))
                this.value = res;
        }
        return true;
    }

    public boolean update(int value) {
        return update((double) value);
    }
    @Override
    public void triggerUpdate() {
        update(0.0);
    }

    public void setMath(MathEvalForVal bin) {
        math = bin;
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
        return update(v);
    }

    public void defValue(double defValue) {
        this.defValue = defValue;
    }

    @Override
    public double asDouble() {
        return value();
    }

    @Override
    public int asInteger() {
        return (int) Math.round(value());
    }

    @Override
    public BigDecimal asBigDecimal() {
        try {
            return BigDecimal.valueOf(value());
        } catch (NumberFormatException e) {
            Logger.warn(id() + " hasn't got a valid value yet to convert to BigDecimal");
            return null;
        }
    }

    public String asString() {
        return String.valueOf(value());
    }
}
