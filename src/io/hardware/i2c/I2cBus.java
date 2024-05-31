package io.hardware.i2c;

import io.netty.channel.EventLoopGroup;
import worker.Datagram;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;

public class I2cBus {

    EventLoopGroup eventloop;
    boolean busy = false;
    int bus;
    int requests=0;

    ArrayList<I2cDevice> slotWait = new ArrayList<>();
    BlockingQueue<Datagram> dQueue;

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
    /* ****************************** R E A D I N G ******************************************* */
    public void addSlot( I2cDevice dev){
        requests++;
        if( busy ){
            if( !slotWait.contains(dev)) // No need to add it twice
                slotWait.add(dev);
        }else{
            eventloop.submit( dev::useBus );
        }
    }
    /* *************************************************************************************** */
    public void doNext(){
        if( !slotWait.isEmpty() ){
            eventloop.submit( () -> slotWait.remove(0).useBus());
        }
        busy=false;
    }
}
