package util.data.vals;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.procs.MathEvalForVal;
import util.evalcore.MathEvaluatorDummy;
import util.tasks.blocks.ConditionBlock;
import util.tasks.blocks.NoOpBlock;

import java.math.BigDecimal;

public class IntegerVal extends BaseVal implements NumericVal {
    int value, defValue;

    ConditionBlock preCheck = NoOpBlock.INSTANCE;
    ;
    boolean ignorePre = true;
    ConditionBlock postCheck = NoOpBlock.INSTANCE;
    boolean ignorePost = true;
    MathEvalForVal math = new MathEvaluatorDummy();

    public IntegerVal(String group, String name, String unit) {
        super(group, name, unit);
        defValue = -1;
    }

    public static IntegerVal newVal(String group, String name) {
        return new IntegerVal(group, name, "");
    }

    public int value() {
        return value;
    }

    @Override
    public void triggerUpdate() {
        update(0);
    }

    public boolean update(double value) {
        return update((int) value);
    }

    public boolean update(int value) {
        var pre = preCheck.start(value, this.value);
        if (ignorePre || pre) {
            var res = math.eval(value, this.value, 0);
            var post = postCheck.start(value, this.value, res);
            if (ignorePost || post) {
                this.value = res;
                return true;
            }
        }
        return false;
    }

    @Override
    public void resetValue() {
        value = defValue;
    }
    public void defValue(int defValue) {
        this.defValue = defValue;
    }

    public int defValue() {
        return defValue;
    }
    public String asString() {
        return String.valueOf(value);
    }

    @Override
    public boolean parseValue(String stringValue) {
        var v = NumberUtils.toInt(stringValue, Integer.MAX_VALUE - 1);
        if (v == Integer.MAX_VALUE - 1) {
            Logger.error(id() + "-> Failed to parse " + stringValue);
            return false;
        }
        update(v);
        return true;
    }

    public void setPreCheck(ConditionBlock pre) {
        ignorePre = false;
        preCheck = pre;
    }

    public void setPostCheck(ConditionBlock post, boolean ignore) {
        ignorePost = ignore;
        postCheck = post;
    }

    public void setMath(MathEvalForVal bin) {
        math = bin;
    }
    @Override
    public double asDouble() {
        return value();
    }

    @Override
    public int asInteger() {
        return value();
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
}
