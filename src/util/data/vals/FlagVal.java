package util.data.vals;

import org.tinylog.Logger;
import util.tools.Tools;

import java.math.BigDecimal;

public class FlagVal extends BaseVal implements NumericVal {
    boolean value, defValue;

    public FlagVal(String group, String name, String unit) {
        super(group, name, unit);
    }

    public void value(boolean state) {
        value = state;
    }

    public void update(boolean state) {
        this.value = state;
    }

    public void toggleState() {
        value = !value;
    }
    public boolean isUp() {
        return value;
    }

    public void update(double val) {
        value = Double.compare(val, 0.0) > 0;
    }

    @Override
    public void resetValue() {
        value = defValue;
    }

    public void defValue(boolean defValue) {
        this.defValue = defValue;
    }

    @Override
    public boolean parseValue(String value) {
        var opt = Tools.parseBool(value);
        opt.ifPresentOrElse(b -> this.value = b, () -> Logger.error(id() + "-> Failed to parse " + value));
        return opt.isPresent();
    }

    @Override
    public double asDouble() {
        return value ? 1.0 : 0.0;
    }

    @Override
    public int asInteger() {
        return value ? 1 : 0;
    }

    @Override
    public BigDecimal asBigDecimal() {
        return value ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    public String asString() {
        return String.valueOf(value);
    }
}
