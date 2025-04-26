package util.data.procs;

import java.util.function.IntBinaryOperator;

public class BuiltinIntProc implements MathEvalForVal {
    IntBinaryOperator bin;

    public BuiltinIntProc(IntBinaryOperator bin) {
        this.bin = bin;
    }

    @Override
    public double eval(double d0, double d1, double d2) {
        return bin.applyAsInt((int) d0, (int) d1);
    }

    @Override
    public int eval(int d0, int d1, int d2) {
        return bin.applyAsInt(d0, d1);
    }
}
