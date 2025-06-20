package util.tasks.blocks;

import org.tinylog.Logger;
import util.data.vals.FlagVal;
import util.data.vals.NumericVal;
import util.data.vals.Rtvals;
import util.evalcore.Evaluator;
import util.evalcore.ParseTools;

import java.util.ArrayList;
import java.util.Optional;

public class ConditionBlock extends AbstractBlock {

    Evaluator logEval;
    FlagVal flag;

    // Only to be used by LogicFab during block construction
    ConditionBlock(Evaluator logEval) {
        this.logEval=logEval;
    }

    ConditionBlock() {
    }

    public static Optional<ConditionBlock> build(String condition, Rtvals rtvals, ArrayList<NumericVal> sharedMem) {
        var logEvalOpt = ParseTools.parseComparison(condition, rtvals, sharedMem);
        if (logEvalOpt == null)
            return Optional.empty();
        return Optional.of(new ConditionBlock(logEvalOpt));
    }

    public void setFlag(FlagVal flag) {
        this.flag = flag;
    }
    @Override
    public boolean start() {
        return start(0.0);
    }

    public boolean start(double... input) {
        // After
        if (logEval == null) {
            Logger.error(id() + " -> This block probably wasn't constructed properly because no valid evaluator was found, route aborted.");
            return false;
        }
        var pass = logEval.logicEval(input);
        if (flag != null)
            flag.update(pass);
        if (pass) {
            doNext(input);
        } else {
            doAltRoute(true);
        }
        return pass;
    }

    void doNext(double... input) {
        if (next != null) {
            if (next instanceof ConditionBlock cb) {
                cb.start(input);
            } else if (next instanceof LogBlock lb) {
                lb.start(input);
            } else {
                next.start();
            }
        }
        sendCallback(id() + " -> OK");
    }
    public String toString() {
        return telnetId() + " -> Check if " + logEval.getOriginalExpression() + (altRoute == null ? "." : ". If not, go to " + altRoute.telnetId());
    }
    public String getEvalInfo(){
        if(logEval==null)
            return "No valid LogEvaluator present.";
        return logEval.getInfo();
    }
}
