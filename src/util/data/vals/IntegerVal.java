package util.data.vals;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;

import java.math.BigDecimal;
import java.util.function.IntBinaryOperator;

public class IntegerVal extends BaseVal implements NumericVal {
    int value, defValue;
    IntBinaryOperator updater = (oldV, newV) -> newV;

    public IntegerVal(String group, String name, String unit) {
        super(group, name, unit);
        defValue = -1;
    }

    @Override
    public void resetValue() {
        value = defValue;
    }

    public void update(int value) {
        this.value = updater.applyAsInt(this.value, value);
    }

    public void defValue(int defValue) {
        this.defValue = defValue;
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
        this.value = v;
        return true;
    }

    @Override
    public double asDouble() {
        return value;
    }

    @Override
    public int asInteger() {
        return value;
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
