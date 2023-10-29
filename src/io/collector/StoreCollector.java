package io.collector;

import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.data.ValStore;
import util.database.TableInsert;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Collector that takes data and processes it using a store. After this, it can trigger a database insert.
 */
public class StoreCollector extends AbstractCollector {
    private ValStore store;
    private final ArrayList<TableInsert> tis = new ArrayList<>();

    public StoreCollector(Element ele, RealtimeValues rtvals  ){
        super( ele.getAttribute("id") );
        valid = readFromXML(ele,rtvals);
        if( valid ) {
            store.shareRealtimeValues(rtvals);
        }
    }
    public boolean readFromXML(Element fwElement, RealtimeValues rtvals) {
        tis.clear(); // Clear any links that might already exist
        id = fwElement.getAttribute("id"); // Will need an id for the store
        ValStore.build(fwElement,id,rtvals).ifPresent( x->store=x ); // Get the store
        return store!=null;
    }
    /**
     * Reload the setup, removing old rtvals and sharing new ones
     * @param storeEle  The element with store tag
     * @param rtvals The RealtimeValues shared by all objects
     * @return True if reading went fine, false if not
     */
    public boolean reload( Element storeEle, RealtimeValues rtvals ){
        if( store != null )
            store.removeRealtimeValues(rtvals);
        if( readFromXML(storeEle,rtvals) ) {
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
            store.apply(data);
            tis.forEach(ti -> ti.insertStore(store.dbTable()));
        }else{
            Logger.error(id+" -> Forward without a valid store...");
            return false;
        }
        return true;
    }

    @Override
    protected void timedOut() {

    }

    /**
     * Retrieve the store this collector uses
     * @return An optional of the store
     */
    public Optional<ValStore> getStore(){
        return Optional.ofNullable(store);
    }

    /**
     * Adds a link to the database that this store inserts data into
     * @param ti The TableInsert that refers to the database needed
     */
    public void addTableInsert( TableInsert ti ){
        tis.add(ti);
    }
    /**
     * Check if this needs a database link
     * @return True if it does
     */
    public boolean needsDB(){
        return store!=null && !store.dbIds().isEmpty();
    }

    /**
     * Get the table name this store writes to
     * @return The name of the table or empty if none
     */
    public String dbTable(){
        if( store==null)
            return "";
        return store.dbTable();
    }

    /**
     * The id's that this store writes to
     * @return An array containing the id's or empty if none
     */
    public String[] dbids(){
        if( store==null)
            return new String[0];
        return store.dbIds().split(",");
    }
}
