package io.forward.steps;

import java.math.BigDecimal;
import java.util.function.Predicate;

public class FilterStep extends AbstractStep {
    Predicate<String> predicate;

    public FilterStep(String id, Predicate<String> predicate) {
        this.id = id;
        this.predicate = predicate;
    }

    public String takeStep(String data, BigDecimal[] bds) {
        if (predicate != null) {
            if (predicate.test(data)) {
                return doNext(data, bds);
            }else{
                return doFailure(data, bds);
            }
        }
        return "";
    }
}
