package util.evalcore;

import java.util.Optional;

public interface Evaluator {
    boolean eval(double... value);

    void setId(String id);
}
