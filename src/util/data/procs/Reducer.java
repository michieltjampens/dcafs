package util.data.procs;

import org.tinylog.Logger;
import util.math.MathUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

public class Reducer {

    public static DoubleArrayToDouble getDoubleReducer(String reducer, double defValue, int windowsize) {
        return switch (reducer.replace(" ", "").toLowerCase()) {
            case "mean", "avg" -> (window) -> Arrays.stream(window).average().orElse(defValue);
            case "median" -> {
                if (windowsize % 2 == 0) {
                    yield (window) -> {
                        var sorted = DoubleStream.of(window).sorted().toArray();
                        return sorted[sorted.length / 2];
                    };
                } else {
                    yield (window) -> {
                        var sorted = DoubleStream.of(window).sorted().toArray();
                        return (sorted[sorted.length / 2] + sorted[sorted.length / 2 - 1]) / 2;
                    };
                }

            }
            case "variance" -> MathUtils::calcVariance;
            case "samplevariance" -> (window) -> MathUtils.calcVariance(window) / (window.length - 1);
            case "populationvariance" -> (window) -> MathUtils.calcVariance(window) / (window.length);
            case "stdev", "standarddeviation" -> (window) -> Math.sqrt(MathUtils.calcVariance(window));
            case "popstdev", "populationstandarddeviation" ->
                    (window) -> Math.sqrt(MathUtils.calcVariance(window) / window.length);
            case "mode" -> (window) -> {
                int scale = 100; // Adjust for desired precision (e.g., 2 decimal places)
                Map<Integer, Long> frequencyMap = Arrays.stream(window)
                        .mapToInt(d -> (int) (d * scale))
                        .boxed()
                        .collect(Collectors.groupingBy(i -> i, Collectors.counting()));

                return frequencyMap.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(e -> e.getKey() / (double) scale)
                        .orElse(defValue);
            };
            default -> {
                Logger.warn("Unknown reducer type '{}'. Returning null. Waiting on your pull request to get it implemented!", reducer);
                yield null;
            }

        };
    }

    public static IntegerArrayToInteger getIntegerReducer(String reducer, int defValue, int windowsize) {
        return switch (reducer.replace(" ", "").toLowerCase()) {
            case "max" -> (window) -> {
                var max = Integer.MIN_VALUE;
                for (var a : window)
                    max = Math.max(max, a);
                return max;
            };
            case "min" -> (window) -> {
                var min = Integer.MAX_VALUE;
                for (var a : window)
                    min = Math.min(min, a);
                return min;
            };
            case "sum" -> sumInts();
            default -> {
                Logger.warn("Unknown reducer type '{}'. Returning null. Waiting on your pull request to get it implemented!", reducer);
                yield null;
            }

        };
    }

    private static IntegerArrayToInteger sumInts() {
        return (window) -> {
            var sum = 0;
            for (var a : window)
                sum += a;
            return sum;
        };
    }
}
