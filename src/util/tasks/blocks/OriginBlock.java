package util.tasks.blocks;

import io.telnet.TelnetCodes;
import org.tinylog.Logger;

public class OriginBlock extends AbstractBlock {
    String info = "";
    int runs = 0;
    boolean autostart = false;
    boolean shutdownhook = false;

    public OriginBlock(String id) {
        this.id = id;
        Logger.info("Block created with id " + id);
    }

    public OriginBlock setInfo(String info) {
        this.info = info;
        return this;
    }
    public String getInfo(){
        return info;
    }
    public void updateChainId() {
        next.resetId();
        next.buildId(id);
    }

    public boolean restart() {
        return start();
    }
    @Override
    public boolean start() {
        reset();
        runs++;
        clean = false;
        Logger.info(id + " -> Starting...");
        doNext();
        return true;
    }

    public void buildId() {
        if (next != null) {
            next.buildId(id());
        }
    }

    public boolean hasAutostart() {
        return autostart;
    }

    public void setAutostart(boolean start) {
        this.autostart = start;
    }

    public void setShutdownhook(boolean hooked) {
        this.shutdownhook = hooked;
    }

    public boolean startIfshutdownhook() {
        if (shutdownhook)
            return start();
        reset();
        return false;
    }
    @Override
    public String telnetId() {
        return TelnetCodes.TEXT_MAGENTA + id() + TelnetCodes.TEXT_DEFAULT;
    }
    public String toString() {
        return telnetId() + " -> " + info + " (runs:" + runs + ")";
    }
}
