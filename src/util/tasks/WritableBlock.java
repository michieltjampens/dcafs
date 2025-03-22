package util.tasks;

import io.Writable;
import worker.Datagram;

public class WritableBlock extends AbstractBlock {
    Writable target;
    String data;

    public WritableBlock(String dest, String data) {
        this.data = data;
        dQueue.add(Datagram.system(dest).writable(this));
    }

    @Override
    boolean start() {
        return false;
    }

    @Override
    void doFailure() {

    }


    @Override
    public boolean giveObject(String info, Object object) {
        return false;
    }
}
