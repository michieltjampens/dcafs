package util.tasks;

import io.Writable;
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
        sendCallback(chainId() + " -> OK");
    }

    public void setNext(AbstractBlock next) {
        this.next = next;
    }

    public AbstractBlock addNext(AbstractBlock block) {
        if (next == null) {
            if (block.order == -1) {
                block.id = id;
                block.order = order + 1;
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
        sendCallback(chainId() + " -> FAILURE");
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
        if (order == 0 && !id.startsWith("Branch"))
            info.add(offset + "Start of chain: " + id);
        info.add(offset + this);
        if (next != null)
            return next.getInfo(info, offset + "  ");
        if (id.startsWith("Branch")) {
            info.add(offset + "-End of the branch-");
        } else {
            info.add(offset + "-End of the chain-");
        }
        return info.toString();
    }

    public void reset() {
        if (next != null)
            next.reset();
    }

    public AbstractBlock getLastBlock() {
        if (next == null)
            return this;
        return getLastBlock();
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
        if (next != null)
            next.id(id);
    }

    public String chainId() {
        return id + "_" + order;
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
