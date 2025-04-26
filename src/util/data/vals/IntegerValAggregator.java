package util.data.vals;

import org.tinylog.Logger;
import util.data.procs.IntegerArrayToInteger;

import java.util.Arrays;
import java.util.stream.IntStream;

public class IntegerValAggregator extends IntegerVal {
    private final int[] window;
    private final int windowSize;
    private int currentIndex = 0;
    private boolean filled = false;

    // DoubleBinaryOperator to apply a logic to update the value, default is average
    IntegerArrayToInteger reducer = (window) -> IntStream.of(window).max().orElse(defValue);
    // Replace with more logic if needed

    public IntegerValAggregator(String group, String name, String unit, IntegerArrayToInteger reducer, int windowSize) {
        super(group, name, unit);
        this.windowSize = windowSize;
        if (reducer != null)
            this.reducer = reducer;
        window = new int[windowSize];
        Arrays.fill(window, defValue);
    }

    public boolean update(int value) {
        window[currentIndex] = value;
        currentIndex = (currentIndex + 1) % windowSize;
        Logger.info("Added val to aggregator");
        if (currentIndex == 0)
            filled = true;
        return false;
    }

    public int value() {
        return reducer.apply(filled ? window : Arrays.copyOf(window, currentIndex));
    }

    @Override
    public void resetValue() {
        Arrays.fill(window, defValue);
        currentIndex = 0;
        filled = false;
    }

    @Override
    public String getExtraInfo() {
        return "    [" + (filled ? windowSize : currentIndex + "/" + windowSize) + "]";
    }
}
