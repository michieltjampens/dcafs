package util.data.vals;

import org.tinylog.Logger;
import util.tasks.blocks.AbstractBlock;
import util.tasks.blocks.NoOpBlock;
import util.tools.Tools;

import java.math.BigDecimal;

public class FlagVal extends BaseVal implements NumericVal {
    boolean value, defValue;

    AbstractBlock raiseBlock = NoOpBlock.INSTANCE;
    AbstractBlock fallBlock = NoOpBlock.INSTANCE;
    AbstractBlock highBlock = NoOpBlock.INSTANCE;
    AbstractBlock lowBlock = NoOpBlock.INSTANCE;

    public FlagVal(String group, String name, String unit) {
        super(group, name, unit);
    }

    public static FlagVal newVal(String group, String name) {
        return new FlagVal(group, name, "");
    }

    public void setBlocks(AbstractBlock highBlock, AbstractBlock lowBlock, AbstractBlock raiseBlock, AbstractBlock fallBlock) {
        this.raiseBlock = raiseBlock == null ? NoOpBlock.INSTANCE : raiseBlock;
        this.fallBlock = fallBlock == null ? NoOpBlock.INSTANCE : fallBlock;
        this.highBlock = highBlock == null ? NoOpBlock.INSTANCE : highBlock;
        this.lowBlock = lowBlock == null ? NoOpBlock.INSTANCE : lowBlock;
    }

    public void value(boolean state) {
        value = state;
    }

    public void update(boolean state) {
        if (value == state) { // Either STAY_LOW or STAY_HIGH
            if (value) { // stay high
                highBlock.start();
            } else { // stay low
                lowBlock.start();
            }
        } else { // Either RAISE or FALL
            if (value) { // fall
                fallBlock.start();
            } else { // set
                raiseBlock.start();
            }
        }
        this.value = state;
    }

    public void toggleState() {
        update(!value);
    }
    public boolean isUp() {
        return value;
    }

    public boolean update(double val) {
        update(Double.compare(val, 0.0) > 0);
        return true;
    }

    @Override
    public boolean update(int value) {
        update(value > 0);
        return true;
    }

    @Override
    public void resetValue() {
        value = defValue;
    }

    public void defValue(boolean defValue) {
        this.defValue = defValue;
    }

    public boolean defValue() {
        return defValue;
    }
    @Override
    public boolean parseValue(String value) {
        var opt = Tools.parseBool(value);
        opt.ifPresentOrElse(b -> this.value = b, () -> Logger.error(id() + "-> Failed to parse " + value));
        return opt.isPresent();
    }

    @Override
    public double asDouble() {
        return value ? 1.0 : 0.0;
    }

    @Override
    public int asInteger() {
        return value ? 1 : 0;
    }

    @Override
    public BigDecimal asBigDecimal() {
        return value ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    public String asString() {
        return String.valueOf(value);
    }

    @Override
    public void triggerUpdate() {
        update(true);
    }
}
