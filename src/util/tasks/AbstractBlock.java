package util.tasks;

import io.Writable;
import worker.Datagram;

import java.util.concurrent.BlockingQueue;

public abstract class AbstractBlock implements Writable {
    Writable feedback;
    AbstractBlock next;
    String id = "";
    BlockingQueue<Datagram> dQueue;

    abstract boolean start();

    void doNext() {
        if (next != null)
            next.start();
    }

    abstract void doFailure();

    void setFeedbackWritable(Writable fb) {
        this.feedback = fb;
        if (next != null)
            next.setFeedbackWritable(fb);
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
        return "";
    }

    @Override
    public boolean isConnectionValid() {
        return false;
    }

    @Override
    public Writable getWritable() {
        return null;
    }
}
