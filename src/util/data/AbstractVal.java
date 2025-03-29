package util.data;

import io.Writable;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLdigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.StringJoiner;

public abstract class AbstractVal {

    protected String name;
    protected String group="";
    protected String unit="";

    /* Position in lists */
    private int order = 100;

    /* Keep Time */
    protected Instant timestamp;
    protected boolean keepTime=false;

    /* History */
    protected int keepHistory=0;

    /* Requests */
    protected ArrayList<Writable> targets = new ArrayList<>();

    enum TRIGGERTYPE {ALWAYS,CHANGED,STDEV,COMP}
    /* ************************************ X M L ************************************************ */
    public static String[] readGroupAndName(Element rtval,String altGroup ){

        var dig = XMLdigger.goIn(rtval);

        // Get the name
        var name = dig.attr("name","");
        if( name.isEmpty()) {
            if( dig.hasChilds() ){
                name = dig.peekAt("name").value("");
            }else{
                name = dig.value("");
            }
            if( name.isEmpty())
                name = dig.attr("id",name);             // or the attribute id
        }
        if( name.isEmpty()){ // If neither of the three options, this failed
            Logger.error("Tried to create a RealVal without id/name");
            return null;
        }

        // Get the group
        var group = dig.attr("group","");
        if( group.isEmpty() ){ // If none defined, check the parent node
            dig.goUp();
            if( dig.tagName("").equalsIgnoreCase("group")){
                group = dig.attr("id","");
            }else {
                group = dig.attr("group", "");
            }
        }
        if( group.isEmpty()){ // If neither of the three options, this failed
            if( altGroup.isEmpty()) {
                Logger.error("Tried to create a RealVal without group");
                return null;
            }
            group=altGroup;
        }
        // Get the group and return found things
        return new String[]{group,name};
    }
    /* ************************************* Options ********************************************* */
    public void reset(){
        keepTime=false;
        keepHistory=0;
        order = 100;
    }
    /**
     * Enable keeping time of the last value update
     */
    public void keepTime(){
        keepTime=true;
    }
    /**
     * Enable keeping old values up till the given count
     * @param count The amount of old values to store
     * @return True if valid
     */
    public boolean enableHistory(int count){
        if(count<=0)
            return false;
        keepHistory=count;
        return true;
    }
    public void disableHistory(){
        keepHistory=0;
    }
    /**
     * Set the order in which this item should be listed in the group list, the higher the order the higher in the list.
     * If the order is shared, it will be sorted alphabetically
     * @param order The new order for this object
     */
    public void order( int order ){
        this.order=order;
    }

    /**
     * Get the order, which determines its place in the group list
     * @return The order of this object
     */
    public int order(){
        return order;
    }
    /* **************************** Triggered Cmds ***************************************************************** */
    /**
     * Enable allowing triggered commands to be added
     */
    public void enableTriggeredCmds(){
        hasTriggeredCmds();
    }

    /**
     * Add a triggered cmd to this Val
     *
     * @param cmd     The cmd, in which $ will be replaced with the value causing it
     * @param trigger The trigger, the options depend on the type of Val
     */
    abstract void addTriggeredCmd(String cmd, String trigger);

    /**
     * Check if this has triggered cmd's
     * @return True if it has at least one cmd
     */
    abstract boolean hasTriggeredCmds();
    /* ************************************** Getters ************************************************************** */
    public void name( String name ){
        this.name=name;
    }
    public void group( String group ){
        this.group=group;
    }
    /**
     * Get the id, which is group + underscore + name
     * @return The concatenation of group, underscore and name
     */
    public String id(){
        return group.isEmpty()?name:(group+"_"+name);
    }
    public String group(){
        return group;
    }
    public String name(){
        return name;
    }
    public String unit(){ return unit; }
    public abstract boolean parseValue( String value );
    public abstract String stringValue();
    public abstract void resetValue();
    /* ********************************* Requests/Targets ********************************************************** */
    public void addTarget( Writable wr){
        if( targets==null)
            targets=new ArrayList<>();
        if( !targets.contains(wr))
            targets.add(wr);
    }
    public boolean removeTarget( Writable wr){
        if( targets==null)
            return false;
        return targets.remove(wr);
    }
    public String getTargets(){
        if( targets==null)
            return "";
        var join = new StringJoiner(",");
        targets.forEach( wr -> join.add(wr.id()));
        return join.toString();
    }

    protected void digOptions(XMLdigger dig) {
        var options = dig.attr("options", "");
        for (var opt : options.split(",")) {
            var arg = opt.split(":");
            switch (arg[0]) {
                case "time" -> keepTime();
                case "order" -> order(NumberUtils.toInt(arg[1], order));
                case "history" -> enableHistory(NumberUtils.toInt(arg[1], -1));
            }
        }
    }
}
