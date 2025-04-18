package util.tasks;

import util.data.NumericVal;
import util.data.RealVal;

import java.util.ArrayList;
import java.util.function.Function;

public class ConditionBlock extends AbstractBlock {

    String ori;
    ArrayList<Function<Double[], Double>> steps = new ArrayList<>();
    ArrayList<NumericVal> sharedMem;
    int resultIndex;
    boolean negate = false;

    public ConditionBlock(String condition) {
        this.ori = condition;
    }
    @Override
    public boolean start() {
        Double[] work = new Double[steps.size() + sharedMem.size()];
        for (int a = 0; a < sharedMem.size(); a++) {
            work[steps.size() + a] = sharedMem.get(a).asDoubleValue();
        }
        for (int a = 0; a < steps.size(); a++)
            work[a] = steps.get(a).apply(work);
        var pass = Double.compare(work[resultIndex], 0.0) > 0;
        pass = negate != pass;

        if (pass) {
            doNext();
        } else {
            doFailure();
        }
        return pass;
    }

    public String toString() {
        return telnetId() + " -> Check if " + ori + (failure == null ? "." : ". If not, go to " + failure.telnetId());
    }

    public boolean alterSharedMem(int index, double val) {
        if (Double.isNaN(val))
            return false;
        while (sharedMem.size() <= index)
            sharedMem.add(RealVal.newVal("", "i" + sharedMem.size()).value(0));
        sharedMem.get(index).updateValue(val);
        return true;
    }
}
