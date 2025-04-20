package util.tasks.blocks;

import org.tinylog.Logger;
import util.data.NumericVal;
import util.data.RealtimeValues;
import util.evalcore.LogicEvaluator;

import java.util.ArrayList;
import java.util.Optional;

public class ConditionBlock extends AbstractBlock {

    LogicEvaluator logEval;

    // Only to be used by LogicFab during block construction
    ConditionBlock(LogicEvaluator logEval) {
        this.logEval=logEval;
    }
    public static Optional<ConditionBlock> build(String condition, RealtimeValues rtvals, ArrayList<NumericVal> sharedMem){
        var logEvalOpt = util.evalcore.LogicFab.parseComparison(condition,rtvals,sharedMem);
        return logEvalOpt.map(ConditionBlock::new);
    }
    @Override
    public boolean start() {
        // After
        if( logEval==null ){
            Logger.error("This block probably wasn't constructed properly because no valid evaluator was found, chain aborted.");
            return false;
        }
        var res = logEval.eval();
        if( res.isEmpty() ){
            Logger.error("Failed to execute evaluation, chain aborted.");
            return false;
        }
        var pass = res.get();
        if (pass) {
            doNext();
        } else {
            doFailure();
        }
        return pass;
    }

    public String toString() {
        return telnetId() + " -> Check if " + logEval.getOriginalExpression() + (failure == null ? "." : ". If not, go to " + failure.telnetId());
    }
    public String getEvalInfo(){
        if(logEval==null)
            return "No valid LogEvaluator present.";
        return logEval.getInfo();
    }
}
