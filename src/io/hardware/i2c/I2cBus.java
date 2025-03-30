package io.hardware.i2c;

import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;

import java.util.ArrayList;

public class I2cBus {

    EventLoopGroup eventloop;
    boolean busy = false;
    int bus;
    int requests=0;
    boolean debug=false;
    ArrayList<I2cDevice> slotWait = new ArrayList<>();

    public I2cBus(int bus, EventLoopGroup eventLoopGroup ){
        this.bus=bus;
        this.eventloop=eventLoopGroup;
    }
    public int id(){
        return bus;
    }
    public int getTotalRequests(){
        return requests;
    }
    public int queuedSize(){
        return slotWait.size();
    }
    public void setDebug( boolean debug ){
        this.debug=debug;
    }
    /* ****************************** R E A D I N G ******************************************* */
    public synchronized void requestSlot(I2cDevice dev){
        requests++;
        if( busy ){
            if( !slotWait.contains(dev)) { // No need to add it twice
                slotWait.add(dev);
                if(debug)
                    Logger.info("Request from "+dev.id()+" queued. (size: "+slotWait.size()+")");
            }else{
                if( debug )
                    Logger.warn("Request from "+dev.id()+" denied, duplicate.");
            }
        }else{
            busy=true;
            if( debug )
                Logger.info("Request from "+dev.id()+" approved.");
            eventloop.submit( ()->dev.useBus(eventloop) );
        }
    }
    /* *************************************************************************************** */
    public void doNext(){
        if( !slotWait.isEmpty() ){
            if( debug )
                Logger.info("(i2c) -> Request from "+slotWait.get(0).id()+" granted.");
            eventloop.submit( () -> slotWait.remove(0).useBus(eventloop));
        }else {
            busy = false;
        }
    }
    public String getInfo(){
        return bus+ " -> busy?"+busy+" requests:"+requests+ " waiting:"+slotWait.size();
    }
    public void reset(){
        busy=false;
        slotWait.clear();
        requests=0;
    }
}
