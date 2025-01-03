package io.forward;

import util.data.RealtimeValues;
import util.data.RealVal;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.NumericVal;
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
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

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

    public MathForward(String id, String source, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals){
        super(id,source,dQueue,rtvals);
        valid = rtvals!=null;
    }
    public MathForward(Element ele, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals, HashMap<String,String> defs){
        super(dQueue,rtvals);
        if( defs !=null )
            defines.putAll(defs);
        readOk = readFromXML(ele);
    }
    public MathForward(Element ele, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals){
        super(dQueue,rtvals);
        readOk = readFromXML(ele);
    }
    public MathForward( Element ele, RealtimeValues rtvals ){
        super(null,rtvals);
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
        parsedOk=true;
        var dig = XMLdigger.goIn(math);
        if( !readBasicsFromXml(dig) )
            return false;

        // Reset the references
        if( referencedNums!=null)
            referencedNums.clear();

        highestI=-1;
        suffix = dig.attr("suffix","");
        ops.clear();
        String content = dig.value("");

        // Check if it Has content but no child elements, so a single op
        if( content != null && dig.peekOut("*").isEmpty() ){
            if( findReferences(content) ){ // Figure out the used references to vals and determine highest used index/i
                var op = addStdOperation(
                        content,
                        dig.attr("scale",-1),
                        dig.attr("cmd","")
                );
                if(op.isEmpty()){ // If no op could be parsed from the expression
                    parsedOk=false;
                    Logger.error(id+"(mf) -> No valid operation found in: "+content);
                    return false;
                }else{ // Processing ok, can stop here
                    return true;
                }
            }
            // Failed to find or parse references to real/int/flagvals
            Logger.error(id +"(mf) -> Failed to process references in "+content);
            parsedOk=false;
            return false;
        }

        // Check for other subnodes besides 'op' those will be considered def's to reference in the op
        dig.peekOut("*")
                .stream().filter( ele -> !ele.getTagName().equalsIgnoreCase("op"))
                        .forEach( def -> {
                            var val = def.getTextContent().replace(",",".");
                            if( def.getTagName().equalsIgnoreCase("def")){
                                defines.put( def.getAttribute("ref"),val);
                            }else{
                                defines.put( def.getTagName(),val);
                            }
                        });

        boolean oldValid=valid; // Store the current state of valid
        // First go through all the ops and to find all references to real/int/flag and determine highest i(ndex) used
        for( var ops : dig.peekOut("op") ){
            // Find all the temps, and prep space for them
            var ts = Pattern.compile("t[0-9]{1,2}")
                    .matcher(ops.getTextContent())
                    .results()
                    .map(MatchResult::group)
                    .distinct()
                    .toArray(String[]::new);
            for( var t : ts) {
                try {
                    int pos = Integer.parseInt(t.substring(1));
                    while( pos >= temps.size())
                        temps.add(BigDecimal.ZERO);
                }catch(NumberFormatException e){
                    Logger.error(id+"(mf) -> Bad temp number: "+t);
                }
            }
            // Find all the references
            if( !findReferences(ops.getTextContent())) {
                parsedOk=false; //Parsing failed, so set the flag and return
                return false;
            }
        }
        // Go through the op's again to actually process the expression
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
        if( !oldValid && valid )// If math specific things made it valid
            sources.forEach( source -> dQueue.add( Datagram.build( source ).label("system").writable(this) ) );
        referencedNums.trimToSize(); // Won't be changed after this, so trime excess space
        return true;
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

        String[] split = data.split(delimiter); // Split the data according to the delimiter

        // Then make sure there's enough items in split
        if( split.length < highestI+1){ // Need at least one more than the highestI (because it starts at 0)
            showError("Need at least "+(highestI+1)+" items after splitting: "+data+ ", got "+split.length+" (bad:"+badDataCount+")");
            return true; // Stop processing
        }
        // Convert the split data to bigdecimals and add references and temps
        BigDecimal[] bds = buildBDArray(split);
        if( bds==null){
            return true;
        }

        // First do a global check, if none of the items is a number, no use to keep trying
        if( bds.length==0 ){
            showError("No valid numbers in the data: "+data+" after split on "+delimiter+ " "+ " (bad:"+badDataCount+")");
            return true;
        }
        // We know that the 'highest needed index' needs to actually be a number
        if( bds[highestI]==null){
            showError("No valid highest I value in the data: "+data+" after split on "+delimiter+ " "+ " (bad:"+badDataCount+")");
            return true;
        }

        // After doing all possible initial test, do the math
        int cnt=0;
        for( var op : ops ){
            var res = op.solve(bds);
            if (res == null) {
                cnt++;
                showError(cnt==1,"Failed to process " + data + " for "+op.ori);
            }
        }
        if( cnt > 0 ) {
            return true;
        }
        // If we got to this point, processing went fine so reset badcounts
        if( badDataCount !=0 )
            Logger.info(id+" (mf) -> Executed properly after previous issues, resetting bad count" );
        badDataCount=0;

        StringJoiner join = new StringJoiner(delimiter); // prepare a joiner to rejoin the data
        for( int a=0;a<split.length;a++){
            if( a <= (highestI==-1?0:highestI) ) {
                join.add(bds[a] != null ? bds[a].toPlainString() : split[a]); // if no valid bd is found, use the original data
            }else{
                join.add(split[a]);
            }
        }

        // append suffix
        String result = switch( suffix ){
                            case "" -> join.toString();
                            case "nmea" -> join+"*"+MathUtils.getNMEAchecksum(join.toString());
                            default -> {
                                Logger.error(id+" (mf)-> No such suffix "+suffix);
                                yield join.toString();
                            }
        };

        if( debug ){ // extra info given if debug is active
            Logger.info(id()+" -> Before: "+data);   // how the data looked before
            Logger.info(id()+" -> After:  "+result); // after applying the operations
        }

        // Use multithreading so the writables don't have to wait for the whole process
        nextSteps.parallelStream().forEach( ns -> ns.writeLine(id(),result));
        targets.parallelStream().forEach( wr -> wr.writeLine(id(),result));

        if( log )
            Logger.tag("RAW").info( id() + "\t" + result);
        if( store!=null) {
            for( int a=0;a<store.size();a++){
                if( bds.length > a && bds[a] != null){
                    store.setValueAt(a,bds[a]);
                }else{
                    store.setValueAt(a,split[a]);
                }
            }

            for( var dbInsert:store.dbInsertSets())
                tableInserters.forEach(ti -> ti.insertStore(dbInsert));
            store.doCalVals();
        }

        if( !cmds.isEmpty())
            cmds.forEach( cmd->dQueue.add(Datagram.system(cmd).writable(this)));

        // If there are no target, no label and no ops that build a command, this no longer needs to be a target
        return !noTargets() || log || store != null;
    }
    private void showError(String error){
        showError(true,error);
    }
    private boolean showError(boolean count, String error){
        if( count)
            badDataCount++;
        if( badDataCount==1 && count) { // only need to do this the first time
            targets.stream().filter( t -> t.id().startsWith("editor")).forEach(t -> t.writeLine("corrupt:1"));
        }
        if( badDataCount < 6) {
            if( !error.isEmpty())
                Logger.error(id+" (mf) -> "+error);
            return true;
        }
        if( badDataCount % 60 == 0 ) {
            if(badDataCount < 900 || badDataCount%600==0){
                if( !error.isEmpty())
                    Logger.error(id+" (mf) -> "+error);
            }
            return true;
        }
        return false;
    }

    /**
     * Alternative addData method that takes integers and just does the math.
     * @param numbers The number to apply the ops to
     * @return An optional Double array with the results
     */
    public ArrayList<Double> addData( ArrayList<Double> numbers ){
        // First check if the operations are actually valid
        if( !readOk ){
            showError("Not processing data because the operations aren't valid");
            return new ArrayList<>();
        }

        // Then make sure there's enough items in split
        if( numbers.size() < highestI+1){ // Need at least one more than the highestI (because it starts at 0)
            showError("Need at least "+(highestI+1)+" items, got "+numbers.size() );
            return new ArrayList<>();
        }

        // Convert the split data to bigdecimals and add references and temps
        BigDecimal[] bds = new BigDecimal[numbers.size()];
        try {
            for( int a=0;a<numbers.size();a++)
                bds[a]= BigDecimal.valueOf(numbers.get(a));
            var refOpt = buildRefBdArray();
            if( refOpt.isEmpty()) {
                Logger.error(id + "(mf) -> Failed to build ref bds");
                return new ArrayList<>();
            }
            bds = ArrayUtils.addAll(bds,refOpt.get());
        }catch(Exception e){
            Logger.error(id+"(mf) -> "+e.getMessage());
            return new ArrayList<>();
        }

        // After doing all possible initial test, do the math
        int cnt=0;
        for( var op : ops ){
            if (op.solve(bds) == null) {
                cnt++;
                showError(cnt==1,"(mf) -> Failed to process " + numbers + " for "+op.ori);
            }
        }
        if( cnt > 0 ) {
            Logger.error(id+"(mf) -> Issues during math, returning empty array");
            return new ArrayList<>();
        }
        // If we got to this point, processing went fine so reset badcounts
        if( badDataCount !=0 )
            Logger.info(id+" (mf) -> Executed properly after previous issues, resetting bad count" );
        badDataCount=0;

        if( debug ){ // extra info given if debug is active
            Logger.info(id()+" -> Before: "+numbers);   // how the data looked before
            Logger.info(id()+" -> After:  "+Arrays.toString(bds)); // after applying the operations
        }

        if( log )
            Logger.tag("RAW").info( id() + "\t" + Arrays.toString(bds));

        ArrayList<Double> results = new ArrayList<>();
        for (BigDecimal bd : bds)
            results.add(bd.doubleValue());

        if( store!=null) {
            for( int a=0;a<store.size();a++){
                if( bds.length > a && bds[a] != null){
                    store.setValueAt(a,bds[a]);
                }
            }
            for( var dbInsert:store.dbInsertSets())
                tableInserters.forEach(ti -> ti.insertStore(dbInsert));
            store.doCalVals();
        }
        return results;
    }
    /* ************************************** ADDING OPERATIONS **************************************************** */
    /**
     * Add an operation to this object
     * @param cmd Send the result as part of a command
     * @param expression The expression to use
     * @return True if it was added
     */
    public Optional<Operation> addStdOperation( String expression, int scale, String cmd ){

        // Support ++ and --
        expression = expression.replace("++","+=1")
                               .replace("--","-=1")
                               .replace(" ",""); //remove spaces

        if( !expression.contains("=") ) {// If this doesn't contain a '=' it's no good
            if(expression.matches("i[0-9]{1,3}")){ // unless the expression is a reference?
                var op = new Operation( expression, NumberUtils.toInt(expression.substring(1),-1));
                op.setCmd(cmd);
                op.setScale(scale);
                rulesString.add(new String[]{"complex", String.valueOf(NumberUtils.toInt(expression.substring(1))),expression});
                addOp(op);
                return Optional.of(op);
            }else {
                Logger.error(id+ "(mf) -> Not a valid expression: "+expression);
                return Optional.empty();
            }
        }
        String exp = expression;
        var split = expression.split("[+-/*^]?="); // split into result and operation (i0 = i1+2 -> [i0][i1+2]

        // The expression might be a simple i0 *= 2, so replace such with i0=i0*2 because of the way it's processed
        // A way to know this is the case, is that normally the summed length of the split items is one lower than
        // the length of the original expression (because the = ), if not that means an operand was in front of '='
        if( split[0].length()+split[1].length()+1 != exp.length()){ // Support += -= *= and /= fe. i0+=1
            String[] spl = exp.split("="); //[0]:i0+ [1]:1
            split[1]=spl[0]+split[1]; // split[1]=i0+1
        }

        var dest = split[0].split(",");  // It's allowed that the result is written to more than one destination
        int index = Tools.parseInt(dest[0].substring(1),-1); // Check if it's in the first or only position
        if( index == -1 && dest.length==2){ // if not and there's a second one
            index = Tools.parseInt(dest[1].substring(1),-1); //check if it's in the second one
        }else if(dest.length==2){
            expression=expression.replace(split[0],dest[1]+","+dest[0]); //swap the {d to front
        }
        // Fix index if targeting a temp?
        if( dest[0].startsWith("t")){
            index += highestI+1;
        }
        // Check if the destination is a realval or integerval and not an i
        if( (dest[0].toLowerCase().startsWith("{d") || dest[0].toLowerCase().startsWith("{r")) && dest.length==1) {
            if( split[1].matches("i[0-9]{1,3}")){ // the expression is only reference to an i
                var op = new Operation( expression, NumberUtils.toInt(split[1].substring(1),-1));
                op.setCmd(cmd);
                op.setScale(scale);

                if( addOp(op) )
                    rulesString.add(new String[]{"complex", String.valueOf(NumberUtils.toInt(split[1].substring(1))),expression});
                return Optional.of(op);
            }else{
                index = -2;
            }

        }
        exp = split[1];

        if( index == -1 ){
            Logger.warn(id + " -> Bad/No index given in "+expression);
        }

        for( var entry : defines.entrySet() ){ // Check for the defaults and replace
            exp = exp.replace(entry.getKey(),entry.getValue());
        }

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
        op.setScale(scale);
        op.setCmd(cmd);

        ops.add(op);

        rulesString.add(new String[]{"complex", String.valueOf(index),expression});
        return Optional.ofNullable(ops.get(ops.size()-1)); // return the one that was added last
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
            var p = new Operation( expression, proc, NumberUtils.toInt(index)).setCmd(cmd);
            if( addOp( p ))
                rulesString.add(new String[]{type.toString().toLowerCase(), index,"scale("+expression+", "+scale+")"});
        }else{
            op.setCmd(cmd);
            rulesString.add(new String[]{type.toString().toLowerCase(), index,expression});
        }
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
            default:
                Logger.error(id+"(mf) -> Invalid op type given, using default complex");
            case "complex": return OP_TYPE.COMPLEX;
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

        StringJoiner join = new StringJoiner(delimiter); // prepare a joiner to rejoin the data
        for( int a=0;a<split.length;a++){
            if( a <= highestI ) {
                join.add(bds[a] != null ? bds[a].toPlainString() : split[a]); // if no valid bd is found, use the original data
            }else{
                join.add(split[a]);
            }
        }
        return join.toString();
    }

    /**
     * Method to use all the functionality but without persistence
     * @param op The formula to compute fe. 15+58+454/3 or a+52 if 'a' was defined
     * @return The result
     */
    public double solveOp( String op ){
        ops.clear();rulesString.clear();
        findReferences("i0="+op);
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
            if( ref.isPresent())
                return ArrayUtils.addAll(MathUtils.toBigDecimals(data,highestI==-1?0:highestI), ref.get());
            return null;
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
    private boolean findReferences(String exp){

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
        public void setScale( int scale ){
            this.scale=scale;
        }
        public Operation setCmd(String cmd){
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
            if( op != null ) {
                if (data.length <= index){
                    showError(false,"(mf) -> Tried to do an op with to few elements in the array (data=" + data.length + " vs index=" + index);
                    return null;
                }
                try {
                    bd = op.apply(data);
                } catch (NullPointerException e) {
                    if (showError(false,"(mf) -> Null pointer when processing for " + ori)){
                        StringJoiner join = new StringJoiner(", ");
                        Arrays.stream(data).map(String::valueOf).forEach(join::add);
                        Logger.error(id() + "(mf) -> Data: " + join);
                    }
                    return null;
                }
            }else if(fab!=null){
                fab.setDebug(debug);
                fab.setShowError( showError(false,""));
                try {
                    var bdOpt = fab.solve(data);
                    if( bdOpt.isEmpty() ){
                        showError(false,"(mf) -> Failed to solve the received data");
                        return null;
                    }
                    bd=bdOpt.get();
                }catch ( ArrayIndexOutOfBoundsException | ArithmeticException | NullPointerException e){
                    showError(false,e.getMessage());
                    return null;
                }
            }else if( directSet!= null ){
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
                dQueue.add(Datagram.system(cmd.replace("$", bd.toString())));
            }
            if(debug)
                Logger.info("Result of op: "+bd.toPlainString());
            return bd;
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
