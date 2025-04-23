package util.data.vals;

public class TextVal extends BaseVal {
    String value, defValue;

    public TextVal value(String value) {
        this.value = value;
        return this;
    }

    public String value() {
        return value;
    }

    public TextVal(String group, String name, String unit) {
        super(group, name, unit);
    }

    @Override
    public void resetValue() {
        value = defValue;
    }

    public void defValue(String defValue) {
        this.defValue = defValue;
    }

    @Override
    public boolean parseValue(String value) {
        this.value = value;
        return true;
    }

    public String asString() {
        return value;
    }

    public String asValueString() {
        return value + unit;
    }
}
