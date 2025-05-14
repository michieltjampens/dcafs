package util.data.vals;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.procs.MathEvalForVal;
import util.evalcore.MathEvaluatorDummy;
import util.tasks.blocks.ConditionBlock;

import java.math.BigDecimal;

public class RealVal extends BaseVal implements NumericVal {
    double value = Double.NaN, defValue = Double.NaN;

    ConditionBlock preCheck;
    boolean ignorePre = true;
    ConditionBlock postCheck;
    boolean ignorePost = true;
    MathEvalForVal math = new MathEvaluatorDummy();

    public RealVal(String group, String name, String unit) {
        super(group, name, unit);
    }

    public static RealVal newVal(String group, String name) {
        return new RealVal(group, name, "");
    }

    public boolean update(double value) {
        if (ignorePre || preCheck.start(value, this.value)) {
            var res = math.eval(value, this.value, 0.0);
            if (ignorePost || postCheck.start(value, this.value, res)) {
                if (ignorePost && postCheck != null)
                    postCheck.start(value, this.value, res);
                this.value = res;
                return true;
            }
        }
        return false;
    }

    public void setPreCheck(ConditionBlock pre) {
        ignorePre = false;
        preCheck = pre;
    }

    public void setPostCheck(ConditionBlock post, boolean ignorePost) {
        this.ignorePost = ignorePost;
        postCheck = post;
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
        if (Double.isNaN(value))
            value = defValue;
    }

    public double defValue() {
        return defValue;
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

    public MathEvalForVal getMath() {
        return math;
    }
}
