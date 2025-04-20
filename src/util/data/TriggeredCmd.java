package util.data;

import org.tinylog.Logger;
import util.evalcore.LogicEvaluator;
import util.evalcore.LogicFab;

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
public class TriggeredCmd {
    private String cmd;
    private TRIGGERTYPE type;
    private LogicEvaluator logEval;
    private boolean triggered = false;
    enum TRIGGERTYPE {ALWAYS,CHANGED,STDEV,COMP}

    public TriggeredCmd(String cmd, String trigger ) {
        this.cmd = cmd;
        type = TRIGGERTYPE.COMP;
        switch (trigger) {
            case "", "always" -> type = TRIGGERTYPE.ALWAYS;
            case "changed" -> type = TRIGGERTYPE.CHANGED;
            default -> {
                if (trigger.contains("stdev")) {
                    type = TRIGGERTYPE.STDEV;
                    trigger = trigger.replace("stdev", "");
                }
                if( !trigger.startsWith("i0"))
                    trigger= "i0 "+trigger;
                var logEvalOpt = LogicFab.parseComparison(trigger,null,null);
                if (logEvalOpt.isEmpty()) {
                    this.cmd = "";
                    Logger.error("Failed to create logic evaluator for "+trigger);
                }else{
                    logEval=logEvalOpt.get();
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
                    ok = logEval.eval((Double) val).orElse(false); // Cast val to Double explicitly
                } else {
                    // Handle other cases for val not being Double
                    ok = logEval.eval(val.doubleValue()).orElse(false); // Use doubleValue() for other Number types
                }
            }
            case STDEV -> {
                var sd = stdevSupplier.get();
                if (Double.isNaN(sd)) return false;
                ok = logEval.eval(sd).orElse(false);
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

