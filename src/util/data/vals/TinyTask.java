package util.data.vals;

import das.Core;
import org.tinylog.Logger;
import util.evalcore.LogicEvaluator;
import worker.Datagram;

import java.util.ArrayList;

public class TinyTask {

    private record EventCmd(boolean pass, String cmd) {
    }

    enum RETURN {I0, I1}

    String id;
    LogicEvaluator logEval;
    RETURN passReturn = RETURN.I1; // Pass returns new value
    RETURN failReturn = RETURN.I0; // Fail returns old value
    ArrayList<EventCmd> cmds = new ArrayList<>();

    public TinyTask(String id, LogicEvaluator logEval, boolean swapReturns) {
        this.id = id;
        this.logEval = logEval;
        if (swapReturns) {
            passReturn = RETURN.I0;
            failReturn = RETURN.I1;
        }
    }

    public void addCmd(boolean pass, String cmd) {
        cmds.add(new EventCmd(pass, cmd));
    }

    public double apply(double i0, double i1) {
        var result = logEval.eval(i0, i1);
        if (result.isEmpty()) {
            Logger.error(id + " -> Failed to process with " + i0 + " and " + i1);
            return Double.NaN; // or same as fail?
        }
        var pass = result.get();
        var ret = switch (pass ? passReturn : failReturn) {
            case I0 -> i0;
            case I1 -> i1;
        };
        cmds.stream().filter(eventCmd -> eventCmd.pass() == pass)
                .map(ec -> ec.cmd().replace("$$", String.valueOf(ret)))
                .forEach(cmd -> Core.addToQueue(Datagram.system(cmd)));
        return ret;
    }

    public int apply(int i0, int i1) {
        return (int) apply((double) i0, (double) i1);
    }
}
