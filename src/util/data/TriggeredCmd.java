package util.data;

import util.math.MathUtils;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * TriggeredCmd is a way to run cmd's if the new value succeeds in either the compare or meets the other options
 * Cmd: if it contains a '$' this will be replaced with the current value
 * Current triggers:
 * - Double comparison fe. above 5 and below 10
 * - always, means always issue the cmd independent of the value
 * - changed, only issue the command if the value has changed
 */
public class TriggeredCmd<T extends Number> {
    private String cmd;
    private String ori;
    private TRIGGERTYPE type;
    private Function<Double, Boolean> comp;
    private boolean triggered = false;
    enum TRIGGERTYPE {ALWAYS,CHANGED,STDEV,COMP}

    public TriggeredCmd(String cmd, String trigger ) {
        this.cmd = cmd;
        this.ori = trigger;
        type = TRIGGERTYPE.COMP;
        switch (trigger) {
            case "", "always" -> type = TRIGGERTYPE.ALWAYS;
            case "changed" -> type = TRIGGERTYPE.CHANGED;
            default -> {
                if (trigger.contains("stdev")) {
                    type = TRIGGERTYPE.STDEV;
                    trigger = trigger.replace("stdev", "");
                }
                comp = MathUtils.parseSingleCompareFunction(trigger);
                if (comp == null) {
                    this.cmd = "";
                }
            }
        }
    }

    public boolean isInvalid() {
        return cmd.isEmpty();
    }

    public <U extends Number> boolean check(U val, U prev, Consumer<String> cmdConsumer, Supplier<Double> stdevSupplier) {
        boolean ok;
        switch (type) {
            case ALWAYS -> {
                cmdConsumer.accept( cmd.replace("$", String.valueOf(val)) );
                return true;
            }
            case CHANGED -> {
                if (!val.equals(prev)) {
                    cmdConsumer.accept( cmd.replace("$", String.valueOf(val)) );
                    return true;
                }
                return false;
            }
            case COMP -> {
                // Explicitly cast val to double if it's a valid Number
                if (val instanceof Double) {
                    ok = comp.apply((Double) val); // Cast val to Double explicitly
                } else {
                    // Handle other cases for val not being Double
                    ok = comp.apply(val.doubleValue()); // Use doubleValue() for other Number types
                }
            }
            case STDEV -> {
                var sd = stdevSupplier.get();
                if (Double.isNaN(sd)) return false;
                ok = comp.apply(sd);
            }
            default -> {
                return false;
            }
        }
        if (!triggered && ok) {
            cmdConsumer.accept(cmd.replace("$", String.valueOf(val)));
            triggered = true;
            return true;
        } else if (triggered && !ok) {
            triggered = false;
        }
        return false;
    }
}

