package io.forward;

import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.data.ValStore;
import util.database.TableInsert;
import worker.Datagram;
import java.util.concurrent.BlockingQueue;

public class StoreForward extends AbstractForward{

    public StoreForward(Element ele, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals  ){
        super(dQueue,rtvals);
        readOk = readFromXML(ele);
        if( readOk )
            store.shareRealtimeValues(rtvals);
    }
    public boolean reload( Element el, RealtimeValues rtvals ){
        if( store != null )
            store.removeRealtimeValues(rtvals);
        if( readFromXML(el) ) {
            store.shareRealtimeValues(rtvals);
            return true;
        }
        return false;
    }
    @Override
    protected boolean addData(String data) {
        if( store !=null ) {
            store.apply(data);
            tis.forEach(ti -> ti.insertStore(store.dbTable()));
        }else{
            Logger.error(id+" -> Forward without a valid store...");
        }
        return false;
    }
    public boolean needsDB(){
        return store!=null && !store.dbIds().isEmpty();
    }
    public String dbTable(){
        if( store==null)
            return "";
        return store.dbTable();
    }
    public String dbids(){
        if( store==null)
            return "";
        return store.dbIds();
    }

    @Override
    public boolean readFromXML(Element fwElement) {
        tis.clear();
        id = fwElement.getAttribute("id");
        ValStore.build(fwElement,id,rtvals).ifPresent( x->store=x ); // Get the store
        // Check if db link is needed. If not, remove any existing
        if( store !=null && store.dbIds().isEmpty()){
            tis.clear();
        }
        return store!=null;
    }

    @Override
    protected String getXmlChildTag() {
        return "store";
    }
}
