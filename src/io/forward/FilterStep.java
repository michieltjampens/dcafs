package io.forward;

import java.math.BigDecimal;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Predicate;

public class FilterStep extends AbstractStep {
    Predicate<String> predicate;

    public FilterStep(Predicate<String> predicate, ThreadPoolExecutor executor) {
        this.executor = executor;
        this.predicate = predicate;
    }

    public void takeStep(String data, BigDecimal[] bds) {
        if (predicate != null) {
            if (predicate.test(data))
                doNext(data, bds);
        }
    }
}
