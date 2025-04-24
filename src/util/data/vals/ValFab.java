package util.data.vals;

import org.tinylog.Logger;
import util.data.procs.Reducer;
import util.xml.XMLdigger;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    public static void digRealVals(XMLdigger dig, String groupName, Map<String, RealVal> realVals) {
        var d = dig.peekOut("real");
        var d2 = dig.peekOut("double");

        Stream.of(d, d2)
                .flatMap(Collection::stream)
                .map(valEle -> digForSymbiotes(XMLdigger.goIn(valEle), groupName, realVals))
                .filter(Objects::nonNull)
                .forEach(it -> realVals.put(it.id(), it));
    }

    public static RealVal digForSymbiotes(XMLdigger dig, String groupName, Map<String, RealVal> rvs) {
        var realOpt = buildRealVal(dig, groupName); // Get the main?
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
                var derived = digForSymbiotes(derive, groupName, rvs);
                if (derived == null)
                    return null;
                if (derived.unit().isEmpty()) // Derived share the same unit if none was set yet
                    derived.unit(real.unit());
                list.add(derived);
            }
            list.forEach(rv -> rvs.put(rv.id(), rv));
            return new RealValSymbiote(list.toArray(RealVal[]::new));
        } else {
            rvs.put(real.id(), real);
        }
        return real;
    }

    private static String fixDerivedName(XMLdigger derive, BaseVal main) {
        if (derive.hasAttr("name")) // Already defined, no need to change anything
            return main.group() + "_" + derive.attr("name", "");  // but return it for the present check

        var suffix = derive.attr("suffix", ""); // Suffix the parent name
        if (suffix.isEmpty()) {
            // Check if they want to append to name of higher one ...
            var tag = getTagName(main);
            var fromOri = derive.attr(tag + "suffix", ""); // Handle both possible vals using this method
            if (!fromOri.isEmpty()) {
                if (derive.saveAndUpRestoreOnFail(tag)) {
                    var name = derive.attr("name", "");
                    derive.restorePoint();
                    derive.currentTrusted().setAttribute("name", name + "_" + fromOri);
                    return main.group() + "_" + derive.attr("name", ""); // Full overwrite
                }
                Logger.error("Failed to get the name of the parent node"); // This should be possible ...
            }
            // Auto generate based on function
            suffix = derive.attr("reducer", "");
            suffix = derive.attr("math", suffix);
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
            var reducer = Reducer.getDoubleReducer(dig.attr("reducer", "avg"), def, window);
            rv = new RealValAggregator(base.group, base.name, base.unit, reducer, window);
            Logger.info("Building RealValAggregator " + rv.id());
        }
        rv.defValue(def);

        return Optional.of(rv);
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
}
