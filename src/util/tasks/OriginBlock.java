package util.tasks;

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

    @Override
    boolean start() {
        runs++;
        doNext();
        Logger.info("Starting...");
        return true;
    }

    public String toString() {
        return id + " -> " + info + " (runs:" + runs + ")";
    }
}
