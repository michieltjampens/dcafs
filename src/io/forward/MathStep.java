package io.forward;

import org.tinylog.Logger;
import util.math.MathOperationSolver;
import util.math.MathUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.StringJoiner;

public class MathStep extends AbstractStep {
    MathOperationSolver[] ops;
    String suffix;

    public MathStep(MathOperationSolver[] ops, String suffix) {
        this.ops = ops;
        this.suffix = suffix;
    }

    @Override
    public void takeStep(String data, BigDecimal[] bds) {
        // Apply the operations
        bds = ops[0].solveBDs(data);
        for (int index = 1; index < ops.length; index++) {
            ops[index].continueBDs(data, bds);
        }
        if (bds == null) {
            Logger.error("(mf) -> Something went wrong processing the data.");
            return;
        }
        // Overwrite the original data with the calculated values if applicable.
        var combined = recombineData(data, bds, ops[0].getDelimiter());
        doNext(appendSuffix(suffix, combined), bds);
    }

    public void takeStep(ArrayList<Double> data) {
        // Apply the operations
        BigDecimal[] bds = ops[0].solveDoubles(data);
        for (int index = 1; index < ops.length; index++) {
            ops[index].continueDoubles(data, bds);
        }
        if (bds == null) {
            Logger.error("(mf) -> Something went wrong processing the data.");
            return;
        }
        for (int index = 0; index < bds.length; index++) {
            if (bds[index] != null)
                data.set(index, bds[index].doubleValue());
        }
        doNext(null, bds);
    }

    public static String recombineData(String data, BigDecimal[] bds, String delimiter) {
        // Recreate the data stream
        var splitData = data.split(delimiter);
        var join = new StringJoiner(delimiter);
        for (int index = 0; index < bds.length; index++) {
            if (bds[index] != null) {
                join.add(bds[index].stripTrailingZeros().toPlainString());
            } else {
                join.add(splitData[index]);
            }
        }
        return join.toString();
    }

    private static String appendSuffix(String suffix, String data) {
        return switch (suffix) {
            case "" -> data;
            case "nmea" -> data + "*" + MathUtils.getNMEAchecksum(data);
            default -> {
                Logger.error(" (mf)-> No such suffix " + suffix);
                yield data;
            }
        };
    }
}
