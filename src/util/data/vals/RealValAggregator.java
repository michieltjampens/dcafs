package util.data.vals;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.procs.DoubleArrayToDouble;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.DoubleStream;

public class RealValAggregator extends RealVal {
    private final double[] window;
    private double defValue = 0.0;
    private final int windowSize;
    private int currentIndex = 0;
    private boolean filled = false;

    // DoubleBinaryOperator to apply a logic to update the value, default is average
    DoubleArrayToDouble reducer = (window) -> DoubleStream.of(window).average().orElse(defValue);
    // Replace with more logic if needed

    public RealValAggregator(String group, String name, String unit, DoubleArrayToDouble reducer, int windowSize) {
        super(group, name, unit);
        this.windowSize = windowSize;
        if (reducer != null)
            this.reducer = reducer;
        window = new double[windowSize];
        Arrays.fill(window, defValue);
    }

    public void update(double value) {
        window[currentIndex] = value;
        currentIndex = (currentIndex + 1) % windowSize;
        Logger.info("Added val to aggregator");
        if (currentIndex == 0)
            filled = true;
    }

    public double value() {
        return reducer.apply(filled ? window : Arrays.copyOf(window, currentIndex));
    }

    @Override
    public void resetValue() {
        Arrays.fill(window, defValue);
        currentIndex = 0;
        filled = false;
    }

    @Override
    public boolean parseValue(String value) {
        var v = NumberUtils.createDouble(value);
        if (v == null)
            return false;
        update(v);
        return true;
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

    @Override
    public String getExtraInfo() {
        return "    [" + (filled ? windowSize : currentIndex + "/" + windowSize) + "]";
    }
}
