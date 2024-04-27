package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import util.xml.XMLtools;
import worker.Datagram;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;

public class TextVal extends AbstractVal{
    private String value="";
    private String def="";
    private final ArrayList<String> history = new ArrayList<>();
    private final ArrayList<TriggeredCmd> triggered = new ArrayList<>();
    private final HashMap<String,String> parser = new HashMap<>();
    private String keepOrignal;
    private boolean regex=false;
    private enum TYPE{STATIC,LOCALDT,UTCDT};
    private TYPE type = TYPE.STATIC;
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
    public static TextVal newLocalTimeVal(String group, String name){
        return new TextVal().group(group).name(name).makeLocalDT();
    }
    public static TextVal newUTCTimeVal(String group, String name){
        return new TextVal().group(group).name(name).makeUTCDT();
    }
    /**
     * Create a new TextVal based on a rtval text node
     * @param rtval The node
     * @param group The group the node is found in
     * @return The created node, still needs dQueue set
     */
    public static Optional<TextVal> build(Element rtval, String group){
        var dig = XMLdigger.goIn(rtval);
        String name = dig.attr("name","");
        name = dig.attr("id",name);
        group = dig.attr("group",group);

        if( name.isEmpty() && dig.peekOut("*").isEmpty() )
            name = dig.value("");

        if( name.isEmpty()){
            Logger.error("Tried to create a TextVal without id/name, group "+group);
            return Optional.empty();
        }
        return Optional.of(TextVal.newVal(group,name).reload(rtval));
    }
    public TextVal makeLocalDT(){
        type=TYPE.LOCALDT;
        return this;
    }
    public TextVal makeUTCDT(){
        type=TYPE.UTCDT;
        return this;
    }
    /**
     * Change the RealVal according to a xml node
     * @param rtval The node
     */
    public TextVal reload(Element rtval){
        reset();
        var dig = XMLdigger.goIn(rtval);
        name(name).group( dig.attr("group", group()));
        defValue( dig.attr( "def", def) );
        defValue( dig.attr( "default", def) );
        if( !def.isEmpty())
            value=def;

        String options = dig.attr( "options", "");
        for (var opt : options.split(",")) {
            var arg = opt.split(":");
            switch (arg[0]) {
                case "time" -> keepTime();
                case "order" -> order(NumberUtils.toInt(arg[1], -1));
                case "history" -> enableHistory(NumberUtils.toInt(arg[1], -1));
            }
        }
        for (var sub : dig.digOut( "*")) {
            switch( sub.tagName("") ){
                case "cmd" -> {
                    String trig = sub.attr("when","");
                    String cmd = sub.value("");
                    addTriggeredCmd(trig, cmd);
                }
                case "parser" -> {
                    var key = sub.attr("key","");
                    if (key.isEmpty()) {
                        key = sub.attr("regex","");
                        if (key.isEmpty()) {
                            Logger.error("Parser node without key/regex in " + id());
                            continue;
                        } else {
                            regex = true;
                        }
                    }
                    parser.put(key, sub.value(""));
                }
                case "keep" -> {
                    if( sub.hasAttr("regex")) {
                        keepOrignal = sub.attr("regex","");
                    }else{
                        keepOrignal = sub.value("");
                        if( keepOrignal.isEmpty())
                            keepOrignal=".*";
                    }
                }
                default -> Logger.error("Unrecognized node in "+id()+" xml");
            }
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
    public String value() {
        return switch (type) {
            case STATIC -> value;
            case LOCALDT -> TimeTools.formatLongNow();
            case UTCDT -> TimeTools.formatLongUTCNow();
        };
    }
    public Object valueAsObject(){ return value();}
    public void defValue(String def){
        this.def=def;
    }
    public String stringValue(){ return value();}

    @Override
    public void resetValue() {
        value=def;
    }

    public TextVal value( String val){

        value=val;

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
            targets.forEach( wr -> wr.writeLine(id(),val));
        }
        return this;
    }
    @Override
    void addTriggeredCmd(String cmd, String trigger) {
        var td = new TriggeredCmd(cmd,trigger);
        if( td.isInvalid()) {
            Logger.error(id()+" (dv)-> Failed to convert trigger: "+trigger);
            return;
        }
        triggered.add( new TriggeredCmd(cmd,trigger) );
    }

    @Override
    boolean hasTriggeredCmds() {
        return !triggered.isEmpty();
    }

    @Override
    public boolean parseValue(String value) {
        if( parser.isEmpty()) { // If no parsing options are defined
            value(value);
            return true;
        }else{ //If there are, look for match
            if( regex ){ // If the parser option contain at least one regex, treat all as regex (this is slightly slower)
                for( var entr : parser.entrySet()){
                    if( value.matches(entr.getKey())) {
                        value(entr.getValue());
                        return true;
                    }
                }
            }else{
               var val = parser.get(value);
               if( val != null){
                   value(val);
                   return true;
               }
            }
            if( keepOrignal !=null){
                if( value.matches(keepOrignal)) {
                    value(value);
                    return true;
                }
            }
            Logger.error(id() +" -> Failed to (regex) parse "+value);
            return false;
        }
    }
    private class TriggeredCmd{
        String cmd; // The cmd to issue
        String ori; // The compare before it was converted to a function (for toString purposes)
        TRIGGERTYPE type;

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
                    dQueue.add(Datagram.system(cmd.replace("$", val)));
                }
                case CHANGED -> {
                    if (val.equals(value))
                        dQueue.add(Datagram.system(cmd.replace("$", val)));
                }
                default -> {
                    Logger.error(id() + " (dv)-> Somehow an invalid trigger sneaked in... ");
                }
            }
        }
    }
}
