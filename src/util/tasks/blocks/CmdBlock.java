package util.tasks.blocks;

import das.Core;
import io.Writable;
import org.tinylog.Logger;
import worker.Datagram;

public class CmdBlock extends AbstractBlock implements Writable {
    Datagram d;

    public CmdBlock(Datagram d) {
        this.d = d.writable(this).toggleSilent();
    }

    public CmdBlock(String command) {
        d = Datagram.system(command).writable(this).toggleSilent();
    }
    @Override
    public boolean start() {
        Core.addToQueue(d);
        return true;
    }

    @Override
    public boolean writeLine(String origin, String data) {
        Logger.info(id() + "-> " + d.getData() + " => Reply: " + data);
        if (data.startsWith("!")) {
            doAltRoute(true);
        } else {
            doNext();
        }
        return true;
    }

    @Override
    public boolean isConnectionValid() {
        return true;
    }

    public String toString() {
        return id() + " -> Queue's '" + d.getData() + "'";
    }
}
