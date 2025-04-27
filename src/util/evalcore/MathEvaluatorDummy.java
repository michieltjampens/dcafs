package util.evalcore;

import util.data.procs.MathEvalForVal;

public class MathEvaluatorDummy implements MathEvalForVal {
    @Override
    public double eval(double d0, double d1, double d2) {
        return d0;
    }

    @Override
    public int eval(int d0, int d1, int d2) {
        return d0;
    }

    @Override
    public String getOriExpr() {
        return "";
    }
}
