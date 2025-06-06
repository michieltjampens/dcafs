package util.evalcore;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.procs.DoubleArrayToDouble;
import util.data.vals.NumericVal;
import util.data.vals.Rtvals;
import util.math.MathUtils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class ParseTools {
    static final Pattern es = Pattern.compile("\\de[+-]?\\d");
    static final MathContext MATH_CONTEXT = new MathContext(10, RoundingMode.HALF_UP);
    private static final Pattern I_REF_PATTERN = Pattern.compile("(?<![a-zA-Z0-9_])i[0-9]{1,2}(?![a-zA-Z0-9_])");
    private static final Pattern T_REF_PATTERN = Pattern.compile("(?<![a-zA-Z0-9_])i[0-9]{1,2}(?![a-zA-Z0-9_])");
    private static final String[] ops = {"+", "-", "*", "/", "%", "^"};

    public static Evaluator parseComparison(String exp, Rtvals rtvals, List<NumericVal> oldRefs) {
        var test = exp.startsWith("-") ? exp.substring(1) : exp; // Remove leading - if any
        test = test.replace("<-", "<");
        test = test.replace(">-", ">");

        boolean math = false;
        if (!test.contains("&&") && !test.contains("||")) {
            for (var op : ops) {
                if (test.contains(op)) {
                    math = true;
                    break;
                }
            }
        }
        if (math)
            return MathFab.parseExpression(exp, rtvals, null);
        return LogicFab.parseComparison(exp, rtvals, oldRefs).orElse(null);
    }
    public static List<String> extractParts(String expression) {

        var list = new ArrayList<String>();
        var ops = "+-/*^%°<>=~!&";

        expression = normalizeScientificNotation(expression);

        var build = new StringBuilder();
        for (var it : expression.toCharArray()) {
            if (ops.indexOf(it) == -1 || build.isEmpty()) {
                if( !list.isEmpty() && build.isEmpty() ){
                    var old = list.get(list.size() - 1); // Get previous stored
                    if ("<>=$|&!".contains(old) && "=|&".indexOf(it) != -1) { // Check if it's an operand
                        list.set(list.size()-1,old+it);
                        continue;
                    }
                }
                build.append(it);
            } else {
                list.add(build.toString().replace("e", "E-"));
                build.setLength(0);
                list.add(String.valueOf(it));
            }
        }
        if (!build.isEmpty()) {
            list.add(build.toString().replace("e", "E-"));
        } else {
            Logger.error("Found an operand at the end of the expression? " + expression);
            return List.of();
        }
        fixNegativeExponent(list);
        return list;
    }
    /**
     * The problem with scientific notation is that they can be mistaken for a combination of a word and numbers
     * especially the negative one can be seen as an operation instead.
     *
     * @param expression The expression to fix
     * @return Expression that has numbers with negative exp use e and positive use E instead
     */
    private static String normalizeScientificNotation(String expression) {
        var ee = es.matcher(expression) // find the numbers with scientific notation
                .results()
                .map(MatchResult::group)
                .distinct().toList();

        for (String el : ee) { // Replace those with uppercase so they all use the same format
            expression = expression.replace(el, el.toUpperCase());
        }
        // Replace the negative ones with a e and the positive ones with E to remove the sign
        String alt = expression.replace("E-", "e");
        return alt.replace("E+", "E");
    }
    private static void fixNegativeExponent(ArrayList<String> list) {

        for (int index = 0; index < list.size(); index++) {
            if (!list.get(index).equals("^"))
                continue;
            var exponent = list.get(index + 1);
            if (!exponent.startsWith("-"))
                continue;
            list.set(index + 1, exponent.substring(1)); // Fix the exponent
            list.add(index - 1, "/");
            list.add(index - 1, "1");
        }
    }
    public static int[] extractIreferences(String expression) {
        return extractreferences(expression, I_REF_PATTERN);// Extract all the references
    }

    public static int[] extractTreferences(String expression) {
        return extractreferences(expression, T_REF_PATTERN);// Extract all the references
    }

    private static int[] extractreferences(String expression, Pattern pattern) {
        return pattern// Extract all the references
                .matcher(expression)
                .results()
                .map(MatchResult::group)
                .distinct()
                .sorted() // so the highest one is at the bottom
                .mapToInt(i -> NumberUtils.toInt(i.substring(1)))
                .toArray();
    }
    /**
     * Check if the brackets used in the expression are correct (meaning same amount of opening as closing and no closing
     * if there wasn't an opening one before
     * @param expression The expression to check
     * @return The formula with enclosing brackets added if none were present or an empty string if there's an error
     */
    public static String checkBrackets(String expression, char openChar, char closeChar, boolean addEnclosing) {
        boolean totalEnclosing = true;
        // No total enclosing brackets
        int cnt=0;
        for (int a = 0; a < expression.length(); a++) {
            if (expression.charAt(a) == openChar) {
                cnt++;
            } else if (expression.charAt(a) == closeChar) {
                cnt--;
            }
            if (cnt == 0 && a < expression.length() - 1)
                totalEnclosing = false;
            if( cnt < 0 ) { // if this goes below zero, an opening bracket is missing
                Logger.error("Found closing bracket without opening in " + expression + " at " + a);
                return "";
            }
        }
        if( cnt != 0 ) {
            Logger.error("Unclosed bracket in " + expression);
            return "";
        }
        if (!totalEnclosing && addEnclosing) // Add enclosing brackets
            expression = "(" + expression + ")";
        return expression;
    }
    public static boolean hasTotalEnclosingBrackets( String expression, char openChar, char closeChar ){
        int cnt=0;
        boolean hasBrackets=false;
        for (int a = 0; a < expression.length(); a++) {
            if (expression.charAt(a) == openChar) {
                cnt++;
                hasBrackets=true;
            } else if (expression.charAt(a) == closeChar) {
                cnt--;
            }
            if (cnt == 0 && a == expression.length() - 1 && hasBrackets )
                return true;
            if( cnt < 0 ) { // if this goes below zero, an opening bracket is missing
                Logger.error("Found closing bracket without opening in " + expression + " at " + a);
                return false;
            }
        }
        return false;
    }
    /**
     * Extract key-value pairs from a string that contains data wrapped in curly braces.
     * The key-value pairs are expected to be in the format of {key:value}, where keys
     * and values are separated by a colon (":").
     *
     * @param data     The string to parse containing key-value pairs wrapped in curly braces.
     * @param distinct If true, only unique key-value pairs are returned. If false, all pairs are included.
     * @return A list of string arrays where each array contains two elements: the key and the value.
     * An empty list is returned if no valid key-value pairs are found.
     */
    public static List<String[]> extractKeyValue(String data, boolean distinct) {
        var keyValuePairs = new ArrayList<String[]>();
        var matches = extractCurlyContent(data, distinct);
        for (var match : matches) {
            String[] kv = match.split(":", 2); // split into key and value only
            if (kv.length == 2) {
                keyValuePairs.add(kv);
            } else {
                Logger.error("Invalid key-value pair: {" + match + "}");
            }
        }
        return keyValuePairs;
    }

    public static String replaceRealtimeValues(String expression, ArrayList<NumericVal> refs, Rtvals rtvals, ArrayList<Integer> refLookup) {

        // Do processing as normal
        var bracketVals = ParseTools.extractCurlyContent(expression, true);
        if (bracketVals == null) {
            Logger.error("Failed to extract curly brackets from " + expression);
            return "";
        }
        if (bracketVals.isEmpty())
            return expression;

        if (rtvals == null) {
            Logger.warn("Couldn't replace rtvals refs because rtvals is null when parsing: " + expression);
            return "";
        }

        for (var val : bracketVals) {
            var result = rtvals.getBaseVal(val);
            if (result.isEmpty()) {
                Logger.error("No such rtval yet: " + val);
                return "";
            } else if (result.get() instanceof NumericVal nv) {
                int size = refLookup.size();
                expression = expression.replace("{" + val + "}", "r" + size);
                refLookup.add(100 + refs.size());
                refs.add(nv);
            } else {
                Logger.error("Can't work with " + result.get() + " NOT a numeric val.");
                return "";
            }
        }
        return expression;
    }
    /**
     * Extracts content enclosed within curly braces ('{...}') from the provided data string.
     * Optionally, it can filter out duplicate matches based on the `distinct` flag.
     *
     * @param data     The string containing the content to extract.
     * @param distinct If true, only unique content within curly braces will be included.
     *                 If false, duplicates will be included.
     * @return A list of strings, each representing the content found within curly braces.
     * If no content is found, returns an empty list.
     */
    public static List<String> extractCurlyContent(String data, boolean distinct) {
        var contents = new ArrayList<String>();
        data = ParseTools.checkBrackets(data, '{', '}', false);
        if (data.isEmpty())
            return List.of();

        var pattern = Pattern.compile("\\{([^{}]+)}");
        var matcher = pattern.matcher(data);

        while (matcher.find()) {
            var match = matcher.group(1); // what's inside the curly braces
            if (!contents.contains(match)) { // If not present yet, add it
                contents.add(match);
            } else if (!distinct) { // If already present but we don't mind duplicates
                contents.add(match);
            }
        }
        return contents.stream().toList();
    }

    // Functional interface to represent a mathematical operation (e.g., +, -, *, /)
// Takes two BigDecimals and returns a BigDecimal result
    @FunctionalInterface
    interface BigDecimalBiFunction {
        BigDecimal apply(BigDecimal bd1, BigDecimal bd2);
    }

    // This method will be called *once* when 'proc' is assigned,
    // based on the operator string (op) and the determined operand types.
    private static Function<BigDecimal[], BigDecimal> createOperationLambda(
            String op,
            boolean numberNumber, boolean indexNumber, boolean numberIndex,
            BigDecimal bd1, BigDecimal bd2, int i1, int i2) { // Pass in context variables

        // Define the core operation based on 'op'
        final BigDecimalBiFunction mathOp = switch (op) {
            // --- Comparison Operators ---
            case ">" -> ((a, b) -> a.compareTo(b) > 0 ? BigDecimal.ONE : BigDecimal.ZERO);
            case "<" -> ((a, b) -> a.compareTo(b) < 0 ? BigDecimal.ONE : BigDecimal.ZERO);
            case ">=" -> ((a, b) -> a.compareTo(b) >= 0 ? BigDecimal.ONE : BigDecimal.ZERO);
            case "<=" -> ((a, b) -> a.compareTo(b) <= 0 ? BigDecimal.ONE : BigDecimal.ZERO);
            case "==" ->
                    ((a, b) -> a.compareTo(b) == 0 ? BigDecimal.ONE : BigDecimal.ZERO);// Use compareTo for numerical equality
            case "!=" -> ((a, b) -> a.compareTo(b) != 0 ? BigDecimal.ONE : BigDecimal.ZERO);

            // --- Math Operators ---
            case "+" -> BigDecimal::add; // Use method references for clarity
            case "-" -> BigDecimal::subtract;
            case "*" -> BigDecimal::multiply;
            case "/" -> (a, b) -> a.divide(b, MATH_CONTEXT);
            case "%" -> BigDecimal::remainder;
            case "scale" -> (a, b) -> a.setScale(b.intValue(), RoundingMode.HALF_UP);
            default -> {
                Logger.error("Unsupported operator: " + op);
                yield null;
            }
        };

        // Now, return the appropriate lambda for 'proc' based on operand types,
        // applying the core operation defined above.
        if (mathOp != null) { // It's a mathematical operator
            if (numberNumber) {
                return x -> mathOp.apply(bd1, bd2);
            } else if (indexNumber) {
                return x -> mathOp.apply(x[i1], bd2);
            } else if (numberIndex) {
                return x -> mathOp.apply(bd1, x[i2]);
            } else {
                return x -> mathOp.apply(x[i1], x[i2]);
            }
        } else {

        }

        // Should ideally not be reached if operator and type combinations are exhaustive
        throw new IllegalStateException("Unhandled operator or operand type combination: " + op);
    }
    /**
     * Converts a simple operation (only two operands) on elements in an array to a function
     * @param first The first element of the operation
     * @param second The second element of the operation
     * @param op The operator to apply
     * @param offset The offset for the index in the array
     * @return The result of the calculation
     */
    public static Function<BigDecimal[],BigDecimal> decodeBigDecimalsOp(String first, String second, String op, int offset ){

        final BigDecimal bd1;
        final int i1;
        final BigDecimal bd2 ;
        final int i2;

        try{
            if(NumberUtils.isCreatable(first) ) {
                bd1 = NumberUtils.createBigDecimal(first);
                i1=-1;
            }else{
                bd1=null;
                int index = NumberUtils.createInteger( first.substring(1));
                i1 = first.startsWith("o") ? index + offset : index;
            }
            if(NumberUtils.isCreatable(second) ) {
                bd2 = NumberUtils.createBigDecimal(second);
                i2=-1;
            }else{
                bd2=null;
                int index = NumberUtils.createInteger( second.substring(1));
                i2 = second.startsWith("o") ? index + offset : index;
            }
        }catch( NumberFormatException e){
            Logger.error("Something went wrong decoding: "+first+" or "+second);
            return null;
        }
        final boolean numberNumber = bd1 != null && bd2 != null;
        final boolean indexNumber = bd1 == null && bd2 != null;
        Function<BigDecimal[],BigDecimal> proc=null;
        switch( op ){
            case "+":
                try {
                    if (numberNumber) { // meaning both numbers
                        var p = bd1.add(bd2);
                        proc = x -> p;
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].add(bd2);
                    } else if (bd1 != null) { // meaning first is a number and second an index
                        proc = x -> bd1.add(x[i2]);
                    } else { // meaning both indexes
                        proc = x -> x[i1].add(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "-":
                try{
                    if (numberNumber) { // meaning both numbers
                        var p = bd1.subtract(bd2);
                        proc = x -> p;
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].subtract(bd2);
                    }else if(bd1 != null){ // meaning first is a number and second an index
                        proc = x -> bd1.subtract(x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].subtract(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "*":
                try{
                    if (numberNumber) { // meaning both numbers
                        proc = x -> bd1.multiply(bd2);
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].multiply(bd2);
                    }else if(bd1 != null){ // meaning first is a number and second an index
                        proc = x -> bd1.multiply(x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].multiply(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;

            case "/": // i0/25
                try {
                    if (numberNumber) { // meaning both numbers
                        if (bd2.equals(BigDecimal.ZERO))
                            Logger.warn("Requested to divide by zero for " + (first + op + second));
                        proc = x -> bd1.divide(bd2, MATH_CONTEXT);
                    } else if (indexNumber) {
                        // meaning first is an index and second a number
                        if (bd2.equals(BigDecimal.ZERO))
                            Logger.warn("Requested to divide by zero for " + (first + op + second));
                        proc = x -> x[i1].divide(bd2, MATH_CONTEXT);
                    } else if (bd1 != null) { //  meaning first is a number and second an index
                        proc = x -> bd1.divide(x[i2], MATH_CONTEXT);
                    } else { // meaning both indexes
                        proc = x -> x[i1].divide(x[i2], MATH_CONTEXT);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;

            case "%": // i0%25
                try{
                    if (numberNumber) { // meaning both numbers
                        proc = x -> bd1.remainder(bd2);
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].remainder(bd2);
                    }else if(bd1 != null){ //  meaning first is a number and second an index
                        proc = x -> bd1.remainder(x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].remainder(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "^": // i0/25
                try{
                    if (numberNumber) { // meaning both numbers
                        proc = x -> bd1.pow(bd2.intValue());
                    } else if (indexNumber) { // meaning first is an index and second a number
                        if( bd2.compareTo(BigDecimal.valueOf(0.5)) == 0){ // root
                            proc = x -> x[i1].sqrt(MathContext.DECIMAL64);
                        }else{
                            proc = x -> x[i1].pow(bd2.intValue());
                        }
                    }else if(bd1 != null){ //  meaning first is a number and second an index
                        proc = x -> bd1.pow(x[i2].intValue());
                    }else{ // meaning both indexes
                        proc = x -> x[i1].pow(x[i2].intValue());
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "~": // i0~25 -> ABS(i0-25)
                try{
                    if (numberNumber) { // meaning both numbers
                        proc = x -> bd1.min(bd2).abs();
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].min(bd2).abs();
                    }else if(bd1 != null){ //  meaning first is a number and second an index
                        proc = x -> bd1.min(x[i2]).abs();
                    }else{ // meaning both indexes
                        proc = x -> x[i1].min(x[i2]).abs();
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "scale": // i0/25
                try{
                    if (numberNumber) { // meaning both numbers
                        proc = x -> bd1.setScale(bd2.intValue(), RoundingMode.HALF_UP);
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].setScale(bd2.intValue(),RoundingMode.HALF_UP);
                    }else if(bd1 != null){ //  meaning first is a number and second an index
                        proc = x -> bd1.setScale(x[i2].intValue(),RoundingMode.HALF_UP);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].setScale(x[i2].intValue(),RoundingMode.HALF_UP);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "ln":
                try{
                    if (numberNumber) { // meaning both numbers
                        Logger.error("Todo - ln bd,bd");
                    } else if (indexNumber) { // meaning first is an index and second a number
                        Logger.error("Todo - ln ix,bd");
                    }else if(bd1 != null){ //  meaning first is a number and second an index
                        proc = x -> BigDecimal.valueOf(Math.log(x[i2].doubleValue()));
                    }else{ // meaning both indexes
                        proc = x -> BigDecimal.valueOf(Math.log(x[i2].doubleValue()));
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "°":
                switch( Objects.requireNonNull(bd1).intValue() ){
                    case 1: //cosd,sin
                        if( bd2==null) {
                            proc = x -> BigDecimal.valueOf(Math.cos(Math.toRadians(x[i2].doubleValue())));
                        }else {
                            var rad = BigDecimal.valueOf(Math.cos(Math.toRadians(bd2.doubleValue())));
                            proc = x -> rad;
                        }
                        break;
                    case 2: //cosr
                        if( bd2==null) {
                            proc = x -> BigDecimal.valueOf(Math.sin(x[i2].doubleValue()));
                        }else {
                            var res = BigDecimal.valueOf(Math.cos(bd2.doubleValue()));
                            proc = x -> res;
                        }
                        break;
                    case 3: //sind,sin
                        if( bd2==null) {
                            proc = x -> BigDecimal.valueOf(Math.sin(Math.toRadians(x[i2].doubleValue())));
                        }else {
                            var rad = BigDecimal.valueOf(Math.sin(Math.toRadians(bd2.doubleValue())));
                            proc = x -> rad;
                        }
                        break;
                    case 4: //sinr
                        if( bd2==null) {
                            proc = x -> BigDecimal.valueOf(Math.sin(x[i2].doubleValue()));
                        }else {
                            var res = BigDecimal.valueOf(Math.sin(bd2.doubleValue()));
                            proc = x -> res;
                        }
                        break;
                    case 5: //abs
                        if( bd2==null) {
                            proc = x -> x[i2].abs();
                        }else {
                            proc = x -> bd2.abs();
                        }
                        break;
                }
                break;
            case "<":
                try {
                    if (numberNumber) { // meaning both numbers
                        proc = x -> bd1.compareTo(bd2) < 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].compareTo(bd2) < 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (bd1 != null) { //  meaning first is a number and second an index
                        proc = x -> bd1.compareTo(x[i2]) < 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else {// meaning both indexes
                        proc = x -> x[i1].compareTo(x[i2]) < 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
                break;
            case ">":
                try {
                    if (numberNumber) { // meaning both numbers
                        proc = x -> bd1.compareTo(bd2) > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].compareTo(bd2) > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (bd1 != null) { //  meaning first is a number and second an index
                        proc = x -> bd1.compareTo(x[i2]) > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else {// meaning both indexes
                        proc = x -> x[i1].compareTo(x[i2]) > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
                break;
            case "<=":
                try {
                    if (numberNumber) { // meaning both numbers
                        proc = x -> bd1.compareTo(bd2) <= 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].compareTo(bd2) <= 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (bd1 != null) { //  meaning first is a number and second an index
                        proc = x -> bd1.compareTo(x[i2]) <= 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else {// meaning both indexes
                        proc = x -> x[i1].compareTo(x[i2]) <= 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
                break;
            case ">=":
                try {
                    if (numberNumber) { // meaning both numbers
                        proc = x -> bd1.compareTo(bd2) >= 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].compareTo(bd2) >= 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (bd1 != null) { //  meaning first is a number and second an index
                        proc = x -> bd1.compareTo(x[i2]) >= 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else {// meaning both indexes
                        proc = x -> x[i1].compareTo(x[i2]) >= 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
                break;
            case "==":
                try {
                    if (numberNumber) { // meaning both numbers
                        proc = x -> bd1.compareTo(bd2) == 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].compareTo(bd2) == 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (bd1 != null) { //  meaning first is a number and second an index
                        proc = x -> bd1.compareTo(x[i2]) == 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else {// meaning both indexes
                        proc = x -> x[i1].compareTo(x[i2]) == 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
                break;
            case "!=":
                try {
                    if (numberNumber) { // meaning both numbers
                        proc = x -> bd1.compareTo(bd2) != 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (indexNumber) { // meaning first is an index and second a number
                        proc = x -> x[i1].compareTo(bd2) != 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else if (bd1 != null) { //  meaning first is a number and second an index
                        proc = x -> bd1.compareTo(x[i2]) != 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    } else {// meaning both indexes
                        proc = x -> x[i1].compareTo(x[i2]) != 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
                break;
            default:Logger.error("Unknown operand: "+op); break;
        }
        return proc;
    }
    /**
     * Converts a simple operation (only two operands) on elements in an array to a function
     * @param first The first element of the operation
     * @param second The second element of the operation
     * @param op The operator to apply
     * @param offset The offset for the index in the array
     * @return The function resulting from the above parameters
     */
    public static DoubleArrayToDouble decodeDoublesOp(String first, String second, String op, int offset) {

        final double db1;
        final int i1;
        final double db2;
        final int i2;

        boolean reverse = first.startsWith("!");

        if( reverse ) {
            op = "!";
            first=first.substring(1);
            second="";
        }else if( op.equalsIgnoreCase("!")){
            first = second.replace("!","");
            second="";
        }

        try{
            if(NumberUtils.isCreatable(first) ) {
                db1 = NumberUtils.createDouble(first);
                i1=-1;
            }else{
                db1 = Double.NaN;
                int index = NumberUtils.createInteger( first.substring(1));
                i1 = first.startsWith("o")?index:index+offset;
            }
            if(second.isEmpty()) {
                if( op.equals("!")){
                    return x -> Double.compare(x[i1],1)>=0?0.0:1.0;
                }else{
                    return i1 == -1 ? x -> db1 : x -> x[i1];
                }
            }
            if(NumberUtils.isCreatable(second) ) {
                db2 = NumberUtils.createDouble(second);
                i2=-1;
            }else{
                db2 = Double.NaN;
                int index = NumberUtils.createInteger( second.substring(1));
                i2 = second.startsWith("o")?index:index+offset;
            }
            if(first.isEmpty())
                return i2==-1?x->db2:x->x[i2];

        }catch( NumberFormatException e){
            Logger.error("Something went wrong decoding: "+first+" or "+second+ "with "+op+" -> "+e.getMessage());
            return null;
        }


        final boolean numberNumber = !Double.isNaN(db1) && !Double.isNaN(db2);
        final boolean indexNumber = Double.isNaN(db1) && !Double.isNaN(db2);
        switch (op) {
            //case "!" -> x -> (Double.compare( x[i1], 1) >= 0 ? 0.0 : 1.0);
            case "+" -> {
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> db1 + db2;
                    if (indexNumber)  // meaning first is an index and second a number
                        return x -> x[i1] + db2;
                    if (!Double.isNaN(db1))  // meaning first is a number and second an index
                        return x -> db1 + x[i2];
                    return x -> x[i1] + x[i2];// meaning both indexes
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "-" -> {
                try {
                    if (numberNumber)  // meaning both numbers
                        return x -> db1 - db2;
                    if (indexNumber)  // meaning first is an index and second a number
                        return x -> x[i1] - db2;
                    if (!Double.isNaN(db1))  // meaning first is a number and second an index
                        return x -> db1 - x[i2];
                    // meaning both indexes
                    return x -> x[i1] - x[i2];
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "*" -> {
                try {
                    if (numberNumber)  // meaning both numbers
                        return x -> db1 * db2;
                    if (indexNumber)  // meaning first is an index and second a number
                        return x -> x[i1] * db2;
                    if (!Double.isNaN(db1))  // meaning first is a number and second an index
                        return x -> db1 * x[i2];
                    // meaning both indexes
                    return x -> x[i1] * x[i2];
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "/" -> { // i0/25
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> db1 / db2;
                    if (indexNumber) // meaning first is an index and second a number
                        return x -> x[i1] / db2;
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> db1 / x[i2];
                    { // meaning both indexes
                        return x -> x[i1] / x[i2];
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "%" -> { // i0%25
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> db1 % db2;
                    if (indexNumber) // meaning first is an index and second a number
                        return x -> x[i1] % db2;
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> db1 % x[i2];
                    { // meaning both indexes
                        return x -> x[i1] % x[i2];
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "^" -> { // i0^2
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> Math.pow(db1, db2);
                    if (indexNumber) // meaning first is an index and second a number
                        if (Double.compare(db2, 0.5) == 0) { // root
                            return x -> Math.sqrt(x[i1]);
                        } else {
                            return x -> Math.pow(x[i1], db2);
                        }
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> Math.pow(db1, x[i2]);
                    // meaning both indexes
                    return x -> Math.pow(x[i1], x[i2]);
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "scale" -> { // i0/25
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> MathUtils.roundDouble(db1, (int) db2);
                    if (indexNumber) // meaning first is an index and second a number
                        return x -> MathUtils.roundDouble(x[i1], (int) db2);
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> MathUtils.roundDouble(db1, (int) x[i2]);
                    // meaning both indexes
                    return x -> MathUtils.roundDouble(x[i1], (int) x[i2]);
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "diff", "~" -> {
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> Math.abs(db1 - db2);
                    if (indexNumber) // meaning first is an index and second a number
                        return x -> Math.abs(x[i1] - db2);
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> Math.abs(db1 - x[i2]);
                    { // meaning both indexes
                        return x -> Math.abs(x[i1] - x[i2]);
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "ln" -> {
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> Math.log(db2); // Todo - ln bd,bd
                    if (indexNumber) // meaning first is an index and second a number
                        return x -> Math.log(db2);
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> Math.log(x[i2]);
                    // meaning both indexes
                    return x -> Math.log(x[i2]);

                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "<" -> {
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> Double.compare(db1, db2) < 0 ? 1.0 : 0.0;
                    if (indexNumber) // meaning first is an index and second a number
                        return x -> Double.compare(x[i1], db2) < 0 ? 1.0 : 0.0;
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> Double.compare(db1, x[i2]) < 0 ? 1.0 : 0.0;
                    // meaning both indexes
                    return x -> Double.compare(x[i1], x[i2]) < 0 ? 1.0 : 0.0;
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case ">" -> {
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> Double.compare(db1, db2) > 0 ? 1.0 : 0.0;
                    if (indexNumber) // meaning first is an index and second a number
                        return x -> Double.compare(x[i1], db2) > 0 ? 1.0 : 0.0;
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> Double.compare(db1, x[i2]) > 0 ? 1.0 : 0.0;
                    // meaning both indexes
                    return x -> Double.compare(x[i1], x[i2]) > 0 ? 1.0 : 0.0;
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "<=" -> {
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> Double.compare(db1, db2) <= 0 ? 1.0 : 0.0;
                    if (indexNumber) // meaning first is an index and second a number
                        return x -> Double.compare(x[i1], db2) <= 0 ? 1.0 : 0.0;
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> Double.compare(db1, x[i2]) <= 0 ? 1.0 : 0.0;
                    // meaning both indexes
                    return x -> Double.compare(x[i1], x[i2]) <= 0 ? 1.0 : 0.0;
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case ">=" -> {
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> Double.compare(db1, db2) >= 0 ? 1.0 : 0.0;
                    if (indexNumber) // meaning first is an index and second a number
                        return x -> Double.compare(x[i1], db2) >= 0 ? 1.0 : 0.0;
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> Double.compare(db1, x[i2]) >= 0 ? 1.0 : 0.0;
                    // meaning both indexes
                    return x -> Double.compare(x[i1], x[i2]) >= 0 ? 1.0 : 0.0;
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "==" -> {
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> Double.compare(db1, db2) == 0 ? 1.0 : 0.0;
                    if (indexNumber) // meaning first is an index and second a number
                        return x -> Double.compare(x[i1], db2) == 0 ? 1.0 : 0.0;
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> Double.compare(db1, x[i2]) == 0 ? 1.0 : 0.0;
                    // meaning both indexes
                    return x -> Double.compare(x[i1], x[i2]) == 0 ? 1.0 : 0.0;
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "!=" -> {
                try {
                    if (numberNumber) // meaning both numbers
                        return x -> Double.compare(db1, db2) != 0 ? 1.0 : 0.0;
                    if (indexNumber) // meaning first is an index and second a number
                        return x -> Double.compare(x[i1], db2) != 0 ? 1.0 : 0.0;
                    if (!Double.isNaN(db1)) //  meaning first is a number and second an index
                        return x -> Double.compare(db1, x[i2]) != 0 ? 1.0 : 0.0;
                    // meaning both indexes
                    return x -> Double.compare(x[i1], x[i2]) != 0 ? 1.0 : 0.0;
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            default -> Logger.error("Unknown operand: " + op);
        }
        return null;
    }
}
