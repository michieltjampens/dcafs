package util.data.vals;

import util.data.procs.DoubleArrayToDouble;
import util.math.MathUtils;

import java.util.Arrays;
import java.util.stream.DoubleStream;

public class RealValAggregator extends RealVal {
    private final double[] window;
    private final double defValue = 0.0;
    private final int windowSize;
    private int currentIndex = 0;
    private boolean filled = false;
    private int scale = -1;
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

    public void setScale(int scale) {
        this.scale = scale;
    }
    public boolean update(double value) {
        window[currentIndex] = value;
        currentIndex = (currentIndex + 1) % windowSize;

        if (currentIndex == 0)
            filled = true;
        return false;
    }

    public double value() {
        var res = reducer.apply(filled ? window : Arrays.copyOf(window, currentIndex));
        if (scale == -1)
            return res;
        return MathUtils.roundDouble(res, scale);
    }

    @Override
    public void resetValue() {
        Arrays.fill(window, defValue);
        currentIndex = 0;
        filled = false;
    }

    @Override
    public String getExtraInfo() {
        return "  [" + (filled ? windowSize : currentIndex + "/" + windowSize) + "]";
    }
}
