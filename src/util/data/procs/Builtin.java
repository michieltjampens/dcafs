package util.data.procs;

import org.tinylog.Logger;

import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntBinaryOperator;

public class Builtin {
    private static List<String> dProcs = List.of("max", "min", "abs", "sqrt");
    private static List<String> iProcs = List.of("max", "min", "abs");

    public static BuiltinDoubleProc getDoubleFunction(String ref, int scale) {
        ref = ref.toLowerCase();
        DoubleBinaryOperator proc = switch (ref) {
            case "max" -> (x, y) -> { // Calculate the max between new and old
                x = Double.isNaN(x) ? Double.MIN_VALUE : x;
                y = Double.isNaN(y) ? Double.MIN_VALUE : y;
                return Math.max(x, y);
            };
            case "min" -> (x, y) -> { // Calculate the min between new and old
                x = Double.isNaN(x) ? Double.MAX_VALUE : x;
                y = Double.isNaN(y) ? Double.MAX_VALUE : y;
                return Math.min(x, y);
            };
            case "abs" -> (i0, i1) -> Math.abs(i0);      // Calculate the absolute value of new
            case "sqrt" -> (i0, i1) -> Math.sqrt(i0);  // Calculate the square root of new
            default -> {
                Logger.error("No such builtin: " + ref);              // Just return the new value without any change
                yield (i0, i1) -> i0;
            }
        };
        ref = ref + (ref.startsWith("m") ? "(i0,old_" + ref + ")" : "(i0)");
        return new BuiltinDoubleProc(proc, scale, ref);
    }

    public static boolean isValidDoubleProc(String proc) {
        return dProcs.contains(proc);
    }

    public static boolean isValidIntProc(String proc) {
        return iProcs.contains(proc);
    }
    public static BuiltinIntProc getIntFunction(String ref) {
        IntBinaryOperator proc = switch (ref.toLowerCase()) {
            case "max" -> Math::max;                                // Calculate the max between new and old
            case "min" -> Math::min;                                // Calculate the min between new and old
            case "abs" -> (i0, i1) -> Math.abs(i0);          // Calculate the absolute value of new
            default -> {
                Logger.error("No such builtin: " + ref);            // Just return the new value without any change
                yield (i0, i1) -> i0;
            }
        };
        return new BuiltinIntProc(proc, ref.toLowerCase());
    }
}
