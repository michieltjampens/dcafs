package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathFab;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

public class RealVal extends AbstractVal implements NumericVal{

    private double value;
    private double rawValue=Double.NaN;

    private double defVal=Double.NaN;

    private int digits=-1;
    private boolean abs=false;

    /* Min max*/
    private double min=Double.MAX_VALUE;
    private double max=-1*Double.MAX_VALUE;
    private boolean keepMinMax=false;

    /* History */
    private ArrayList<Double> history;

    /* Triggering */
    private ArrayList<TriggeredCmd> triggered;
    private MathFab parseOp;

    private RealVal(){}

    /**
     * Constructs a new RealVal with the given group and name
     *
     * @param group The group this RealVal belongs to
     * @param name The name for the RealVal
     * @return The constructed RealVal
     */
    public static RealVal newVal(String group, String name){
        var rv = new RealVal();
        rv.name(name);
        rv.group(group);
        return rv;
    }

    /* ********************************* Constructing ************************************************************ */

    /**
     * Create a new Realval based on a rtval real node
     * @param rtval The node
     * @return The created node, still needs dQueue set
     */
    public static Optional<RealVal> build(Element rtval,String altGroup){
        var read = readGroupAndName(rtval,altGroup);
        if( read == null)
            return Optional.empty();
        return Optional.of(RealVal.newVal(read[0],read[1]).alter(rtval));
    }

    /**
     * Change the RealVal according to a xml node
     * @param rtval The node
     */
    public RealVal alter( Element rtval ){
        reset();

        var dig = XMLdigger.goIn(rtval);
        unit( dig.attr("unit", dig.peekAt("unit").value("")) );
        scale( dig.attr("scale", dig.peekAt("scale").value(-1)) ) ;

        defValue( dig.attr("default", defVal) );
        defValue( dig.attr("def", defVal) );

        value=defVal; // Set the current value to the default

        String options = dig.attr("options", "");
        for (var opt : options.split(",")) {
            var arg = opt.split(":");
            switch (arg[0]) {
                case "minmax" -> keepMinMax();
                case "time" -> keepTime();
                case "scale" -> scale(NumberUtils.toInt(arg[1], -1));
                case "order" -> order(NumberUtils.toInt(arg[1], -1));
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

        return this;
    }
    public void setParseOp( String op ){
        if( op.isEmpty())
            return;
        op=op.replace("i","i0");
        op=op.replace("i00","i0"); // just incase it was actually with i0
        parseOp = MathFab.newFormula(op);
        if( !parseOp.isValid() ){
            Logger.error(id() +" -> Tried to apply an invalid op for parsing "+op);
        }else{
            Logger.info(id()+" -> Applying "+op+" after parsing to real/double.");
        }
    }
    /**
     * Set the unit of the value fe. °C
     * @param unit The unit for the value
     * @return This object with updated unit
     */
    public RealVal unit(String unit){
        this.unit=unit;
        return this;
    }
    public boolean parseValue( String val ){
        var res = NumberUtils.toDouble(val,Double.NaN);

        if(!Double.isNaN(res)){
            if( parseOp != null) {
                res = parseOp.solveFor(res);
                if( Double.isNaN(res))
                    Logger.error(id()+" -> Failed to parse "+val+" with "+parseOp.getOri());
            }
            rawValue=res;
            value(res);
            return true;
        }else if( Double.isNaN(defVal) ){
            value(defVal);
            return true;
        }
        Logger.error(id() + " -> Failed to parse "+val);
        return false;
    }
    public void resetValue(){
        value=defVal;
    }
    /**
     * Update the value, this will -depending on the options set- also update related variables
     * @param val The new value
     * @return This object after updating the value etc
     */
    public RealVal value(double val ){

        /* Keep history of passed values */
        if( keepHistory!=0 ) {
            history.add(val);
            if( history.size()>keepHistory)
                history.remove(0);
        }
        /* Keep time of last value */
        if( keepTime )
            timestamp= Instant.now();

        /* Keep min max */
        if( keepMinMax ){
            min = Math.min(min,val);
            max = Math.max(max,val);
        }
        if(abs)
            val = Math.abs(val);

        if( digits != -1) {
            value = Tools.roundDouble(val, digits);
        }else{
            value=val;
        }
        /* Respond to triggered command based on value */
        if( dQueue!=null && triggered!=null ) {
            for( var trigger : triggered )
                trigger.check(val,value, cmd -> dQueue.add( Datagram.system(cmd)), this::getStdev );
        }

        if( targets!=null ){
            double v = val;
            targets.forEach( wr -> wr.writeLine(id(),Double.toString(v)));
        }
        return this;
    }

    /**
     * Set the default value, this will be used as initial value and after a reset
     *
     * @param defVal The default value
     */
    public void defValue(double defVal){
        if( !Double.isNaN(defVal) ) { // If the given value isn't NaN
            this.defVal = defVal;
            if( Double.isNaN(value))
                value=defVal;
        }
    }

    /**
     * Reset this RealVal to its default value
     */
    @Override
    public void reset(){
        keepMinMax=false;
        digits=-1;
        if( triggered!=null)
            triggered.clear();
        super.reset();
    }
    /**
     * Set the amount of digits to scale to using half up rounding
     * @param fd The amount of digits
     * @return This object after setting the digits
     */
    public RealVal scale(int fd){
        this.digits=fd;
        return this;
    }

    /**
     * Enable keeping track of the max and min values received since last reset
     */
    public void keepMinMax(){
        keepMinMax=true;
    }

    @Override
    public boolean enableHistory(int count){
        if( count > 0)
            history=new ArrayList<>();
        return super.enableHistory(count);
    }
    @Override
    public void disableHistory(){
        keepHistory=0;
        clearHistory();
    }
    public void clearHistory(){
        if( history!=null)
            history.clear();
    }

    public void enableAbs(){
        abs=true;
    }
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
            Logger.error(id()+" (dv)-> Failed to convert trigger: "+trigger);
            return;
        }
        triggered.add( td );
    }
    public boolean hasTriggeredCmds(){
        return triggered!=null&& !triggered.isEmpty();
    }
    /* ***************************************** U S I N G ********************************************************** */
    /**
     * Get a delimited string with all the used options
     * @return The options in a listing or empty if none are used
     */
    private String getOptions(){
        var join = new StringJoiner(",");
        if( keepTime )
            join.add("time");
        if( keepHistory>0)
            join.add("history:"+keepHistory);
        if( keepMinMax )
            join.add("minmax");
        if( order !=-1 )
            join.add("order:"+order);
        if( abs )
            join.add("abs");
        return join.toString();
    }

