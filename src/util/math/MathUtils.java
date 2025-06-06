package util.math;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.evalcore.ParseTools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.DoubleStream;

public class MathUtils {

    // Ordered ops come in pairs, the pairs share precedence, so repeated means it's higher than whatever comes next
    static final String[] ORDERED_OPS = {"°", "°", "^", "^", "*", "/", "%", "%", "+", "-", "<", ">", "<=", ">="};
    static final MathContext MATH_CONTEXT = new MathContext(10, RoundingMode.HALF_UP);
    /**
     * Splits a simple expression of the type i1+125 etc. into distinct parts i1,+,125
     * @param expression The expression to split
     * @param indexOffset The offset to apply to the index
     * @param debug Give extra debug information
     * @return The result of the splitting, so i1+125 => {"i1","+","125"}
     */
    public static List<String[]> splitAndProcessMathExpression(String expression, int indexOffset, boolean debug ){
        var result = new ArrayList<String[]>();
        var oriExpression=expression;
        // adding a negative number is the same as subtracting
        expression=expression.replace("+-","-");
        // Split it in parts: numbers,ix and operands
        var parts = ParseTools.extractParts(expression);
        if (parts.isEmpty())
            return result;

        if( debug ) Logger.info("-> Splitting: "+expression);

        indexOffset++; // For some reason this is done here
        if (parts.size() == 3) { // If there are only three parts, this is a single operation
            result.add( simplifyTriple( parts.get(0),parts.get(1),parts.get(2) ) );
            return result;
        }
        // There are more or less than three parts...
        try {
            int oIndex = indexOffset;
            for (int a = 0; a < ORDERED_OPS.length; a += 2) {
                // Calculate the index of the current operands
                int opIndex = getIndexOfOperator(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
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
                    opIndex = getIndexOfOperator(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
                }
            }
        }catch( IndexOutOfBoundsException e){
            Logger.error("Index issue while processing "+oriExpression+" at "+expression,e);
        }
        if( result.isEmpty())
            result.add(new String[]{parts.get(0),"0","+"});
        return result;
    }
    private static String[] simplifyTriple( String bd1, String operand, String bd2 ){
        // Check if both sides are numbers, if so, simplify the formula
        if( NumberUtils.isCreatable(bd1)&&NumberUtils.isCreatable(bd2)){
            var bd = calcBigDecimalsOp(bd1,bd2,operand);
            return new String[]{bd==null?"":bd.toPlainString(),"0","+"};
        }else { // If not copy directly
            return new String[]{bd1, bd2, operand};
        }
    }
    private static int getIndexOfOperator(List<String> data, String op1, String op2){
        int op1Index = data.indexOf(op1);
        int op2Index = data.indexOf(op2);

        if (op1Index == -1)  // first op can't be found, so it's e
            return op2Index;
        if (op2Index == -1)
            return op1Index;
        return Math.min(op1Index, op2Index);
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
            case ">" -> bd1.compareTo(bd2) > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
            case ">=" -> bd1.compareTo(bd2) >= 0 ? BigDecimal.ONE : BigDecimal.ZERO;
            case "<" -> bd1.compareTo(bd2) < 0 ? BigDecimal.ONE : BigDecimal.ZERO;
            case "<=" -> bd1.compareTo(bd2) <= 0 ? BigDecimal.ONE : BigDecimal.ZERO;
            case "==" -> bd1.compareTo(bd2) == 0 ? BigDecimal.ONE : BigDecimal.ZERO;
            case "!=" -> bd1.compareTo(bd2) != 0 ? BigDecimal.ONE : BigDecimal.ZERO;
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
            case "°" -> handleAngleDoublesOps((int)d1,d2);
            case ">" -> Double.compare(d1,d2) > 0?1:0;
            case ">=" -> Double.compare(d1,d2) >= 0?1:0;
            case "<" -> Double.compare(d1,d2) < 0?1:0;
            case "<=" -> Double.compare(d1,d2) <= 0?1:0;
            case "==" -> Double.compare(d1,d2) == 0?1:0;
            case "!=" -> Double.compare(d1,d2) != 0?1:0;
            default ->{
                Logger.error("Unknown operand: "+op);
                yield Double.NaN;
            }
        };
    }
    private static double handleAngleDoublesOps( int function, double d2 ){
        return switch( function ){
            case 1 -> Math.cos(Math.toRadians(d2)); //cosd,sin
            case 2 -> Math.cos(d2); //cosr
            case 3 -> Math.sin(Math.toRadians(d2)); //sind,sin
            case 4 -> Math.sin(d2); //sinr
            case 5 -> Math.abs(d2);
            default -> Double.NaN;
        };
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

    public static double calcVariance(double[] window) {
        var sum = DoubleStream.of(window).sum();
        var avg = sum / window.length;
        return DoubleStream.of(window).map(d -> (d - avg) * (d - avg)).sum();
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

        formula = ParseTools.checkBrackets(formula, '(', ')',true);
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
        var parts = ParseTools.extractParts(expression);
        if (parts.isEmpty())
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
                int opIndex = getIndexOfOperator(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
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

                    opIndex = getIndexOfOperator(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
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
}
