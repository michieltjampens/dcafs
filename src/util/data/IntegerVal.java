package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class IntegerVal extends NumberVal<Integer>{

    private boolean roundDoubles=false;

    public IntegerVal(){
        /* Min max*/
        min=Integer.MAX_VALUE;
        max=Integer.MIN_VALUE;

        defVal = 0;
    }
    /**
     * Constructs a new RealVal with the given group and name
     *
     * @param group The group this RealVal belongs to
     * @param name The name for the RealVal
     * @return The constructed RealVal
     */
    public static IntegerVal newVal(String group, String name){
        var iv = new IntegerVal();
        iv.name(name);
        iv.group(group);
        return iv;
    }

    /* ********************************* Constructing ************************************************************ */
    /**
     * Create a new IntegerVal based on a rtval real node
     * @param rtval The node
     * @param group The group the node is found in
     * @return The created node, still needs dQueue set
     */
    public static Optional<IntegerVal> build(Element rtval, String group){

        var read = readGroupAndName(rtval,group);
        if( read == null)
            return Optional.empty();

        return Optional.of(IntegerVal.newVal(read[0],read[1]).alter(rtval));
    }

    /**
     * Change the RealVal according to a xml node
     * @param rtval The node
     */
    public IntegerVal alter( Element rtval ){
        reset();
        var dig = XMLdigger.goIn(rtval);

        defValue( dig.attr( "def", defVal) );
        defValue( dig.attr( "default", defVal) );
        roundDoubles = dig.attr("allowreal",false);

        value=defVal; // Set the current value to the default

        baseAlter(dig);

        return this;
    }
    /**
     * Update the value, this will -depending on the options set- also update related variables
     * @param val The new value
     * @return This object after updating the value etc
     */
    public IntegerVal value( int val ){

       updateHisoryAndTimestamp(val);

        /* Keep min max */
        if( keepMinMax ){
            min = Math.min(min,val);
            max = Math.max(max,val);
        }
        if( abs )
            val=Math.abs(val);
        value=val;

        /* Respond to triggered command based on value and forward */
        triggerAndForward(val);
        return this;
    }
    public void increment(){
        value(value+1);
    }
    public void resetValue(){
        value=defVal;
    }
    @Override
    public void updateValue( double val ) {
        value(((Double)val).intValue());
    }
    public boolean parseValue( String val ){
        // NumberUtils.createInteger can handle hex, but considers leading zero and not hex as octal
        // So remove those leading zero's if not hex
        if( val.startsWith("0") && val.length()>2 && val.charAt(1)!='x') {
            while( val.charAt(0)=='0' && val.length()>1)
                val = val.substring(1);
        }
        try {
            var res = NumberUtils.createInteger(val.trim());
            if( parseOp != null) {
                var dres = parseOp.solveFor((double)res);
                if( Double.isNaN(dres)) {
                    Logger.error(id() + " -> Failed to parse " + val + " with " + parseOp.getOri());
                }else{
                    res = (int) dres;
                }
            }
            value(res);
            return true;
        }catch( NumberFormatException e ){
            try {
                if (val.contains(".") && roundDoubles) {
                    value = (int) Math.rint(NumberUtils.createDouble(val));
                    return true;
                }
            }catch(NumberFormatException ed){
                Logger.error(id() + " -> Failed to parse to int/double: "+val);
                return false;
            }
            Logger.error(id()+" -> Failed to parse "+val+" to integer.");
            if( defVal != Integer.MAX_VALUE ) {
                value(defVal);
                return true;
            }
            Logger.error(id() + " -> Failed to parse to int: "+val);
            return false;
        }
    }
    /**
     * Set the default value, this will be used as initial value and after a reset
     *
     * @param defVal The default value
     */
    public void defValue(Integer defVal){
        this.defVal = defVal;
    }

    /* ***************************************** U S I N G ********************************************************** */

}
