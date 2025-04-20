package util.evalcore;

import util.data.NumericVal;

public class BaseEvaluator {
    // Info for debugging
    protected String originalExpression;
    protected String normalizedExpression;
    protected String parseResult;

    // Variables for evaluation
    protected NumericVal[] refs;
    protected int highestI=-1;
    protected Integer[] refLookup;

    void setHighestI( int hI){ highestI=hI; }
    void setRefs( NumericVal[] refs){
        this.refs=refs;
    }

    public NumericVal[] getRefs() {
        return refs;
    }
    public String getOriginalExpression(){
        return originalExpression;
    }

}
