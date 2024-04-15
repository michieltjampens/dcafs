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
            if( valid ){
                Logger.info(id() +" -> Requesting data from "+source);
                dQueue.add( Datagram.system( source ).writable(this) );
            }
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
                valid=true;
                if( !inPath)
                    sources.forEach( source -> dQueue.add( Datagram.system( source ).writable(this) ) );
            }
            if( target.id().startsWith("telnet")) {
                targets.add(0,target);
            }else{
                targets.add(target);
            }
        }else{
            Logger.info(id+" -> Trying to add duplicate target "+target.id());
        }
    }
    public boolean hasSrc(){
        return !sources.isEmpty();
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
        return targets.isEmpty() && store==null && !log;
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
            valid=true;
            sources.forEach( source -> dQueue.add( Datagram.build( source ).label("system").writable(this) ) );
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
    }

    /**
     * Add a table insert link to this forward that is needed for the store
     * @param ti The table insert to add.
     */
    public void addTableInsert( TableInsert ti ){
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

}
