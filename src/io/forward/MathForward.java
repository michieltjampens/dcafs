package io.forward;

import das.Core;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.NumericVal;
import util.data.RealtimeValues;
import util.math.MathOpFab;
import util.math.MathOperation;
import util.math.MathUtils;
import util.xml.XMLdigger;
import worker.Datagram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.StringJoiner;

public class MathForward extends AbstractForward {

    private String suffix="";

    private final ArrayList<Operation> ops = new ArrayList<>();

    private boolean doCmd = false;
    private boolean doUpdate=false;

    public MathForward(String id, String source, RealtimeValues rtvals){
        super(id,source,rtvals);
        valid = rtvals!=null;
    }

    public MathForward(Element ele, RealtimeValues rtvals) {
        super(rtvals);
        readOk = readFromXML(ele);
    }
    @Override
    public String getRules(){
        int index=0;
        var join = new StringJoiner("\r\n");
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

        reset(); // Reset everything to start

        suffix = dig.attr("suffix", "");

        // Check for other subnodes besides 'op' those will be considered def's to reference in the op
        var defines = digForDefinedConstants(dig);

        boolean oldValid = valid; // Store the current state of valid

        // Find all the references to realtime values
        for (var op : dig.digOut("op")) {

            var expression = op.value("");

            // Replace the defines
            for (var define : defines.entrySet())
                expression = expression.replace(define.getKey(), define.getValue());

            Optional<MathOperation> mopOpt;
            if (ops.isEmpty()) {
                mopOpt = MathOpFab.withExpression(expression, rtvals).getMathOp();
            } else {
                mopOpt = MathOpFab.withExpression(expression, rtvals, ops.get(ops.size() - 1).getValRefs()).getMathOp();
            }
            if (mopOpt.isEmpty()) {
                Logger.error(id + "(mf) -> Failed to parse " + op.value(""));
                return false;
            }
            var mop = mopOpt.get();
            if (mop.getResultIndex() % 200 < 100) //meaning an rtval
                doUpdate = true;

            var operation = new Operation(op.value(""), mop);
            operation.scale(op.attr("scale", -1));
            operation.cmd(op.attr("cmd", ""));
            ops.add(operation);
            rulesString.add(new String[]{"complex", String.valueOf(mop.getResultIndex()), expression});
        }

        if( !oldValid && valid )// If math specific things made it valid
            sources.forEach( source -> Core.addToQueue( Datagram.system( source ).writable(this) ) );

        return true;
    }

    private void reset() {
        // Reset everything that will be altered
        parsedOk = true;
        doUpdate = false;
        doCmd = false;
        ops.clear();
        rulesString.clear();
    }
    /**
     * Check the node for references to static values and odd those to the collection
     * @param dig A digger pointing to the MathForward
     */
    private static HashMap<String, String> digForDefinedConstants(XMLdigger dig) {
        HashMap<String, String> defines = new HashMap<>();
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
        return defines;
    }

    /**
     * Give data to this forward for processing
     * @param data The data received
     * @return True if given to a target afterward, false if not
     */
    @Override
    protected boolean addData(String data) {

        // First check if the operations are actually valid
        if (!parsedOk || ops.isEmpty()) {
            Logger.error(id + "(mf)->Not processing data because the operations aren't valid");
            return true;
        }

        // Apply the operations
        BigDecimal[] bds = ops.get(0).solveBDs(data);
        for (int index = 1; index < ops.size(); index++) {
            ops.get(index).continueBDs(data, bds);
        }
        if (bds == null) {
            Logger.error(id + "(mf) -> Something went wrong processing the data.");
            return true;
        }
        // Overwrite the original data with the calculated values if applicable.
        var combined = recombineData(data, bds);
        // Forward and store the results
        return forwardData(data, combined, bds);
    }

    public ArrayList<Double> addData(ArrayList<Double> data) {
        // First check if the operations are actually valid
        if (!parsedOk || ops.isEmpty()) {
            Logger.error(id + "(mf)->Not processing data because the operations aren't valid");
            return data;
        }
        // Apply the operations
        BigDecimal[] bds = ops.get(0).solveDoubles(data);
        for (int index = 1; index < ops.size(); index++) {
            ops.get(index).continueDoubles(data, bds);
        }
        if (bds == null) {
            Logger.error(id + "(mf) -> Something went wrong processing the data.");
            return data;
        }
        for (int index = 0; index < bds.length; index++) {
            if (bds[index] != null)
                data.set(index, bds[index].doubleValue());
        }
        return data;
    }
    private String recombineData(String data, BigDecimal[] bds) {
        // Recreate the data stream
        var splitData = data.split(delimiter);
        var join = new StringJoiner(delimiter);
        for (int index = 0; index < bds.length; index++) {
            if (bds[index] != null) {
                join.add(bds[index].toPlainString());
            } else {
                join.add(splitData[index]);
            }
        }
        return join.toString();
    }

    private boolean forwardData(String data, String joined, BigDecimal[] bds) {
        var finalData = appendSuffix(suffix, joined);
        // Use multithreading so the Writable's don't have to wait for the whole process
        nextSteps.forEach(ns -> ns.writeLine(id(), finalData));
        targets.forEach(wr -> wr.writeLine(id(), finalData));

        logResult(data, finalData);

        // Potentially store the data in memory and databases
        storeData(bds, data.split(delimiter));

        if (!cmds.isEmpty())
            cmds.forEach(cmd -> Core.addToQueue(Datagram.system(cmd).writable(this)));
        // If there are no target, no label and no ops that build a command, this no longer needs to be a target
        return !noTargets() || log || store != null;
    }


    private static String appendSuffix(String suffix, String data) {
       return switch( suffix ) {
           case "" -> data;
           case "nmea" -> data + "*" + MathUtils.getNMEAchecksum(data);
           default -> {
               Logger.error(" (mf)-> No such suffix " + suffix);
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

    /* ************************************* O P E R A T I O N ***************************************************** */
    /**
     * Storage class for everything related to an operation.
     * Contains the functions that
     */
    public class Operation {
        MathOperation mop;
        int scale=-1;
        String ori;          // The expression before it was decoded mainly for listing purposes
        String cmd ="";      // Command in which to replace the $ with the result
        NumericVal update;

        public Operation(String ori, MathOperation mop) {
            this.ori=ori;
            this.mop = mop;
        }

        public NumericVal[] getValRefs() {
            return mop.getValRefs();
        }

        /* If the input data is a string */
        public BigDecimal[] solveBDs(String data) {
            var bds = mop.solveRaw(data, delimiter);
            applyScale(bds);
            return bds;
        }
        public void continueBDs(String data, BigDecimal[] bds) {
            mop.continueRaw(data, delimiter, bds);
            applyScale(bds);
        }

        /* if the input data is a arraylist with doubles */
        public BigDecimal[] solveDoubles(ArrayList<Double> data) {
            var bds = mop.solveDoubles(data);
            applyScale(bds);
            return bds;
        }

        public void continueDoubles(ArrayList<Double> data, BigDecimal[] bds) {
            mop.continueDoubles(data, bds);
            applyScale(bds);
        }

        private void applyScale(BigDecimal[] bds) {
            if (scale != -1) {
                int index = mop.getResultIndex();
                bds[index] = bds[index].setScale(scale, RoundingMode.HALF_UP);
            }
        }
        public void scale(int scale) {
            this.scale=scale;
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

    }
    @Override
    public String toString(){
        StringJoiner join = new StringJoiner("\r\n" );
        join.add("math:"+id+ (sources.isEmpty()?"":" getting data from "+String.join( ";",sources)));
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
