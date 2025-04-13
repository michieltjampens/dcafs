package io.forward;

import java.math.BigDecimal;
import java.util.function.Function;

public class EditorStep extends AbstractStep {
    Function<String, String>[] edits;

    public EditorStep(Function<String, String>[] edits) {
        this.edits = edits;
    }

    @Override
    public void takeStep(String data, BigDecimal[] bds) {
        for (var edit : edits) {
            data = edit.apply(data);
        }
        doNext(data, bds);
    }
}
