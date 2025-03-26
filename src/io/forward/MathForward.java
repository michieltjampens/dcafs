package io.forward;

import das.Core;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.NumericVal;
import util.data.RealVal;
import util.data.RealtimeValues;
import util.gis.GisTools;
import util.math.Calculations;
import util.math.MathFab;
import util.math.MathUtils;
import util.tools.Tools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MathForward extends AbstractForward {

    private String suffix="";

    private final ArrayList<Operation> ops = new ArrayList<>();
    private boolean doCmd = false;
    private boolean doUpdate=false;
    HashMap<String,String> defines = new HashMap<>();

    public enum OP_TYPE{COMPLEX, SCALE, LN, SALINITY, SVC,TRUEWINDSPEED,TRUEWINDDIR,UTM,GDC}
    private ArrayList<NumericVal> referencedNums = new ArrayList<>();
    private int highestI=-1;
    private final ArrayList<BigDecimal> temps = new ArrayList<>();

    public MathForward(String id, String source, RealtimeValues rtvals){
        super(id,source,rtvals);
        valid = rtvals!=null;
    }
    public MathForward(Element ele,  RealtimeValues rtvals, HashMap<String,String> defs){
        super(rtvals);
        if( defs !=null )
            defines.putAll(defs);
        readOk = readFromXML(ele);
    }
    public MathForward(Element ele,  RealtimeValues rtvals){
        super(rtvals);
        readOk = readFromXML(ele);
    }
    @Override
    public String getRules(){
        int index=0;
        StringJoiner join = new StringJoiner("\r\n");
        join.setEmptyValue(" -> No ops yet.");
        if( !parsedOk ){
             join.add("Failed to parse from xml, check logs");
        }else {
            for (String[] x : rulesString) {
                join.add("   " + (index++) + " : " + x[2]);
            }
        }
        return join.toString();
    }
    /* ***************************************** X M L ************************************************************ */
    /**
     * Get the tag that is used for the child nodes, this way the abstract class can refer to it
     * @return The child tag for this forward, parent tag is same with added s
     */
    protected String getXmlChildTag(){
        return "math";
    }
    /**
     * Read the settings for a mathForward from the given element
     * @param math The math child element
     * @return True if this was successful
     */
    @Override
    public boolean readFromXML(Element math) {

        var dig = XMLdigger.goIn(math);
        if (!readBasicsFromXml(dig)) // Read the basic parameters that are the same in all forwards
            return false;

        // Reset everything that will be altered
        parsedOk = true;
        referencedNums.clear();
        temps.clear();
        highestI = -1;
        ops.clear();

        suffix = dig.attr("suffix", "");
        String content = dig.value("");

        // Check if it Has content but no child elements, so a single op
        if (content != null && dig.peekOut("*").isEmpty()) {
            return procSingleOp(dig, content);
        }

        // Check for other subnodes besides 'op' those will be considered def's to reference in the op
        digForFixedOperands(dig);

        boolean oldValid = valid; // Store the current state of valid

        // First go through all the ops and to find all references to real/int/flag and determine highest i(ndex) used
        if (!digForMaxTempIndex(dig)) {
            return false;
        }
        // Find all the references to realtime values
        for (var ops : dig.peekOut("op")){
            if (!findRtvals(ops.getTextContent())) {
                parsedOk = false; //Parsing failed, so set the flag and return
                return false;
            }
        }
        // Go through the op's again to actually process the expression
        digForOpsProcessing(dig);

        if( !oldValid && valid )// If math specific things made it valid
            sources.forEach( source -> Core.addToQueue( Datagram.system( source ).writable(this) ) );
        referencedNums.trimToSize(); // Won't be changed after this, so trime excess space
        return true;
    }
    private boolean procSingleOp( XMLdigger dig, String content ){
        if( findRtvals(content) ){ // Figure out the used references to vals and determine the highest used index/i
            var op = addStdOperation(
                    content,
                    dig.attr("scale",-1),
                    dig.attr("cmd","")
            );
            if(op.isEmpty()){ // If no op could be parsed from the expression
                parsedOk=false;
                Logger.error(id+"(mf) -> No valid operation found in: "+content);
                return false;
            }
            // Processing ok, can stop here
            return true;
        }
        // Failed to find or parse references to real/int/flagvals
        Logger.error(id +"(mf) -> Failed to process references in "+content);
        parsedOk=false;
        return false;
    }

    /**
     * Check the node for references to static values and odd those to the collection
     * @param dig A digger pointing to the MathForward
     */
    private void digForFixedOperands(XMLdigger dig ){
        dig.peekOut("*")
                .stream().filter( ele -> !ele.getTagName().equalsIgnoreCase("op"))
                .forEach( def -> {
                    var val = def.getTextContent().replace(",","."); // unify decimal separator
                    if( def.getTagName().equalsIgnoreCase("def")){
                        defines.put( def.getAttribute("ref"),val); // <def ref="A1">12.5</def>
                    }else{
                        defines.put( def.getTagName(),val);             // <A1>12.5</A1>
                    }
                });
    }

    /**
     * Go through the op's and find any reference to temporary values designated with tx then prepare the temps
     * collection to hold that many elements
     * @param dig A digger pointing to the MathForward
     */
    private boolean digForMaxTempIndex(XMLdigger dig ){
        var pattern = Pattern.compile("t[0-9]{1,2}");
        try {
            for (var opNode : dig.peekOut("op")) {
                // Find all the temp indexes
                var maxIndex = pattern
                        .matcher(opNode.getTextContent())// Apply the pattern to the text content
                        .results() // Get the results of the pattern applied
                        .map(m -> m.group().substring(1)) // Remove the t at the start (i.e., extract the number)
                        .filter(t -> {
                            boolean isValid = NumberUtils.isCreatable(t); // Check if it's a valid number
                            if (!isValid)
                                throw new IllegalArgumentException("Invalid temp index found: " + t);
                            return true; // Only allow valid indexes through
                        })
                        .mapToInt(NumberUtils::toInt) // Convert valid strings to integers
                        .max() // Find the maximum index
                        .orElse(0); // Default to 0 if no valid indexes are found

                // Increase the temp collection so all will fit
                while (maxIndex >= temps.size())
                    temps.add(BigDecimal.ZERO); // Fill in zero for the future temp
            }
        }catch( IllegalArgumentException e ){
            Logger.error(id + "(mf) -> "+e.getMessage() );
            parsedOk=false; //Parsing failed, so set the fla
            return false;
        }
        return true;
    }
    private void digForOpsProcessing(XMLdigger dig ){
        dig.digOut("op").forEach( ops -> {
            try {
                var type= fromStringToOPTYPE(ops.attr( "type", "complex"));
                switch (Objects.requireNonNull(type)) {
                    case COMPLEX -> addStdOperation(
                            ops.value(""),
                            ops.attr(  "scale", -1),
                            ops.attr( "cmd", "")
                    );
                    case LN, SALINITY, SVC, TRUEWINDSPEED, TRUEWINDDIR, UTM, GDC -> addOperation(
                            ops.attr(  "index", "-1"),
                            ops.attr(  "scale", -1),
                            type,
                            ops.attr(  "cmd", ""),
                            ops.value(""));
                    default -> Logger.error("Bad type " + type);
                }

            }catch( NumberFormatException e){
                Logger.error(id+" (mf)-> Number format Exception "+e.getMessage());
            }
        });
    }
    /**
     * Give data to this forward for processing
     * @param data The data received
     * @return True if given to a target afterward, false if not
     */
    @Override
    protected boolean addData(String data) {

        // First check if the operations are actually valid
        if( !parsedOk ){
            showError("Not processing data because the operations aren't valid");
            return true;
        }

        // Split the data according to the delimiter
        String[] split = data.split(delimiter);

        // Then make sure there's enough items in split, need at least one more than the highestI (because it starts at 0)
        if( !checkDataLength( data, split.length, highestI )) return true;

        // Convert to BigDecimals and do some checks
        var bdsOpt = convertToBigDecimals(data,split);
        if( bdsOpt.isEmpty()) { // Something failed, abort
            badDataCount++;
            return true;
        }
        var bds = bdsOpt.get(); // Nothing failed, unpack

        // After doing all possible initial tests, do the math
        if (applyOperations(bds, data)) {
            badDataCount++;
            Logger.error(id + "(math) -> Applying operation failed");
            return true;
        }

        // Insert the BigDecimals in the received data and apply any requested suffix
        final String finalData = insertBigDecimalsInData(bds,split);

        // If we got to this point, processing went fine so reset badDataCount
        if( badDataCount != 0 )
            Logger.info(id+" (mf) -> Executed properly after previous issues, resetting bad count" );
        badDataCount=0;

        // Use multithreading so the Writable's don't have to wait for the whole process
        nextSteps.parallelStream().forEach( ns -> ns.writeLine(id(),finalData));
        targets.parallelStream().forEach( wr -> wr.writeLine(id(),finalData));

        logResult(data,finalData);

        // Potentially store the data in memory and databases
        storeData(bds,split);

        if( !cmds.isEmpty())
            cmds.forEach( cmd->Core.addToQueue(Datagram.system(cmd).writable(this)));

        // If there are no target, no label and no ops that build a command, this no longer needs to be a target
        return !noTargets() || log || store != null;
    }
    /**
     * Checks if the length of the provided data (split by the delimiter) is sufficient
     * to match the required number of items, based on the highest index needed.
     *
     * @param data The original data string being processed.
     * @param receivedCount The array of strings obtained by splitting the data.
     * @param minCount The length of the split array, used to check if it meets the necessary condition.
     * @return {@code true} if the data has enough items to proceed, {@code false} otherwise.
     */
    private boolean checkDataLength( String data, int receivedCount, int minCount ){
        if( receivedCount <= minCount ) {
            showError("Need at least " + (minCount+1) + " items after splitting: " + data + ", got " + receivedCount + " (bad:" + badDataCount + ")");
            badDataCount++;
            return false; // Stop processing
        }
        return true;
    }
    /**
     * Converts the provided data (split by delimiter) into an array of {@link BigDecimal} values.
     * This method checks that the data contains valid numerical values, and ensures that
     * the required "highest index" is a valid number before returning the result.
     *
     * @param data The original data string that is being processed.
     * @param split The array of strings obtained by splitting the data.
     * @return An {@link Optional} containing the array of {@link BigDecimal} values if the conversion is successful,
     *         or {@link Optional#empty()} if any validation or conversion fails.
     */
    private Optional<BigDecimal[]> convertToBigDecimals( String data, String[] split ){
        // Convert the split data to BigDecimals and add references and temps
        BigDecimal[] bds = buildBDArray(split);
        if( bds==null){
            return Optional.empty();
        }

        // Check if none of the items is a valid number
        if( bds.length==0 ){
            showError("No valid numbers in the data: "+data+" after split on "+delimiter+ " "+ " (bad:"+badDataCount+")");
            return Optional.empty();
        }
        // Ensure the 'highest needed index' is a valid number
        if( bds[highestI]==null){
            showError("No valid highest I value in the data: "+data+" after split on "+delimiter+ " "+ " (bad:"+badDataCount+")");
            return Optional.empty();
        }
        return Optional.of(bds);
    }
    private boolean applyOperations(BigDecimal[] bigDecimals, String data) {
        int errorCount = 0;
        for (var op : ops) {
            if (op.solve(bigDecimals) == null) {
                errorCount++;
                showError(errorCount == 1, "Failed to process " + data + " for " + op.ori);
            }
        }
        return errorCount>0;
    }
    private String appendSuffix( String data ){
       return switch( suffix ) {
           case "" -> data;
           case "nmea" -> data + "*" + MathUtils.getNMEAchecksum(data);
           default -> {
               Logger.error(id + " (mf)-> No such suffix " + suffix);
               yield data;
           }
       };
    }
    private void storeData(BigDecimal[] bds, String[] split){
        if( store!=null) {
            for( int a=0;a<store.size();a++){
                if( bds.length > a && bds[a] != null){
                    store.setValueAt(a,bds[a]);
                }else if(split!=null){
                    store.setValueAt(a,split[a]);
                }
            }
            for( var dbInsert:store.dbInsertSets())
                tableInserters.forEach(ti -> ti.insertStore(dbInsert));
            store.doCalVals();
        }
    }
    private void logResult(String data, String finalData){
        if( debug ){ // extra info given if debug is active
            Logger.info( id + "(mf) -> Before: "+data);   // how the data looked before
            Logger.info( id + "(mf) -> After:  "+finalData); // after applying the operations
        }

        if( log )
            Logger.tag("RAW").info( id() + "\t" + finalData);
    }
    private void showError(String error){
        showError(true,error);
    }
    private boolean showError(boolean count, String error){
        if( count)
            badDataCount++;
        if( badDataCount==1 && count) { // only need to do this the first time
            targets.stream().filter(t -> t.id().startsWith("editor")).forEach(t -> t.writeLine(id, "corrupt:1"));
        }
        if( badDataCount < 6) {
            if( !error.isEmpty())
                Logger.error(id+" (mf) -> "+error);
            return true;
        }
        if( badDataCount % 60 == 0 ) {
            if ((badDataCount < 900 || badDataCount % 600 == 0) && !error.isEmpty()) {
                    Logger.error(id+" (mf) -> "+error);
            }
            return true;
        }
        return false;
    }

    /**
     * Alternative addData method that takes doubles and just does the math.
     * @param numbers The number to apply the ops to
     * @return An optional Double array with the results
     */
    public ArrayList<Double> addData( ArrayList<Double> numbers ){
        // First check if the operations are actually valid
        if( !parsedOk ){
            showError("Not processing data because the operations aren't valid");
            return new ArrayList<>();
        }
        // Concatenate the values for error messages etc
        var data = numbers.stream().map(String::valueOf).collect(Collectors.joining(","));

        // Then make sure there's enough items in split
        if( !checkDataLength( data, numbers.size(), highestI )) return new ArrayList<>();

        // Convert the split data to BigDecimals and add references and temps
        var bdsOpt = convertDoublesToBigDecimal(numbers);
        if( bdsOpt.isEmpty()){
            badDataCount++;
            Logger.error(id+"(mf) -> Failed to convert to BigDecimals");
            return new ArrayList<>();
        }
        var bds = bdsOpt.get();

        // After doing all possible initial test, do the math
        if( !applyOperations(bds, data ) ){
            Logger.error(id+"(mf) -> Issues during math, returning empty array");
            return new ArrayList<>();
        }

        // If we got to this point, processing went fine so reset badcounts
        if( badDataCount !=0 )
            Logger.info(id+" (mf) -> Executed properly after previous issues, resetting bad count" );
        badDataCount=0;

        logResult(data,Arrays.stream(bds).map(BigDecimal::toPlainString).collect(Collectors.joining(",")));

        // Potentially store the data in memory and databases
        storeData(bds,null);

        // Convert the resulting BigDecimals back to doubles and into an arraylist
        return Arrays.stream(bds).map(BigDecimal::doubleValue).collect(Collectors.toCollection(ArrayList::new));
    }
    private Optional<BigDecimal[]> convertDoublesToBigDecimal(ArrayList<Double> numbers){
        BigDecimal[] bds;
        try {
            bds =numbers.stream().map(BigDecimal::valueOf).toArray(BigDecimal[]::new);
            var refOpt = buildRefBdArray();
            if( refOpt.isEmpty()) {
                Logger.error(id + "(mf) -> Failed to build ref bds");
                badDataCount++;
                return Optional.empty();
            }
            bds = ArrayUtils.addAll(bds,refOpt.get());
        }catch(Exception e){
            Logger.error(id+"(mf) -> "+e.getMessage());
            badDataCount++;
            return Optional.empty();
        }
        return Optional.of(bds);
    }
    /* ************************************** ADDING OPERATIONS **************************************************** */
    /**
     * Add an operation to this object
     * @param cmd Send the result as part of a command
     * @param expression The expression to use
     * @return True if it was added
     */
    public Optional<Operation> addStdOperation( String expression, int scale, String cmd ){

        // Remove spaces and replace ++ with +=1 etc
        expression = normalizeExpression(expression);

        if( !expression.contains("=") ) // If this doesn't contain a '=' it's no good
            return handleSingleIndexOp(expression,cmd,scale);

        //String exp = expression;
        var split = expression.split("[+-/*^]?="); // split into result and operation (i0 = i1+2 -> [i0][i1+2]

        handleCompoundAssignment(split, expression);

        // Process destination references (like i0, i1, t0, etc.)
        var dest = split[0].split(",");
        var map = getDestinationIndex(split, dest, expression );
        int index = map.getKey();
        expression = map.getValue();

        // Check if the destination is a realval or integerval and not an i
        if( (dest[0].toLowerCase().startsWith("{d") || dest[0].toLowerCase().startsWith("{r")) && dest.length==1) {
            if( split[1].matches("i[0-9]{1,3}")){ // the expression is only reference to an i
                return Optional.of(addOp( NumberUtils.toInt(split[1].substring(1),-1), expression,cmd,scale ));
            }else{
                index= -2;
            }
        }

        var exp = split[1];

        if( index == -1 ){
            Logger.warn(id + " -> Bad/No index given in "+expression);
        }

        for( var entry : defines.entrySet() ) // Check for the defaults and replace
            exp = exp.replace(entry.getKey(),entry.getValue());

        exp = replaceTemps(exp); // replace the tx's with ix's
        exp = replaceReferences(exp); // Replace the earlier found references with i's

        if( exp.isEmpty() ) {
            Logger.error(id+" (mf)-> Expression is empty after replacing refs?");
            return Optional.empty();
        }
        Operation op;
        if( NumberUtils.isCreatable(exp.replace(",","."))) {
            op = new Operation( expression, exp.replace(",","."),index);
        }else{
            var fab = MathFab.newFormula(exp.replace(",","."));
            if( fab.isValid() ) { // If the formula could be parsed
                op = new Operation(expression, fab, index); // create an operation
            }else{
                Logger.error(id+" (mf)-> Failed to build mathfab");
                parsedOk=false;
                readOk=false;
                return Optional.empty(); // If not, return empty
            }
        }
        op.scale(scale).cmd(cmd);

        ops.add(op);
        rulesString.add(new String[]{"complex", String.valueOf(index),expression});
        return Optional.of(op); // return the one that was added last
    }
    private String normalizeExpression( String expression ){
        // Support ++ and --
        return expression.replace("++","+=1")
                .replace("--","-=1")
                .replace(" ",""); //remove spaces
    }
    private Optional<Operation> handleSingleIndexOp( String expression, String cmd, int scale ){
        if(expression.matches("i[0-9]{1,3}")){ // unless the expression is a reference?
            return Optional.of(addOp( NumberUtils.toInt(expression.substring(1),-1),expression,cmd,scale));
        }else {
            Logger.error(id+ "(mf) -> Not a valid expression: "+expression);
            return Optional.empty();
        }
    }
    private void handleCompoundAssignment(String[] split, String exp){
        // The expression might be a simple i0 *= 2, so replace such with i0=i0*2 because of the way it's processed
        // A way to know this is the case, is that normally the summed length of the split items is one lower than
        // the length of the original expression (because the = ), if not that means an operand was in front of '='
        int lengthAfterSplit = split[0].length()+split[1].length();
        if( lengthAfterSplit+1 != exp.length()){ // Support += -= *= and /= fe. i0+=1
            String[] spl = exp.split("="); //[0]:i0+ [1]:1
            split[1]=spl[0]+split[1]; // split[1]=i0+1
        }
    }
    private Map.Entry<Integer,String> getDestinationIndex(String[] split, String[] dest, String expression ){
          // It's allowed that the result is written to more than one destination
        int index = Tools.parseInt(dest[0].substring(1),-1); // Check if it's in the first or only position
        if( dest.length==2){
            if( index == -1){ // if not and there's a second one
                index = Tools.parseInt(dest[1].substring(1),-1); //check if it's in the second one
            }else{
                expression = expression.replace(split[0],dest[1]+","+dest[0]); //swap the {d to front
            }
        }

        // Fix index if targeting a temp?
        if( dest[0].startsWith("t")){
            index += highestI+1;
        }
        return new AbstractMap.SimpleEntry<>(index,expression);
    }

    public void addOperation(String index, int scale, OP_TYPE type, String cmd , String expression  ){

        expression=expression.replace(" ",""); //remove spaces

        String exp = expression;

        if( index.equalsIgnoreCase("-1") ){
            Logger.warn(id + " -> Bad/No index given in '"+cmd+"'|"+expression+" for "+type);
        }
        highestI = Math.max(highestI,NumberUtils.toInt(index,-1));

        exp=replaceTemps(exp); // replace the tx's with ix's
        exp=replaceReferences(exp); // replacec real/int/flag vals with i's

        if( exp.isEmpty() )
            return;

        Operation op;
        String[] indexes = exp.split(",");

        switch (type) {
            case LN ->
                    op = new Operation(expression, MathUtils.decodeBigDecimalsOp("i" + index, exp, "ln", 0), NumberUtils.toInt(index));
            case SALINITY -> {
                if (indexes.length != 3) {
                    Logger.error(id + " (mf)-> Not enough args for salinity calculation");
                    return;
                }
                op = new Operation(expression, Calculations.procSalinity(indexes[0], indexes[1], indexes[2]), NumberUtils.toInt(index));
            }
            case SVC -> {
                if (indexes.length != 3) {
                    Logger.error(id + " (mf)-> Not enough args for soundvelocity calculation");
                    return;
                }
                op = new Operation(expression, Calculations.procSoundVelocity(indexes[0], indexes[1], indexes[2]), NumberUtils.toInt(index));
            }
            case TRUEWINDSPEED -> {
                if (indexes.length != 5) {
                    Logger.error(id + " (mf)-> Not enough args for True wind speed calculation");
                    return;
                }
                op = new Operation(expression, Calculations.procTrueWindSpeed(indexes[0], indexes[1], indexes[2], indexes[3], indexes[4]), NumberUtils.toInt(index));
            }
            case TRUEWINDDIR -> {
                if (indexes.length != 5) {
                    Logger.error(id + " (mf)-> Not enough args for True wind direction calculation");
                    return;
                }
                op = new Operation(expression, Calculations.procTrueWindDirection(indexes[0], indexes[1], indexes[2], indexes[3], indexes[4]), NumberUtils.toInt(index));
            }
            case UTM -> op = new Operation(expression, GisTools.procToUTM(indexes[0], indexes[1],
                    Arrays.stream(index.split(",")).map(NumberUtils::toInt).toArray(Integer[]::new)), -1);
            case GDC -> op = new Operation(expression, GisTools.procToGDC(indexes[0], indexes[1],
                    Arrays.stream(index.split(",")).map(NumberUtils::toInt).toArray(Integer[]::new)), -1);
            default -> {
                return;
            }
        }
        addOp(op);

        if( scale != -1){ // Check if there's a scale op needed
            Function<BigDecimal[],BigDecimal> proc = x -> x[NumberUtils.toInt(index)].setScale(scale, RoundingMode.HALF_UP);
            var p = new Operation( expression, proc, NumberUtils.toInt(index)).cmd(cmd);
            if( addOp( p ))
                rulesString.add(new String[]{type.toString().toLowerCase(), index,"scale("+expression+", "+scale+")"});
        }else{
            op.cmd(cmd);
            rulesString.add(new String[]{type.toString().toLowerCase(), index,expression});
        }
    }
    private Operation addOp( int index, String expression, String cmd, int scale ){
        var op = new Operation( expression, index );
        op.cmd(cmd).scale(scale);
        if( addOp(op) )
            rulesString.add(new String[]{"complex", String.valueOf(index),expression});
        return op;
    }
    private boolean addOp( Operation op ){
        if( op == null ) {
            valid = false;
            Logger.error(id+"(mf) -> Tried to add a null operation, MathFormward is invalid");
            return false;
        }
        if( op.isValid()){
            valid=false;
            Logger.error(id+"(mf) -> Tried to add an invalid op, MathFormward is invalid");
        }
        ops.add(op);
        return true;
    }
    /**
     * Convert a string version of OP_TYPE to the enum
     * @return The resulting enum value
     */
    private OP_TYPE fromStringToOPTYPE(String optype) {
        switch(optype.toLowerCase()){

            case "scale": return OP_TYPE.SCALE;
            case "ln": return OP_TYPE.LN;
            case "salinity": return OP_TYPE.SALINITY;
            case "svc": return OP_TYPE.SVC;
            case "truewinddir": return OP_TYPE.TRUEWINDDIR;
            case "truewindspeed": return OP_TYPE.TRUEWINDSPEED;
            case "utm": return OP_TYPE.UTM;
            case "gdc": return OP_TYPE.GDC;
            case "complex":
            default:
                Logger.error(id+"(mf) -> Invalid op type given, using default complex");
                return OP_TYPE.COMPLEX;
        }
    }

    /**
     * Solve the operations based on the given data
     * @param data The data to use in solving the operations
     * @return The data after applying all the operations
     */
    public String solveFor(String data){

        String[] split = data.split(delimiter);

        BigDecimal[] bds = buildBDArray(split);
        if( bds==null){
            Logger.error(id+"(mf) -> Bd's null after buildarray");
            return "";
        }
        ops.forEach( op -> op.solve(bds) );
        return insertBigDecimalsInData(bds,split);
    }

    /**
     * Method to use all the functionality but without persistence
     * @param op The formula to compute fe. 15+58+454/3 or a+52 if 'a' was defined
     * @return The result
     */
    public double solveOp( String op ){
        ops.clear();rulesString.clear();
        findRtvals("i0="+op);
        var opt = addStdOperation("i0="+op,-1,"");
        if( opt.isEmpty())
            return Double.NaN;
        return NumberUtils.toDouble(solveFor("0"),Double.NaN);
    }
    /* ************************************* R E F E R E N C E S *************************************************** */

    /**
     * Create a static numericalval
     * @param key The id  to use
     * @param val The value
     */
    public void addNumericalRef( String key, double val){
        if( referencedNums==null)
            referencedNums=new ArrayList<>();
        for( var v : referencedNums ) {
            if (v.id().equalsIgnoreCase("matrix_" + key)) {
                v.updateValue(val);
                return;
            }
        }
        referencedNums.add( RealVal.newVal("matrix",key).value(val) );
    }
    /**
     * Build the BigDecimal array based on received data and the local references.
     * From the received data only the part that holds used 'i's is converted (so if i1 and i5 is used, i0-i5 is taken)
     * @param data The data received, to be split
     * @return The created array
     */
    private BigDecimal[] buildBDArray(String[] data ){
        if( (referencedNums!=null && !referencedNums.isEmpty()) || !temps.isEmpty() ){
            var ref = buildRefBdArray();
            return ref.map(bigDecimals -> ArrayUtils.addAll(MathUtils.toBigDecimals(data, highestI == -1 ? 0 : highestI), bigDecimals)).orElse(null);
        }else{
            return MathUtils.toBigDecimals(data,highestI==-1?0:highestI); // Split the data and convert to big decimals
        }
    }
    private Optional<BigDecimal[]> buildRefBdArray(){
        var refBds = new BigDecimal[referencedNums.size()+temps.size()];

        // First add the temps so they can be requested easier by the store
        for (int a = 0; a < temps.size();a++ ){
            refBds[a]=temps.get(a);
        }
        for (int a = 0; a < referencedNums.size();a++ ){
            refBds[a+temps.size()]=referencedNums.get(a).toBigDecimal();
            if( refBds[a+temps.size()]==null ){
                Logger.error(id+"(mf) -> Failed to convert "+referencedNums.get(a).id()+" to BigDecimal");
                return Optional.empty();
            }
        }
        return Optional.of(refBds);
    }
    /**
     * Check the expression for references to:
     * - reals -> {r:id} or {real:id}
     * - flags -> {f:id} or {flag:id}
     * If found, check if those exist and if so, add them to the corresponding list
     *
     * @param exp The expression to check
     * @return True if everything went ok and all references were found
     */
    private boolean findRtvals(String exp){

        // Find all the double/int/flag pairs
        var pairs = Tools.parseKeyValue(exp,true);
        if( referencedNums==null)
            referencedNums = new ArrayList<>();
        int originalSize = referencedNums.size();

        for( var p : pairs ) {
            if (p.length == 2 || p.length == 1) {
                int nums = referencedNums.size(); // Store current size to later check if it increased
                var find = p[p.length - 1];
                if (referencedNums.stream().noneMatch(val -> val.id().equalsIgnoreCase(find))){
                    if (rtvals.hasReal(find))
                        rtvals.getRealVal(p[p.length - 1]).ifPresent(referencedNums::add);
                    if (rtvals.hasInteger(find))
                        rtvals.getIntegerVal(p[p.length - 1]).ifPresent(referencedNums::add);
                    if (rtvals.hasFlag(find))
                        rtvals.getFlagVal(p[p.length - 1]).ifPresent(referencedNums::add);
                    if( referencedNums.size()==nums) // No increase, so not found
                        Logger.error(id+ "(mf) -> Couldn't find val with id "+find);
                }
            }else{
                Logger.error(id+" (mf)-> Pair containing odd amount of elements: "+String.join(":",p));
            }
        }
        if( originalSize==referencedNums.size()){
            Logger.debug(id+"(mf) -> No vals found in "+exp);
        }
        // Find the highest used 'i' index
        var is = Pattern.compile("i[0-9]{1,2}")
                .matcher(exp)
                .results()
                .map(MatchResult::group)
                .sorted()
                .toArray(String[]::new);
        if( is.length!=0 ) {
            highestI = Math.max(highestI,Integer.parseInt(is[is.length-1].substring(1)));
            Logger.debug(id+"(mf) -> Highest I needed is "+highestI);
        }
        return true;
    }

    /**
     * Use the earlier found references and replace them with the corresponding index.
     * The indexes will be altered so that they match if the correct index of an array containing
     * - The received data split according to the delimiter up to the highest used index
     * - The realVals found
     * - The intVals found
     * - The flagVals found

     * So if highest is 5 then the first double will be 6 and first flag will be 5 + size of double list + 1
     *
     * @param exp The expression to replace the references in
     * @return The altered expression or an empty string if something failed
     */
    private String replaceReferences( String exp ){
        // Find the pairs in the expression
        for( var p : Tools.parseKeyValue(exp,true) ) {
            if (p.length == 2 || p.length==1) { // The pair should be an actual pair
                boolean ok=false; // will be used at the end to check if ok
                for (int pos = 0; pos < referencedNums.size(); pos++) { // go through the known Vals
                    var d = referencedNums.get(pos);
                    if (d.id().equalsIgnoreCase(p[p.length-1])) { // If a match is found
                        var repl = "{"+p[0]+"}";
                        if( p.length==2)
                            repl = "{"+p[0]+":"+p[1]+"}";
                        var i = "i" + (highestI + pos + temps.size() + 1);
                        exp = exp.replace(repl, i);
                        Logger.debug(id+"(mf) -> Replacing "+repl+" with "+i);
                        ok = true;
                        break;
                    }
                }
                if(!ok){
                    Logger.error(id+" (mf)-> Didn't find a match when looking for "+String.join(":",p));
                    return "";
                }
            }else{
                Logger.error(id+" (mf)-> Pair containing to many elements: "+String.join(":",p));
                return "";
            }
        }
        return exp;
    }
    private String replaceTemps( String exp ){
        int index = highestI+1;
        var ts = Pattern.compile("\bt[0-9]+")
                .matcher(exp)
                .results()
                .map(MatchResult::group)
                .toArray(String[]::new);
        for( var t : ts ){
            int in = Integer.parseInt(t.substring(1));
            try {
                exp = exp.replace(t, "i" + (index + in));
            }catch (NumberFormatException e){
                Logger.error(e);
            }
        }
        return exp;
    }
    /**
     * Inserts BigDecimal values into the provided data array, replacing original data where applicable.
     * This method takes in a set of BigDecimal values and the corresponding original data (as strings),
     * and inserts the BigDecimal values into the data at the specified indices, where they are available.
     * For indices greater than the highest valid index, the original string data is retained.
     * If no BigDecimal value is found at a given index, the original string data is used as a fallback.
     *
     * @param bds An array of BigDecimal values to insert into the data. Each element corresponds to
     *            an index in the `split` array.
     * @param split An array of original string data that will be processed. If no BigDecimal value is
     *              available for a particular index, the original string data will be retained.
     * @return A string containing the modified data, with BigDecimal values inserted where available.
     */
    private String insertBigDecimalsInData(BigDecimal[] bds, String[] split ){
        StringJoiner join = new StringJoiner(delimiter); // prepare a joiner to rejoin the data
        int validHighestIndex = (highestI == -1) ? 0 : highestI; // Determine the valid highest index
        for( int a=0;a<split.length;a++){
            if( a <= validHighestIndex ) {
                // Add BigDecimal value if available, else use original data from split
                join.add(bds[a] != null ? bds[a].toPlainString() : split[a]);
            }else{
                // For indices greater than the highest, add original split data
                join.add(split[a]);
            }
        }
        return appendSuffix( join.toString() );
    }

    /* ************************************* O P E R A T I O N ***************************************************** */
    /**
     * Storage class for everything related to an operation.
     * Contains the functions that
     */
    public class Operation {
        Function<BigDecimal[],BigDecimal> op=null; // for the scale type
        MathFab fab=null;    // for the complex type
        int index;           // index for the result
        int scale=-1;
        String ori;          // The expression before it was decoded mainly for listing purposes
        String cmd ="";      // Command in which to replace the $ with the result
        NumericVal update;
        BigDecimal directSet;

        public Operation(String ori,int index){
            this.ori=ori;
            this.index=index;

            if( ori.contains(":") && ori.indexOf(":")<ori.indexOf("=") ) { // If this contains : it means it has a reference
                try {
                    String sub = ori.substring(ori.indexOf(":") + 1, ori.indexOf("}"));

                    String val = ori.substring(ori.indexOf(":") + 1, ori.indexOf("}") + 1);
                    if (ori.startsWith("{r")||ori.startsWith("{d")) {
                        rtvals.getRealVal(sub)
                                .ifPresent(dv -> {
                                    update = dv;
                                    doUpdate = true;
                                });
                        if (!doUpdate)
                            Logger.error("Asking to update {r:" + val + " but doesn't exist");
                    } else if (ori.startsWith("{i")) {
                        rtvals.getIntegerVal(sub)
                                .ifPresent(iv -> {
                                    update = iv;
                                    doUpdate = true;
                                });
                        if (!doUpdate)
                            Logger.error("Asking to update {i:" + val + " but doesn't exist");
                    }else{
                        Logger.error( "No idea what to do with "+ori);
                    }
                }catch(IndexOutOfBoundsException e ){
                    Logger.error( id+" (mf) -> Index out of bounds: "+e.getMessage());
                }
            }
        }
        public Operation(String ori, Function<BigDecimal[],BigDecimal> op, int index ){
            this(ori,index);
            this.op=op;
        }
        public Operation(String ori, MathFab fab, int index ){
            this(ori,index);
            if( fab.isValid())
                this.fab=fab;
        }
        public Operation(String ori, String value, int index ){
            this(ori,index);
            this.directSet = NumberUtils.createBigDecimal(value);
        }
        public boolean isValid(){
            return op!=null || fab!=null;
        }
        public Operation scale(int scale ){
            this.scale=scale;
            return this;
        }
        public Operation cmd(String cmd){
            if( cmd.isEmpty())
                return this;
            this.cmd=cmd;
            valid=true;
            doCmd = true;

            if( ((cmd.startsWith("real:update")||cmd.startsWith("rv")) && cmd.endsWith(",$"))  ){
                String val = cmd.substring(8).split(",")[1];
                this.cmd = rtvals.getRealVal(val).map(dv-> {
                    update=dv;
                    doUpdate=true;
                    return "";
                } ).orElse(cmd);
            }
            return this;
        }
        public BigDecimal solve( BigDecimal[] data ){
            BigDecimal bd;
            boolean changeIndex=true;
            if( op != null ) { // If there's an op, use it
                var bdOpt = solveWithOp(data);
                if( bdOpt.isEmpty())
                    return null;
                bd = bdOpt.get();
            }else if(fab!=null){ // If no op, but a fab, use it
                var bdOpt = solveWithFab(data);
                if( bdOpt.isEmpty())
                    return null;
                bd = bdOpt.get();
            }else if( directSet!= null ){ // Might be just direct set
                bd = directSet;
            }else if(index!=-1){
                if( data[index]==null){
                    showError(false," (mf) -> Index "+index+" in data is null");
                    return null;
                }
                bd = data[index];
                changeIndex=false;
            }else{
                return null;
            }

            if( scale != -1) // If scaling is requested
                bd=bd.setScale(scale,RoundingMode.HALF_UP);

            if( index>= 0 && index < data.length && changeIndex )
                data[index]=bd;

            if( update != null ) {
                update.updateValue(bd.doubleValue());
            }else if( !cmd.isEmpty()){
                Core.addToQueue(Datagram.system(cmd.replace("$", bd.toString())));
            }
            if(debug)
                Logger.info("Result of op: "+bd.toPlainString());
            return bd;
        }
        private Optional<BigDecimal> solveWithOp(BigDecimal[] data){
            if (data.length <= index){
                showError(false,"(mf) -> Tried to do an op with to few elements in the array (data=" + data.length + " vs index=" + index);
                return Optional.empty();
            }
            try {
                return Optional.of(op.apply(data));
            } catch (NullPointerException e) {
                if (showError(false,"(mf) -> Null pointer when processing for " + ori)){
                    StringJoiner join = new StringJoiner(", ");
                    Arrays.stream(data).map(String::valueOf).forEach(join::add);
                    Logger.error(id() + "(mf) -> Data: " + join);
                }
                return Optional.empty();
            }
        }
        private Optional<BigDecimal> solveWithFab( BigDecimal[] data){
            fab.setDebug(debug);
            fab.setShowError( showError(false,""));
            try {
                var bdOpt = fab.solve(data);
                if( bdOpt.isEmpty() ){
                    showError(false,"(mf) -> Failed to solve the received data");
                    return Optional.empty();
                }
                return bdOpt;
            }catch ( ArrayIndexOutOfBoundsException | ArithmeticException | NullPointerException e){
                showError(false,e.getMessage());
                return Optional.empty();
            }
        }
    }
    @Override
    public String toString(){
        StringJoiner join = new StringJoiner("\r\n" );
        join.add("math:"+id+ (sources.isEmpty()?"":" getting data from "+String.join( ";",sources)));
        // Make sure to show the def's
        if( !defines.isEmpty()) {
            join.add(" Defines");
            defines.forEach((key, value) -> join.add("   " + key + " = " + value));
        }
        // Add the ops
        join.add(" Ops");
        join.add(getRules());

        if(!targets.isEmpty()) {
            StringJoiner ts = new StringJoiner(", ", "    Targets: ", "");
            targets.forEach(x -> ts.add(x.id()));
            join.add(ts.toString());
        }
        if( store != null )
            join.add(store.toString());
        return join.toString();
    }
    /* ************************************************************************************************************* */
    @Override
    public boolean noTargets(){
        return super.noTargets() && !doCmd && !doUpdate;
    }
}
