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
    }
    @Override
    protected boolean addData(String data) {
        if( store !=null ) {
            store.apply(data);
            tis.forEach(ti -> ti.insertStore(""));
        }else{
            Logger.error(id+" -> Forward without a valid store...");
        }
        return false;
    }
    public void addTableInsert( TableInsert ti ){
        tis.add(ti);
    }
    @Override
    public boolean readFromXML(Element fwElement) {
        tis.clear();
        ValStore.build(fwElement).ifPresent( x->store=x ); // Get the store
        // Request the ti?
        return store!=null;
    }

    @Override
    protected String getXmlChildTag() {
        return "store";
    }
}
