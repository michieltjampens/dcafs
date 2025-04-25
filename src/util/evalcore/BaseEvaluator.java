package util.evalcore;

import org.tinylog.Logger;
import util.data.vals.NumericVal;

public class BaseEvaluator {
    // Info for debugging
    protected String originalExpression;
    protected String normalizedExpression;
    protected String parseResult;

    // Variables for evaluation
    protected NumericVal[] refs;
    protected int highestI=-1;
    protected Integer[] refLookup;
    protected boolean valid = false;

    String id = "";

    void setHighestI( int hI){ highestI=hI; }
    void setRefs( NumericVal[] refs){
        this.refs=refs;
    }

    public void setId(String id) {
        this.id = id;
    }
    public NumericVal[] getRefs() {
        return refs;
    }
    public String getOriginalExpression(){
        return originalExpression;
    }

    public String getInfo(String id) {
        return "";
    }

    protected boolean badInputCount(int length, String data) {
        if (length < highestI) {
            Logger.error(id + " (eval) -> Not enough elements in input data, need " + (1 + highestI) + " got " + length + ": " + data);
            return true;
        }
        return false;
    }

    public boolean isInValid() {
        return !valid;
    }
}
