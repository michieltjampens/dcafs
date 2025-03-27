package io.forward;

import das.Core;
import org.apache.commons.lang3.ArrayUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.NumericVal;
import util.data.RealVal;
import util.data.RealtimeValues;
import util.math.MathUtils;
import worker.Datagram;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

public class MathForward extends AbstractForward {

    private String suffix="";

    private final ArrayList<MathOperation> ops = new ArrayList<>();
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

    public ArrayList<NumericVal> getReferencedNums() {
        return referencedNums;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }

    public void setHighestI(int i) {
        highestI = Math.max(i, highestI);
    }

    public int getHighestI() {
        return highestI;
    }
    /* ***************************************** X M L ************************************************************ */
    /**
     * Get the tag that is used for the child nodes, this way the abstract class can refer to it
     * @return The child tag for this forward, parent tag is same with added s
     */
    protected String getXmlChildTag(){
        return "math";
    }
    @Override
    public boolean readFromXML(Element fwElement) {
        return false;
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
            Logger.error(id + "(mf) -> Not processing data because the operations aren't valid");
            return true;
        }

        // Split the data according to the delimiter
        String[] dataParts = data.split(delimiter);

        // Then make sure there's enough items in dataParts, need at least one more than the highestI (because it starts at 0)
        if (!checkDataLength(data, dataParts.length, highestI)) return true;

        // Convert to BigDecimals and do some checks
        var bdsOpt = convertToBigDecimals(data, dataParts);
        if( bdsOpt.isEmpty()) { // Something failed, abort
            badDataCount++;
            return true;
        }
        var bds = bdsOpt.get(); // Nothing failed, unpack

        // After doing all possible initial tests, do the math
        if (applyOperations(bds, data)) {
            badDataCount++;
            Logger.error(id + "(mf) -> Applying operation failed");
            return true;
        }

        // Insert the BigDecimals in the received data and apply any requested suffix
        final String finalData = insertBigDecimalsInData(bds, dataParts);

        // If we got to this point, processing went fine so reset badDataCount
        if( badDataCount != 0 )
            Logger.info(id + " (mf) -> Executed properly after previous issues, resetting bad count.");
        badDataCount=0;

        // Use multithreading so the Writable's don't have to wait for the whole process
        nextSteps.parallelStream().forEach( ns -> ns.writeLine(id(),finalData));
        targets.parallelStream().forEach( wr -> wr.writeLine(id(),finalData));

        logResult(data,finalData);

