package util.evalcore;

public interface Evaluator {
    boolean logicEval(double... value);

    String getOriginalExpression();
    void setId(String id);

    String getInfo();
}
