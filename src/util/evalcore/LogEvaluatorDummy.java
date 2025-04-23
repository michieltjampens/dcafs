package util.evalcore;

import java.util.Optional;

public class LogEvaluatorDummy implements Evaluator {

    @Override
    public Optional<Boolean> eval(double... value) {
        return Optional.of(true); // Always returns true, no evaluation needed
    }

}
