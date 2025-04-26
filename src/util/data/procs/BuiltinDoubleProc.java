package util.data.procs;

import java.util.function.DoubleBinaryOperator;

public class BuiltinDoubleProc implements MathEvalForVal {
    DoubleBinaryOperator bin;

    public BuiltinDoubleProc(DoubleBinaryOperator bin) {
        this.bin = bin;
    }

    @Override
    public double eval(double d0, double d1, double d2) {
        return bin.applyAsDouble(d0, d1);
    }

    public int eval(int d0, int d1, int d2) {
        return (int) bin.applyAsDouble(d0, d1);
    }
}
