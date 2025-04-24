package util.data.vals;

import java.util.Arrays;

public class RealValSymbiote extends RealVal {

    RealVal[] underlings;
    boolean passOriginal = true;
    int level = 0;

    public RealValSymbiote(int level, RealVal... underlings) {
        this.underlings = underlings;
        this.level = level;

        // Mimic the main
        this.name = underlings[0].name;
        this.group = underlings[0].group;
        this.unit = underlings[0].unit;
    }

    public boolean update(double val) {
        var result = underlings[0].update(val);
        this.value = underlings[0].value();

        var forwardedValue = passOriginal ? val : value;
        if (result || passOriginal)
            Arrays.stream(underlings, 1, underlings.length).forEach(rv -> rv.update(forwardedValue));

        return result;
    }

    public double value() {
        return underlings[0].value();
    }

    public int level() {
        return level;
    }
    @Override
    public void resetValue() {
        underlings[0].defValue(defValue);
        value = underlings[0].value();
    }

    public void defValue(double defValue) {
        underlings[0].defValue(defValue);
        this.defValue = defValue;
    }

    public RealVal[] getUnderlings() {
        return underlings;
    }

    public RealVal[] getDerived() {
        return Arrays.copyOfRange(underlings, 1, underlings.length);
    }
}
