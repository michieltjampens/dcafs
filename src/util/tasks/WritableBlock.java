package util.tasks;

import io.Writable;
import org.tinylog.Logger;
import worker.Datagram;

import java.util.concurrent.BlockingQueue;

public class WritableBlock extends AbstractBlock {
    Writable target;
    String data;
    String dest;
    int attempts = -1;
    int tempAttempts = -1;

    public WritableBlock(BlockingQueue<Datagram> dQueue) {
        this.dQueue = dQueue;
    }

    public WritableBlock setMessage(String dest, String data) {
        this.data = data;
        this.dest = dest;
        var split = dest.split(":", 2);
        var cmd = switch (split[0]) {
            case "stream", "raw" -> "ss:" + split[1] + ",reqwritable";
            case "file" -> "fc:" + split[1] + ",reqwritable";
            default -> "";
        };
        if (!cmd.isEmpty())
            dQueue.add(Datagram.system(cmd).writable(this));
        return this;
    }

    public WritableBlock setAttempts(int attempts) {
        this.attempts = attempts;
        this.tempAttempts = attempts;
        return this;
    }
    @Override
    boolean start() {
        if (target != null) {
            if (tempAttempts != 0) {
                if (!target.writeLine(data)) {
                    doFailure();
                } else {
                    tempAttempts--;
                    doNext();
                }
            } else {
                doFailure();
            }
        } else {
            Logger.error(chainId() + " -> Don't have a valid writable yet...");
        }
        return true;
    }

    public void reset() {
        tempAttempts = attempts;
        if (next != null)
            next.reset();
    }

    public String toString() {
        return chainId() + " -> Send '" + data + "' to " + dest;
    }

    @Override
    public boolean giveObject(String info, Object object) {
        if (info.equalsIgnoreCase("writable")) {
            target = (Writable) object;
            if (target != null) {
                Logger.info(chainId() + " -> Received writable from " + target.id());
                return true;
            } else {
                Logger.info(chainId() + " -> Received null instead of response to " + dest);
                return true;
            }
        } else {
            Logger.warn(chainId() + " -> Given object with unknown info... ?" + info);
        }
        return false;
    }
}
