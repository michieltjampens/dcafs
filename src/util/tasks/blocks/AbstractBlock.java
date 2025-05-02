package util.tasks.blocks;

import io.Writable;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;

import java.util.StringJoiner;

public abstract class AbstractBlock {
    protected Writable feedback, callback;
    protected AbstractBlock next, altRoute;
    protected String id = "";
    protected int order = -1;
    protected boolean clean = true;

    public abstract boolean start();

    void doNext() {
        if (next != null)
            next.start();
        sendCallback(id() + " -> OK");
    }

    public AbstractBlock setNext(AbstractBlock next) {
        this.next = next;
        return this;
    }

    public AbstractBlock getNext() {
        return next;
    }
    public AbstractBlock addNext(AbstractBlock block) {
        if (block == null)
            return this;
        if (next == null) {
            next = block;
        } else {
            next.addNext(block);
        }
        return this;
    }

    public void setAltRouteBlock(AbstractBlock altRoute) {
        this.altRoute = altRoute;
    }

    protected void doAltRoute(boolean tagAsFailure) {
        if (tagAsFailure)
            sendCallback(id() + " -> FAILURE");
        if (altRoute != null)
            altRoute.start();
    }

    void setFeedbackWritable(Writable fb) {
        this.feedback = fb;
        if (next != null)
            next.setFeedbackWritable(fb);
    }

    void setCallbackWritable(Writable cb) {
        this.callback = cb;
        if (next != null)
            next.setCallbackWritable(cb);
    }

    public void sendCallback(String data) {
        if (callback != null)
            callback.writeLine(id, data);
    }

    public String getInfo(StringJoiner info, String offset) {

        info.add(offset + this);
        if (altRoute != null && altRoute.id().startsWith(id())) {
            info.add("    " + altRoute);
            addAltRouteInfo(altRoute, info);
        }
        if (next != null)
            return next.getInfo(info, offset);
        return info.toString();
    }

    // Recursive method to handle alternative chain
    private void addAltRouteInfo(AbstractBlock alternative, StringJoiner info) {
        if (alternative != null && alternative.next != null &&
                alternative.next.id().matches(".*\\|\\d+F\\d+$")) { // Check if the next alternative matches the desired pattern
            info.add("    " + alternative.next);  // Add the next alternative
            addAltRouteInfo(alternative.next, info);  // Recurse for next alternative
        }
    }
    public void reset() {
        if (!clean) {
            clean = true;
            if (next != null)
                next.reset();
            if (altRoute != null)
                altRoute.reset();
        }
    }
    public AbstractBlock getLastBlock() {
        if (next == null)
            return this;
        return next.getLastBlock();
    }

    public String id() {
        return id;
    }

    public String telnetId() {
        return TelnetCodes.TEXT_CYAN + id() + TelnetCodes.TEXT_DEFAULT;
    }
    public void id(String id) {
        this.id = id;
    }

    public void resetId() {
        if (id.isEmpty())
            return;
        id = "";
        if (next != null)
            next.resetId();
        if (altRoute != null)
            altRoute.resetId();
    }
    public void buildId(String id) {
        if (!this.id.isEmpty())
            return;

        var split = id.split("\\|", 2);
        if (split.length == 2) {
            var nrs = split[1].split("F");
            if (nrs.length == 2) {
                var nr = NumberUtils.toInt(nrs[1]) + 1;
                this.id = split[0] + "|" + nrs[0] + "F" + nr;
            } else {
                var nr = NumberUtils.toInt(nrs[0]) + 1;
                this.id = split[0] + "|" + nr;
            }
        } else {
            this.id = id + "|0";
        }
        if (altRoute != null)
            altRoute.buildId(this.id + "F0");

        if (next != null && next.id().isEmpty())
            next.buildId(this.id);

    }
}
