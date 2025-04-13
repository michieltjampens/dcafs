package io.forward;

import io.Writable;
import org.tinylog.Logger;
import util.data.ValStore;

import java.math.BigDecimal;
import java.util.concurrent.ThreadPoolExecutor;

public abstract class AbstractStep {
    AbstractStep next;
    boolean debug = false;
    ThreadPoolExecutor executor;
    Writable feedback;
    String info = "";
    boolean wantsData = false;

    public abstract void takeStep(String data, BigDecimal[] bds);

    public void setNext(AbstractStep next) {
        if (next != null) {
            this.next = next;
        } else {
            Logger.error("Tried to add invalid step");
        }
    }

    public void setFeedback(Writable feedback) {
        this.feedback = feedback;
    }

    public void doNext(String data, BigDecimal[] bds) {
        if (next == null) { // Meaning the last step in the chain
            if (feedback != null && data != null)
                feedback.writeString(data);
            return;
        }
        next.takeStep(data, bds);
    }

    public void setInfo(String info) {
        this.info = info;
    }

    public void setWantsData(boolean want) {
        this.wantsData = want;
    }

    public boolean wantsData() {
        if (wantsData)
            return true;
        if (next == null)
            return false;
        return next.wantsData();
    }

    public void reset() {
        if (next != null)
            next.reset();
    }

    public ValStore getStore() {
        if (next == null)
            return null;
        return next.getStore();
    }

    public void getFeedbackFromLastStep(Writable wr) {
        if (next == null) {
            feedback = wr;
        } else {
            next.getFeedbackFromLastStep(wr);
        }
    }
}
