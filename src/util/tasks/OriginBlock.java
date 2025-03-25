package util.tasks;

import io.telnet.TelnetCodes;
import org.tinylog.Logger;

public class OriginBlock extends AbstractBlock {
    String info = "";
    int runs = 0;

    public OriginBlock(String id) {
        this.id = id;
    }

    public OriginBlock setInfo(String info) {
        this.info = info;
        return this;
    }

    public void updateChainId() {
        next.resetId();
        next.buildId(id);
    }
    @Override
    boolean start() {
        runs++;
        doNext();
        Logger.info("Starting...");
        return true;
    }

    @Override
    public String telnetId() {
        return TelnetCodes.TEXT_MAGENTA + id() + TelnetCodes.TEXT_DEFAULT;
    }
    public String toString() {
        return telnetId() + " -> " + info + " (runs:" + runs + ")";
    }
}
