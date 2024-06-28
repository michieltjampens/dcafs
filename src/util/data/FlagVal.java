package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLdigger;
import worker.Datagram;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.StringJoiner;

public class FlagVal extends AbstractVal implements NumericVal{

    boolean state=false;
    boolean defState=false;

    /* Triggering */
    ArrayList<String> raisedList = new ArrayList<>();
    ArrayList<String> loweredList = new ArrayList<>();

    /* Options */
    ArrayList<Boolean> history;
    private final ArrayList<String> TRUE = new ArrayList<>();
    private String trueRegex;
    private final ArrayList<String> FALSE = new ArrayList<>();
    private String falseRegex;
    private FlagVal(){}

    /**
     * Construct a new FlagVal
     * @param group The group the flag belongs to
     * @param name The name of the flag
     * @return The constructed FlagVal
     */
    public static FlagVal newVal(String group, String name){
        return new FlagVal().group(group).name(name);
    }

    /* **************************** C O N S T R U C T I N G ******************************************************* */
    public static Optional<FlagVal> build(Element rtval, String group){
        var read = readGroupAndName(rtval,group);
        if( read == null)
            return Optional.empty();
        return Optional.of(FlagVal.newVal(read[0],read[1]).reload(rtval));
    }
    public FlagVal reload(Element rtval){
        reset(); // reset is needed if this is called because of reload
        name(name);

        var dig = XMLdigger.goIn(rtval);

        group( dig.attr("group", group()));
        defState( dig.attr("default", defState));
        defState( dig.attr("def", defState));

        state=defState; // Set the current state to the default

        String options =  dig.attr("options", "");
        for (var opt : options.split(",")) {
            var arg = opt.split(":");
            switch (arg[0]) {
                case "time" -> keepTime();
                case "order" -> order(NumberUtils.toInt(arg[1], -1));
                case "history" -> enableHistory(NumberUtils.toInt(arg[1], -1));
            }
        }

        // Triggered Commands
        if ( dig.hasPeek("cmd") )
            enableTriggeredCmds(dQueue);
        for (Element trigCmd : dig.peekOut("cmd")) {
            String trig = trigCmd.getAttribute("when");
            String cmd = trigCmd.getTextContent();
            addTriggeredCmd(trig, cmd);
        }
        // Parsing
        if( dig.hasPeek("true"))
            TRUE.clear();
        for (Element parse : dig.peekOut("true")) {
            if( parse.hasAttribute("delimiter")){
                var trues = parse.getTextContent().split(parse.getAttribute("delimiter"));
                TRUE.addAll(Arrays.asList(trues));
            }else if( parse.hasAttribute("regex")){
                trueRegex=parse.getAttribute("regex");
                break;
            }else {
                TRUE.add(parse.getTextContent());
            }
        }
        if( !TRUE.isEmpty() && trueRegex!=null ){
            Logger.error(id()+" -> Can't combine both fixed and regex based for true flag");
        }
        if( dig.hasPeek("false"))
            FALSE.clear();
        for (Element parse : dig.peekOut("false")) {
            if( parse.hasAttribute("delimiter")){
                var falses = parse.getTextContent().split(parse.getAttribute("delimiter"));
                FALSE.addAll(Arrays.asList(falses));
            }else if( parse.hasAttribute("regex")){
                falseRegex=parse.getAttribute("regex");
            }else {
                FALSE.add(parse.getTextContent());
            }
        }
        if( !FALSE.isEmpty() && falseRegex!=null ){
            Logger.error(id()+" -> Can't combine both fixed and regex based for false flag");
        }
        return this;
    }
    /**
     * Set the name
     * @param name The name
     * @return This object with altered name
     */
    public FlagVal name(String name){
        this.name=name;
        return this;
    }

    /**
     * Set the group this belongs to
     * @param group The group of which this is part
     * @return This object with altered group
     */
    public FlagVal group(String group){
        this.group=group;
        return this;
    }
    public FlagVal value(String state ){
        if( state.equalsIgnoreCase("true")
                || state.equalsIgnoreCase("1")
                || state.equalsIgnoreCase("on")) {
            value(true);
        }else if( state.equalsIgnoreCase("false")|| state.equalsIgnoreCase("0")
                || state.equalsIgnoreCase("off")) {
            value(false);
        }
        return this;
    }
    /**
     * Set the state, apply the options and check for triggered cmd's
     * @param val The new state
     * @return This object with altered state
     */
    public FlagVal value(boolean val){
        /* Keep time of last value */
        if( keepTime )
            timestamp= Instant.now();
        /* Check if valid and issue commands if trigger is met */
        if( val!=state){ // If the value has changed
            if( val ){ // If the flag goes from FALSE to TRUE => raised
                raisedList.forEach( c -> dQueue.add(Datagram.system(c.replace("$","true"))));
            }else{ // If the flag goes from TRUE to FALSE => lowered
                loweredList.forEach( c -> dQueue.add(Datagram.system(c.replace("$","false"))));
            }
        }
        targets.forEach( x -> x.writeLine(id(),Boolean.toString(val)));

        state=val;// update the state
        return this;
    }

