package io.stream;

import io.Writable;
import org.json.XML;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;

public class LocalStream extends BaseStream implements Writable {

    boolean valid=true;

    public LocalStream(BlockingQueue<Datagram> dQueue, Element stream) {
        super(dQueue,stream);
        if( stream!=null){
            var src = XMLtools.getStringAttribute(stream,"src","");
            if( !src.isEmpty())
                triggeredActions.add(new TriggerAction(TRIGGER.OPEN, src));
        }
    }

    @Override
    protected boolean readExtraFromXML(Element stream) {
        return false;
    }

    @Override
    public boolean writeString(String data) {
        return processData(data);
    }
    @Override
    public boolean writeBytes(byte[] data) {
        return processData(new String(data));
    }
    @Override
    public boolean writeLine(String data) {
        return processData(data);
    }
    @Override
    public boolean writeLine(String origin, String data) {
        return processData(data);
    }
    private boolean processData( String msg ){
        if( readerIdle ){
            readerIdle=false;
		    listeners.forEach( l-> l.notifyActive(id));
	   }	
       if (msg != null && !(msg.isBlank() && clean)) { //make sure that the received data is not 'null' or an empty string           
            if(!label.isEmpty()) {
                var d = Datagram.build(msg).priority(priority).label(label).writable(this);
                dQueue.add(d);
            }
            // Log anything and everything (except empty strings)
            if( !msg.isBlank() && log )		// If the message isn't an empty string and logging is enabled, store the data with logback
        	    Logger.tag("RAW").warn( id() + "\t" + msg );

			if( !targets.isEmpty() ){
                targets.forEach(dt -> eventLoopGroup.submit( () -> dt.writeLine(id,msg)) );
                targets.removeIf(wr -> !wr.isConnectionValid() ); // Clear inactive
			}
		
            long p = Instant.now().toEpochMilli() - timestamp;	// Calculate the time between 'now' and when the previous message was received
            if( p > 0 ){	// If this time is valid
                passed = p; // Store it
            }                    
            timestamp = Instant.now().toEpochMilli();    		// Store the timestamp of the received message
        }else{
            return false;
        }
        return true;
    }
    @Override
    public Writable getWritable() {
        return this;
    }

    @Override
    public boolean connect() {
        valid=true;
        applyTriggeredAction(TRIGGER.OPEN);
        return true;
    }

    @Override
    public boolean disconnect() {
        valid=false;
        return true;
    }

    @Override
    public boolean isConnectionValid() {
        return valid;
    }

    @Override
    public long getLastTimestamp() {
        return timestamp;
    }

    @Override
    public String getInfo() {
        return "LOCAL [" + id + "|" + label + "] " + String.join(";", getTriggeredActions(TRIGGER.OPEN));
    }

    @Override
    protected String getType() {
        return "local";
    }

    @Override
    protected void flagIdle() {

    }

}
