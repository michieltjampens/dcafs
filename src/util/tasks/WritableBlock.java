package util.tasks;

import das.Core;
import io.Writable;
import org.tinylog.Logger;
import worker.Datagram;

public class WritableBlock extends AbstractBlock implements Writable {
    Writable target;
    String data;
    String dest;
    String cmd;

    public WritableBlock setMessage(String dest, String data) {
        this.data = data;
        this.dest = dest;
        var split = dest.split(":", 2);
        cmd = switch (split[0]) {
            case "stream", "raw" -> "ss:" + split[1] + ",reqwritable";
            case "file" -> "fc:" + split[1] + ",reqwritable";
            default -> "";
        };
        if (cmd.isEmpty()) {
            Logger.error(id + " -> No valid destination given, needs to be of format stream:id, raw:id or file:id");
        }
        return this;
    }

    @Override
    boolean start() {
        if (target != null) {
            if (!target.writeLine(id, data)) {
                Logger.info(id + " -> Failed to send to " + dest);
                doFailure();
            } else {
                Logger.info(id + " -> Send data to " + dest);
                doNext();
            }
        } else {
            if (!cmd.isEmpty()) {
                Logger.info(id() + " -> Requesting writable for " + dest);
                Core.addToQueue(Datagram.system(cmd).writable(this));
            }
        }
        return true;
    }

    public String toString() {
        return telnetId() + " -> Send '" + data + "' to " + dest;
    }

    @Override
    public void giveObject(String info, Object object) {
        if (info.equalsIgnoreCase("writable")) {
            target = (Writable) object;
            if (target != null) {
                Logger.info(id() + " -> Received writable from " + target.id());
                if (!target.writeLine(id, data)) {
                    doFailure();
                } else {
                    doNext();
                }
            } else {
                Logger.info(id() + " -> Received null instead of response to " + dest);
            }
        } else {
            Logger.warn(id() + " -> Given object with unknown info... ?" + info);
        }
    }

    @Override
    public boolean writeLine(String origin, String data) {
        return false;
    }

    @Override
    public boolean isConnectionValid() {
        return true;
    }
}
