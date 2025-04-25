package util.data.vals;

public abstract class BaseVal {

    protected String name, group, unit;

    public BaseVal(String group, String name, String unit) {
        this.group = group;
        this.name = name;
        this.unit = unit;
    }

    public BaseVal(ValFab.Basics base) {
        this.group = base.group();
        this.unit = base.unit();
        this.name = base.name();
    }

    BaseVal() {
    }
    /* ******** Using first generic ****************************************** */
    public void name(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void group(String group) {
        this.group = group;
    }

    public String group() {
        return group;
    }

    public void unit(String unit) {
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }

    /**
     * Get the id, which is group + underscore + name
     *
     * @return The concatenation of group, underscore and name
     */
    public String id() {
        return group.isEmpty() ? name : (group + "_" + name);
    }

    public abstract void resetValue();

    /* ************************* Abstract mathods ********************************** */
    public abstract boolean parseValue(String value);

    public abstract String asString();

    public abstract void triggerUpdate();
}
