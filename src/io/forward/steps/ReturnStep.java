package io.forward.steps;

import org.tinylog.Logger;

import java.math.BigDecimal;

public class ReturnStep extends AbstractStep {
    @Override
    public String takeStep(String data, BigDecimal[] bds) {
        return data;
    }

    public void setNext(AbstractStep next) {
        Logger.info("Ignoring setNext on return");
    }
}
