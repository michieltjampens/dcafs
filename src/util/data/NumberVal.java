package util.data;

import org.tinylog.Logger;
import util.math.MathFab;
import util.math.MathUtils;
import util.tools.Tools;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;

public abstract class NumberVal<T extends Number> extends AbstractVal implements NumericVal{
    protected T value;
    protected MathFab parseOp;
    /* History */
    protected ArrayList<T> history;
    /* Triggering */
    protected ArrayList<TriggeredCmd> triggered;

    public void setParseOp( String op ){
        op=op.replace("i","i0");
        op=op.replace("i00","i0"); // just in case it was actually with i0
        parseOp = MathFab.newFormula(op);
        if( !parseOp.isValid() ){
            Logger.error(id() +" -> Tried to apply an invalid op for parsing "+op);
        }else{
            Logger.info(id()+" -> Applying "+op+" after parsing to real/double.");
        }
    }
    public void updateHisoryAndTimestamp(T value ){
        /* Keep history of passed values */
        if( keepHistory!=0 ) {
            history.add(value);
            if( history.size()>keepHistory)
                history.remove(0);
        }
        /* Keep time of last value */
        if( keepTime )
            timestamp= Instant.now();

    }
    /* *********************************** H I S T O R Y ************************************************************* */
    public boolean enableHistory(int count){
        if( count > 0)
            history=new ArrayList<>();
        return super.enableHistory(count);
    }
    public void disableHistory(){
        keepHistory=0;
        clearHistory();
    }
    public void clearHistory(){
        if( history!=null)
            history.clear();
    }
    /**
     * Calculate the average of all the values stored in the history
     * @return The average of the stored values
     */
    public double getAvg(){
        double total=0;
        if(history!=null){
            for( T h : history){
                total += h.doubleValue();
            }
        }else{
            Logger.warn(id() + "(iv)-> Asked for the average of "+(group.isEmpty()?"":group+"_")+name+" but no history kept");
            return value.doubleValue();
        }
        return Tools.roundDouble(total/history.size(),3);
    }
    /**
     * Get the current Standard Deviation based on the history rounded to digits + 2 or 5 digits if no scale was set
     * @return The calculated standard deviation or NaN if either no history is kept or the history hasn't reached
     * the full size yet.
     */
    public double getStdev(int digits){
        if( history==null) {
            Logger.error(id()+" (iv)-> Can't calculate standard deviation without history");
            return Double.NaN;
        }else if( history.size() != keepHistory){
            return Double.NaN;
        }
        ArrayList<Double> decs = new ArrayList<>();
        history.forEach( x -> decs.add(x.doubleValue()));
        return MathUtils.calcStandardDeviation( decs,digits);
    }
    public double getStdev(){
        return getStdev(3);
    }
    /* *********************************** T R I G G E R E D ******************************************************** */
    /**
     * Tries to add a cmd with given trigger, will warn if no valid queue is present to actually execute them
     *
     * @param cmd     The cmd to trigger, $ will be replaced with the current value
     * @param trigger The trigger which is either a comparison between the value and another fixed value fe. above 10 or
     *                'always' to trigger on every update or 'changed' to trigger only on a changed value
     */
    public void addTriggeredCmd(String trigger, String cmd ){

        if( triggered==null)
            triggered = new ArrayList<>();

        var td = new TriggeredCmd(cmd,trigger);
        if( td.isInvalid()) {
            Logger.error(id()+" (iv)-> Failed to convert trigger: "+trigger);
            return;
        }
        triggered.add( td );
    }
    public boolean hasTriggeredCmds(){
        return triggered!=null&& !triggered.isEmpty();
    }
    /* ************************************************************************************************************** */
    /**
     * Get the value but as a BigDecimal instead of double
     * @return The BigDecimal value of this object
     */
    public BigDecimal toBigDecimal(){
        try {
            return BigDecimal.valueOf(value.doubleValue());
        }catch(NumberFormatException e){
            Logger.warn(id()+" hasn't got a valid value yet to convert to BigDecimal");
            return null;
        }
    }
    public abstract void updateValue( double val );
    public abstract void defValue( T val);
    public abstract int asIntegerValue();
    public double asDoubleValue(){ return value.doubleValue(); }
}
