package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.util.Optional;

public class RealVal extends NumberVal<Double>{

    private double rawValue=Double.NaN;

    private RealVal(){
        min = Double.MAX_VALUE;
        max = Double.MIN_VALUE;
        defVal=Double.NaN;
    }

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
     * @return The created node
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

        scale( dig.attr("scale", dig.peekAt("scale").value(-1)) ) ;

        defValue( dig.attr("default", defVal) );
        defValue( dig.attr("def", defVal) );

        value=defVal; // Set the current value to the default

        baseAlter(dig);

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
        triggerAndForward(val);
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
            if( value==null||Double.isNaN(value))
                value=defVal;
        }
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
     *
     * @return The amount of digits to scale to using rounding half up
     */
    public int scale(){ return digits; }
    /* ***************************************** U S I N G ********************************************************** */
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

    /**
     * Update the value
     * @param val The new value
     */
    public void updateValue(double val){
        value(val);
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
}
