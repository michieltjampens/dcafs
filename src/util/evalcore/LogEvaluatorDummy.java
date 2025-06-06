package util.evalcore;

public class LogEvaluatorDummy implements Evaluator {

    @Override
    public boolean logicEval(double... value) {
        return true; // Always returns true, no evaluation needed
    }

    public String getOriginalExpression() {
        return "";
    }
    public void setId(String id) {
    }

    public String getInfo() {
        return "";
    }
}
