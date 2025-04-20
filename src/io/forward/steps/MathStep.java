package io.forward.steps;

import org.tinylog.Logger;
import util.evalcore.MathEvaluator;
import util.math.MathUtils;

import java.math.BigDecimal;
import java.util.StringJoiner;

public class MathStep extends AbstractStep {
    MathEvaluator[] ops;
    String suffix;
    String delimiter = ",";

    public MathStep(MathEvaluator[] ops, String suffix, String delimiter) {
        this.ops = ops;
        this.suffix = suffix;
        this.delimiter = delimiter;
    }

    @Override
    public String takeStep(String data, BigDecimal[] bds) {
        // Apply the operations
        bds = ops[0].updateBdArray(null, data, delimiter);
        for (MathEvaluator op : ops) {
            var res = op.eval(bds);
            if (res.isEmpty()) {
                Logger.error("Failed to calculate for expression: " + op.getOriginalExpression());
                return "error";
            }
            op.updateBdArray(bds, data, delimiter);
        }
        if (bds == null) {
            Logger.error("(mf) -> Something went wrong processing the data.");
            return "error";
        }
        // Overwrite the original data with the calculated values if applicable.
        var combined = recombineData(data, bds, delimiter);
        return doNext(appendSuffix(suffix, combined), bds);
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
