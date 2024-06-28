package util.data;

import io.Writable;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLdigger;
import worker.Datagram;

import java.time.Instant;
import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;

public abstract class AbstractVal {

    protected String name;
    protected String group="";
    protected String unit="";

    /* Position in lists */
    int order = -1;

    /* Keep Time */
    protected Instant timestamp;
    protected boolean keepTime=false;

    /* History */
    protected int keepHistory=0;

    protected BlockingQueue<Datagram> dQueue;

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
            group = dig.attr("group","");
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
        order=-1;
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
     * @param dQueue The queue in which the datagram holding the command needs to be put
     */
    public void enableTriggeredCmds(BlockingQueue<Datagram> dQueue){
        if( hasTriggeredCmds())
            this.dQueue=dQueue;
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
    public abstract Object valueAsObject();
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
    public int getTargetCount(){
        return targets==null?0:targets.size();
    }
    public String getTargets(){
        if( targets==null)
            return "";
        var join = new StringJoiner(",");
        targets.forEach( wr -> join.add(wr.id()));
        return join.toString();
    }
}
