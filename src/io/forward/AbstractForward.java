package io.forward;

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
import java.util.concurrent.BlockingQueue;

/**
 * Abstract class to create a 'Forward'.
 * The idea is that this class gets data from anything that accepts a writable, do something with it and then forwards
 * it to any submitted writable.
 */
public abstract class AbstractForward implements Writable {

    protected final BlockingQueue<Datagram> dQueue;                        // Queue to send commands
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
    protected boolean inPath=true;
    protected boolean parsedOk=true;
    protected String delimiter = ","; // Delimiter to use for splitting

    // Consecutive steps in a path
    protected final ArrayList<NextStep> nextSteps = new ArrayList<>();       // To where the data needs to be send
    protected AbstractForward parent;

    protected AbstractForward(String id, String source, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals ){
        this.id=id;
        this.rtvals=rtvals;
        if( !source.isEmpty() )
            sources.add(source);
        this.dQueue=dQueue;
    }
    protected AbstractForward( BlockingQueue<Datagram> dQueue, RealtimeValues rtvals){
        this.dQueue=dQueue;
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
            sources.forEach(source -> dQueue.add(Datagram.system(source).writable(this)));
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
        return targets.isEmpty() && store==null && !log && nextSteps.stream().noneMatch(ns->ns.enabled);
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
        if( log ){ // this counts as a target, so enable it
            valid=true;
        }

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
        if( !valid && store!=null){
            requestSource();
        }
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
        nextSteps.forEach( x -> x.getForward().removeStoreVals(rtv));
    }

    /**
     * Add a table insert link to this forward that is needed for the store
     * @param ti The table insert to add.
     */
    public void addTableInsert( TableInsert ti ){
        if(!tableInserters.contains(ti))
            tableInserters.add(ti);
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

    protected void addNextStep( AbstractForward fw, boolean askData ){
        if( fw != null ) {
            if( nextSteps.stream().anyMatch( rs -> rs.getForward()==fw) ){
                Logger.warn(id()+" -> Duplicate request for "+fw.id);
                return;
            }
            fw.setParent(this); // Let the next step know its parent
            nextSteps.add( new NextStep(fw, askData) ); // Add it to the list of steps
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
        var matchOpt = nextSteps.stream().filter( ns -> ns.af==step).findFirst();
        if( matchOpt.isPresent()) {
            var match = matchOpt.get();
            if( !match.enabled ){ // No use asking twice
                if( nextSteps.stream().noneMatch(ns -> ns.enabled) )  // If any other step is enabled, then source has been requested
                    requestSource();
                match.enabled = true;
            }else{
                Logger.info(id()+ "-> Already enabled "+step.id());
            }
        }else{
            Logger.error( id()+" -> No match found for "+step.id() );
        }
    }
    protected void stopDataToStep( AbstractForward step ){
        var matchOpt = nextSteps.stream().filter( ns -> ns.af==step).findFirst();
        if( matchOpt.isPresent()) {
            var match = matchOpt.get();
            match.enabled = false;
        }else{
            Logger.error( id()+" -> No match found for "+step.id() );
        }
    }
    protected AbstractForward getLastStep(){
        if( nextSteps.isEmpty())
            return this;
        return nextSteps.get(nextSteps.size()-1).getForward().getLastStep();
    }
    protected AbstractForward getStepById( String id){
        for( var next : nextSteps ){
            if(next.getForward().id.equalsIgnoreCase(id) ) {
                return next.getForward();
            }else{ // Check the sub?
                var step = next.getForward().getStepById(id);
                if( step!=null)
                    return step;
            }
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
        String oriId=id;
        if( parent != null)
            oriId = parent.firstParentId();
        return oriId;
    }
    /* **********************Writable implementation ****************************/
    @Override
    public boolean writeString(String data) {
        return addData(data);
    }
    @Override
    public boolean writeLine(String data) {
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
    @Override
    public Writable getWritable(){
        return this;
    }
    /* ********************** Storage class  ****************************/
    protected static class NextStep{
        AbstractForward af;
        boolean enabled;

        NextStep( AbstractForward af, boolean askData){
            this.af=af;
            this.enabled=askData;
        }
        public AbstractForward getForward(){
            return af;
        }
    }
}
