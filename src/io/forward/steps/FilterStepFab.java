package io.forward.steps;

import io.telnet.TelnetCodes;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.vals.Rtvals;
import util.evalcore.LogicFab;
import util.math.MathUtils;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Predicate;

public class FilterStepFab {

    public static Optional<FilterStep> buildFilterStep(XMLdigger dig, String delimiter, Rtvals rtvals) {
        return digFilterNode(dig, rtvals, delimiter);
    }

    /**
     * Read the FilterWritable setup from the xml element
     *
     * @param dig The element containing the setup
     * @return True if all went fine
     */
    private static Optional<FilterStep> digFilterNode(XMLdigger dig, Rtvals rtvals, String delimiter) {

        Predicate<String> rules = null;
        var id = dig.attr("id", "");

        // If rules are defined by tagnames that represent types
        if (dig.hasChilds() && !dig.tagName("").equals("if")) {
            rules = processTagTypes(dig, delimiter, rtvals);
            return Optional.of(new FilterStep(id, rules));
        }
        // For a filter without rules or an if tag
        if (!dig.value("").isEmpty() || dig.tagName("").equals("if")) { // If only a single rule is defined
            var type = dig.attr("type", "");
            // If an actual type is given
            if (!type.isEmpty())
                rules = addRule(type, dig.value(""), delimiter, rtvals);
            // If no type attribute nor a check attribute
            for (var att : dig.allAttr().split(",")) {
                if (!(att.equals("id") || att.startsWith("delim") || att.startsWith("src"))) {
                    rules = concatValues(dig.attr(att, "", true), att, delimiter, rtvals);
                }
            }
        }
        if (rules == null)
            return Optional.empty();
        return Optional.of(new FilterStep(id, rules));
    }

    private static Predicate<String> processTagTypes(XMLdigger dig, String delimiter, Rtvals rtvals) {
        var typeDigs = dig.digOut("*");
        boolean prevOr = false, prevAnd = false;
        Predicate<String> rules = null;

        for (var typeDig : typeDigs) {
            delimiter = typeDig.attr("delimiter", delimiter);
            var value = typeDig.value("");

            Predicate<String> pred;
            switch (typeDig.tagName("").toUpperCase()) {
                case "OR":
                    prevOr = true;
                    continue;
                case "AND":
                    prevAnd = true;
                    continue;
                case "RULE":
                    pred = concatValues(value, typeDig.attr("type", ""), delimiter, rtvals);
                    break;
                default:
                    pred = concatValues(value, typeDig.tagName(""), delimiter, rtvals);
            }
            if (pred == null)
                return null;

            if (rules == null) {
                rules = pred;
            }
            if (prevOr) {
                rules = rules.or(pred);
                prevOr = false;
            } else if (prevAnd) {
                rules = rules.and(pred);
                prevAnd = false;
            }
        }
        return rules;
    }

    private static Predicate<String> concatValues(String value, String type, String delimiter, Rtvals rtvals) {
        Predicate<String> pred = null;
        String operator = value.contains(" AND ") ? "AND" : "OR";
        String[] parts = value.split(" " + operator + " ");
        for (String part : parts) {
            Predicate<String> p = addRule(type, part, delimiter, rtvals);
            if (p == null) {
                Logger.error("(ff) -> Unknown type " + type);
                return null;
            }
            pred = (pred == null) ? p : (operator.equals("AND") ? pred.and(p) : pred.or(p));
        }
        return pred;
    }

    /**
     * Add a rule to the filter
     *
     * @param type  predefined type of the filter e.g. start,nostart,end ...
     * @param value The value for the type e.g. start:$GPGGA to start with $GPGGA
     * @return -1 -> unknown type, 1 if ok
     */
    private static Predicate<String> addRule(String type, String value, String delimiter, Rtvals rtvals) {
        String[] values = value.split(",");

        value = Tools.fromEscapedStringToBytes(value);
        Logger.info(" -> Adding rule " + type + " > " + value);

        try {
            return switch (StringUtils.removeEnd(type, "s")) {
                case "item" ->
                        createItemCount(delimiter, Tools.parseInt(values[0], -1), Tools.parseInt(values.length == 1 ? values[0] : values[1], -1));
                case "maxitem" -> createItemMaxCount(delimiter, Tools.parseInt(value, -1));
                case "minitem" -> createItemMinCount(delimiter, Tools.parseInt(value, -1));
                case "start" -> createStartsWith(value);
                case "nostart", "!start" -> createStartsNotWith(value);
                case "end" -> createEndsWith(value);
                case "contain", "include" -> createContains(value);
                case "!contain" -> createContainsNot(value);
                case "c_start" -> createCharAt(Tools.parseInt(values[0], -1) - 1, value.charAt(value.indexOf(",") + 1));
                case "c_end" ->
                        createCharFromEnd(Tools.parseInt(values[0], -1) - 1, value.charAt(value.indexOf(",") + 1));
                case "minlength" -> createMinimumLength(Tools.parseInt(value, -1));
                case "maxlength" -> createMaximumLength(Tools.parseInt(value, -1));
                case "nmea" -> createNMEAcheck(Tools.parseBool(value, true));
                case "regex" -> createRegex(value);
                case "logic" -> createLogicEvaluator(delimiter, value, rtvals);
                default -> {
                    if (type.startsWith("at")) {
                        var in = type.substring(2);
                        int index = NumberUtils.toInt(in, -1);
                        if (index != -1) {
                            yield createItemAtIndex(index, delimiter, value);
                        }
                    }
                    Logger.error(" -> Unknown type chosen " + type);
                    yield null;
                }
            };
        }catch (NumberFormatException | IndexOutOfBoundsException e ){
            Logger.error("Failed to create filter rule: "+e.getMessage());
            return null;
        }
    }

