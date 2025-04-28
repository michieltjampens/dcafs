package util.tasks.blocks;

import io.telnet.TelnetCodes;
import org.tinylog.Logger;

public class OriginBlock extends AbstractBlock {
    String info = "";
    int runs = 0;
    boolean autostart = false;

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
        reset();
        return start();
    }
    @Override
    public boolean start() {
        runs++;
        clean = false;
        doNext();
        Logger.info(id + " -> Starting...");
        return true;
    }

    public boolean hasAutostart() {
        return autostart;
    }

    public void setAutostart(boolean start) {
        this.autostart = start;
    }
    @Override
    public String telnetId() {
        return TelnetCodes.TEXT_MAGENTA + id() + TelnetCodes.TEXT_DEFAULT;
    }
    public String toString() {
        return telnetId() + " -> " + info + " (runs:" + runs + ")";
    }
}
