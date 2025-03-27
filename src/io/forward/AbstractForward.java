package io.forward;

import das.Core;
import io.Writable;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.data.ValStore;
import util.database.TableInsert;
import util.xml.XMLdigger;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Abstract class to create a 'Forward'.
 * The idea is that this class gets data from anything that accepts a writable, do something with it and then forwards
 * it to any submitted writable.
 */
public abstract class AbstractForward implements Writable {

    protected final ArrayList<String> cmds = new ArrayList<>();            // Commands to execute after processing
    protected final ArrayList<Writable> targets = new ArrayList<>();       // To where the data needs to be send
    protected final ArrayList<String[]> rulesString = new ArrayList<>();   // Readable info regarding rules
    protected ArrayList<TableInsert> tableInserters = new ArrayList<>();   // Links to database table for inserting data
    protected String id="";                                   // The identifier for this object
    protected final ArrayList<String> sources = new ArrayList<>();  // The commands that provide the data to filter
    protected boolean valid = false;           // Flag that determines of data should be received or not
    protected boolean debug = false;           // Flag that outputs more in the console for debugging purposes
    protected Path xml;                        // Path to the xml file containing the info
    protected int badDataCount=0;               // Keep track of the amount of bad data received
    protected boolean log = false;              // Log to raw files or not
    protected final RealtimeValues rtvals;      // Link to rtvals for getting/settings realtime data
    protected ValStore store;                   // A Store that holds links to rtvals used by the forward
    protected boolean readOk=false;
    public enum RESULT{ERROR,EXISTS,OK}
    protected boolean parsedOk=true;
    protected String delimiter = ","; // Delimiter to use for splitting

    // Consecutive steps in a path
    protected final ArrayList<AbstractForward> nextSteps = new ArrayList<>();       // To where the data needs to be send
    protected AbstractForward parent;

    protected AbstractForward(String id, String source, RealtimeValues rtvals ){
        this.id=id;
        this.rtvals=rtvals;
        if( !source.isEmpty() )
            sources.add(source);
    }
    protected AbstractForward( RealtimeValues rtvals){
        this.rtvals=rtvals;
    }
    public void setDebug( boolean debug ){
        this.debug=debug;
    }
    public boolean isReadOk(){
        return readOk&&parsedOk;
    }
    /**
     * Add a source of data for this forward, it can be any command
     * @param source The command that provides the data fe. raw:... calc:... etc
     */
    public void addSource(String source ){
        if( !sources.contains(source) && !source.isEmpty()){
            sources.add(source);
            Logger.info(id() +" -> Adding source "+source);
        }
    }

    /**
     * Request the data from the source of this forward (asks the whole chain)
     */
    protected void requestSource(){
        valid=true;
        if( parent == null ) {
            sources.forEach(source -> Core.addToQueue(Datagram.system(source).writable(this)));
        }else{
            parent.sendDataToStep(this); // Request data from parent
        }
    }
    /**
     * Add a writable that the result of the forward should be written to
     * @param target The writable the result of the forward will be written to
     */
    public synchronized void addTarget( Writable target ){
        if( target.id().isEmpty()) {
            Logger.error(id+" -> Tried to add target with empty id");
            return;
        }
        if( !targets.contains(target)){
            if( !valid ){
                requestSource();
            }
            if( target.id().startsWith("telnet")) { // Can't remember why this is done
                targets.add(0,target);
            }else{
                targets.add(target);
            }
            Logger.info("Added "+target.id()+" as target to "+id());
        }else{
            Logger.warn(id+" -> Trying to add duplicate target "+target.id());
        }
    }

    public String getSrc(){
        return sources.isEmpty()?"":sources.get(0);
    }
    public String src(){
        return sources.isEmpty()?"":sources.get(0);
    }
    public boolean removeTarget( Writable target ){
        return targets.remove(target);
    }
    public void removeTargets(){
        targets.clear();
    }
    public boolean noTargets(){
        return targets.isEmpty() && store==null && !log && nextSteps.isEmpty();
    }
    public ArrayList<Writable> getTargets(){
        return targets;
    }
    public void invalidate(){
        valid=false;
    }
    public String toString(){

        StringJoiner join = new StringJoiner("\r\n" );
        join.add(getXmlChildTag()+":"+id+ (sources.isEmpty()?"":" getting data from "+String.join( ";",sources)));
        join.add(getRules());

        if(!targets.isEmpty()) {
            StringJoiner ts = new StringJoiner(", ", "    Targets: ", "");
            targets.forEach(x -> ts.add(x.id()));
            join.add(ts.toString());
        }
        if( store != null )
            join.add(store.toString());
        return join.toString();
    }
    /**
     * Get a readable list of the rules
     * @return The listing of the rules
     */
    public String getRules(){
        int index=0;
        StringJoiner join = new StringJoiner("\r\n");
        if( this instanceof MathForward) {
            join.setEmptyValue(" -> No ops yet.");
        }else if( this instanceof EditorForward ){
            join.setEmptyValue(" -> No edits yet.");
        }else if( this instanceof FilterForward){
            join.setEmptyValue(" -> No rules yet.");
        }
        if( !parsedOk ){
            join.add("Failed to parse, check logs");
        }else {
            for (String[] x : rulesString) {
                join.add("\t" + (index++) + " : " + x[1] + " -> " + x[2]);
            }
        }
        return join.toString();
    }
    protected boolean readBasicsFromXml( XMLdigger dig ){

        if( dig==null) { // Can't work if this is null
            parsedOk=false;
            return false;
        }
        /* Attributes */
        id = dig.attr("id","");
        if( id.isEmpty() ) {// Cant work without id
            parsedOk=false;
            return false;
        }
        // Read the delimiter and taking care of escape codes
        delimiter=dig.attr("delimiter",delimiter,true);
        delimiter=dig.attr("delim",delimiter,true); // Could have been shortened

        log = dig.attr("log",false); // Logging to raw files or not
        if (log) // this counts as a target, so enable it
            valid=true;

        /* Sources */
        sources.clear();
        addSource(dig.attr("src", ""));
        dig.peekOut("src").forEach( ele ->sources.add(ele.getTextContent()) );

        rulesString.clear();
        Logger.info(id+" -> Reading from xml");
        return true;
    }

