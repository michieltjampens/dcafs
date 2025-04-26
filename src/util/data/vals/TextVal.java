package util.data.vals;

public class TextVal extends BaseVal {
    String value, defValue;

    private enum TYPE {STATIC, LOCALDT, UTCDT}

    private TYPE type = TYPE.STATIC;

    public TextVal value(String value) {
        this.value = value;
        return this;
    }

    public static TextVal newVal(String group, String name) {
        return new TextVal(group, name, "");
    }

    public static TextVal newLocalTimeVal(String group, String name) {
        return new TextVal(group, name, "").makeLocalDT();
    }

    public static TextVal newUTCTimeVal(String group, String name) {
        return new TextVal(group, name, "").makeUTCDT();
    }

    public TextVal makeLocalDT() {
        type = TYPE.LOCALDT;
        return this;
    }

    public TextVal makeUTCDT() {
        type = TYPE.UTCDT;
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

    @Override
    public void triggerUpdate() {

    }

    public String formatValueWithUnit() {
        return value + unit;
    }
}
