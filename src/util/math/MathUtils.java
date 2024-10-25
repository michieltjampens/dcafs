package util.math;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.Tools;

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
    static final String OPS_REGEX="[+\\-\\/*<>^=%~!°]+[=]?";
    static final Pattern es = Pattern.compile("\\de[+-]?\\d");
    /**
     * Splits a simple expression of the type i1+125 etc into distinct parts i1,+,125
     * @param expression The expression to split
     * @param indexOffset The offset to apply to the index
     * @param debug Give extra debug information
     * @return The result of the splitting, so i1+125 => {"i1","+","125"}
     */
    public static List<String[]> splitExpression(String expression, int indexOffset, boolean debug ){
        var result = new ArrayList<String[]>();

        expression=expression.replace("+-","-"); // adding a negative number is the same as subtractingression=expression.replace("-o","-1*o");
        var parts = extractParts(expression);

        if( debug ){
            Logger.info("-> Splitting: "+expression);
        }
        indexOffset++;
        try {
            if (parts.size() == 3) {
                if( NumberUtils.isCreatable(parts.get(0))&&NumberUtils.isCreatable(parts.get(2))){
                    var bd1 = NumberUtils.createBigDecimal(parts.get(0));
                    var bd2 = NumberUtils.createBigDecimal(parts.get(2));
                    var bd3 = calcBigDecimalsOp(bd1,bd2,parts.get(1));
                    result.add(new String[]{bd3==null?"":bd3.toPlainString(),"0","+"});
                }else {
                    if (debug) {
                        Logger.info("  Sub: " + "o" + indexOffset + "=" + expression);
                    }
                    result.add(new String[]{parts.get(0), parts.get(2), parts.get(1)});
                }
            } else {
                int oIndex = indexOffset;
                for (int a = 0; a < ORDERED_OPS.length; a += 2) {

                    int opIndex = getIndexOfOperand(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);

                    while (opIndex != -1) {
                        if( parts.size()<=opIndex+1) {
                            Logger.error("Not enough data in parts -> Expression'"+expression+"' Parts:"+parts.size()+" needed "+(opIndex+1) );
                            return result;
                        }
                        // Check if this isn't a formula that can already be processed
                        if( NumberUtils.isCreatable(parts.get(opIndex-1))&&NumberUtils.isCreatable(parts.get(opIndex+1))){
                            var bd1 = NumberUtils.createBigDecimal(parts.get(opIndex-1));
                            var bd2 = NumberUtils.createBigDecimal(parts.get(opIndex+1));
                            var bd3 = calcBigDecimalsOp(bd1,bd2,parts.get(opIndex));

                            parts.remove(opIndex);  // remove the operand
                            parts.remove(opIndex);  // remove the top part
                            parts.set(opIndex - 1, bd3==null?"":bd3.toPlainString()); // replace the bottom one
                            opIndex = getIndexOfOperand(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
                            continue;
                        }

                        String res = parts.get(opIndex - 1) + parts.get(opIndex) + parts.get(opIndex + 1);
                        result.add(new String[]{parts.get(opIndex - 1), parts.get(opIndex + 1), parts.get(opIndex)});
                        parts.remove(opIndex);  // remove the operand
                        parts.remove(opIndex);  // remove the top part
                        parts.set(opIndex - 1, "o" + oIndex); // replace the bottom one

                        if (debug) {
                            Logger.info("  Sub: " + "o" + oIndex + "=" + res);
                        }
                        expression = expression.replace(res, "o" + oIndex++);
                        opIndex = getIndexOfOperand(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
                    }
                }
            }
        }catch( IndexOutOfBoundsException e){
            Logger.error("Index issue while processing "+expression);
            Logger.error(e);
        }
        if( result.isEmpty())
            result.add(new String[]{parts.get(0),"0","+"});
        return result;
    }

    private static int getIndexOfOperand( List<String> data, String op1, String op2){
        int op1Index = data.indexOf(op1);
        int op2Index = data.indexOf(op2);

        if( op1Index==-1) { // first op can't be found, so it's e
            return op2Index;
        }
        if( op2Index==-1){
            return op1Index;
        }else{
            return Math.min( op1Index,op2Index);
        }
    }

    /**
     * Chop a formula into processable parts splitting it according to operators fe. i1+5-> i1,+,5
     * The most error prone are scientific notations
     * @param formula The formula to chop into parts
     * @return The resulting pieces
     */
    public static List<String> extractParts( String formula ){

        if( formula.isEmpty() ) {
            Logger.warn("Tried to extract parts from empty formula");
            return new ArrayList<>();
        }
        // The problem with scientific notation is that they can be mistaken for a combination of a word and numbers
        // especially the negative one can be seen as an operation instead
        var ee = es.matcher(formula) // find the numbers with scientific notation
                .results()
                .map(MatchResult::group)
                .distinct().toList();

        for( String el : ee ){ // Replace those with uppercase so they all use the same format
            formula = formula.replace(el,el.toUpperCase());
        }
        // Replace the negative ones with a e and the positive ones with E to remove the sign
        String alt = formula.replace("E-","e");
        alt = alt.replace("E+","E");

        String[] spl = alt.split(OPS_REGEX); // now split the string base on operands, this now won't split scientific

        String ops = alt.replaceAll("[a-zA-Z0-9_:]", "");// To get the ops, just remove all other characters
        ops=ops.replace(".","");// and the dots (special regex character)
        // The above replace all doesn't handle it properly if the formula starts with a - (fe. -5*i1)
        // So if this is the case, remove it from the ops line
        if( ops.startsWith("-")&&formula.startsWith("-"))
            ops=ops.substring(1);

        var full = new ArrayList<String>();
        int b=0;
        for (int a = 0; a < spl.length; a++) {
            if (spl[a].isEmpty() && !formula.startsWith("!")) {
                spl[a + 1] = "-" + spl[a + 1];
            } else {
                var m = es.matcher(spl[a]); // now check if it matches a scientific one
                if( m.find() ){ // if so, replace the lowercase e back to uppercase with minus, for uppercase + is redundant
                    full.add(spl[a].replace("e","E-"));
                }else{ // if not, just add as is
                    full.add(spl[a]);
                }

                // add the op
                if( b<ops.length()) {
                    if( ops.length()>b+1 && ops.charAt(b+1)=='=') { // == doesn't get processed properly, so fix this
                        full.add(ops.substring(b, b + 2));
                        b++;
                    }else {// if not == just add it
                        full.add(String.valueOf(ops.charAt(b)));
                    }
                }
                b++;
            }
        }
        return full;
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
        String ori = op;
        op=op.replace("->","-");

        // between 40 and 50
        if( op.startsWith("between") ){
            op=op.replace("between ",">");
            op=op.replace(" and ", ";<");
        }
        if( op.startsWith("not between") ){
            op=op.replace("not between ","<=");
            op=op.replace(" and ", ";>=");
        }
        if( op.startsWith("from ") ){
            op=op.replace("from ",">");
            op=op.replace(" to ", ";<");
            op=op.replace(" till ", ";<");
        }
        if( op.contains(" through ")){
            op=op.replace(" through ", "<=$<=");
        }
        // 15 < x <= 25   or x <= 25
        op = op.replace("not below ",">=");   // retain support for below
        op = op.replace("not above ","<=");   // retain support for above
        op = op.replace("at least ",">=");
        op = op.replace("below ","<");   // retain support for below
        op = op.replace("above ",">");   // retain support for above
        op = op.replace("equals ","=="); // retain support for equals
        op = op.replace("not ","!="); // retain support for not equals
        op = op.replace("++","+=1");
        op = op.replace("--","-=1");

        op = op.replace(" ",""); // remove all spaces

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
                        proc = x -> bd1.divide(bd2, DIV_SCALE, RoundingMode.HALF_UP);
                    } else if (bd1 == null && bd2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1].divide(bd2, DIV_SCALE, RoundingMode.HALF_UP);
                    } else if (bd1 != null) { //  meaning first is a number and second an index
                        proc = x -> bd1.divide(x[i2], DIV_SCALE, RoundingMode.HALF_UP);
                    } else { // meaning both indexes
                        proc = x -> x[i1].divide(x[i2], DIV_SCALE, RoundingMode.HALF_UP);
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
    public static BigDecimal calcBigDecimalsOp(BigDecimal bd1, BigDecimal bd2, String op ){

        switch( op ){
            case "+":
                return bd1.add(bd2);
            case "-":
                return bd1.subtract(bd2);
            case "*":
                return bd1.multiply(bd2);

            case "/": // i0/25
                return bd1.divide(bd2, DIV_SCALE, RoundingMode.HALF_UP);
            case "%": // i0%25
                return bd1.remainder(bd2);
            case "^": // i0/25
                return bd1.pow(bd2.intValue());
            case "~": // i0~25 -> ABS(i0-25)
                return bd1.min(bd2).abs();
            case "scale": // i0/25
                return bd1.setScale(bd2.intValue(),RoundingMode.HALF_UP);
            case "°":
                switch( bd1.intValue() ){
                    case 1: //cosd,sin
                        return BigDecimal.valueOf(Math.cos(Math.toRadians(bd2.doubleValue())));
                    case 2: //cosr
                        return BigDecimal.valueOf(Math.cos(bd2.doubleValue()));
                    case 3: //sind,sin
                        return BigDecimal.valueOf(Math.sin(Math.toRadians(bd2.doubleValue())));
                    case 4: //sinr
                        return BigDecimal.valueOf(Math.sin(bd2.doubleValue()));
                    case 5: //abs
                        return bd2.abs();
                }
                break;
            default:Logger.error("Unknown operand: "+op); break;
        }
        return null;
    }
    /**
     * Process a simple formula that only contains numbers and no references
     * @param formula The formula to parse and calculate
     * @param error The value to return on an error
     * @param debug True will return extra debug information
     * @return The result or the error if something went wrong
     */
    public static double simpleCalculation( String formula,double error, boolean debug ){
        ArrayList<Function<Double[],Double>> steps = new ArrayList<>();

        // First check if the amount of brackets is correct
        int opens = StringUtils.countMatches(formula,"(");
        int closes = StringUtils.countMatches(formula,")");


        if( opens != closes ){
            Logger.error("Brackets don't match, (="+opens+" and )="+closes);
            return error;
        }
        formula= "("+formula+")";// Make sure it has surrounding brackets

        formula=formula.replace(" ",""); // But doesn't contain any spaces

        // Next go through the brackets from left to right (inner)
        var subFormulas = new ArrayList<String[]>(); // List to contain all the subformulas

        while( formula.contains("(") ){ // Look for an opening bracket
            int close = formula.indexOf(")"); // Find the first closing bracket
            int look = close-1; // start looking from one position left of the closing bracket
            int open = -1; // reset the open position to the not found value

            while( look>=0 ){ // while we didn't traverse the full string
                if( formula.charAt(look)=='(' ){ // is the current char an opening bracket?
                    open = look; // if so, store this position
                    break;// and quite the loop
                }
                look --;//if not, decrement the pointer
            }
            if( open !=-1 ){ // if the opening bracket was found
                String part = formula.substring(open+1,close); // get the part between the brackets
                int s = subFormulas.size();
                subFormulas.addAll( MathUtils.splitExpression( part, subFormulas.size(),debug) );    // split that part in the subformulas
                if( s==subFormulas.size() ){
                    Logger.warn("SplitExpression of "+part+" from "+formula +" failed");
                }
                String piece = formula.substring(open,close+1); // includes the brackets
                // replace the sub part in the original formula with a reference to the last subformula
                formula=formula.replace(piece,"o"+(subFormulas.size()));
                if( debug )
                    Logger.info("=>Formula: "+formula);
            }else{
                Logger.error("Didn't find opening bracket");
                return error;
            }
        }

        for( String[] sub : subFormulas ){ // now convert the subformulas into lambda's
            var x = MathUtils.decodeDoublesOp(sub[0],sub[1],sub[2],0);
            if( x==null ){
                Logger.error("Failed to convert "+formula);
                return error;
            }
            steps.add( x ); // and add it to the steps list
        }
        Double[] result = new Double[subFormulas.size()+1];
        int i=1;
        try {
            for (var step : steps) {
                result[i] = step.apply(result);
                i++;
            }
        }catch( NullPointerException e ){
            Logger.error( "Null pointer when processing "+formula+" on step "+i);
            return error;
        }
        return result[i-1];
    }

    /**
     * Check if the brackets used in the formula are correct (meaning same amount of opening as closing and no closing
     * if there wasn't an opening one before
     * @param formula The formula to check
     * @return The formula with enclosing brackets added if none were present or an empty string if there's an error
     */
    public static String checkBrackets( String formula ){

        // No total enclosing brackets
        int cnt=0;
        for( int a=0;a<formula.length()-1;a++){
            if( formula.charAt(a)=='(') {
                cnt++;
            }else if( formula.charAt(a)==')' ){
                cnt--;
            }
            if( cnt < 0 ) { // if this goes below zero, an opening bracket is missing
                Logger.error("Found closing bracket without opening in "+formula+" at "+a);
                return "";
            }
        }
        if( cnt != 0 ) {
            Logger.error("Unclosed bracket in "+formula);
            return "";
        }
        if( formula.charAt(0)!='(') // Add enclosing brackets
            formula="("+formula+")";
        return formula;
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
                        proc = x -> Tools.roundDouble(db1, db2.intValue());
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> Tools.roundDouble(x[i1], db2.intValue());
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> Tools.roundDouble(db1, x[i2].intValue());
                    } else { // meaning both indexes
                        proc = x -> Tools.roundDouble(x[i1], x[i2].intValue());
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
     * @param list The delimited string
     * @param delimiter The delimiter to use
     * @return The resulting array
     */
    public static BigDecimal[] toBigDecimals(String list, String delimiter, int maxIndex ){
        return toBigDecimals(list.split(delimiter),maxIndex);
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
     * Convert a delimited string to an array of doubles, inserting null where conversion is not possible
     * @param list The delimited string
     * @param delimiter The delimiter to use
     * @return The resulting array
     */
    public static Double[] toDoubles(String list, String delimiter ){
        String[] split = list.split(delimiter);
        var dbs = new Double[split.length];
        int nulls=0;

        for( int a=0;a<split.length;a++){
            if( NumberUtils.isCreatable(split[a])) {
                try {
                    dbs[a] = NumberUtils.createDouble(split[a]);
                }catch(NumberFormatException e) {
                    // hex doesn't go wel to double...
                    dbs[a] = NumberUtils.createBigInteger(split[a]).doubleValue();
                }
            }else{
                dbs[a] = null;
                nulls++;
            }
        }
        return nulls==dbs.length?null:dbs;
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
        return Tools.roundDouble(Math.sqrt(sum2 / set.size()), decimals);
    }
}
