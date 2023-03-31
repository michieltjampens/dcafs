package worker;

import io.Writable;
import io.Readable;
import java.time.Instant;

/**
 * Simple storage class that holds the raw data before processing
 */
public class Datagram {
	
    String data;             // The received data
    byte[] raw;              // Raw received data
    int priority = 1;        // The priority of the data source
    String label="";         // The label of the data source
    String originID ="";     // ID of the origin of the message
    Writable writable;  //
    boolean silent = true;

    public Datagram(String data){
        this.data = data;
        raw = data.getBytes();
    }
    public Datagram(){
    }

    public Writable getWritable(){
        return writable;
    }
    public String getOriginID(){ return originID;}

    public String getData(){
        return data ==null?"": data;
    }
    public void setData(String msg ){
        this.data =msg;
        raw = msg.getBytes();
    }
    public byte[] getRaw(){
        return raw;
    }
    public String getLabel(){ return label.toLowerCase(); }
    public boolean isSilent(){ return silent;}


    /* ***************************** Fluid API ******************************************* */
    public static Datagram build(String message){
        return new Datagram(message);
    }
    public static Datagram build(byte[] message){
        var d = new Datagram( new String(message));
        d.raw=message;
        return d;
    }
    public static Datagram build(){
        return new Datagram();
    }
    public static Datagram system(String message){
        return Datagram.build(message).label("system");
    }
    public Datagram label(String label){
        this.label=label;
        return this;
    }
    public Datagram priority(int priority){
        this.priority=priority;
        return this;
    }
    /**
     * Set the writable in this datagram, also overwrites the origin with id from writable
     * @param writable The writable to set
     * @return The datagram with updated writable
     */
    public Datagram writable(Writable writable){
        this.writable=writable;
        if(originID.isEmpty())
            this.originID=writable.id();
        return this;
    }
    public Datagram origin( String origin ){
        this.originID=origin;
        return this;
    }
    public Datagram raw( byte[] raw ){
        this.raw=raw;
        return this;
    }
    public Datagram toggleSilent(){
        silent = !silent;
        return this;
    }
}
