package util.tasks;

import org.tinylog.Logger;
import worker.Datagram;

import java.util.concurrent.BlockingQueue;

public class CmdBlock extends AbstractBlock {
    String cmd;

    public CmdBlock(BlockingQueue<Datagram> dQueue) {
        this.dQueue = dQueue;
    }

    public CmdBlock setCmd(String cmd) {
        this.cmd = cmd;
        return this;
    }

    @Override
    boolean start() {
        dQueue.add(Datagram.system(cmd));
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
