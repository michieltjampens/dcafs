package util.tasks.blocks;

import io.Writable;

public class ControlBlock extends AbstractBlock {
    Writable manager;
    String message;

    public ControlBlock(Writable manager) {
        this.manager = manager;
    }

    public ControlBlock setMessage(String message) {
        this.message = message;
        return this;
    }

    @Override
    public boolean start() {
        manager.writeLine(id(), message);
        return false;
    }
}
