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

        return this;
    }

    @Override
    boolean start() {
        if (target != null) {
            if (!target.writeLine(id, data)) {
                doFailure();
            } else {
                doNext();
            }
        } else {
            if (!cmd.isEmpty())
                Core.addToQueue(Datagram.system(cmd).writable(this));
            Logger.warn(id() + " -> Don't have a valid writable yet...");
        }
        return true;
    }

    public String toString() {
        return telnetId() + " -> Send '" + data + "' to " + dest;
    }

    @Override
    public boolean giveObject(String info, Object object) {
        if (info.equalsIgnoreCase("writable")) {
            target = (Writable) object;
            if (target != null) {
                Logger.info(id() + " -> Received writable from " + target.id());
                if (!target.writeLine(id, data)) {
                    doFailure();
                } else {
                    doNext();
                }
                return true;
            } else {
                Logger.info(id() + " -> Received null instead of response to " + dest);
                return true;
            }
        } else {
            Logger.warn(id() + " -> Given object with unknown info... ?" + info);
        }
        return false;
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