    /* Filters */
    private static Predicate<String> createItemAtIndex(int index, String deli, String val) {
        return (p -> {
            var items = p.split(deli);
            return items.length > index && items[index].equals(val);
        });
    }

    private static Predicate<String> createItemCount(String deli, int min, int max) {
        return (p -> {
            var items = p.split(deli);
            return items.length >= min && items.length <= max;
        });
    }

    private static Predicate<String> createItemMinCount(String deli, int min) {
        return (p -> p.split(deli).length >= min);
    }

    private static Predicate<String> createItemMaxCount(String deli, int max) {
        return (p -> p.split(deli).length <= max);
    }

    private static Predicate<String> createStartsWith(String with) {
        return (p -> p.startsWith(with));
    }

    private static Predicate<String> createRegex(String regex) {
        return (p -> p.matches(regex));
    }

    private static Predicate<String> createStartsNotWith(String with) {
        return (p -> !p.startsWith(with));
    }

    private static Predicate<String> createContains(String contains) {
        return (p -> p.contains(contains));
    }

    private static Predicate<String> createContainsNot(String contains) {
        return (p -> !p.contains(contains));
    }

    private static Predicate<String> createEndsWith(String with) {
        return (p -> p.endsWith(with));
    }

    private static Predicate<String> createCharAt(int index, char c) {
        return (p -> index >= 0 && index < p.length() && p.charAt(index) == c);
    }

    private static Predicate<String> createCharFromEnd(int index, char c) {
        return (p -> index >= 0 && p.length() > index && p.charAt(p.length() - index - 1) == c);
    }

    private static Predicate<String> createMinimumLength(int length) {
        return (p -> p.length() >= length);
    }

    private static Predicate<String> createMaximumLength(int length) {
        return (p -> p.length() <= length);
    }

    private static Predicate<String> createNMEAcheck(boolean ok) {
        return (p -> (MathUtils.doNMEAChecksum(p)) == ok);
    }

    /**
     * Creates a logic evaluator predicate based on the given expression and delimiter.
     *
     * @param delimiter The delimiter used to split the input string.
     * @param expression The logic expression to be parsed.
     * @param rtvals The global real-time values collection to get references from
     * @return A predicate that evaluates the logic expression, or null if the evaluator couldn't be created.
     */
    private static Predicate<String> createLogicEvaluator(String delimiter, String expression, Rtvals rtvals) {
        // Create logic evaluator
        var logEval = LogicFab.parseComparison(expression, rtvals, null);
        if (logEval.isEmpty()) {
            Logger.error("Failed to build logic evaluator");
            return null;
        }

        var le = logEval.get();
        // Return predicate using the logic evaluator
        return p -> le.eval(p, delimiter).orElse(false);
    }

    public static String getHelp(String eol) {
        StringJoiner join = new StringJoiner(eol);
        var gr = TelnetCodes.TEXT_GREEN;
        var re = TelnetCodes.TEXT_DEFAULT;
        join.add(gr + "items" + re + " -> How many items are  there after split on delimiter")
                .add("    fe. <filter type='items' delimiter=';'>2,4</filter> --> Item count of two up to 4 (so 2,3,4 are ok)")
                .add("    fe. <filter type='items'>2</filter> --> Item count of two using default delimiter");
        join.add(gr + "start" + re + " -> Which text the data should start with")
                .add("    fe. <filter type='start'>$</filter> --> The data must start with $");
        join.add(gr + "nostart" + re + " -> Which text the data can't start with")
                .add("    fe. <filter type='nostart'>$</filter> --> The data can't start with $");
        join.add(gr + "end" + re + " -> Which text the data should end with")
                .add("    fe. <filter type='end'>!?</filter> --> The data must end with !?");
        join.add(gr + "contain" + re + " -> Which text the data should contain")
                .add("    fe. <filter type='contain'>zda</filter> --> The data must contain zda somewhere");
        join.add(gr + "c_start" + re + " -> Which character should be found on position c from the start (1=first)")
                .add("    fe. <filter type='c_start'>1,+</filter> --> The first character must be a +");
        join.add(gr + "c_end" + re + " -> Which character should be found on position c from the end (1=last)")
                .add("    fe. <filter type='c_end'>3,+</filter> --> The third last character must be a +");
        join.add(gr + "minlength" + re + " -> The minimum length the data should be")
                .add("    fe. <filter type='minlength'>6</filter> --> if data is shorter than 6 chars, filter out");
        join.add(gr + "maxlength" + re + " -> The maximum length the data can be")
                .add("    fe.<filter type='maxlength'>10</filter>  --> if data is longer than 10, filter out");
        join.add(gr + "nmea" + re + " -> True or false that it's a valid nmea string")
                .add("    fe. <filter type='nmea'>true</filter> --> The data must end be a valid nmea string");
        join.add(gr + "regex" + re + " -> Matches the given regex")
                .add("    fe. <filter type='regex'>\\s[a,A]</filter> --> The data must contain an empty character followed by a in any case");
        join.add(gr + "math" + re + " -> Checks a mathematical comparison")
                .add("    fe. <filter type='math' delimiter=','>i1 below 2500 and i1 above 10</filter>");
        join.add(gr + "match" + re + " -> Compare to the item at index x")
                .add("    fe. <filter at1='test'> ");
        return join.toString();
    }
}
