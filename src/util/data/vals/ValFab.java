package util.data.vals;

import org.tinylog.Logger;
import util.data.procs.Builtin;
import util.data.procs.Reducer;
import util.evalcore.MathFab;
import util.xml.XMLdigger;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ValFab {
    public record Basics(String group, String name, String unit) {
    }

    public record RefVal(String ref, BaseVal bs) {
    }

    public static void digRealVals(XMLdigger dig, String groupName, Map<String, RealVal> realVals, Rtvals rtvals) {
        var d = dig.peekOut("real");
        var d2 = dig.peekOut("double");

        Stream.of(d, d2)
                .flatMap(Collection::stream)
                .map(valEle -> digForRealSymbiotes(XMLdigger.goIn(valEle), groupName, realVals, rtvals, 0))
                .filter(Objects::nonNull)
                .forEach(it -> realVals.put(it.id(), it));
    }

    public static RealVal digForRealSymbiotes(XMLdigger dig, String groupName, Map<String, RealVal> rvs, Rtvals rtvals, int level) {
        var realOpt = buildRealVal(dig, groupName, rtvals); // Get the main?
        if (realOpt.isEmpty())
            return null;

        var real = realOpt.get();
        if (dig.tagName("").equals("derived")) {
            Logger.info("Found a derived :" + real.id());
        } else {
            Logger.info("Found a RealVal :" + real.id());
        }
        if (dig.hasPeek("derived")) {
            var list = new ArrayList<RealVal>();
            list.add(real);
            Logger.info(real.id() + " -> Processing derived of " + real.id());
            for (var derive : dig.digOut("derived")) {
                // Make sure the derived node adheres to the regular node rules for naming
                var deriveId = fixDerivedName(derive, real);
                if (rvs.get(deriveId) != null) {
                    Logger.error("A realval already exists with the ID " + deriveId);
                    return null;
                }
                // If it gets here, the name is fine
                var derived = digForRealSymbiotes(derive, groupName, rvs, rtvals, level + 1);
                if (derived == null)
                    return null;
                list.add(derived);
            }
            list.forEach(rv -> rvs.put(rv.id(), rv));
            return new RealValSymbiote(level, list.toArray(RealVal[]::new));
        } else {
            rvs.put(real.id(), real);
        }
        return real;
    }

    public static Optional<RealVal> buildRealVal(XMLdigger dig, String altGroup, Rtvals rtvals) {
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

            if (dig.hasAttr("math")) {
                var math = dig.attr("math", "");
                var mathEval = MathFab.parseExpression(math, rtvals);
                if (mathEval.isInValid())
                    return Optional.empty();
                var scale = dig.attr("scale", -1);
                mathEval.setScale(scale);
                rv.setMath(MathFab.stripForValIfPossible(mathEval));
            } else if (dig.hasAttr("builtin")) {
                rv.setMath(Builtin.getDoubleFunction(dig.attr("builtin", ""), dig.attr("scale", -1)));
            }
        } else {
            var reducer = Reducer.getDoubleReducer(dig.attr("reducer", "avg"), def, window);
            var ra = new RealValAggregator(base.group, base.name, base.unit, reducer, window);
            ra.setScale(dig.attr("scale", -1));
            Logger.info("Building RealValAggregator " + ra.id());
            rv = ra;
        }
        rv.defValue(def);
        rv.update(def);
        return Optional.of(rv);
    }


    public static void digIntegerVals(XMLdigger dig, String groupName, Map<String, IntegerVal> integerVals, Rtvals rtvals) {
        var i = dig.peekOut("int");
        var i2 = dig.peekOut("integer");

        Stream.of(i, i2)
                .flatMap(Collection::stream)
                .map(rtval -> digForIntSymbiotes(XMLdigger.goIn(rtval), groupName, integerVals, rtvals, 0))
                .filter(Objects::nonNull)
                .forEach(it -> integerVals.put(it.id(), (IntegerVal) it));
    }

    public static NumericVal digForIntSymbiotes(XMLdigger dig, String groupName, Map<String, IntegerVal> rvs, Rtvals rtvals, int level) {
        var intOpt = buildIntegerVal(dig, groupName, rtvals); // Get the main?
        if (intOpt.isEmpty())
            return null;

        var intVal = intOpt.get();
        if (dig.tagName("").equals("derived")) {
            Logger.info("Found a derived :" + intVal.id());
        } else {
            Logger.info("Found a IntegerVal :" + intVal.id());
        }
        if (dig.hasPeek("derived")) {
            var list = new ArrayList<NumericVal>();
            list.add(intVal);
            Logger.info(intVal.id() + " -> Processing derived of " + intVal.id());
            for (var derive : dig.digOut("derived")) {
                // Make sure the derived node adheres to the regular node rules for naming
                var deriveId = fixDerivedName(derive, intVal);
                if (rvs.get(deriveId) != null) {
                    Logger.error("A realval already exists with the ID " + deriveId);
                    return null;
                }
                // If it gets here, the name is fine
                var derived = digForIntSymbiotes(derive, groupName, rvs, rtvals, level + 1);
                if (derived == null)
                    return null;
                list.add(derived);
            }
            //list.forEach(nv -> rvs.put(nv.id(), nv));
            return new IntegerValSymbiote(level, intVal, list.toArray(NumericVal[]::new));
        } else {
            rvs.put(intVal.id(), intVal);
        }
        return intVal;
    }

    public static Optional<IntegerVal> buildIntegerVal(XMLdigger dig, String altGroup, Rtvals rtvals) {
        var base = readBasics(dig, altGroup);
        if (base == null)
            return Optional.empty();

        IntegerVal iv;
        var windowSize = dig.attr("window", -1);
        var def = dig.attr("default", 0);
        def = dig.attr("def", def);

        if (windowSize <= 1) {
            iv = new IntegerVal(base.group, base.name, base.unit);
            Logger.info("Building IntegerVal " + iv.id());

            if (dig.hasAttr("math")) {
                var math = dig.attr("math", "");
                var mathEval = MathFab.parseExpression(math, rtvals);
                if (mathEval.isInValid())
                    return Optional.empty();

                iv.setMath(MathFab.stripForValIfPossible(mathEval));
            } else if (dig.hasAttr("builtin")) {
                iv.setMath(Builtin.getIntFunction(dig.attr("builtin", "")));
            }
        } else {
            var reducer = Reducer.getIntegerReducer(dig.attr("reducer", "avg"), def, windowSize);
            iv = new IntegerValAggregator(base.group, base.name, base.unit, reducer, windowSize);
        }
        iv.defValue(def);
        iv.update(def);
        return Optional.of(iv);
    }


    public static Map<String, FlagVal> digFlagVals(XMLdigger dig, String groupName, Rtvals rtvals) {
        return dig.peekOut("flag").stream()
                .map(rtval -> buildFlagVal(XMLdigger.goIn(rtval), groupName, rtvals).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(FlagVal::id, Function.identity()));
    }

    public static Optional<FlagVal> buildFlagVal(XMLdigger dig, String altGroup, Rtvals rtvals) {
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

    public static BaseVal buildVal(XMLdigger rtval, String group, Rtvals rtvals) {
        var valOpt = switch (rtval.tagName("")) {
            case "double", "real" -> buildRealVal(rtval, group, rtvals);
            case "integer", "int" -> buildIntegerVal(rtval, group, rtvals);
            case "flag" -> buildFlagVal(rtval, group, rtvals);
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
            dig.restorePoint();
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

    private static String fixDerivedName(XMLdigger derive, BaseVal main) {
        if (!derive.hasAttr("unit"))
            derive.currentTrusted().setAttribute("unit", main.unit());

        if (derive.hasAttr("name")) // Already defined, no need to change anything
            return main.group() + "_" + derive.attr("name", "");  // but return it for the present check

        String oriName = "?";
        if (derive.saveAndUpRestoreOnFail(getTagName(main))) {
            oriName = derive.attr("name", "");
            var scale = derive.attr("scale", -1);
            derive.restorePoint();
            if (derive.noAttr("scale"))
                derive.currentTrusted().setAttribute("scale", "" + scale);
        }

        var suffix = derive.attr("suffix", ""); // Suffix the parent name
        if (suffix.isEmpty()) {
            // Check if they want to append to name of higher one ...
            var fromOri = derive.attr("mainsuffix", ""); // Handle both possible vals using this method
            if (!fromOri.isEmpty()) {
                derive.currentTrusted().setAttribute("name", oriName + "_" + fromOri);
                return main.group() + "_" + derive.attr("name", ""); // Full overwrite
            }
            // Auto generate based on function
            suffix = derive.attr("reducer", "");
            suffix = derive.attr("builtin", suffix);
        }
        derive.currentTrusted().setAttribute("name", main.name() + "_" + suffix);
        return main.group() + "_" + derive.attr("name", ""); // Full overwrite
    }

    private static String getTagName(BaseVal main) {
        if (main instanceof RealVal rv)
            return "real";
        if (main instanceof IntegerVal iv)
            return "integer";
        return "";
    }
}
