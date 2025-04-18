package util.math;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MathUtils {
    final static int DIV_SCALE = 8;
    static final String[] ORDERED_OPS={"°","°","^","^","*","/","%","%","+","-"};
    static final String[] COMPARES={"<","<=","==","!=",">=",">"};
    static final String OPS_REGEX= "[+\\-/*<>^=%~!°]+=?";
    static final Pattern es = Pattern.compile("\\de[+-]?\\d");
    static final MathContext MATH_CONTEXT = new MathContext(10, RoundingMode.HALF_UP);
    /**
     * Splits a simple expression of the type i1+125 etc. into distinct parts i1,+,125
     * @param expression The expression to split
     * @param indexOffset The offset to apply to the index
     * @param debug Give extra debug information
     * @return The result of the splitting, so i1+125 => {"i1","+","125"}
     */
    public static List<String[]> splitAndProcessExpression(String expression, int indexOffset, boolean debug ){
        var result = new ArrayList<String[]>();
        var oriExpression=expression;
        // adding a negative number is the same as subtracting
        expression=expression.replace("+-","-");
        // Split it in parts: numbers,ix and operands
        var parts = extractParts(expression);
        if (parts == null)
            return result;

        if( debug ) Logger.info("-> Splitting: "+expression);

        indexOffset++; // For some reason this is done here
        if (parts.size() == 3) { // If there are only three parts, this is a single operation
            result.add( simplifyTriple( parts.get(0),parts.get(1),parts.get(2),debug) );
            return result;
        }
        // There are more or less than three parts...
        try {
            int oIndex = indexOffset;
            for (int a = 0; a < ORDERED_OPS.length; a += 2) {
                // Calculate the index of the current operands
                int opIndex = getIndexOfOperand(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
                // Process the expression till the operand is no longer found
                while (opIndex != -1) {
                    if( parts.size()<=opIndex+1) {
                        Logger.error("Not enough data in parts -> Expression'"+expression+"' Parts:"+parts.size()+" needed "+(opIndex+1) );
                        return result;
                    }
                    var leftOperand = parts.get(opIndex-1);
                    var rightOperand = parts.get(opIndex + 1);
                    var operator = parts.get(opIndex);
                    var subExpression = leftOperand + operator + rightOperand;

                    // Check if this isn't a formula that can already be processed
                    String replacement;
                    if( NumberUtils.isCreatable(leftOperand) && NumberUtils.isCreatable(rightOperand)){
                        var bd = calcBigDecimalsOp( leftOperand,rightOperand,operator);
                        replacement = bd.toPlainString(); // replace the bottom one
                    }else{
                        result.add( new String[]{ leftOperand, rightOperand,operator } );
                        replacement = "o" + oIndex; // replace the bottom one
                        oIndex++;
                        if (debug) {
                            Logger.info("  Sub: " + "o" + oIndex + "=" +subExpression);
                        }
                    }

                    expression = expression.replace(subExpression, replacement);

                    parts.remove(opIndex);  // remove the operand
                    parts.remove(opIndex);  // remove the top part
                    parts.set(opIndex - 1, replacement); // replace the bottom one
                    opIndex = getIndexOfOperand(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
                }
            }
        }catch( IndexOutOfBoundsException e){
            Logger.error("Index issue while processing "+oriExpression+" at "+expression,e);
        }
        if( result.isEmpty())
            result.add(new String[]{parts.get(0),"0","+"});
        return result;
    }
    private static String[] simplifyTriple( String bd1, String operand, String bd2, boolean debug){
        // Check if both sides are numbers, if so, simplify the formula
        if( NumberUtils.isCreatable(bd1)&&NumberUtils.isCreatable(bd2)){
            var bd = calcBigDecimalsOp(bd1,bd2,operand);
            return new String[]{bd==null?"":bd.toPlainString(),"0","+"};
        }else { // If not copy directly
            return new String[]{bd1, bd2, operand};
        }
    }
    private static int getIndexOfOperand( List<String> data, String op1, String op2){
        int op1Index = data.indexOf(op1);
        int op2Index = data.indexOf(op2);

        if (op1Index == -1)  // first op can't be found, so it's e
            return op2Index;
        if (op2Index == -1)
            return op1Index;
        return Math.min(op1Index, op2Index);
    }
    public static String normalizeExpression(String expression) {
        expression = MathUtils.normalizeDualOperand(expression); // Replace stuff like i0++ with i0=i0+1
        // words like sin,cos etc messes up the processing, replace with references
        expression = MathUtils.replaceGeometricTerms(expression);
        expression = expression.replace(" ", ""); // Remove any spaces
        expression = MathUtils.handleCompoundAssignment(expression); // Replace stuff like i0*=5 with i0=i0*5
        return expression;
    }

    public static String normalizeDualOperand(String expression) {
        // Support ++ and --
        return expression.replace("++", "+=1")
                .replace("--", "-=1")
                .replace(" ", ""); //remove spaces
    }
    public static String handleCompoundAssignment(String exp) {
        if (!exp.contains("="))
            return exp;
        // The expression might be a simple i0 *= 2, so replace such with i0=i0*2 because of the way it's processed
        // A way to know this is the case, is that normally the summed length of the split items is one lower than
        // the length of the original expression (because the = ), if not that means an operand was in front of '='
        var split = exp.split("[+-/*^]?=");
        int lengthAfterSplit = split[0].length() + split[1].length();
        if (lengthAfterSplit + 1 != exp.length()) { // Support += -= *= and /= fe. i0+=1
            String[] spl = exp.split("="); //[0]:i0+ [1]:1
            split[1] = spl[0] + split[1]; // split[1]=i0+1
        }
        return split[0] + "=" + split[1];
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
    public static List<String> extractParts(String expression) {

        var list = new ArrayList<String>();
        var ops = "+-/*<>^=%~!°";

        expression = normalizeScientificNotation(expression);

        var build = new StringBuilder();
        for (var it : expression.toCharArray()) {
            if (ops.indexOf(it) == -1 || build.isEmpty()) {
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
            return null;
        }
        fixNegativeExponent(list);
        return list;
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

    /**
     * Convert the comparison sign to a compare function that accepts a double array
     * @param comp The comparison fe. < or >= etc
     * @return The function or null if an invalid comp was given
     */
    public static Function<double[],Boolean> getCompareFunction( String comp ){
        Function<double[],Boolean> proc=null;
        switch( comp ){
            case "<":  proc = x -> Double.compare(x[0],x[1])<0;  break;
            case "<=": proc = x -> Double.compare(x[0],x[1])<=0; break;
            case ">":  proc = x -> Double.compare(x[0],x[1])>0;  break;
            case ">=": proc = x -> Double.compare(x[0],x[1])>=0; break;
            case "==": proc = x -> Double.compare(x[0],x[1])==0; break;
            case "!=": proc = x -> Double.compare(x[0],x[1])!=0; break;
            default:
                Logger.error( "Tried to convert an unknown compare to a function: "+comp);
        }
        return proc;
    }

    /**
     * Combine two function that return a double into a single one the compares the result of the two
     * @param comp The kind of comparison fe. < or >= etc
     * @param f1 The left function
     * @param f2 The right function
     * @return The resulting function
     */
    public static Function<Double[],Boolean> getCompareFunction( String comp, Function<Double[],Double> f1, Function<Double[],Double> f2 ){
        Function<Double[],Boolean> proc=null;
        switch (comp) {
            case "<" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) < 0;
            case "<=" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) <= 0;
            case ">" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) > 0;
            case ">=" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) >= 0;
            case "==" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) == 0;
            case "!=" -> proc = x -> Double.compare(f1.apply(x), f2.apply(x)) != 0;
            default -> Logger.error("Tried to convert an unknown compare to a function: " + comp);
        }
        return proc;
    }

    /**
     * Split a compare into subparts so left>right will become left,>,right
     * @param comparison The comparison to split
     * @return The resulting string
     */
    public static String[] splitCompare( String comparison ){
        var compOps = Pattern.compile("[><=!][=]?");
        var full = new String[3];
        full[1] = compOps.matcher(comparison)
                .results()
                .map(MatchResult::group)
                .collect(Collectors.joining());
        var split = comparison.split(full[1]);
        full[0]=split[0];
        full[2]=split[1];
        return full;
    }

    /**
     * Parse a comparator operation with a single variable to a function, allowed formats:
     * - Using <,>,=,!= so : <50,>30,x<25,y==65,z<=125.2 etc
     * - Combining two like 1 < x < 10
     * - using above or below: above 1, below 10
     * - using not or equals: not 5, equals 10
     * - combining with not: not below 5 (>=5) or not above 10 (<=10)
     * - Maximum of two can be combined: 'above 1, below 10' = '1<x<10' (or ; as separator)
     * - between 20 and 50 will be parsed to 20<x<50
     * - from 1 to 10 will be parsed to 1<=x<10
     * - 1 through 10 will be parsed to 1<=x<=10
     * - or a range with '-' or '->' so 1-10 or -5->15
     * @param op An operation in the understood format
     * @return The combined functions that takes x and returns the result
     */
    public static Function<Double,Boolean> parseSingleCompareFunction( String op ){
        var comparePattern = Pattern.compile("[><=!][=]?");
        var ori = op;

        op = mapExpressionToSymbols(op);

        // At this point it should no longer contain letters, they should all have been replaced
        if( Pattern.matches("",op)) {
            Logger.error("The op shouldn't contain letters at this point! Original op: "+ori+" Parsed: "+op);
            return null;
        }
        var cc = comparePattern.matcher(op)
                .results()
                .map(MatchResult::group).toList();
        if( cc.isEmpty() ){ // fe. 1-10
            op=op.replace("--","<=$<=-");// -5 - -10 => -5<=$<=-10
            if(!op.contains("$"))// Don't replace if the previous step already did
                op=op.replace("-","<=$<=");
            if( op.equalsIgnoreCase(ori)) {
                Logger.error("Couldn't process: "+ori+" reached "+op);
                return null;
            }
        }else if( cc.size()==1){
            var c1 = op.split(cc.get(0));
            double fi1 = NumberUtils.toDouble(c1[1]);
            return getSingleCompareFunction(fi1,cc.get(0));
        }
        double fi1;
        Function<Double,Boolean> fu1;
        if( op.startsWith(cc.get(0))){
            fi1 = NumberUtils.toDouble(op.substring( cc.get(0).length(),op.lastIndexOf(cc.get(1))-1));
            fu1 = getSingleCompareFunction(fi1,cc.get(1));
        }else{
            fi1 = NumberUtils.toDouble(op.substring( 0,op.indexOf(cc.get(0))));
            fu1 = getSingleCompareFunction(fi1,invertCompare(cc.get(1)));
        }

        double fi2 = NumberUtils.toDouble(op.substring( op.lastIndexOf(cc.get(1))+cc.get(1).length()));
        var fu2 = getSingleCompareFunction(fi2,cc.get(1));

        return x -> fu1.apply(x) && fu2.apply(x);
    }

    /**
     * Replaces the string equivalent of the math compare symbols to the symbols fe. below -> <
     *
     * @param operation The operation to convert
     * @return The operation after conversion
     */
    public static String mapExpressionToSymbols(String operation) {
        var op = operation;
        op = op.replace("->", "-");

        op = op.replace(" and ", " && ");
        op = op.replace(" exor ", " !| ");
        op = op.replace(" or ", " || ");

        // between 40 and 50
        if (op.startsWith("between")) {
            op = op.replace("between ", ">");
            op = op.replace(" and ", ";<");
        }
        if (op.startsWith("not between")) {
            op = op.replace("not between ", "<=");
            op = op.replace(" and ", ";>=");
        }
        if (op.startsWith("from ")) {
            op = op.replace("from ", ">");
            op = op.replace(" to ", ";<");
            op = op.replace(" till ", ";<");
        }
        if (op.contains(" through ")) {
            op = op.replace(" through ", "<=$<=");
        }
        // 15 < x <= 25   or x <= 25
        op = op.replace("not below ", ">=");   // retain support for below
        op = op.replace("not above ", "<=");   // retain support for above
        op = op.replace("at least ", ">=");
        op = op.replace("below ", "<");   // retain support for below
        op = op.replace("above ", ">");   // retain support for above
        op = op.replace("equals ", "=="); // retain support for equals
        op = op.replace("not ", "!="); // retain support for not equals
        op = op.replace("++", "+=1");
        op = op.replace("--", "-=1");

        // diff?
        op = op.replace(" diff ", "~");

        return op.replace(" ", "");
    }

    public static String replaceGeometricTerms(String formula) {
        // Replace to enable geometric stuff?
        formula = formula.replace("cos(", "1°(");
        formula = formula.replace("cosd(", "1°(");
        formula = formula.replace("cosr(", "2°(");

        formula = formula.replace("sin(", "3°(");
        formula = formula.replace("sind(", "3°(");
        formula = formula.replace("sinr(", "4°(");
        formula = formula.replace("abs(", "5°(");

        // Remove unneeded brackets?
        int dot = formula.indexOf("°(");
        String cleanup;
        while (dot != -1) {
            cleanup = formula.substring(dot + 2); // Get the formula without found °(
            int close = cleanup.indexOf(")"); // find a closing bracket
            String content = cleanup.substring(0, close);// Get te content of the bracket
            if (NumberUtils.isCreatable(content) || content.matches("i\\d+")) { // If it's just a number or index
                formula = formula.replace("°(" + content + ")", "°" + content);
            }
            dot = cleanup.indexOf("°(");
        }
        return formula;
    }
    /**
     * Convert the given fixed value and comparison to a function that requires another double and return if correct
     * @param fixed The fixed part of the comparison
     * @param comp The type of comparison (options: <,>,<=,>=,!=,==)
     * @return The generated function
     */
    public static Function<Double,Boolean> getSingleCompareFunction( double fixed,String comp ){
        return switch (comp) {
            case "<" -> x -> x < fixed;
            case "<=" -> x -> x <= fixed;
            case ">" -> x -> x > fixed;
            case ">=" -> x -> x >= fixed;
            case "==" -> x -> x == fixed;
            case "!=" -> x -> x != fixed;
            default -> null;
        };
    }

    /**
     * Invert the compare symbol, so < -> > and so forth
     * @param comp The original symbol
     * @return The inverted version
     */
    private static String invertCompare(String comp){
        return switch (comp) {
            case "<" -> ">";
            case "<=" -> ">=";
            case ">" -> "<";
            case ">=" -> "<=";
            default -> comp;
        };
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
                i1 = first.startsWith("o")?index:index+offset;
            }
            if(NumberUtils.isCreatable(second) ) {
                bd2 = NumberUtils.createBigDecimal(second);
                i2=-1;
            }else{
                bd2=null;
                int index = NumberUtils.createInteger( second.substring(1));
                i2 = second.startsWith("o")?index:index+offset;
            }
        }catch( NumberFormatException e){
            Logger.error("Something went wrong decoding: "+first+" or "+second);
            return null;
        }

        Function<BigDecimal[],BigDecimal> proc=null;
        switch( op ){
            case "+":
                try {
                    if (bd1 != null && bd2 != null) { // meaning both numbers
                        var p = bd1.add(bd2);
                        proc = x -> p;
                    } else if (bd1 == null && bd2 != null) { // meaning first is an index and second a number
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
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        var p = bd1.subtract(bd2);
                        proc = x -> p;
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
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
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.multiply(bd2);
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
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
                    if (bd1 != null && bd2 != null) { // meaning both numbers
                        proc = x -> bd1.divide(bd2, MATH_CONTEXT);
                    } else if (bd1 == null && bd2 != null) { // meaning first is an index and second a number
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
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.remainder(bd2);
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
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
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.pow(bd2.intValue());
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
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
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.min(bd2).abs();
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
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
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.setScale(bd2.intValue(),RoundingMode.HALF_UP);
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
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
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        Logger.error("Todo - ln bd,bd");
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
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
            default:Logger.error("Unknown operand: "+op); break;
        }
        return proc;
    }
    public static BigDecimal calcBigDecimalsOp(String bd1, String bd2, String op ){
        return calcBigDecimalsOp(NumberUtils.createBigDecimal(bd1),NumberUtils.createBigDecimal(bd2),op);
    }
    public static BigDecimal calcBigDecimalsOp(BigDecimal bd1, BigDecimal bd2, String op ){
        return switch( op ){
            case "+" -> bd1.add(bd2);
            case "-" -> bd1.subtract(bd2);
            case "*" -> bd1.multiply(bd2);
            case "/" -> bd1.divide(bd2, MATH_CONTEXT);
            case "%" -> bd1.remainder(bd2);
            case "^" -> bd1.pow(bd2.intValue());
            case "~" -> bd1.min(bd2).abs();
            case "scale" -> bd1.setScale(bd2.intValue(),RoundingMode.HALF_UP);
            case "°" -> handleAngleBigDecimalsOps(bd1,bd2);
            default ->{
                Logger.error("Unknown operand: "+op);
                yield BigDecimal.ZERO;
            }
        };
    }
    private static BigDecimal handleAngleBigDecimalsOps( BigDecimal bd1, BigDecimal bd2 ){
        return switch( bd1.intValue() ){
            case 1 -> BigDecimal.valueOf(Math.cos(Math.toRadians(bd2.doubleValue()))); //cosd,sin
            case 2 -> BigDecimal.valueOf(Math.cos(bd2.doubleValue())); //cosr
            case 3 -> BigDecimal.valueOf(Math.sin(Math.toRadians(bd2.doubleValue()))); //sind,sin
            case 4 -> BigDecimal.valueOf(Math.sin(bd2.doubleValue())); //sinr
            case 5 -> bd2.abs();
            default -> BigDecimal.ZERO;
        };
    }
    public static double calcDoublesOp(String d1, String d2, String op ){
        return calcDoublesOp( NumberUtils.toDouble(d1),NumberUtils.toDouble(d2),op);
    }
    public static double calcDoublesOp(double d1, double d2, String op ){
        return switch( op ){
            case "+" -> d1+d2;
            case "-" -> d1-d2;
            case "*" -> d1*d2;
            case "/" -> d1/d2;
            case "%" -> d1%d2;
            case "^" -> Math.pow(d1,d2);
            case "~" -> Math.abs(d1-d2);
            case "scale" -> MathUtils.roundDouble(d1, (int) d2);
            case "°" -> handleAngleDoublesOps(d1,d2);
            default ->{
                Logger.error("Unknown operand: "+op);
                yield Double.NaN;
            }
        };
    }
    private static double handleAngleDoublesOps( double d1, double d2 ){
        return switch( (int)d1 ){
            case 1 -> Math.cos(Math.toRadians(d2)); //cosd,sin
            case 2 -> Math.cos(d2); //cosr
            case 3 -> Math.sin(Math.toRadians(d2)); //sind,sin
            case 4 -> Math.sin(d2); //sinr
            case 5 -> Math.abs(d2);
            default -> Double.NaN;
        };
    }
    /**
     * Performs a simple mathematical calculation based on a given expression.
     * <p>
     * The method splits the expression into parts (numbers and operators), processes it by applying mathematical
     * operations in the correct order of precedence, and returns the calculated result. If an error occurs, it will
     * return the provided error value.
     * </p>
     *
     * @param expression The mathematical expression to be evaluated. The expression can contain numbers, operators
     *                   (e.g., +, -, *, /), and the special operator (e.g., scale). The operands should be valid numbers
     *                   or references that can be evaluated.
     * @param error The value to return in case of an error or invalid expression. This allows the caller to specify the
     *              error value based on the context of the calculation.
     * @param debug A flag indicating whether debug information should be logged during the calculation process. If set
     *              to true, the method will log additional information about the operation at each step.
     * @return The result of the calculation as a double. If an error occurs (e.g., invalid expression or operands), the
     *         method will return the provided error value.
     */
    public static double insideBracketsCalculation(String expression, double error, boolean debug) {

        var oriExpression=expression;
        // adding a negative number is the same as subtracting
        expression=expression.replace("+-","-");

        if (expression.isEmpty())
            return error;

        // Split it in parts: numbers,ix and operands
        var parts = extractParts(expression);
        if (parts == null)
            return error;

        if( debug ) Logger.info("-> Calculating: "+expression);

        if (parts.size() == 3) { // If there are only three parts, this is a single operation
            var result = calcDoublesOp(parts.get(0), parts.get(2), parts.get(1));
            return Double.isNaN(result)?error:result;
        }
        var doubles = new ArrayList<Double>();
        // Do all parsing in a single step, and also use this arraylist for intermediate results
        for (String part : parts) {
            if (NumberUtils.isCreatable(part)) {
                doubles.add(NumberUtils.toDouble(part));
            } else {
                doubles.add(Double.NaN);
            }
        }

        // There are more or less than three parts...
        try {
            for (int a = 0; a < ORDERED_OPS.length; a += 2) {
                // Calculate the index of the current operands
                int opIndex = getIndexOfOperand(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
                // Process the expression till the operand is no longer found
                while (opIndex != -1) {
                    if( parts.size()<=opIndex+1) {
                        Logger.error("Not enough data in parts -> Expression'"+expression+"' Parts:"+parts.size()+" needed "+(opIndex+1) );
                        return error;
                    }
                    var leftOperand = doubles.get(opIndex - 1);
                    var rightOperand = doubles.get(opIndex + 1);
                    var operator = parts.get(opIndex);
                    var subExpression = leftOperand + operator + rightOperand;

                    // Check if this isn't a formula that can already be processed, if not something is wrong
                    var result = calcDoublesOp(leftOperand,rightOperand,operator);

                    if( Double.isNaN(result)){
                        Logger.error("Something went wrong trying to calculate "+subExpression);
                        return error;
                    }

                    expression = expression.replace(subExpression, String.valueOf(result));

                    parts.remove(opIndex);  // remove the operand
                    doubles.remove(opIndex);
                    parts.remove(opIndex);  // remove the top part
                    doubles.remove(opIndex);
                    doubles.set(opIndex - 1, result); // replace the bottom one

                    opIndex = getIndexOfOperand(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
                }
            }
            if( parts.size()!=1){
                Logger.error("Something went wrong processing "+oriExpression);
            }
            return doubles.get(0);
        }catch( IndexOutOfBoundsException e){
            Logger.error("Index issue while processing "+oriExpression+" at "+expression,e);
        }
        return error;
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
    /**
     * Converts a simple operation (only two operands) on elements in an array to a function
     * @param first The first element of the operation
     * @param second The second element of the operation
     * @param op The operator to apply
     * @param offset The offset for the index in the array
     * @return The function resulting from the above parameters
     */
    public static Function<Double[],Double> decodeDoublesOp(String first, String second, String op, int offset ){

        final Double db1;
        final int i1;
        final Double db2 ;
        final int i2;
        Function<Double[],Double> proc=null;
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
                db1=null;
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
                db2=null;
                int index = NumberUtils.createInteger( second.substring(1));
                i2 = second.startsWith("o")?index:index+offset;
            }
            if(first.isEmpty())
                return i2==-1?x->db2:x->x[i2];

        }catch( NumberFormatException e){
            Logger.error("Something went wrong decoding: "+first+" or "+second+ "with "+op+" -> "+e.getMessage());
            return null;
        }


        switch (op) {
            case "!" -> proc = x -> Double.compare(x[i1], 1) >= 0 ? 0.0 : 1.0;
            case "+" -> {
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> db1 + db2;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1] + db2;
                    } else if (db1 != null) { // meaning first is a number and second an index
                        proc = x -> db1 + x[i2];
                    } else { // meaning both indexes
                        proc = x -> x[i1] + x[i2];
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "-" -> {
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> db1 - db2;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1] - db2;
                    } else if (db1 != null) { // meaning first is a number and second an index
                        proc = x -> db1 - x[i2];
                    } else { // meaning both indexes
                        proc = x -> x[i1] - x[i2];
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "*" -> {
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> db1 * db2;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1] * db2;
                    } else if (db1 != null) { // meaning first is a number and second an index
                        proc = x -> db1 * x[i2];
                    } else { // meaning both indexes
                        proc = x -> x[i1] * x[i2];
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "/" -> { // i0/25
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> db1 / db2;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1] / db2;
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> db1 / x[i2];
                    } else { // meaning both indexes
                        proc = x -> x[i1] / x[i2];
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "%" -> { // i0%25
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> db1 % db2;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1] % db2;
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> db1 % x[i2];
                    } else { // meaning both indexes
                        proc = x -> x[i1] % x[i2];
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "^" -> { // i0^2
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> Math.pow(db1, db2);
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        if (db2.compareTo(0.5) == 0) { // root
                            proc = x -> Math.sqrt(x[i1]);
                        } else {
                            proc = x -> Math.pow(x[i1], db2);
                        }

                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> Math.pow(db1, x[i2]);
                    } else { // meaning both indexes
                        proc = x -> Math.pow(x[i1], x[i2]);
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "scale" -> { // i0/25
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> MathUtils.roundDouble(db1, db2.intValue());
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> MathUtils.roundDouble(x[i1], db2.intValue());
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> MathUtils.roundDouble(db1, x[i2].intValue());
                    } else { // meaning both indexes
                        proc = x -> MathUtils.roundDouble(x[i1], x[i2].intValue());
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "diff", "~" -> {
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> Math.abs(db1 - db2);
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> Math.abs(x[i1] - db2);
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> Math.abs(db1 - x[i2]);
                    } else { // meaning both indexes
                        proc = x -> Math.abs(x[i1] - x[i2]);
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "ln" -> {
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        Logger.error("Todo - ln bd,bd");
                        proc = x -> Math.log(db2);
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> Math.log(db2);
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> Math.log(x[i2]);
                    } else { // meaning both indexes
                        proc = x -> Math.log(x[i2]);
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "<" -> {
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> Double.compare(db1, db2) < 0 ? 1.0 : 0.0;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> Double.compare(x[i1], db2) < 0 ? 1.0 : 0.0;
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> Double.compare(db1, x[i2]) < 0 ? 1.0 : 0.0;
                    } else { // meaning both indexes
                        proc = x -> Double.compare(x[i1], x[i2]) < 0 ? 1.0 : 0.0;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case ">" -> {
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> Double.compare(db1, db2) > 0 ? 1.0 : 0.0;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> Double.compare(x[i1], db2) > 0 ? 1.0 : 0.0;
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> Double.compare(db1, x[i2]) > 0 ? 1.0 : 0.0;
                    } else { // meaning both indexes
                        proc = x -> Double.compare(x[i1], x[i2]) > 0 ? 1.0 : 0.0;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "<=" -> {
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> Double.compare(db1, db2) <= 0 ? 1.0 : 0.0;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> Double.compare(x[i1], db2) <= 0 ? 1.0 : 0.0;
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> Double.compare(db1, x[i2]) <= 0 ? 1.0 : 0.0;
                    } else { // meaning both indexes
                        proc = x -> Double.compare(x[i1], x[i2]) <= 0 ? 1.0 : 0.0;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case ">=" -> {
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> Double.compare(db1, db2) >= 0 ? 1.0 : 0.0;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> Double.compare(x[i1], db2) >= 0 ? 1.0 : 0.0;
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> Double.compare(db1, x[i2]) >= 0 ? 1.0 : 0.0;
                    } else { // meaning both indexes
                        proc = x -> Double.compare(x[i1], x[i2]) >= 0 ? 1.0 : 0.0;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "==" -> {
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> Double.compare(db1, db2) == 0 ? 1.0 : 0.0;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> Double.compare(x[i1], db2) == 0 ? 1.0 : 0.0;
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> Double.compare(db1, x[i2]) == 0 ? 1.0 : 0.0;
                    } else { // meaning both indexes
                        proc = x -> Double.compare(x[i1], x[i2]) == 0 ? 1.0 : 0.0;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            case "!=" -> {
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> Double.compare(db1, db2) != 0 ? 1.0 : 0.0;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> Double.compare(x[i1], db2) != 0 ? 1.0 : 0.0;
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> Double.compare(db1, x[i2]) != 0 ? 1.0 : 0.0;
                    } else { // meaning both indexes
                        proc = x -> Double.compare(x[i1], x[i2]) != 0 ? 1.0 : 0.0;
                    }
                } catch (IndexOutOfBoundsException | NullPointerException e) {
                    Logger.error("Bad things when " + first + " " + op + " " + second + " was processed");
                    Logger.error(e);
                }
            }
            default -> Logger.error("Unknown operand: " + op);
        }
        return proc;
    }

    /**
     * Convert a delimited string to BigDecimals array where possible, fills in null if not
     * @param split The data splitted
     * @param maxIndex The highest required index
     * @return The resulting array
     */
    public static BigDecimal[] toBigDecimals(String[] split, int maxIndex ){

        if( maxIndex==-1)
            maxIndex=split.length-1;

        var bds = new BigDecimal[maxIndex+1];

        int nulls=0;
        for( int a=0;a<=maxIndex;a++){
            if( a<split.length&&(NumberUtils.isCreatable(split[a])||NumberUtils.isParsable(split[a])) ) {
                try {
                    bds[a] = NumberUtils.createBigDecimal(split[a]);
                }catch(NumberFormatException e) {
                    bds[a] = new BigDecimal( NumberUtils.createBigInteger(split[a]));
                }
            }else{
                bds[a] = null;
                nulls++;
            }
        }
        return nulls==bds.length?null:bds;
    }

    /**
     * Rounds a double to a certain number of digits after the decimal point,
     * using {@link RoundingMode#HALF_UP}, which always rounds .5 away from zero.
     *
     * @param value        the double to round
     * @param decimalPlace the number of digits after the decimal point
     * @return the rounded value, or the original if invalid input
     */
    public static double roundDouble(double value, int decimalPlace) {
        if (Double.isInfinite(value) || Double.isNaN(value) || decimalPlace < 0)
            return value;
        BigDecimal bd = BigDecimal.valueOf(value);
        return bd.setScale(decimalPlace, RoundingMode.HALF_UP).doubleValue();
    }
    /**
     * Convert a 8bit value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned8bit( int ori ){
        if( ori>=0x80 ){ //two's complement
            ori = -1*((ori^0xFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 10bit value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned10bit( int ori ){
        if( ori>0x200 ){ //two's complement
            ori = -1*((ori^0x3FF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 12bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned12bit( int ori ){
        if( ori>0x800 ){ //two's complement
            ori = -1*((ori^0xFFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 16bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned16bit( int ori ){
        if( ori>0x8000 ){ //two's complement
            ori = -1*((ori^0xFFFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 20bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned20bit( int ori ){
        if( ori>0x80000 ){ //two's complement
            ori = -1*((ori^0xFFFFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 24bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned24bit( int ori ){
        if( ori>0x800000 ){ //two's complement
            ori = -1*((ori^0xFFFFFF) + 1);
        }
        return ori;
    }
    /**
     * Convert a 32bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static long toSigned32bit( int ori ){
        long or = ori;
        if( or>0x80000000L ){ //two's complement
            or = -1*((or^0xFFFFFFFF) + 1);
        }
        return or;
    }
    /**
     * Method that does a crc checksum for the given nmea string
     *
     * @param nmea The string to do the checksum on
     * @return True if checksum is ok
     */
    public static boolean doNMEAChecksum(String nmea) {
        int checksum = 0;
        for (int i = 1; i < nmea.length() - 3; i++) {
            checksum = checksum ^ nmea.charAt(i);
        }
        return nmea.endsWith(Integer.toHexString(checksum).toUpperCase());
    }

    /**
     * Calculates the nmea checksum for the given string and retunrs it
     * @param nmea The data to calculate the checksum from
     * @return The calculated hex value
     */
    public static String getNMEAchecksum(String nmea) {
        int checksum = 0;
        for (int i = 1; i < nmea.length(); i++) {
            checksum = checksum ^ nmea.charAt(i);
        }
        String hex = Integer.toHexString(checksum).toUpperCase();
        if (hex.length() == 1)
            hex = "0" + hex;
        return hex;
    }
    /**
     * Calculate the CRC16 according to the modbus spec
     *
     * @param data The data to calculate this on
     * @param append If true the crc will be appended to the data, if not only crc will be returned
     * @return Result based on append
     */
    public static byte[] calcCRC16_modbus(byte[] data, boolean append) {
        byte[] crc = calculateCRC16(data, data.length, 0xFFFF, 0xA001);
        if (!append)
            return crc;

        return ByteBuffer.allocate(data.length+crc.length)
                .put( data )
                .put(crc,0,crc.length)
                .array();
    }

    /**
     * Calculate CRC16 of byte data
     * @param data The data to calculate on
     * @param cnt Amount of data to process
     * @param start The value to start from
     * @param polynomial Which polynomial to use
     * @return An array containing remainder and dividend
     */
    public static byte[] calculateCRC16(byte[] data, int cnt, int start, int polynomial) {

        for (int pos = 0; pos < cnt; pos++) {
            start ^= (data[pos] & 0xFF);
            for (int x = 0; x < 8; x++) {
                boolean wasOne = start % 2 == 1;
                start >>>= 1;
                if (wasOne) {
                    start ^= polynomial;
                }
            }
        }
        return new byte[]{ (byte) Integer.remainderUnsigned(start, 256), (byte) Integer.divideUnsigned(start, 256) };
    }

    /**
     * Calculate the standard deviation
     *
     * @param set      The set to calculate the stdev from
     * @param decimals The amount of decimals
     * @return Calculated Standard Deviation
     */
    public static double calcStandardDeviation(ArrayList<Double> set, int decimals) {
        double sum = 0, sum2 = 0;
        int offset = 0;
        int size = set.size();

        if( size == 0 )
            return 0;

        for (int a = 0; a < set.size(); a++) {
            double x = set.get(a);
            if (Double.isNaN(x) || Double.isInfinite(x)) {
                set.remove(a);
                a--;
            }
        }

        if (size != set.size()) {
            Logger.error("Numbers in set are NaN or infinite, lef " + set.size()
                    + " of " + size + " elements");
        }
        for (double d : set) {
            sum += d;
        }

        double mean = sum / (set.size() - offset);
        for (double d : set) {
            sum2 += (d - mean) * (d - mean);
        }
        return MathUtils.roundDouble(Math.sqrt(sum2 / set.size()), decimals);
    }

    /**
     * Process a formula that can contain brackets but only contains numbers and no references
     *
     * @param formula The formula to parse and calculate
     * @param error   The value to return on an error
     * @param debug   True will return extra debug information
     * @return The result or the error if something went wrong
     */
    public static double noRefCalculation(String formula, double error, boolean debug) {

        formula = formula.replace(" ", ""); // But doesn't contain any spaces

        formula = checkBrackets(formula, '(', ')',true);
        if (formula.isEmpty())
            return error;

        // Next go through the brackets from left to right (inner)
        while (formula.contains("(")) { // Look for an opening bracket
            int close = formula.indexOf(")"); // Find the first closing bracket
            int open = formula.substring(0, close).lastIndexOf("(");

            String part = formula.substring(open + 1, close); // get the part between the brackets

            var result = insideBracketsCalculation(part, Double.NaN, debug);//MathUtils.splitAndProcessExpression( part, 0,debug).get(0);    // split that part in the subformulas
            if (Double.isNaN(result))
                return error;
            String piece = formula.substring(open, close + 1); // includes the brackets
            // replace the sub part in the original formula with a reference to the last subformula
            formula = formula.replace(piece, String.valueOf(result));
            if (debug)
                Logger.info("=>Formula: " + formula);
        }
        return NumberUtils.toDouble(formula);
    }
}
