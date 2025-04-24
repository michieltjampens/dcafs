package io.forward.steps;

import io.Writable;
import org.tinylog.Logger;
import util.data.store.ValStore;

import java.math.BigDecimal;
import java.util.concurrent.ThreadPoolExecutor;

public abstract class AbstractStep {
    AbstractStep next,failure;
    boolean debug = false;
    ThreadPoolExecutor executor;
    Writable feedback;
    String info = "";
    boolean wantsData = false;
    String id;

    public abstract String takeStep(String data, BigDecimal[] bds);

    public void setNext(AbstractStep next) {
        if (next != null) {
            this.next = next;
        } else {
            Logger.error("Tried to add invalid next step");
        }
    }
    public void setFailure(AbstractStep fail){
        if (fail != null) {
            this.failure = fail;
        } else {
            Logger.error("Tried to add invalid failure step");
        }
    }
    public void setFeedback(Writable feedback) {
        this.feedback = feedback;
    }

    public String doNext(String data, BigDecimal[] bds) {
        if (next == null) { // Meaning the last step in the chain
            if (feedback != null && data != null)
                feedback.writeString(data);
            return data;
        }
        return next.takeStep(data, bds);
    }

    public String doFailure(String data, BigDecimal[] bds) {
        if (failure != null)
            return failure.takeStep(data, bds);
        return "";
    }
    public AbstractStep getLastStep(){
        if( next != null )
            return next.getLastStep();
        return this;
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

    public void removeStore() {
        if (next instanceof StoreStep) {
            next = null;
        }
        if (next != null) {
            next.removeStore();
        }
        if (failure != null) {
            if (failure instanceof StoreStep) {
                failure = null;
            } else {
                failure.removeStore();
            }
        }
    }
}
