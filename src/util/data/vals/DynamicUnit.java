package util.data.vals;

import org.tinylog.Logger;
import util.math.MathUtils;
import util.xml.XMLdigger;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

/* ******************************** D Y N A M I C  U N I T **************************************************** */
public class DynamicUnit {

    String base;
    int baseScale = -1;
    String valRegex = "";

    enum TYPE {STEP, LEVEL}

    DynamicUnit.TYPE type = DynamicUnit.TYPE.STEP;
    ArrayList<SubUnit> subs = new ArrayList<>();


    public DynamicUnit(String base) {
        this.base = base;
    }

    public static Map.Entry<String, DynamicUnit> processUnitElement(XMLdigger dig) {

        var base = dig.attr("base", ""); // Starting point
        var unit = new DynamicUnit(base);
        unit.setValRegex(dig.attr("nameregex", ""));
        var defDiv = dig.attr("div", 1);
        var defScale = dig.attr("scale", dig.attr("digits", -1));

        if (dig.hasPeek("level")) {
            for (var lvl : dig.digOut("level")) { // Go through the levels
                var val = lvl.value(""); // the unit
                var div = lvl.attr("div", defDiv); // the multiplier to go to next step
                var max = lvl.attr("till", lvl.attr("max", 0.0)); // From which value the nex unit should be used
                var scale = lvl.attr("scale", -1);
                var trailing = lvl.attr("trailing", true);
                scale = lvl.attr("digits", scale == -1 ? defScale : scale);
                unit.addLevel(val, div, max, scale, trailing);
            }
        } else if (dig.hasPeek("step")) {
            for (var step : dig.digOut("step")) { // Go through the steps
                var val = step.value(""); // the unit
                var cnt = step.attr("cnt", 1); // the multiplier to go to next step
                unit.addStep(val, cnt);
            }
        } else {
            Logger.warn("No valid subnodes in the unit node for " + base);
        }
        if (base.isEmpty())
            base = unit.subs.stream().map(sub -> sub.unit).distinct().collect(Collectors.joining(","));
        return new AbstractMap.SimpleEntry<>(base, unit);
    }

    public void setValRegex(String regex) {
        this.valRegex = regex;
    }

    public boolean matchesRegex(String name) {
        return !valRegex.isEmpty() && name.matches(valRegex);
    }

    public int baseScale() {
        return baseScale;
    }

    public boolean noSubs() {
        return subs.isEmpty();
    }

    public void addStep(String unit, int cnt) {
        type = TYPE.STEP;
        subs.add(new SubUnit(unit, cnt, 0, 0, 0, true));
    }

    public void addLevel(String unit, int mul, double max, int scale, boolean trailing) {
        type = TYPE.LEVEL;
        double min = Double.NEGATIVE_INFINITY;
        if (!subs.isEmpty()) {
            var prev = subs.get(subs.size() - 1);
            if (prev.unit.equals(unit)) { // If the same unit, don't divide
                min = prev.max;
            } else {// If different one, do
                min = prev.max / mul;
            }
        }

        subs.add(new SubUnit(unit, mul, min, max, scale, trailing));
    }

    public String apply(double total, String curUnit) {
        return switch (type) {
            case STEP -> processStep(total);
            case LEVEL -> processLevel(total, curUnit);
        };
    }

    private String processStep(double total) {
        StringBuilder result = new StringBuilder();
        var amount = (int) Math.rint(total);
        String unit = base;
        for (int a = 0; a < subs.size(); a++) {
            var sub = subs.get(a);
            if (amount > sub.div) { // So first sub applies...
                result.insert(0, (amount % sub.div) + unit); // Add the first part
                amount = amount / sub.div; // Change to next unit
            } else { // Sub doesn't apply
                result.insert(0, amount + unit); // Add it
                return result.toString(); // Finished
            }
            unit = sub.unit;
            if (a == subs.size() - 1) { // If this is the last step
                result.insert(0, amount + unit); // Add it
                return result.toString(); // Finished
            }
        }
        return total + base;
    }

    private String processLevel(double total, String curUnit) {
        if (curUnit.isEmpty())
            return String.valueOf(total);

        int index;
        for (index = 0; index < subs.size(); index++) {
            if (subs.get(index).unit.equalsIgnoreCase(curUnit))
                break;
        }
        if (index == subs.size()) {
            Logger.warn("Couldn't find corresponding unit in list: " + curUnit);
            return total + curUnit;
        }
        // Check lower limit
        while (subs.get(index).min != 0 && total <= subs.get(index).min && index != 0) {
            if (!subs.get(index).unit.equals(subs.get(index - 1).unit))
                total *= subs.get(index).div;
            index--;
        }
        while (subs.get(index).max != 0 && total > subs.get(index).max && index != subs.size() - 1) {
            if (!subs.get(index).unit.equals(subs.get(index + 1).unit))
                total /= subs.get(index).div;
            index++;
        }
        if (subs.get(index).scale != -1) // -1 scale is ignored by round double, but cleaner this way?
            total = MathUtils.roundDouble(total, subs.get(index).scale);
        if (subs.get(index).trailing)
            return total + subs.get(index).unit;
        var crop = String.valueOf(total).replace(".0", "");
        return crop + subs.get(index).unit;
    }

    public static class SubUnit {
        String unit;
        int div;
        double min, max;
        int scale;
        boolean trailing = true;

        public SubUnit(String unit, int mul, double min, double max, int scale, boolean trailing) {
            this.unit = unit;
            this.div = mul;
            this.min = min;
            this.max = max;
            this.scale = scale;
            this.trailing = trailing;
        }

    }
}
