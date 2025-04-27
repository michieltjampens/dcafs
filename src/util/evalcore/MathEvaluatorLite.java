package util.evalcore;

import org.tinylog.Logger;
import util.data.procs.MathEvalForVal;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Function;

public class MathEvaluatorLite implements MathEvalForVal {

    Function<BigDecimal[], BigDecimal>[] ops;
    BigDecimal[] scratchpad;
    // Info for debugging
    protected String originalExpression;

    String id = "";

    public MathEvaluatorLite(String id, String ori, Function<BigDecimal[], BigDecimal>[] ops) {
        this.id = id;
        this.ops = ops;
        this.originalExpression = ori;

        scratchpad = new BigDecimal[ops.length + 3];
    }
    /* *********************************** Do evaluation ************************************************ */

    /**
     * Applies an array of doubles as the input for the expression and returns the result.
     *
     * @return The result if successful or NaN if something went wrong
     */
    public double eval(double d0, double d1, double d2) {
        scratchpad[0] = BigDecimal.valueOf(d0);
        scratchpad[1] = BigDecimal.valueOf(d1);
        scratchpad[2] = BigDecimal.valueOf(d2);
        return solve().map(BigDecimal::doubleValue).orElse(Double.NaN);
    }

    @Override
    public int eval(int i0, int i1, int i2) {
        scratchpad[0] = BigDecimal.valueOf(i0);
        scratchpad[1] = BigDecimal.valueOf(i1);
        scratchpad[2] = BigDecimal.valueOf(i2);
        return solve().map(BigDecimal::intValue).orElse(Integer.MAX_VALUE);
    }

    private Optional<BigDecimal> solve() {
        try {
            for (int a = 0; a < ops.length; a++)
                scratchpad[a + 3] = ops[a].apply(scratchpad);
            return Optional.of(scratchpad[scratchpad.length - 1]);
        } catch (NullPointerException np) {
            Logger.error(id + " (me) -> Nullpointer while evaluating: " + originalExpression, np);
        } catch (ArithmeticException ae) {
            Logger.error(id + " (me) -> ArithmeticException:" + ae.getMessage());
        }
        return Optional.empty();
    }

    /* ************************* Alternative variants of eval for specific use cases ****************************** */
    @Override
    public String getOriExpr() {
        return originalExpression;
    }

}
