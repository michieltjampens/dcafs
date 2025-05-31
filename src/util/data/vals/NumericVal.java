package util.data.vals;

import util.data.procs.MathEvalForVal;
import util.tasks.blocks.ConditionBlock;

import java.math.BigDecimal;

public interface NumericVal {

    boolean update(double value);
    boolean update(int value);

    void resetValue();

    String name();
    String id(); //get the id
    String unit();

    double asDouble(); // Get the value as a double
    int asInteger(); // Get the value as an integer
    String asString();
    BigDecimal asBigDecimal();

    default void setPreCheck(ConditionBlock pre) {
    }

    default void setPostCheck(ConditionBlock post, boolean ignoreResult) {
    }

    default void setMath(MathEvalForVal math) {
    }
    default String getExtraInfo() {
        return "";
    }
}
