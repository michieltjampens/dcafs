package util.data.procs;

import util.math.MathUtils;

import java.util.function.DoubleBinaryOperator;

public class BuiltinDoubleProc implements MathEvalForVal {
    DoubleBinaryOperator bin;
    int scale;

    public BuiltinDoubleProc(DoubleBinaryOperator bin, int scale) {
        this.bin = bin;
        this.scale = scale;
    }

    @Override
    public double eval(double d0, double d1, double d2) {
        var res = bin.applyAsDouble(d0, d1);
        return scale == -1 ? res : MathUtils.roundDouble(res, scale);
    }

    public int eval(int d0, int d1, int d2) {
        return (int) bin.applyAsDouble(d0, d1);
    }
}
