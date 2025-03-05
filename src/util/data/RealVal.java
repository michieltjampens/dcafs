package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.time.*;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class RealVal extends NumberVal<Double>{

    private double rawValue=Double.NaN;
    private double defVal=Double.NaN;

    private int digits=-1;
    private boolean abs=false;

    /* Min max*/
    private double min=Double.MAX_VALUE;
    private double max=-1*Double.MAX_VALUE;
    private boolean keepMinMax=false;

    /* Triggering */
    private ArrayList<TriggeredCmd> triggered;

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

        updateHisoryAndTimestamp(val);

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
    public void defValue(Double defVal){
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

    public void enableAbs(){
        abs=true;
    }

    /* ***************************************** U S I N G ********************************************************** */
    /**
     *
     * @return The amount of digits to scale to using rounding half up
     */
    public int scale(){ return digits; }
    /**
     * @return Get the current value as a double
     */
    //public double value(){ return value; }
    public Object valueAsObject(){ return value;}
    public String stringValue(){ return String.valueOf(value);}
    public double value( String type ){
        return switch( type ){
            case "stdev", "stdv"-> getStdev();
            case "avg", "average" ->  getAvg();
            case "min" -> min();
            case "max" -> max();
            case "raw" -> raw();
            default -> asDoubleValue();
        };
    }
    public int asIntegerValue(){ return value.intValue(); }
    /**
     * Update the value
     * @param val The new value
     */
    public void updateValue(double val){
        value(val);
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
    @Override
    public double getStdev(){
        return getStdev(digits);
    }

    /**
     * Compare two RealVal's based on their values
     * @param dv The RealVal to compare to
     * @return True if they have the same value
     */
    public boolean equals( RealVal dv){
        return Double.compare(value,dv.asDoubleValue())==0;
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
