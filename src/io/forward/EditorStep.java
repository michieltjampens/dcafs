package io.forward;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.function.Function;

public class EditorStep extends AbstractStep {
    ArrayList<Function<String, String>> edits;

    public EditorStep(ArrayList<Function<String, String>> edits) {
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
