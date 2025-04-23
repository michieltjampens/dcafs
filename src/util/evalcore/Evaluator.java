package util.evalcore;

import java.util.Optional;

public interface Evaluator {
    Optional<Boolean> eval(double... value);
}
