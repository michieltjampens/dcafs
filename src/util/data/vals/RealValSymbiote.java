package util.data.vals;

import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;

public class RealValSymbiote extends RealVal {

    RealVal[] underlings;
    boolean passOriginal = false;
    int level = 0;

    public RealValSymbiote(int level, RealVal... underlings) {
        super(underlings[0].group(), underlings[0].name(), underlings[0].unit());

        this.underlings = underlings;
        this.level = level;
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

    public void addUnderling(RealVal underling) {
        underlings = ArrayUtils.add(underlings, underling);
    }
    public RealVal[] getUnderlings() {
        return underlings;
    }

    public RealVal[] getDerived() {
        return Arrays.copyOfRange(underlings, 1, underlings.length);
    }

    public String getExtraInfo() {
        return underlings[0].getExtraInfo();
    }
}
