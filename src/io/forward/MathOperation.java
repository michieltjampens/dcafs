package io.forward;

import das.Core;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.NumericVal;
import util.data.RealtimeValues;
import util.math.MathFab;
import worker.Datagram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;

/**
 * Storage class for everything related to an operation.
 * Contains the functions that
 */
public class MathOperation {

    Function<BigDecimal[], BigDecimal> op = null; // for the scale type
    MathFab fab = null;    // for the complex type
    int index;           // index for the result
    int scale = -1;
    String ori;          // The expression before it was decoded mainly for listing purposes
    String cmd = "";      // Command in which to replace the $ with the result
    NumericVal update;
    BigDecimal directSet;

    boolean doUpdate;
    boolean valid;
    boolean doCmd;
    boolean debug = false;
    int badDataCount = 0;

    public MathOperation(String ori, int index, RealtimeValues rtvals) {
        this.ori = ori;
        this.index = index;

        if (ori.contains(":") && ori.indexOf(":") < ori.indexOf("=")) { // If this contains : it means it has a reference
            try {
                String sub = ori.substring(ori.indexOf(":") + 1, ori.indexOf("}"));

                String val = ori.substring(ori.indexOf(":") + 1, ori.indexOf("}") + 1);
                if (ori.startsWith("{r") || ori.startsWith("{d")) {
                    rtvals.getRealVal(sub)
                            .ifPresent(dv -> {
                                update = dv;
                                doUpdate = true;
                            });
                    if (!doUpdate)
                        Logger.error("Asking to update {r:" + val + " but doesn't exist");
                } else if (ori.startsWith("{i")) {
                    rtvals.getIntegerVal(sub)
                            .ifPresent(iv -> {
                                update = iv;
                                doUpdate = true;
                            });
                    if (!doUpdate)
                        Logger.error("Asking to update {i:" + val + " but doesn't exist");
                } else {
                    Logger.error("No idea what to do with " + ori);
                }
            } catch (IndexOutOfBoundsException e) {
                Logger.error(id + " (mf) -> Index out of bounds: " + e.getMessage());
            }
        }
    }

    public MathOperation(String ori, Function<BigDecimal[], BigDecimal> op, int index, RealtimeValues rtvals) {
        this(ori, index, rtvals);
        this.op = op;
    }

    public MathOperation(String ori, MathFab fab, int index, RealtimeValues rtvals) {
        this(ori, index, rtvals);
        if (fab.isValid())
            this.fab = fab;
    }

    public MathOperation(String ori, String value, int index, RealtimeValues rtvals) {
        this(ori, index, rtvals);
        this.directSet = NumberUtils.createBigDecimal(value);
    }

    public boolean isValid() {
        return op != null || fab != null;
    }

    public MathOperation scale(int scale) {
        this.scale = scale;
        return this;
    }

    public MathOperation cmd(String cmd, RealtimeValues rtvals) {
        if (cmd.isEmpty())
            return this;
        this.cmd = cmd;
        valid = true;
        doCmd = true;

        if (((cmd.startsWith("real:update") || cmd.startsWith("rv")) && cmd.endsWith(",$"))) {
            String val = cmd.substring(8).split(",")[1];
            this.cmd = rtvals.getRealVal(val).map(dv -> {
                update = dv;
                doUpdate = true;
                return "";
            }).orElse(cmd);
        }
        return this;
    }

    public BigDecimal solve(BigDecimal[] data) {
        BigDecimal bd;
        boolean changeIndex = true;
        if (op != null) { // If there's an op, use it
            var bdOpt = solveWithOp(data);
            if (bdOpt.isEmpty())
                return null;
            bd = bdOpt.get();
        } else if (fab != null) { // If no op, but a fab, use it
            var bdOpt = solveWithFab(data);
            if (bdOpt.isEmpty())
                return null;
            bd = bdOpt.get();
        } else if (directSet != null) { // Might be just direct set
            bd = directSet;
        } else if (index != -1) {
            if (data[index] == null) {
                showError(false, " (mf) -> Index " + index + " in data is null");
                return null;
            }
            bd = data[index];
            changeIndex = false;
        } else {
            return null;
        }

        if (scale != -1) // If scaling is requested
            bd = bd.setScale(scale, RoundingMode.HALF_UP);

        if (index >= 0 && index < data.length && changeIndex)
            data[index] = bd;

        if (update != null) {
            update.updateValue(bd.doubleValue());
        } else if (!cmd.isEmpty()) {
            Core.addToQueue(Datagram.system(cmd.replace("$", bd.toString())));
        }
        if (debug)
            Logger.info("Result of op: " + bd.toPlainString());
        return bd;
    }

    private Optional<BigDecimal> solveWithOp(BigDecimal[] data) {
        if (data.length <= index) {
            showError(false, "(mf) -> Tried to do an op with to few elements in the array (data=" + data.length + " vs index=" + index);
            return Optional.empty();
        }
        try {
            return Optional.of(op.apply(data));
        } catch (NullPointerException e) {
            if (showError(false, "(mf) -> Null pointer when processing for " + ori)) {
                StringJoiner join = new StringJoiner(", ");
                Arrays.stream(data).map(String::valueOf).forEach(join::add);
                Logger.error("(mf) -> Data: " + join);
            }
            return Optional.empty();
        }
    }

    private Optional<BigDecimal> solveWithFab(BigDecimal[] data) {
        fab.setDebug(debug);
        fab.setShowError(showError(false, ""));
        try {
            var bdOpt = fab.solve(data);
            if (bdOpt.isEmpty()) {
                showError(false, "(mf) -> Failed to solve the received data");
                return Optional.empty();
            }
            return bdOpt;
        } catch (ArrayIndexOutOfBoundsException | ArithmeticException | NullPointerException e) {
            showError(false, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean showError(boolean count, String error) {
        if (count)
            badDataCount++;
        if (badDataCount < 6) {
            if (!error.isEmpty())
                Logger.error(id + " (mf) -> " + error);
            return true;
        }
        if (badDataCount % 60 == 0) {
            if ((badDataCount < 900 || badDataCount % 600 == 0) && !error.isEmpty()) {
                Logger.error(id + " (mf) -> " + error);
            }
            return true;
        }
        return false;
    }
}

}