    /**
     * Set the store that is filled in with the result of this forward
     * @param store The store to use
     */
    public void setStore( ValStore store ){
        this.store=store;
        if (!valid && store != null)
            requestSource();
    }

    /**
     * Get the store that is filled in with the result of this forward.
     * @return The store if any or an empty optional if none.
     */
    public Optional<ValStore> getStore(){
        return Optional.ofNullable(store);
    }

    /**
     * Remove the vals associated with this store from the global pool of vals.
     * @param rtv The global pool to remove them from
     */
    public void removeStoreVals(RealtimeValues rtv){
        if( store!=null)
            store.removeRealtimeValues(rtv);
        nextSteps.forEach( x -> x.removeStoreVals(rtv));
    }

    /**
     * Add a table insert link to this forward that is needed for the store
     * @param ti The table insert to add.
     */
    public void addTableInsert( TableInsert ti ){
        if(!tableInserters.contains(ti))
            tableInserters.add(ti);
    }

    protected void applyDataToStore(String data) {
        if (store != null) {
            if (!store.apply(data))
                return;
            for (var dbInsert : store.dbInsertSets())
                tableInserters.forEach(ti -> ti.insertStore(dbInsert));
        }
    }
    /* *********************** Abstract Methods ***********************************/
    /**
     * This is called when data is received through the writable
     * @param data The data received
     * @return True if everything went fine
     */
    protected abstract boolean addData( String data );

    /**
     * Read all the settings for this from the given xml element
     * @param fwElement the element containing the info
     * @return True if ok
     */
    public abstract boolean readFromXML( Element fwElement );
    protected abstract String getXmlChildTag();

    protected void addNextStep( AbstractForward fw ){
        if( fw != null ) {
            if( nextSteps.stream().anyMatch( rs -> rs==fw) ){
                Logger.warn(id()+" -> Duplicate request for "+fw.id);
                return;
            }
            fw.setParent(this); // Let the next step know its parent
            nextSteps.add( fw ); // Add it to the list of steps
        }else{
            Logger.error(id()+" -> Tried to add step, but null.");
        }
    }
    protected void setParent( AbstractForward fw ){
        if( parent != fw ) {
            parent = fw;
        }else{
            Logger.error(id()+" -> Can't make this forward it's own parent.");
        }
    }

    protected void sendDataToStep( AbstractForward step ){
        if (nextSteps.stream().anyMatch(ns -> ns == step)) {
            requestSource();
        }else{
            Logger.error( id()+" -> No match found for "+step.id() );
        }
    }
    protected AbstractForward getLastStep(){
        if( nextSteps.isEmpty())
            return this;
        return nextSteps.get(nextSteps.size()-1).getLastStep();
    }
    protected AbstractForward getStepById( String id){
        for( var next : nextSteps ){
            if (next.id.equalsIgnoreCase(id))
                return next;
            // Check the sub?
            var step = next.getStepById(id);
            if (step != null)
                return step;
        }
        Logger.warn("No step found with id "+id);
        return null;
    }
    protected int steps(){
        return nextSteps.size();
    }
    protected int siblings(){
        int total = nextSteps.size();
        if( parent != null)
            total += parent.siblings();
        return total;
    }
    protected String firstParentId(){
        return parent != null ? parent.firstParentId() : id;
    }
    /* **********************Writable implementation ****************************/
    @Override
    public boolean writeString(String data) {
        return addData(data);
    }
    @Override
    public boolean writeLine(String origin, String data) {
        return addData(data);
    }
    @Override
    public boolean writeBytes(byte[] data) {
        return addData(new String(data));
    }
    @Override
    public String id() {
        return getXmlChildTag()+":"+id;
    }
    @Override
    public boolean isConnectionValid() {
        return valid;
    }
}
