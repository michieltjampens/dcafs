package util.evalcore;

import das.Core;
import org.tinylog.Logger;
import worker.Datagram;

import java.util.Arrays;

public class LogicEvalTrigger {

    Evaluator check = new LogEvaluatorDummy();
    String[] passCmd;
    String[] failCmd;
    boolean propagate = true;
    boolean passAlter = false;
    boolean failAlter = false;

    boolean hasCmds = false;

    String id = "";

    public LogicEvalTrigger(LogicEvaluator check, String[] passCmd, String[] failCmd) {
        this.check = check;
        this.passCmd = passCmd;
        this.failCmd = failCmd;

        passAlter = hasIs(passCmd);
        failAlter = hasIs(failCmd);

        hasCmds = passCmd.length != 0 || failCmd.length != 0;
    }

    public LogicEvalTrigger() {

    }

    public void togglePropagate() {
        propagate = !propagate;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean eval(double i0, double i1, double i2) {
        var res = check.eval(i0, i1, i2);
        if (!hasCmds)
            return propagate ? res.orElse(false) : true;

        if (res.isEmpty()) {
            Logger.error(id + " -> Something went wrong during eval");
        } else if (res.get()) { // pass
            doCmds(passCmd, i0, i1, i2, passAlter);
        } else { //fail
            doCmds(failCmd, i0, i1, i2, failAlter);
        }
        return propagate ? res.orElse(false) : true;
    }

    private static boolean hasIs(String[] cmds) {
        for (var cmd : cmds) {
            if (ParseTools.extractIreferences(cmd).length > 0)
                return true;
        }
        return false;
    }

    private static void doCmds(String[] cmds, double i0, double i1, double i2, boolean replace) {
        if (replace) {
            for (var cmd : cmds) {
                cmd = cmd.replace("i0", String.valueOf(i0));
                cmd = cmd.replace("i1", String.valueOf(i1));
                cmd = cmd.replace("i2", String.valueOf(i2));
                Core.addToQueue(Datagram.system(cmd));
            }
        } else {
            Arrays.stream(cmds).forEach(cmd -> Core.addToQueue(Datagram.system(cmd)));
        }
    }
}
