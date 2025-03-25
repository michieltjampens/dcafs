package util.tasks;

import io.Writable;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;

import java.util.StringJoiner;

public abstract class AbstractBlock implements Writable {
    Writable feedback, callback;
    AbstractBlock next, failure;
    String id = "";
    int order = -1;

    abstract boolean start();

    void doNext() {
        if (next != null)
            next.start();
        sendCallback(id() + " -> OK");
    }

    public void setNext(AbstractBlock next) {
        this.next = next;
    }

    public AbstractBlock addNext(AbstractBlock block) {
        if (block == null)
            return null;
        if (next == null) {
            if (block.id().isEmpty()) {
                block.id(id);
            }
            next = block;
        } else {
            next.addNext(block);
        }
        return this;
    }

    public AbstractBlock setFailureBlock(AbstractBlock failure) {
        this.failure = failure;
        return this;
    }

    protected void doFailure() {
        sendCallback(id() + " -> FAILURE");
        if (failure != null)
            failure.start();
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
            callback.writeLine(data);
    }

    public String getInfo(StringJoiner info, String offset) {

        info.add(offset + this);
        if (failure != null && failure.id().startsWith(id())) {
            info.add("    " + failure);
            addFailureInfo(failure, info);
        }
        if (next != null)
            return next.getInfo(info, offset);
        return info.toString();
    }

    // Recursive method to handle failure chain
    private void addFailureInfo(AbstractBlock failure, StringJoiner info) {
        if (failure != null && failure.next != null) {
            // Check if the next failure matches the desired pattern
            if (failure.next.id().matches(".*\\|\\d+F\\d+$")) {
                info.add("    " + failure.next);  // Add the next failure
                addFailureInfo(failure.next, info);  // Recurse for next failure
            }
        }
    }
    public void reset() {
        if (next != null)
            next.reset();
    }

    public String telnetId() {
        return TelnetCodes.TEXT_CYAN + id() + TelnetCodes.TEXT_DEFAULT;
    }
    public AbstractBlock getLastBlock() {
        if (next == null)
            return this;
        return next.getLastBlock();
    }
    @Override
    public boolean writeString(String data) {
        return false;
    }

    @Override
    public boolean writeLine(String data) {
        return false;
    }

    @Override
    public boolean writeLine(String origin, String data) {
        return false;
    }

    @Override
    public boolean writeBytes(byte[] data) {
        return false;
    }

    @Override
    public String id() {
        return id;
    }

    public void id(String id) {
        this.id = id;
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
        if (failure != null)
            failure.buildId(this.id + "F0");

        if (next != null && next.id().isEmpty())
            next.buildId(this.id);

    }

    public void resetId() {
        if (id.isEmpty())
            return;
        id = "";
        if (next != null)
            next.resetId();
        if (failure != null)
            failure.resetId();
    }
    @Override
    public boolean isConnectionValid() {
        return true;
    }

    @Override
    public Writable getWritable() {
        return null;
    }

    @Override
    public boolean giveObject(String info, Object object) {
        return false;
    }
}
