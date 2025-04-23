package util.data.vals;

import java.math.BigDecimal;

public interface NumericVal {

    void update(double value);

    String name();
    String id(); //get the id
    String unit();

    double asDouble(); // Get the value as a double
    int asInteger(); // Get the value as an integer
    String asString();
    BigDecimal asBigDecimal();
}
