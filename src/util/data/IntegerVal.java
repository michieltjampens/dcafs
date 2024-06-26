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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class IntegerVal extends AbstractVal implements NumericVal{
    private int value;
    private int rawValue=Integer.MAX_VALUE;
    private int defVal=0;
    private boolean abs=false;

    /* Min max*/
    private int min=Integer.MAX_VALUE;
    private int max=Integer.MIN_VALUE;
    private boolean keepMinMax=false;

    /* History */
    private ArrayList<Integer> history;

    /* Triggering */
    private ArrayList<TriggeredCmd> triggered;
    private MathFab parseOp;
    private boolean roundDoubles=false;
    /**
     * Constructs a new RealVal with the given group and name
     *
     * @param group The group this RealVal belongs to
     * @param name The name for the RealVal
     * @return The constructed RealVal
     */
    public static IntegerVal newVal(String group, String name){
        return new IntegerVal().group(group).name(name);
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
    public void setParseOp( String op ){
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
     * Set the name, this needs to be unique within the group
     * @param name The new name
     * @return This object with altered name
     */
    public IntegerVal name(String name){
        this.name=name;
        return this;
    }
    /**
     * Set the group, multiple DoubleVals can share a group id
     * @param group The new group
     * @return This object with altered group
     */
    public IntegerVal group(String group){
        this.group=group;
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
        if( abs )
            val=Math.abs(val);

        /* Respond to triggered command based on value */
        if( dQueue!=null && triggered!=null ) {
            double v = val;
            // Execute all the triggers, only if it's the first time
            triggered.forEach(tc -> tc.apply(v));
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
        if( val.startsWith("0") ){
            if( val.length()>2){
                if( val.charAt(1)!='x') {
                    while( val.charAt(0)=='0' && val.length()>1)
                        val = val.substring(1);
                }
            }
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
            rawValue=res;
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
                rawValue=defVal;
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
    public void defValue(int defVal){
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
    @Override
    public boolean enableHistory(int count){
        if( count > 0)
            history=new ArrayList<>();
        return super.enableHistory(count);
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
        triggered.add( new TriggeredCmd(cmd,trigger) );
    }
    public boolean hasTriggeredCmds(){
        return triggered!=null&& !triggered.isEmpty();
    }
    /* ***************************************** U S I N G ********************************************************** */
    /**
     * Get a ',' delimited string with all the used options
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
        return join.toString();
    }
    @Override
    public BigDecimal toBigDecimal() {
        try {
            return BigDecimal.valueOf(value);
        }catch(NumberFormatException e){
            Logger.warn(id()+" hasn't got a valid value yet to convert to BigDecimal");
            return null;
        }
    }
    public int max(){
        return max;
    }
    public int min(){
        return min;
    }
    @Override
    public double value() {
        return value;
    }
    public Object valueAsObject(){ return value;}
    public String stringValue(){ return String.valueOf(value);}
    @Override
    public int intValue() {
        return value;
    }
    public int intValue( String type) {
        return switch( type ){
            case "min" -> min();
            case "max" -> max();
            case "raw" -> rawValue;
            default -> intValue();
        };
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
            Logger.warn(id() + "(iv)-> Asked for the average of "+(group.isEmpty()?"":group+"_")+name+" but no history kept");
            return value;
        }
        return Tools.roundDouble(total/history.size(),3);
    }
    /**
     * Get the current Standard Deviation based on the history rounded to digits + 2 or 5 digits if no scale was set
     * @return The calculated standard deviation or NaN if either no history is kept or the history hasn't reached
     * the full size yet.
     */
    public double getStdev(){
        if( history==null) {
            Logger.error(id()+" (iv)-> Can't calculate standard deviation without history");
            return Double.NaN;
        }else if( history.size() != keepHistory){
            return Double.NaN;
        }
        ArrayList<Double> decs = new ArrayList<>();
        history.forEach( x -> decs.add((double)x));
        return MathUtils.calcStandardDeviation( decs,3);
    }
    public String asValueString(){
        return value+unit;
    }
    public String toString(){
        String line = value+unit;
        if( keepMinMax && max!=Integer.MIN_VALUE )
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
    /**
     * TriggeredCmd is a way to run cmd's if the new value succeeds in either the compare or meets the other options
     * Cmd: if it contains a '$' this will be replaced with the current value
     * Current triggers:
     * - Double comparison fe. above 5 and below 10
     * - always, means always issue the cmd independent of the value
     * - changed, only issue the command if the value has changed
     */
    private class TriggeredCmd{
        String cmd; // The cmd to issue
        String ori; // The compare before it was converted to a function (for toString purposes)
        RealVal.TRIGGERTYPE type;
        Function<Double,Boolean> comp; // The compare after it was converted to a function
        boolean triggered=false; // The last result of the comparison

        /**
         * Create a new TriggeredCmd with the given cmd and trigger, doesn't set the cmd of it failed to convert the trigger
         * @param cmd The cmd to execute when triggered
         * @param trigger Either 'always' if the cmd should be done on each update, or 'changed' if the value changed
         *                or a single or double compare fe 'above 10' or 'below 5 or above 50' etc
         */
        public TriggeredCmd( String cmd, String trigger){
            this.cmd=cmd;
            this.ori=trigger;
            type= RealVal.TRIGGERTYPE.COMP;
            switch (trigger) {
                case "", "always" -> type = RealVal.TRIGGERTYPE.ALWAYS;
                case "changed" -> type = RealVal.TRIGGERTYPE.CHANGED;
                default -> {
                    if (trigger.contains("stdev")) {
                        type = RealVal.TRIGGERTYPE.STDEV;
                        trigger = trigger.replace("stdev", "");
                    }
                    comp = MathUtils.parseSingleCompareFunction(trigger);
                    if (comp == null) {
                        this.cmd = "";
                    }
                }
            }
        }
        public boolean isInvalid(){
            return cmd.isEmpty();
        }
        public void apply( double val ){
            if( dQueue==null) {
                Logger.error(id()+" (iv)-> Tried to check for a trigger without a dQueue");
                return;
            }
            boolean ok;
            switch (type) {
                case ALWAYS -> {
                    dQueue.add(Datagram.system(cmd.replace("$", String.valueOf(val))));
                    return;
                }
                case CHANGED -> {
                    if (val != value)
                        dQueue.add(Datagram.system(cmd.replace("$", String.valueOf(val))));
                    return;
                }
                case COMP -> ok = comp.apply(val);
                case STDEV -> {
                    double sd = getStdev();
                    if (Double.isNaN(sd))
                        return;
                    ok = comp.apply(getStdev()); // Compare with the Standard Deviation instead of value
                }
                default -> {
                    Logger.error(id() + " (iv)-> Somehow an invalid trigger sneaked in... ");
                    return;
                }
            }
            if( !triggered && ok ){
                dQueue.add(Datagram.system(cmd.replace("$", String.valueOf(val))));
            }else if( triggered && !ok){
                triggered=false;
            }
        }
    }
}
