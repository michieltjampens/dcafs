package io.forward;

import util.data.ValStore;

import java.math.BigDecimal;

public class StoreStep extends AbstractStep {
    ValStore store;

    public StoreStep(ValStore store) {
        this.store = store;
        wantsData = true;
    }

    @Override
    public String takeStep(String data, BigDecimal[] bds) {
        store.apply(data);
        return doNext(data, bds);
    }

    public ValStore getStore() {
        return store;
    }
}