        // Potentially store the data in memory and databases
        storeData(bds, dataParts);

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
            Logger.error(id + "(mf) -> Need at least " + (minCount + 1) + " items after splitting: " + data + ", got " + receivedCount + " (bad:" + badDataCount + ")");
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
     * @param dataParts The array of strings obtained by splitting the data.
     * @return An {@link Optional} containing the array of {@link BigDecimal} values if the conversion is successful,
     *         or {@link Optional#empty()} if any validation or conversion fails.
     */
    private Optional<BigDecimal[]> convertToBigDecimals(String data, String[] dataParts) {
        // Convert the split data to BigDecimals and add references and temps
        BigDecimal[] bds = buildBDArray(dataParts);
        if( bds==null){
            return Optional.empty();
        }

        // Check if none of the items is a valid number
        if( bds.length==0 ){
            Logger.error(id + "(mf) -> No valid numbers in the data: " + data + " after split on " + delimiter + " " + " (bad:" + badDataCount + ")");
            return Optional.empty();
        }
        // Ensure the 'highest needed index' is a valid number
        if( bds[highestI]==null){
            Logger.error(id + "(mf) -> No valid highest I value in the data: " + data + " after split on " + delimiter + " " + " (bad:" + badDataCount + ")");
            return Optional.empty();
        }
        return Optional.of(bds);
    }

    /**
     * Build the BigDecimal array based on received data and the local references.
     * From the received data only the part that holds used 'i's is converted (so if i1 and i5 is used, i0-i5 is taken)
     *
     * @param data The data received, to be split
     * @return The created array
     */
    private BigDecimal[] buildBDArray(String[] data) {
        if ((referencedNums != null && !referencedNums.isEmpty()) || !temps.isEmpty()) {
            var ref = buildRefBdArray();
            return ref.map(bigDecimals -> ArrayUtils.addAll(MathUtils.toBigDecimals(data, highestI == -1 ? 0 : highestI), bigDecimals)).orElse(null);
        } else {
            return MathUtils.toBigDecimals(data, highestI == -1 ? 0 : highestI); // Split the data and convert to big decimals
        }
    }

    private Optional<BigDecimal[]> buildRefBdArray() {
        // First add the temps so they can be requested easier by the store
        var bds = new ArrayList<>(temps);
        for (NumericVal referencedNum : referencedNums) {
            var bd = referencedNum.toBigDecimal();
            if (bd == null) {
                Logger.error(id + "(mf) -> Failed to convert " + referencedNum.id() + " to BigDecimal");
                return Optional.empty();
            }
            bds.add(bd);
        }
        return Optional.of(bds.toArray(BigDecimal[]::new));
    }
    private boolean applyOperations(BigDecimal[] bigDecimals, String data) {
        return ops.stream().anyMatch(op -> {
            boolean failed = op.solve(bigDecimals) == null;
            if (failed)
                Logger.error(id + "(mf) -> Failed to process " + data + " for " + op.ori);
            return failed;
        });
    }

    private void storeData(BigDecimal[] bds, String[] dataParts){
        if( store!=null) {
            for( int a=0;a<store.size();a++){
                if( bds.length > a && bds[a] != null){
                    store.setValueAt(a,bds[a]);
                } else if (dataParts != null) {
                    store.setValueAt(a, dataParts[a]);
                }
            }
            for( var dbInsert:store.dbInsertSets())
                tableInserters.forEach(ti -> ti.insertStore(dbInsert));
            store.doCalVals();
        }
    }
    /**
     * Alternative addData method that takes doubles and just does the math.
     * @param numbers The number to apply the ops to
     * @return An optional Double array with the results
     */
    public ArrayList<Double> addData( ArrayList<Double> numbers ){
        // First check if the operations are actually valid
        if( !parsedOk) {
            Logger.error(id + "(mf) -> Not processing data because the operations aren't valid");
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
            badDataCount++;
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
                return Optional.empty();
            }
            bds = ArrayUtils.addAll(bds,refOpt.get());
        }catch(Exception e){
            Logger.error(id+"(mf) -> "+e.getMessage());
            return Optional.empty();
        }
        return Optional.of(bds);
    }
    /**
     * Inserts BigDecimal values into the provided data array, replacing original data where applicable.
     * This method takes in a set of BigDecimal values and the corresponding original data (as strings),
     * and inserts the BigDecimal values into the data at the specified indices, where they are available.
     * For indices greater than the highest valid index, the original string data is retained.
     * If no BigDecimal value is found at a given index, the original string data is used as a fallback.
     *
     * @param bds An array of BigDecimal values to insert into the data. Each element corresponds to
     *            an index in the `dataParts` array.
     * @param dataParts An array of original string data that will be processed. If no BigDecimal value is
     *              available for a particular index, the original string data will be retained.
     * @return A string containing the modified data, with BigDecimal values inserted where available.
     */
    private String insertBigDecimalsInData(BigDecimal[] bds, String[] dataParts ){
        StringJoiner join = new StringJoiner(delimiter); // prepare a joiner to rejoin the data
        int validHighestIndex = (highestI == -1) ? 0 : highestI; // Determine the valid highest index
        for (int a = 0; a < dataParts.length;a++){
            if( a <= validHighestIndex ) {
                // Add BigDecimal value if available, else use original data from split
                join.add(bds[a] != null ? bds[a].toPlainString() : dataParts[a]);
            }else{
                // For indices greater than the highest, add original split data
                join.add(dataParts[a]);
            }
        }
        return appendSuffix( join.toString() );
    }

    private String appendSuffix(String data) {
        return switch (suffix) {
            case "" -> data;
            case "nmea" -> data + "*" + MathUtils.getNMEAchecksum(data);
            default -> {
                Logger.error(id + " (mf)-> No such suffix " + suffix);
                yield data;
            }
        };
    }

    private void logResult(String data, String finalData) {
        if (debug) { // extra info given if debug is active
            Logger.info(id + "(mf) -> Before: " + data);   // how the data looked before
            Logger.info(id + "(mf) -> After:  " + finalData); // after applying the operations
        }

        if (log)
            Logger.tag("RAW").info(id() + "\t" + finalData);
    }
    /**
     * Solve the operations based on the given data
     * @param data The data to use in solving the operations
     * @return The data after applying all the operations
     */
  /*  public String solveFor(String data){

        String[] split = data.split(delimiter);

        BigDecimal[] bds = buildBDArray(split);
        if( bds==null){
            Logger.error(id+"(mf) -> Bd's null after buildarray");
            return "";
        }
        ops.forEach( op -> op.solve(bds) );
        return insertBigDecimalsInData(bds,split);
    }*/

    /**
     * Method to use all the functionality but without persistence
     * @param op The formula to compute fe. 15+58+454/3 or a+52 if 'a' was defined
     * @return The result
     */
  /*  public double solveOp( String op ){
        ops.clear();rulesString.clear();
        findRtvals("i0="+op);
        var opt = addStdOperation("i0="+op,-1,"");
        if( opt.isEmpty())
            return Double.NaN;
        return NumberUtils.toDouble(solveFor("0"),Double.NaN);
    }*/
    /* ************************************* R E F E R E N C E S *************************************************** */

    /**
     * Create a static numericalval
     *
     * @param key The id  to use
     * @param val The value
     */
    public void addNumericalRef(String key, double val) {
        if (referencedNums == null)
            referencedNums = new ArrayList<>();
        for (var v : referencedNums) {
            if (v.id().equalsIgnoreCase("matrix_" + key)) {
                v.updateValue(val);
                return;
            }
        }
        referencedNums.add(RealVal.newVal("matrix", key).value(val) );
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