    /**
     *
     * @return The amount of digits to scale to using rounding half up
     */
    public int scale(){ return digits; }

    /**
     * @return Get the current value as a double
     */
    public double value(){ return value; }
    public Object valueAsObject(){ return value;}
    public String stringValue(){ return String.valueOf(value);}
    public double value( String type ){
        return switch( type ){
            case "stdev", "stdv"-> getStdev();
            case "avg", "average" ->  getAvg();
            case "min" -> min();
            case "max" -> max();
            case "raw" -> raw();
            default -> value();
        };
    }
    public int intValue(){ return ((Double)value).intValue(); }
    /**
     * Update the value
     * @param val The new value
     */
    public void updateValue(double val){
        value(val);
    }

    /**
     * Get the value but as a BigDecimal instead of double
     * @return The BigDecimal value of this object
     */
    public BigDecimal toBigDecimal(){
        try {
            return BigDecimal.valueOf(value);
        }catch(NumberFormatException e){
            Logger.warn(id()+" hasn't got a valid value yet to convert to BigDecimal");
            return null;
        }
    }
    public double min(){
        return min;
    }
    public double max(){
        return max;
    }
    public double raw() {
        if( Double.isNaN(rawValue))
            return value;
        return rawValue;
    }
    /**
     * Calculate the average of all the values stored in the history
     * @return The average of the stored values
     */
    public double getAvg(){
        double total=0;
        if(history!=null){
            for( var h : history){
                total+=h;
            }
        }else{
            Logger.warn(id() + "(dv)-> Asked for the average of "+(group.isEmpty()?"":group+"_")+name+" but no history kept");
            return value;
        }
        return Tools.roundDouble(total/history.size(),digits==-1?3:digits);
    }

    /**
     * Get the current Standard Deviation based on the history rounded to digits + 2 or 5 digits if no scale was set
     * @return The calculated standard deviation or NaN if either no history is kept or the history hasn't reached
     * the full size yet.
     */
    public double getStdev(int scale){
        if( history==null) {
            Logger.error(id()+" (dv)-> Can't calculate standard deviation without history");
            return Double.NaN;
        }else if( history.size() != keepHistory){
            return Double.NaN;
        }
        return MathUtils.calcStandardDeviation(history,scale==-1?5:scale+2);
    }
    public double getStdev(){
        return getStdev(digits);
    }

    /**
     * Compare two RealVal's based on their values
     * @param dv The RealVal to compare to
     * @return True if they have the same value
     */
    public boolean equals( RealVal dv){
        return Double.compare(value,dv.value())==0;
    }

    /**
     * Compare with a double
     * @param d The double to compare to
     * @return True if they are equal
     */
    public boolean equals( double d){
        return Double.compare(value,d)==0;
    }
    public String asValueString(){
        return value+unit;
    }
    public String toString(){
        String line = value+unit;
        if( keepMinMax && max!=Double.MIN_VALUE )
            line += " (Min:"+min+unit+", Max: "+max+unit+")";
        if( keepHistory>0 && !history.isEmpty()) {
            line = (line.endsWith(")") ? line.substring(0, line.length() - 1) + ", " : line + " (") + "Avg:" + getAvg() + unit + ")";
            if( history.size()==keepHistory){
                line = line.substring(0,line.length()-1) +" StDev: "+getStdev()+unit+")";
            }
        }
        if( keepTime ) {
            if (timestamp != null) {
                line += " Age: " + TimeTools.convertPeriodtoString(Duration.between(timestamp, Instant.now()).getSeconds(), TimeUnit.SECONDS);
            } else {
                line += " Age: No updates yet.";
            }
        }
        return line;
    }
}
