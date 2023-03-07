package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLdigger;
import util.xml.XMLtools;
import worker.Datagram;

import java.time.Instant;
import java.util.ArrayList;
import java.util.function.Function;

public class TextVal extends AbstractVal{
    private String value="";
    private ArrayList<String> history = new ArrayList<>();
    private ArrayList<TriggeredCmd> triggered = new ArrayList<>();

    /* ********************************* Constructing ************************************************************ */
    /**
     * Constructs a new RealVal with the given group and name
     *
     * @param group The group this RealVal belongs to
     * @param name The name for the RealVal
     * @return The constructed RealVal
     */
    public static TextVal newVal(String group, String name){
        return new TextVal().group(group).name(name);
    }

    /**
     * Create a new TextVal based on a rtval text node
     * @param rtval The node
     * @param group The group the node is found in
     * @return The created node, still needs dQueue set
     */
    public static TextVal build(Element rtval, String group){
        String name = XMLtools.getStringAttribute(rtval,"name","");
        name = XMLtools.getStringAttribute(rtval,"id",name);

        if( name.isEmpty() && XMLtools.getChildElements(rtval).isEmpty() )
            name = rtval.getTextContent();

        if( name.isEmpty()){
            Logger.error("Tried to create a TextVal without id/name, group "+group);
            return null;
        }
        return TextVal.newVal(group,name).alter(rtval);
    }

    /**
     * Change the RealVal according to a xml node
     * @param rtval The node
     */
    public TextVal alter( Element rtval ){
        reset();
        String options = XMLtools.getStringAttribute(rtval, "options", "");
        for (var opt : options.split(",")) {
            var arg = opt.split(":");
            switch (arg[0]) {
                case "time" -> keepTime();
                case "order" -> order(NumberUtils.toInt(arg[1], -1));
                case "history" -> enableHistory(NumberUtils.toInt(arg[1], -1));
            }
        }
        for (Element trigCmd : XMLtools.getChildElements(rtval, "cmd")) {
            String trig = trigCmd.getAttribute("when");
            String cmd = trigCmd.getTextContent();
            addTriggeredCmd(trig, cmd);
        }
        return this;
    }

    public TextVal group(String group){
        this.group=group;
        return this;
    }
    public TextVal name(String name){
        this.name=name;
        return this;
    }
    public String value(){ return value; }
    public String stringValue(){ return value;}
    public TextVal value( String val){
        /* Keep history of passed values */
        if( keepHistory!=0 ) {
            history.add(val);
            if( history.size()>keepHistory)
                history.remove(0);
        }
        /* Keep time of last value */
        if( keepTime )
            timestamp= Instant.now();

        /* Respond to triggered command based on value */
        if( dQueue!=null && !triggered.isEmpty() ) {
            // Execute all the triggers, only if it's the first time
            triggered.forEach(tc -> tc.apply(val));
        }

        if( targets!=null ){
            targets.forEach( wr -> wr.writeLine(id()+":"+val));
        }
        return this;
    }
    @Override
    boolean addTriggeredCmd(String cmd, String trigger) {
        var td = new TriggeredCmd(cmd,trigger);
        if( td.isInvalid()) {
            Logger.error(id()+" (dv)-> Failed to convert trigger: "+trigger);
            return false;
        }
        triggered.add( new TriggeredCmd(cmd,trigger) );
        return true;
    }

    @Override
    boolean hasTriggeredCmds() {
        return !triggered.isEmpty();
    }

    @Override
    public boolean parseValue(String value) {
        this.value=value;
        return true;
    }
    private class TriggeredCmd{
        String cmd; // The cmd to issue
        String ori; // The compare before it was converted to a function (for toString purposes)
        TRIGGERTYPE type;
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
            type=TRIGGERTYPE.COMP;
            switch (trigger) {
                case "", "always" -> type = TRIGGERTYPE.ALWAYS;
                case "changed" -> type = TRIGGERTYPE.CHANGED;
                default -> {
                   Logger.warn(id() +" -> (text) Tried to add an non-existing trigger: "+trigger);
                }
            }
        }
        public boolean isInvalid(){
            return cmd.isEmpty();
        }
        public void apply( String val ){
            if( dQueue==null) {
                Logger.error(id()+" (dv)-> Tried to check for a trigger without a dQueue");
                return;
            }
            switch (type) {
                case ALWAYS -> {
                    dQueue.add(Datagram.system(cmd.replace("$", "" + val)));
                }
                case CHANGED -> {
                    if (val.equals(value))
                        dQueue.add(Datagram.system(cmd.replace("$", "" + val)));
                }
                default -> {
                    Logger.error(id() + " (dv)-> Somehow an invalid trigger sneaked in... ");
                }
            }
        }
    }
}
