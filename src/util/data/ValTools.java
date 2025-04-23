package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.vals.*;
import util.evalcore.ParseTools;
import util.math.MathUtils;
import util.tools.TimeTools;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class ValTools {

    private final static Pattern words = Pattern.compile("[a-zA-Z]+[_:\\d]*[a-zA-Z\\d]+\\d*");

    /**
     * Checks the exp for any mentions of the numerical rtvals and if found adds these to the nums arraylist and replaces
     * the reference with 'i' followed by the index in nums+offset.
     * The reason for doing this is mainly parse once, use expression many times

     * fe. {r:temp}+30, nums still empty and offset 1: will add the RealVal temp to nums and alter exp to i1 + 30
     * @param exp The expression to check
     * @param nums The Arraylist to hold the numerical values
     * @param offset The index offset to apply
     * @return The altered expression
     */
    public static String buildNumericalMem(Rtvals rtvals, String exp, ArrayList<NumericVal> nums, int offset) {
        if( nums==null)
            nums = new ArrayList<>();

        // Find all the real/flag/int pairs
        var pairs = ParseTools.extractKeyValue(exp, true); // Add those of the format {d:id}

        for( var p : pairs ) {
            boolean ok=false;
            if (p.length != 2) {
                Logger.error("Pair containing odd amount of elements: " + String.join(":", p));
                continue;
            }
            for (int pos = 0; pos < nums.size(); pos++) { // go through the known realVals
                var d = nums.get(pos);
                if (d.id().equalsIgnoreCase(p[1])) { // If a match is found
                    exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + (offset + pos));
                    exp = exp.replace(p[0] + ":" + p[1], "i" + (offset + pos));
                    ok = true;
                    break;
                }
            }
            if (ok)
                continue;

            int index = findOrAddValue(p[1], p[0], rtvals, nums, offset);
            if (index == -1)
                return "";

            exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
            exp = exp.replace(p[0] + ":" + p[1], "i" + index);
        }
        // Figure out the rest?
        var found = words.matcher(exp).results().map(MatchResult::group).toList();
        for( String fl : found){
            if( fl.matches("^i\\d+") )
                continue;
            int index = findOrAddValue( fl, fl.contains("flag:") ? "f" : "d",  rtvals, nums, offset);
            if (index == -1) {
                return "";
            }
            exp = exp.replace(fl, "i" + index);
        }
        nums.trimToSize();
        return exp;
    }

    private static int findOrAddValue(String id, String type, Rtvals rtvals, ArrayList<NumericVal> nums, int offset) {
        int index;

        var value = switch (type) {
            case "d", "double", "r", "real" -> rtvals.getRealVal(id);
            case "int", "i" -> rtvals.getIntegerVal(id);
            case "f", "flag", "b" -> rtvals.getFlagVal(id);
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };

        if (value.isEmpty()) {
            Logger.error("Couldn't find a " + type + " with id " + id);
            return -1; // Or any other sentinel value indicating failure
        }

        NumericVal val = value.get();
        index = nums.indexOf(val);
        if (index == -1) {
            nums.add(val);
            index = nums.size() - 1;
        }
        return index + offset;
    }
    /**
     * Process an expression that contains both numbers and references and figure out the result
     *
     * @param expr The expression to process
     * @param rv The realtimevalues store
     * @return The result or NaN if failed
     */
    public static double processExpression(String expr, Rtvals rv) {
        double result=Double.NaN;

        expr = ValTools.parseRTline(expr,"",rv);
        expr = expr.replace("true","1");
        expr = expr.replace("false","0");

        expr = ValTools.simpleParseRT(expr,"",rv); // Replace all references with actual numbers if possible

        if( expr.isEmpty()) // If any part of the conversion failed
            return result;

        var parts = ParseTools.extractParts(expr);
        if( parts.size()==1 ){
            result = NumberUtils.createDouble(expr);
        }else if (parts.size()==3){
            result = Objects.requireNonNull(ParseTools.decodeDoublesOp(parts.get(0), parts.get(2), parts.get(1), 0)).apply(new double[]{});
        }else{
            try {
                result = MathUtils.noRefCalculation(expr, Double.NaN, false);
            }catch(IndexOutOfBoundsException e){
                Logger.error("Index out of bounds while processing "+expr);
                return Double.NaN;
            }
        }
        if( Double.isNaN(result) )
            Logger.error("Something went wrong processing: "+expr);
        return result;
    }
    /**
     * Stricter version to parse a realtime line, must contain the references within { }
     * Options are:
     * - RealVal: {d:id} and {real:id}
     * - FlagVal: {f:id} or {b:id} and {flag:id}
     * This also checks for {utc}/{utclong},{utcshort} to insert current timestamp
     * @param line The original line to parse/alter
     * @param error Value to put if the reference isn't found
     * @return The (possibly) altered line
     */
    public static String parseRTline(String line, String error, Rtvals rtvals) {

        if( !line.contains("{"))
            return line;

        var pairs = ParseTools.extractKeyValue(line, true);
        for( var p : pairs ){
            if (p.length != 2) {
                line = replaceTime(p[0], line, rtvals);
                continue;
            }
            switch (p[0]) {
                case "d", "r", "double", "real" -> {
                    var d = rtvals.getReal(p[1], Double.NaN);
                    if (!Double.isNaN(d) || !error.isEmpty())
                        line = line.replace("{" + p[0] + ":" + p[1] + "}", Double.isNaN(d) ? error : String.valueOf(d));
                }
                case "i", "int", "integer" -> {
                    var i = rtvals.getIntegerVal(p[1]).map(IntegerVal::asInteger).orElse(Integer.MAX_VALUE);
                    if (i != Integer.MAX_VALUE)
                        line = line.replace("{" + p[0] + ":" + p[1] + "}", String.valueOf(i));
                }
                case "t", "text" -> {
                    String t = rtvals.getTextVal(p[1]).map(TextVal::value).orElse(error);
                    if (!t.isEmpty())
                        line = line.replace("{" + p[0] + ":" + p[1] + "}", t);
                }
                case "f", "b", "flag" -> {
                    var d = rtvals.getFlagVal(p[1]);
                    var r = d.map(FlagVal::toString).orElse(error);
                    if (!r.isEmpty())
                        line = line.replace("{" + p[0] + ":" + p[1] + "}", r);
                }
            }
        }
        var lower = line.toLowerCase();
        if ((lower.contains("{d:") || lower.contains("{r:") ||
                lower.contains("{f:") || lower.contains("{i:")) && !pairs.isEmpty()) {
            Logger.warn("Found a {*:*}, might mean parsing a section of "+line+" failed");
        }
        return line;
    }

    private static String replaceTime(String ref, String line, Rtvals rtvals) {
        return switch(ref){
            case "ref" -> line.replace("{utc}", TimeTools.formatLongUTCNow());
            case "utclong" -> line.replace("{utclong}", TimeTools.formatLongUTCNow());
            case "utcshort"-> line.replace("{utcshort}", TimeTools.formatShortUTCNow());
            default ->
            {
                var val = rtvals.getBaseVal(ref);
                if( val.isPresent())
                    yield line.replace("{" + ref + "}", val.get().id());
                yield line;
            }
        };
    }
    /**
     * Simple version of the parse realtime line, just checks all the words to see if any matches the hashmaps.
     * If anything goes wrong, the 'error' will be returned. If this is set to ignore if something is not found it
     * will be replaced according to the type: real-> NaN, int -> Integer.MAX
     * @param line The line to parse
     * @param error The line to return on an error or 'ignore' if errors should be ignored
     * @return The (possibly) altered line
     */
    public static String simpleParseRT(String line, String error, Rtvals rv) {

        var found = words.matcher(line).results().map(MatchResult::group).toList();
        for( var word : found ){
            // Check if the word contains a : with means it's {d:id} etc, if not it could be anything
            String replacement = word.contains(":") ? findReplacement(rv, word, line, error)
                    : checkRealtimeValues(rv, word, line, error);
            assert replacement != null;
            if (replacement.equalsIgnoreCase(error))
                return error;
            if (!replacement.isEmpty())
                line = line.replace(word, replacement);
        }
        return line;
    }

    private static String findReplacement(Rtvals rv, String word, String line, String error) {
        var id = word.split(":")[1];
        return switch (word.charAt(0)) {
            case 'd', 'r' -> {
                if (!rv.hasReal(id)) {
                    Logger.error("No such real " + id + ", extracted from " + line); // notify
                    if (!error.equalsIgnoreCase("ignore")) // if errors should be ignored
                        yield error;
                }
                yield String.valueOf(rv.getReal(id, Double.NaN));
            }
            case 'i' -> {
                if (!rv.hasInteger(id)) { // ID found
                    Logger.error("No such integer " + id + ", extracted from " + line); // notify
                    if (!error.equalsIgnoreCase("ignore")) // if errors should be ignored
                        yield error;
                }
                yield String.valueOf(rv.getIntegerVal(id).map(IntegerVal::asInteger).orElse(Integer.MAX_VALUE));
            }
            case 'f' -> {
                if (!rv.hasFlag(id)) {
                    Logger.error("No such flag " + id + ", extracted from " + line);
                    if (!error.equalsIgnoreCase("ignore"))
                        yield error;
                }
                yield rv.getFlagState(id) ? "1" : "0";
            }
            case 't', 'T' -> {
                if (!rv.hasText(id)) {
                    if (word.charAt(0) == 'T') {
                        rv.setText(id, "");
                    } else {
                        Logger.error("No such text " + id + ", extracted from " + line);
                        if (!error.equalsIgnoreCase("ignore"))
                            yield error;
                    }
                    yield "";
                } else {
                    yield rv.getTextVal(id).map(TextVal::value).orElse("");
                }
            }
            default -> {
                Logger.error("No such type: " + word.charAt(0));
                yield error;
            }
        };
    }

    private static String checkRealtimeValues(Rtvals rv, String word, String line, String error) {
        if (rv.hasReal(word))  //first check for real
            return String.valueOf(rv.getReal(word, Double.NaN));

        // if not
        if (rv.hasInteger(word)) {
            return String.valueOf(rv.getIntegerVal(word).map(IntegerVal::asInteger).orElse(Integer.MAX_VALUE));
        }
        if (rv.hasText(word)) { //next, try text
            return rv.getTextVal(word).map(TextVal::value).orElse("");
        }
        if (rv.hasFlag(word)) { // if it isn't a text, check if it's a flag
            return rv.getFlagState(word) ? "1" : "0";
        }
        Logger.error("Couldn't process " + word + " found in " + line); // log it and abort
        return error;
    }
}
