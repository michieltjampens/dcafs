package util.data.procs;

import java.util.function.DoubleBinaryOperator;

public class BuiltinProc implements MathEvalForVal {
    DoubleBinaryOperator bin;

    public BuiltinProc(DoubleBinaryOperator bin) {
        this.bin = bin;
    }

    @Override
    public double eval(double d0, double d1, double d2) {
        return bin.applyAsDouble(d0, d1);
    }
}
