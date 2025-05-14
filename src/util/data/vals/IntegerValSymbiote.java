package util.data.vals;

import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;

public class IntegerValSymbiote extends IntegerVal {

    NumericVal[] underlings;
    boolean passOriginal = true;
    int level = 0;
    IntegerVal host;

    public IntegerValSymbiote(int level, IntegerVal host, NumericVal... underlings) {
        super(host.group(), host.name(), host.unit());

        this.underlings = underlings;
        this.level = level;
        this.host = host;
    }

    public boolean update(double val) {
        var result = host.update(val);
        this.value = host.value();

        var forwardedValue = passOriginal ? val : value;
        if (result || passOriginal)
            Arrays.stream(underlings, 1, underlings.length).forEach(rv -> rv.update(forwardedValue));

        return result;
    }

    public int value() {
        return host.value();
    }

    public int level() {
        return level;
    }

    @Override
    public void resetValue() {
        host.defValue(defValue);
        value = host.value();
    }

    public void defValue(int defValue) {
        host.defValue(defValue);
        this.defValue = defValue;
    }

    public NumericVal[] getUnderlings() {
        return underlings;
    }

    public void addUnderling(NumericVal underling) {
        underlings = ArrayUtils.add(underlings, underling);
    }
    public NumericVal[] getDerived() {
        return Arrays.copyOfRange(underlings, 1, underlings.length);
    }
}
