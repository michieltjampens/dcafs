package util.tasks;

import das.Core;
import org.tinylog.Logger;
import worker.Datagram;

public class CmdBlock extends AbstractBlock {
    String cmd;

    public CmdBlock setCmd(String cmd) {
        this.cmd = cmd;
        return this;
    }

    @Override
    boolean start() {
        Core.addToQueue(Datagram.system(cmd));
        return true;
    }

    @Override
    public boolean writeLine(String origin, String data) {
        Logger.info(chainId() + " -> Reply: " + data);
        if (data.startsWith("!")) {
            doFailure();
        } else {
            doNext();
        }
        return true;
    }

    public String toString() {
        return chainId() + " -> Queue's '" + cmd + "'";
    }
}
