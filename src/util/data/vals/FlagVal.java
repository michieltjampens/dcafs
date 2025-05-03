package util.data.vals;

import das.Core;
import org.tinylog.Logger;
import util.tasks.blocks.ConditionBlock;
import util.tools.Tools;

import java.math.BigDecimal;
import java.util.ArrayList;

public class FlagVal extends BaseVal implements NumericVal {
    boolean value, defValue;

    String[] raiseCmd;
    String[] fallCmd;
    String[] lowCmd;
    String[] highCmd;

    BaseVal[] raiseVal;
    BaseVal[] fallVal;
    BaseVal[] lowVal;
    BaseVal[] highVal;

    boolean hasCmds = false;
    boolean hasVals = false;

    public FlagVal(String group, String name, String unit) {
        super(group, name, unit);
    }

    public void setCmds(ArrayList<String[]> cmds) {

        raiseCmd = cmds.stream().filter(x -> x[0].equals("raise")).map(it -> it[1]).toArray(String[]::new);
        fallCmd = cmds.stream().filter(x -> x[0].equals("fall")).map(it -> it[1]).toArray(String[]::new);
        lowCmd = cmds.stream().filter(x -> x[0].equals("low")).map(it -> it[1]).toArray(String[]::new);
        highCmd = cmds.stream().filter(x -> x[0].equals("high")).map(it -> it[1]).toArray(String[]::new);

        hasCmds = true;
    }

    public void setVals(ArrayList<ValFab.RefVal> cmds) {

        raiseVal = cmds.stream().filter(x -> x.ref().equals("raise")).map(ValFab.RefVal::bs).toArray(BaseVal[]::new);
        fallVal = cmds.stream().filter(x -> x.ref().equals("fall")).map(ValFab.RefVal::bs).toArray(BaseVal[]::new);
        lowVal = cmds.stream().filter(x -> x.ref().equals("low")).map(ValFab.RefVal::bs).toArray(BaseVal[]::new);
        highVal = cmds.stream().filter(x -> x.ref().equals("high")).map(ValFab.RefVal::bs).toArray(BaseVal[]::new);

        hasVals = true;
    }
    public void value(boolean state) {
        value = state;
    }

    public void update(boolean state) {
        if (!hasCmds && !hasVals) { // Now cmds, so no use checking
            this.value = state;
            return;
        }
        if (hasCmds) {
            if (value == state) { // Either STAY_LOW or STAY_HIGH
                for (var cmd : value ? highCmd : lowCmd)
                    Core.queueSystemCmd(cmd);
            } else { // Either RAISE or FALL
                for (var cmd : value ? fallCmd : raiseCmd)
                    Core.queueSystemCmd(cmd);
            }
        }
        if (hasVals) {
            if (value == state) { // Either STAY_LOW or STAY_HIGH
                for (var val : value ? highVal : lowVal)
                    val.triggerUpdate();
            } else { // Either RAISE or FALL
                for (var val : value ? fallVal : raiseVal)
                    val.triggerUpdate();
            }
        }
        this.value = state;
    }

    public void toggleState() {
        value = !value;
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
        update(Integer.compare(value, 0) > 0);
        return true;
    }

    @Override
    public void resetValue() {
        value = defValue;
    }

    public void defValue(boolean defValue) {
        this.defValue = defValue;
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
