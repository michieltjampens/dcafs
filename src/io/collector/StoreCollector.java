package io.collector;

import org.tinylog.Logger;
import util.data.RealtimeValues;
import util.data.ValStore;
import util.data.ValStoreFab;
import util.xml.XMLdigger;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Collector that takes data and processes it using a store. After this, it can trigger a database insert.
 */
public class StoreCollector extends AbstractCollector {
    private ValStore store;

    public StoreCollector(XMLdigger dig, RealtimeValues rtvals) {
        super(dig.attr("id", ""));
        valid = readFromXML(dig, rtvals);
        if( valid ) {
            store.shareRealtimeValues(rtvals);
        }
    }

    public boolean readFromXML(XMLdigger dig, RealtimeValues rtvals) {
        id = dig.attr("id", ""); // Will need an id for the store
        store = ValStoreFab.buildValStore(dig, id, rtvals); // Get the store
        return !store.isInvalid();
    }
    /**
     * Reload the setup, removing old rtvals and sharing new ones
     * @param dig  The element with store tag
     * @param rtvals The RealtimeValues shared by all objects
     * @return True if reading went fine, false if not
     */
    public boolean reload(XMLdigger dig, RealtimeValues rtvals) {
        if( store != null )
            store.removeRealtimeValues(rtvals);
        if (readFromXML(dig, rtvals)) {
            store.shareRealtimeValues(rtvals);
            return true;
        }
        valid=false;
        Logger.error(id+"(sc) -> Failed to reload");
        return false;
    }

    /**
     * Add data to be processed by the store
     * @param data The data received
     * @return True if processed, false if not
     */
    protected boolean addData(String data) {
        if( store !=null ) {
            if (!store.apply(data))
                return true;
        }else{
            Logger.error(id+"(sc) -> Forward without a valid store...");
            return false;
        }
        return true;
    }

    @Override
    protected void timedOut() {

    }

    public void doIdle() {
        store.doIdle();
    }
    /**
     * Retrieve the store this collector uses
     * @return An optional of the store
     */
    public Optional<ValStore> getStore(){
        return Optional.ofNullable(store);
    }
    /**
     * Check if this needs a database link
     * @return True if it does
     */
    public boolean needsDB(){
        return store!=null && !store.dbInsertSets().isEmpty();
    }
    /**
     * The id's that this store writes to
     * @return An array containing the id's or empty if none
     */
    public ArrayList<String[]> dbInsertSets(){
        if( store==null)
            return new ArrayList<>();
        return store.dbInsertSets();
    }
}
