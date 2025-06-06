package util.evalcore;

import org.tinylog.Logger;
import util.data.procs.DoubleArrayToDouble;

import java.util.Arrays;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

public class LogicEvaluator extends BaseEvaluator implements Evaluator {

    private record LogicOperation(DoubleArrayToDouble func, double logic) {
    }

    LogicOperation[] logicOps;
    double[] scratchpad;

    LogicEvaluator( String ori, String normalized, String parseResult, int ops ){
        logicOps = new LogicOperation[ops];
        originalExpression=ori;
        this.normalizedExpression =normalized;
        this.parseResult=parseResult;
    }
    /* ***************************** Set up the class ************************************************* */
    void addOp(int index, DoubleArrayToDouble func, double logic) {
        logicOps[index] = new LogicOperation(func,logic);
    }
    void setRefLookup( Integer[] refLookup ){
        this.refLookup=refLookup;
        scratchpad = new double[refLookup.length];
    }

    /* *********************************** Do evaluation ************************************************ */

    /**
     * Fills the scratchpad with data from the inputs and rtvals according to the lookup table.
     * @param inputs The inputs received for evaluation.
     * @return True if the scratchpad was successfully filled, false otherwise.
     */
    private boolean buildScratchpad(double[] inputs){
        if (badInputCount(inputs.length, DoubleStream.of(inputs).mapToObj(String::valueOf).collect(Collectors.joining(","))))
            return false;

        for( int a=0;a<refLookup.length;a++ ){
            var r = refLookup[a];

            double val;
            if( r<100 && r < inputs.length-1 ) {
                val = inputs[r];
            } else if (refs != null && r - 100 < refs.length) {
                val = refs[r - 100].asDouble();
            }else{
                Logger.error("Scratchpad couldn't be filled for index "+a+" from r"+r+" due to out of bounds");
                return false;
            }
            if( Double.isNaN(val)) {
                Logger.error("Scratchpad entry at "+a+" coming from r"+r+" is (still) null.");
                return false;
            }
            scratchpad[a]=val;
        }
        return true;
    }

    public boolean logicEval(double... inputs) {
        if( ! buildScratchpad(inputs) )
            return false;

        for( var op : logicOps ){
            var res = Double.compare( op.func().apply(scratchpad),1.0)==0;
            if( res || Double.isNaN(op.logic()))
                return res;
        }
        return false;
    }
    public Optional<Boolean> eval( String data, String delimiter ){
        double[] inputs;
        try {
            inputs = Arrays.stream(data.split(delimiter))
                    .mapToDouble(Double::parseDouble)
                    .toArray();
        } catch (NumberFormatException ex) {
            Logger.error("Failed to parse input data: " + data);
            return Optional.empty(); // or however you want to handle the failure
        }
        return Optional.of(logicEval(inputs));
    }
    /* ************************  Get Debug information ******************************************* */

    /**
     * Provides an overview of the current state of the scratchpad register.
     * Includes details about the origin of the values stored.
     * @return A string representation of the current scratchpad state.
     */
    public String getCurrentScratchpad(){
        var info = new StringJoiner("\r\n");
        info.add("Scratchpad state");
        for( int a=0;a<scratchpad.length;a++ ){
            var extra = "";
            if( refLookup[a]>=100 ) {
                extra = " (" + refs[refLookup[a]-100].id() + ")";
            }else{
                extra = " ( i"+refLookup[a]+")";
            }

            info.add("r"+refLookup[a]+": "+scratchpad[a]+extra);
        }
        return info.toString();
    }
    public String getInfo(){
        var info = new StringJoiner("\r\n");
        info.add( "Original: "+originalExpression );
        info.add( "Normalized: "+ normalizedExpression );
        info.add( "Expected input: "+highestI+" items" );
        info.add( "");
        info.add( "Parse result before conversion" );
        info.add( parseResult );
        info.add( "" );
        info.add( getCurrentScratchpad() );

        return info.toString();
    }
}
