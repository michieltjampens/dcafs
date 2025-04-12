package util.data;

import das.Core;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.math.MathOpFab;
import util.math.MathOperation;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public abstract class NumberVal<T extends Number> extends AbstractVal implements NumericVal{
    protected T value,defVal;
    protected T min,max;
    protected MathOperation parseOp;
    protected int digits=-1;
    /* History */
    protected ArrayList<T> history;
    /* Triggering */
    protected ArrayList<TriggeredCmd> triggered;
    protected boolean keepMinMax=false;
    protected boolean abs=false;

    public void setParseOp( String op ){
        op=op.replace("i","i0");
        op=op.replace("i00","i0"); // just in case it was actually with i0
        var opOpt = MathOpFab.withExpression(op).getMathOp();
        if (opOpt.isEmpty()) {
            Logger.error(id() +" -> Tried to apply an invalid op for parsing "+op);
        }else{
            Logger.info(id()+" -> Applying "+op+" after parsing to real/double.");
            parseOp = opOpt.get();
        }
    }

    public void updateHistoryAndTimestamp(T value) {
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
    /**
     * Enable keeping track of the max and min values received since last reset
     */
    public void keepMinMax(){
        keepMinMax=true;
    }

    public void enableAbs(){
        abs=true;
    }
    /**
     * Set the unit of the value fe. °C
     * @param unit The unit for the value
     */
    public void unit(String unit){
        this.unit=unit;
    }
    /* *********************************** X M L ********************************************************************* */
    public void baseAlter( XMLdigger dig ){

        unit( dig.attr("unit", dig.peekAt("unit").value("")) );

        String options = dig.attr("options", "");
        for (var opt : options.split(",")) {
            var arg = opt.split(":");
            switch (arg[0]) {
                case "minmax" -> keepMinMax();
                case "time" -> keepTime();
                case "scale" -> digits = NumberUtils.toInt(arg[1], -1);
                case "order" -> order(NumberUtils.toInt(arg[1], order()));
                case "history" -> enableHistory(NumberUtils.toInt(arg[1], -1));
                case "abs" -> enableAbs();
            }
        }
        dig.peekOut("cmd").forEach( trigCmd -> {
            String trig = trigCmd.getAttribute("when");
            String cmd = trigCmd.getTextContent();
            addTriggeredCmd(trig, cmd);
        });
        var op = dig.attr("op","");
        if( op.isEmpty() )
            op = dig.peekAt("op").value("");
        if( !op.isEmpty())
            setParseOp(op);
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
        return MathUtils.roundDouble(total / history.size(), 3);
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
        }
        if (history.size() != keepHistory)// Not enough data yet
            return Double.NaN;

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
        return triggered != null && !triggered.isEmpty();
    }
    public void triggerAndForward(T val){
        /* Respond to triggered command based on value */
        if( triggered!=null ) {
            for( var trigger : triggered )
                trigger.check(val,value, cmd -> Core.addToQueue( Datagram.system(cmd)), this::getStdev );
        }

        if (targets != null)
            targets.forEach( wr -> wr.writeLine(id(),String.valueOf(val)));

    }
    /* ************************************************************************************************************** */
    /**
     * Reset this RealVal to its default value
     */
    @Override
    public void reset(){
        keepMinMax=false;
        digits=-1;
        abs=false;
        if( triggered!=null)
            triggered.clear();
        super.reset();
    }
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
    public T max(){
        return max;
    }
    public T min(){
        return min;
    }
    public abstract void updateValue( double val );
    public abstract void defValue( T val);
    public int asIntegerValue(){ return value.intValue();}

    public double asDoubleValue(){ return value.doubleValue(); }

    public String stringValue(){ return String.valueOf(value);}
    public String asValueString(){ return value+unit; }

    public String getExtras() {
        var line = "";
        if (keepMinMax)
            line += " (Min:" + min + unit + ", Max: " + max + unit + ")";

        // Check if history is kept, if so, append relevant info
        if (keepHistory > 0 && !history.isEmpty()) {
            // Check is we previously added minmax, so we know to remove the closing )
            line = (line.endsWith(")") ? line.substring(0, line.length() - 1) + ", " : line + " (") + "Avg:" + getAvg() + unit + ")";
            if (history.size() == keepHistory) {
                line = line.substring(0, line.length() - 1) + " StDev: " + getStdev() + unit + ")";
            }
        }
        if (!keepTime)
            return line;

        if (timestamp != null)
            return line + " Age: " + TimeTools.convertPeriodToString(Duration.between(timestamp, Instant.now()).getSeconds(), TimeUnit.SECONDS);
        return line + " Age: No updates yet.";
    }
    public String toString(){
        return value + unit + getExtras(); // Check if min max data is kept, if so, add it.
    }
}
