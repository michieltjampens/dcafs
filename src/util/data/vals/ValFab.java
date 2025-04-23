package util.data.vals;

import org.tinylog.Logger;
import util.data.procs.DoubleArrayToDouble;
import util.math.MathUtils;
import util.xml.XMLdigger;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

public class ValFab {
    public record Basics(String group, String name, String unit) {
    }

    public static Map<String, RealVal> digRealVals(XMLdigger dig, String groupName) {
        var d = dig.peekOut("real");
        var d2 = dig.peekOut("double");

        return Stream.of(d, d2)
                .flatMap(Collection::stream)
                .map(rtval -> buildRealVal(XMLdigger.goIn(rtval), groupName).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(RealVal::id, Function.identity()));
    }

    public static Optional<RealVal> buildRealVal(XMLdigger dig, String altGroup) {
        var base = readBasics(dig, altGroup);
        if (base == null)
            return Optional.empty();

        RealVal rv;
        // Difference between an aggregator or a normal RealVal
        var window = dig.attr("window", -1);
        var def = dig.attr("default", Double.NaN);
        def = dig.attr("def", def);

        if (window < 1) {
            rv = new RealVal(base.group, base.name, base.unit);
            Logger.info("Building RealVal " + rv.id());
        } else {
            var reducer = getReducer(dig.attr("reducer", "avg"), def, window);
            rv = new RealValAggregator(base.group, base.name, base.unit, reducer, window);
            Logger.info("Building RealValAggregator " + rv.id());
        }


        rv.defValue(def);

        return Optional.of(rv);
    }

    private static DoubleArrayToDouble getReducer(String reducer, double defValue, int windowsize) {
        return switch (reducer.replace(" ", "").toLowerCase()) {
            case "mean", "avg" -> {
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
                Logger.warn("Unknown reducer type '{}'. Defaulting to 'avg'. Waiting on your pull request to get it implemented!", reducer);
                yield (window) -> DoubleStream.of(window).average().orElse(defValue);
            }

        };
    }

    public static Map<String, IntegerVal> digIntegerVals(XMLdigger dig, String groupName) {
        var i = dig.peekOut("int");
        var i2 = dig.peekOut("integer");

        return Stream.of(i, i2)
                .flatMap(Collection::stream)
                .map(rtval -> buildIntegerVal(XMLdigger.goIn(rtval), groupName).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(IntegerVal::id, Function.identity()));
    }
    public static Optional<IntegerVal> buildIntegerVal(XMLdigger dig, String altGroup) {
        var base = readBasics(dig, altGroup);
        if (base == null)
            return Optional.empty();

        var iv = new IntegerVal(base.group, base.name, base.unit);
        var def = dig.attr("default", iv.defValue);
        iv.defValue(dig.attr("def", def));

        return Optional.of(iv);
    }


    public static Map<String, FlagVal> digFlagVals(XMLdigger dig, String groupName) {
        return dig.peekOut("flag").stream()
                .map(rtval -> buildFlagVal(XMLdigger.goIn(rtval), groupName).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(FlagVal::id, Function.identity()));
    }

    public static Optional<FlagVal> buildFlagVal(XMLdigger dig, String altGroup) {
        var base = readBasics(dig, altGroup);
        if (base == null)
            return Optional.empty();

        var fv = new FlagVal(base.group, base.name, base.unit);
        var def = dig.attr("default", fv.defValue);
        fv.defValue(dig.attr("def", def));

        return Optional.of(fv);
    }

    public static Map<String, TextVal> digTextVals(XMLdigger dig, String groupName) {
        return dig.peekOut("text").stream()
                .map(rtval -> buildTextVal(XMLdigger.goIn(rtval), groupName).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(TextVal::id, Function.identity()));
    }

    public static Optional<TextVal> buildTextVal(XMLdigger dig, String altGroup) {
        var base = readBasics(dig, altGroup);
        if (base == null)
            return Optional.empty();
        return Optional.of(new TextVal(base.group, base.name, base.unit));
    }

    public static BaseVal buildVal(XMLdigger rtval, String group) {
        var valOpt = switch (rtval.tagName("")) {
            case "double", "real" -> buildRealVal(rtval, group);
            case "integer", "int" -> buildIntegerVal(rtval, group);
            case "flag" -> buildFlagVal(rtval, group);
            case "text" -> buildTextVal(rtval, group);
            default -> Optional.empty();
        };
        return (BaseVal) valOpt.orElse(null);
    }

    public static Basics readBasics(XMLdigger dig, String altGroup) {
        // Get the name
        var name = digForName(dig);
        if (name == null)
            return null;

        // Get the group
        var group = digForGroup(dig, altGroup);

        // Get the unit
        var unit = dig.attr("unit", "");

        // Get the group and return found things
        return new Basics(group, name, unit);
    }

    private static String digForName(XMLdigger dig) {
        var name = dig.attr("name", "");
        if (name.isEmpty()) {
            if (dig.hasChilds()) {
                name = dig.peekAt("name").value("");
            } else {
                name = dig.value("");
            }
            if (name.isEmpty())
                name = dig.attr("id", name);             // or the attribute id
        }
        if (name.isEmpty()) { // If neither of the three options, this failed
            Logger.error("Tried to create a RealVal without id/name");
            return null;
        }
        return name;
    }

    private static String digForGroup(XMLdigger dig, String altGroup) {
        var group = dig.attr("group", "");
        if (group.isEmpty()) { // If none defined, check the parent node
            dig.savePoint();
            dig.goUp();
            if (dig.tagName("").equalsIgnoreCase("group")) {
                group = dig.attr("id", "");
            } else {
                group = dig.attr("group", "");
            }
            dig.loadPoint();
        }
        if (group.isEmpty()) { // If neither of the three options, this failed
            if (altGroup.isEmpty()) {
                Logger.error("Tried to create a RealVal without group");
                return null;
            }
            group = altGroup;
        }
        return group;
    }
}
