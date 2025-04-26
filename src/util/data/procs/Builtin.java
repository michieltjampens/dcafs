package util.data.procs;

import org.tinylog.Logger;

import java.util.function.DoubleBinaryOperator;
import java.util.function.IntBinaryOperator;

public class Builtin {

    public static BuiltinDoubleProc getDoubleFunction(String ref) {
        DoubleBinaryOperator proc = switch (ref.toLowerCase()) {
            case "max" -> Math::max;                                // Calculate the max between new and old
            case "min" -> Math::min;                                // Calculate the min between new and old
            case "abs" -> (i0, i1) -> Math.abs(i0);      // Calculate the absolute value of new
            case "sqrt" -> (i0, i1) -> Math.sqrt(i0);  // Calculate the square root of new
            default -> {
                Logger.error("No such builtin: " + ref);              // Just return the new value without any change
                yield (i0, i1) -> i0;
            }
        };
        return new BuiltinDoubleProc(proc);
    }

    public static BuiltinIntProc getIntFunction(String ref) {
        IntBinaryOperator proc = switch (ref.toLowerCase()) {
            case "max" -> Math::max;                                // Calculate the max between new and old
            case "min" -> Math::min;                                // Calculate the min between new and old
            case "abs" -> (i0, i1) -> Math.abs(i0);      // Calculate the absolute value of new
            default -> {
                Logger.error("No such builtin: " + ref);              // Just return the new value without any change
                yield (i0, i1) -> i0;
            }
        };
        return new BuiltinIntProc(proc);
    }
}
