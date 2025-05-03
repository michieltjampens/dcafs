package util.evalcore;

import java.util.Optional;

public class LogEvaluatorDummy implements Evaluator {

    @Override
    public boolean eval(double... value) {
        return true; // Always returns true, no evaluation needed
    }

    public void setId(String id) {
    }

}
