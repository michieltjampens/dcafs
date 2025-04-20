package io.forward.steps;

import java.math.BigDecimal;
import java.util.function.Function;

public class EditorStep extends AbstractStep {
    Function<String, String>[] edits;

    public EditorStep(Function<String, String>[] edits) {
        this.edits = edits;
    }

    @Override
    public String takeStep(String data, BigDecimal[] bds) {
        for (var edit : edits) {
            data = edit.apply(data);
        }
        return doNext(data, bds);
    }
}
