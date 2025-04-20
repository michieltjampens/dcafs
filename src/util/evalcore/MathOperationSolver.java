package util.evalcore;

import org.tinylog.Logger;
import util.data.NumericVal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;

/**
 * Storage class for everything related to an operation.
 * Contains the functions that
 */
public class MathOperationSolver {
    MathOperation mop;
    int scale = -1;
    String ori;          // The expression before it was decoded mainly for listing purposes
    String cmd = "";      // Command in which to replace the $ with the result
    NumericVal update;
    String delimiter;
    boolean valid = true;
    boolean doCmd = true;

    public MathOperationSolver(String ori, String delimiter, MathOperation mop) {
        this.ori = ori;
        this.mop = mop;
        this.delimiter = delimiter;

        if (delimiter.isEmpty())
            Logger.error("Delimiter handed to MathOpSolver is empty");
    }

    public NumericVal[] getValRefs() {
        return mop.getValRefs();
    }

    public String getDelimiter() {
        return delimiter;
    }

    /* If the input data is a string */
    public BigDecimal[] solveBDs(String data) {
        var bds = mop.solveRaw(data, delimiter);
        applyScale(bds);
        return bds;
    }

    public void continueBDs(String data, BigDecimal[] bds) {
        mop.continueRaw(data, delimiter, bds);
        applyScale(bds);
    }

    /* if the input data is a arraylist with doubles */
    public BigDecimal[] solveDoubles(ArrayList<Double> data) {
        var bds = mop.solveDoubles(data);
        applyScale(bds);
        return bds;
    }

    public void continueDoubles(ArrayList<Double> data, BigDecimal[] bds) {
        mop.continueDoubles(data, bds);
        applyScale(bds);
    }

    private void applyScale(BigDecimal[] bds) {
        if (scale != -1) {
            int index = mop.getResultIndex();
            bds[index] = bds[index].setScale(scale, RoundingMode.HALF_UP);
        }
    }

    public void scale(int scale) {
        this.scale = scale;
    }

    public MathOperationSolver cmd(String cmd) {
        if (cmd.isEmpty())
            return this;
        this.cmd = cmd;
        valid = true;
        doCmd = true;
        return this;
    }

}