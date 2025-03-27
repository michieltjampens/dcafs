package io.collector;

import io.Writable;
import org.tinylog.Logger;
import util.tools.TimeTools;

import java.util.ArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public abstract class AbstractCollector implements Writable {

    protected String id;                                                        // unique identifier
    protected final ArrayList<CollectorFuture> listeners = new ArrayList<>();   // collection of the listeners
    protected String source="";                                                 // The command that provides the data to the collector
    protected boolean valid=true;                                               // boolean to indicate that this collector wants to collect
    protected ScheduledFuture<?> timeoutFuture;                        // the future of the timeout submit in order to cancel it
    protected long secondsTimeout=0;

    public AbstractCollector( String id ){
        this.id=id;
    }
    public AbstractCollector( String id, String timeoutPeriod, ScheduledExecutorService scheduler ){
        this.id=id;
        withTimeOut( timeoutPeriod, scheduler);
    }
    /**
     * Collectors allows for other objects to register to receive notification on completion
     * @param listener The listener to add
     */
    public void addListener(CollectorFuture listener ){
        listeners.add(listener);
    }

    public void addSource(String source){
        this.source=source;
    }
    public String getSource(){
        return source;
    }
    /* *********************** Abstract Methods ***********************************/
    /**
     * This is called when data is received through the writable
     * @param data The data received
     * @return True if everything went fine
     */
    protected abstract boolean addData( String data );
    /**
     * This is called when the timeout task runs, should alter the valid flag and inform the listeners on a good
     * or bad result
     */
    protected abstract void timedOut();

    /* **********************Writable implementation ****************************/
    @Override
    public synchronized boolean writeString(String data) {
        return addData(data);
    }
    @Override
    public synchronized boolean writeLine(String origin, String data) {
        return addData(data);
    }
    @Override
    public synchronized boolean writeBytes(byte[] data) {
        return addData(new String(data));
    }

    @Override
    public String id() {
        return id;
    }
    @Override
    public boolean isConnectionValid() {
        return valid;
    }
    /* *********************** TIME OUT *******************************************/
    /**
     * Set a timeout for this collector, so it won't gather/wait indefinitely
     *
     * @param timeoutPeriod String representation of the period eg. 10s or 5m50s etc
     * @param scheduler     The service to register the timeout with
     */
    public void withTimeOut(String timeoutPeriod, ScheduledExecutorService scheduler ){
        secondsTimeout = TimeTools.parsePeriodStringToSeconds(timeoutPeriod);
        Logger.tag("TASK").debug(id+" -> Collector started with timeout of "+secondsTimeout+"s");
        timeoutFuture = scheduler.schedule(this::timedOut, secondsTimeout, TimeUnit.SECONDS);
    }
}
