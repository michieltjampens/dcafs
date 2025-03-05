package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class IntegerVal extends NumberVal<Integer>{

    private int defVal=0;
    private boolean abs=false;

    /* Min max*/
    private int min=Integer.MAX_VALUE;
    private int max=Integer.MIN_VALUE;
    private boolean keepMinMax=false;
    private boolean roundDoubles=false;
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
        unit( dig.attr("unit", dig.peekAt("unit").value("")) );
        defValue( dig.attr( "def", defVal) );
        defValue( dig.attr( "default", defVal) );
        roundDoubles = dig.attr("allowreal",false);

        value=defVal; // Set the current value to the default

        String options = dig.attr( "options", "");
        for (var opt : options.split(",")) {
            var arg = opt.split(":");
            switch (arg[0]) {
                case "minmax" -> keepMinMax();
                case "time" -> keepTime();
                case "order" -> order(NumberUtils.toInt(arg[1], -1));
                case "history" -> enableHistory(NumberUtils.toInt(arg[1], -1));
                case "abs" -> enableAbs();
            }
        }
        if( dig.hasPeek("op")){
            setParseOp( dig.peekAt("op").value("") );
        }

        for (Element trigCmd : dig.peekOut("cmd") ) {
            String trig = trigCmd.getAttribute("when");
            String cmd = trigCmd.getTextContent();
            addTriggeredCmd(trig, cmd);
        }

        return this;
    }

    /**
     * Set the unit of the value fe. °C
     * @param unit The unit for the value
     * @return This object with updated unit
     */
    public IntegerVal unit(String unit){
        this.unit=unit;
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

        /* Respond to triggered command based on value */
        if( dQueue!=null && triggered!=null ) {
            for( var trigger : triggered )
                trigger.check(val,value, cmd -> dQueue.add( Datagram.system(cmd)), this::getStdev );
        }
        value=val;
        if( targets!=null ){
            int v = val;
            targets.forEach( wr -> wr.writeLine(id(), Integer.toString(v)));
        }
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
     * Reset this RealVal to its default value
     */
    @Override
    public void reset(){
        keepMinMax=false;
        if( triggered!=null)
            triggered.clear();
        super.reset();
    }

    /* ***************************************** U S I N G ********************************************************** */
    public int max(){
        return max;
    }
    public int min(){
        return min;
    }

    public double asDoubleValue() {
        return value;
    }
    public Object valueAsObject(){ return value;}
    public String stringValue(){ return String.valueOf(value);}
    @Override
    public int asIntegerValue() {
        return value;
    }
    public String asValueString(){
        return value+unit;
    }
    public String toString(){
        String line = value+unit;
        // Check if min max data is kept, if so, add it.
        if( keepMinMax && max!=Integer.MIN_VALUE )
            line += " (Min:"+min+unit+", Max: "+max+unit+")";

        // Check if history is kept, if so, append relevant info
        if( keepHistory>0 && !history.isEmpty()) {
            // Check is we previously added minmax, so we know to remove the closing )
            line = (line.endsWith(")") ? line.substring(0, line.length() - 1) + ", " : line + " (") + "Avg:" + getAvg() + unit + ")";
            if( history.size()==keepHistory){
                line = line.substring(0,line.length()-1) +" StDev: "+getStdev()+unit+")";
            }
        }
        if( !keepTime )
            return line;

        if (timestamp != null)
            return line + " Age: " + TimeTools.convertPeriodtoString(Duration.between(timestamp, Instant.now()).getSeconds(), TimeUnit.SECONDS);
        return line + " Age: No updates yet.";
    }
}
