package util.data.procs;

import java.util.function.IntBinaryOperator;

public class BuiltinIntProc implements MathEvalForVal {
    IntBinaryOperator bin;
    String ori;

    public BuiltinIntProc(IntBinaryOperator bin, String ori) {
        this.bin = bin;
        this.ori = ori;
    }

    @Override
    public double eval(double d0, double d1, double d2) {
        return bin.applyAsInt((int) d0, (int) d1);
    }

    @Override
    public int eval(int d0, int d1, int d2) {
        return bin.applyAsInt(d0, d1);
    }

    @Override
    public String getOriExpr() {
        return ori;
    }
}