    /**
     * Toggle the state of the flag and return the new state
     */
    public void toggleState(){
        value(!state);
    }
    /**
     * Alter the default state (default is false)
     * @param defState The new default state
     */
    public void defState( boolean defState){
        this.defState = defState;
    }

    /**
     * Enable the storage of old values up till the count
     * @param count The amount of old values to store
     * @return True if this was enabled
     */
    @Override
    public boolean enableHistory(int count){
        if( count > 0) // only valid if above 0, so no need to init if not
            history=new ArrayList<>();
        return super.enableHistory(count);
    }
    @Override
    public boolean parseValue(String state) {
        state=state.toLowerCase();
        if( TRUE.isEmpty() ){
            if( !trueRegex.isEmpty() ) {
                if (state.matches(trueRegex)) {
                    value(true);
                    return true;
                }
            }
        }else if( TRUE.contains(state) ) {
            value(true);
            return true;
        }
        if( FALSE.isEmpty() ){
            if( !falseRegex.isEmpty() ) {
                if (state.matches(falseRegex)) {
                    value(false);
                    return true;
                }
            }
        }else if( FALSE.contains(state)) {
            value(false);
            return true;
        }
        Logger.error( id() + " -> Couldn't parse "+state);
        return false;
    }
    /* *************************************** U S I N G *********************************************************** */

    /**
     * Alter the state based on the double value, false if 0 or true if not
     * @param val The value to check that determines the state
     */
    @Override
    public void updateValue(double val) {
        value( Double.compare(val,0.0)!=0 );
    }

    @Override
    public String asValueString() {
        return toString();
    }
    public Object valueAsObject(){return state;}
    /**
     * Convert the flag state to a big decimal value
     * @return BigDecimal.ONE if the state is true or ZERO if not
     */
    public BigDecimal toBigDecimal(){
        return state?BigDecimal.ONE:BigDecimal.ZERO;
    }

    /**
     * Check if the flag state matches that of another flag
     * @param fv The flag to compare to
     * @return True if the flags have the same state
     */
    public boolean equals(FlagVal fv){
        return state == fv.isUp();
    }

    /**
     * Check if the flag is up
     * @return True if up
     */
    public boolean isUp(){
        return state;
    }
    /**
     * Check if the flag is down
      * @return True if down
     */
    public boolean isDown(){
        return !state;
    }

    /**
     * Retrieve the state of the flag but as a double
     * @return 1.0 if true and 0.0 if false
     */
    public double value(){
        return state?1:0;
    }

    @Override
    public int intValue() {
        return state?1:0;
    }
    public String stringValue(){ return toString();}
    public String toString(){
        return String.valueOf(state);
    }

    /**
     * Reset this flagVal to default state, disables options and clears cmd's
     */
    @Override
    public void reset(){
        state = defState;
        raisedList.clear();
        loweredList.clear();
        trueRegex=null;
        falseRegex=null;
        FALSE.clear();
        FALSE.addAll(Arrays.asList("false","1","off","low"));
        TRUE.clear();
        TRUE.addAll(Arrays.asList("true","1","on","high"));
        super.reset(); // This resets common options like keep time
    }
    public void resetValue(){
        state=defState;
    }
    /* ******************************** T R I G G E R E D ********************************************************** */
    /**
     * Tries to add a cmd with given trigger, will warn if no valid queue is present to actually execute them
     *
     * @param trigger The trigger which is either a comparison between the value and another fixed value fe. above 10 or
     *                'always' to trigger on every update or 'changed' to trigger only on a changed value
     * @param cmd     The cmd to trigger, $ will be replaced with the current value
     */
    public void addTriggeredCmd(String trigger, String cmd){

        switch (trigger) {
            case "raised", "up", "set" -> // State goes from false to true
                    raisedList.add(cmd);
            case "lowered", "down", "clear" -> // state goes from true to false
                    loweredList.add(cmd);
            default -> { // No other triggers for now or typo's
            }
        }
    }
    public boolean hasTriggeredCmds(){
        return !loweredList.isEmpty()||!raisedList.isEmpty();
    }

    private String getOptions(){
        var join = new StringJoiner(",");
        if( keepTime )
            join.add("time");
        if( keepHistory>0)
            join.add("history:"+keepHistory);
        if( order !=-1 )
            join.add("order:"+order);
        return join.toString();
    }
}
