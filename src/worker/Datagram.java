package worker;

import io.Writable;

/**
 * Simple storage class that holds the raw data before processing
 */
public class Datagram {

    //String data;             // The received data
    String cmd;
    String args;
    int priority = 1;        // The priority of the data source
    String label="";         // The label of the data source
    String originID ="";     // ID of the origin of the message
    Writable writable;       //
    Object payload;          //
    boolean silent = true;

    public Datagram(String data){
        var spl = data.split(":", 2);
        cmd = spl[0].toLowerCase();
        args = spl.length == 2 ? spl[1] : "";
    }

    public Datagram(String cmd, String args) {
        this.cmd = cmd.toLowerCase();
        this.args = args;
    }

    public Writable getWritable(){
        return writable;
    }

    public String originID() {
        return originID;
    }

    public String cmd() {
        return cmd;
    }

    public Datagram cmd(String cmd) {
        this.cmd = cmd;
        return this;
    }
    public String args() {
        return args;
    }

    public String[] argList() {
        return args.split(",");
    }

    public Datagram args(String args) {
        this.args = args;
        return this;
    }

    public String getData(){
        return cmd + ":" + args;
    }
    public void setData(String msg ){
        var spl = msg.split(":", 2);
        cmd = spl[0];
        args = spl.length == 2 ? spl[1] : "";
    }
    public String getLabel(){ return label.toLowerCase(); }
    public boolean isSilent(){ return silent;}


    /* ***************************** Fluid API ******************************************* */
    public static Datagram build(String message){
        return new Datagram(message);
    }
    public static Datagram build(byte[] message){
        return new Datagram(new String(message));
    }

    public static Datagram build(String cmd, String args) {
        return new Datagram(cmd, args);
    }

    public static Datagram system(String message){
        return Datagram.build(message).label("system");
    }

    public static Datagram system(String cmd, String args) {
        return Datagram.build(cmd, args).label("system");
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
    public Datagram toggleSilent(){
        silent = !silent;
        return this;
    }
    public Datagram payload( Object pl ){
        payload=pl;
        return this;
    }
    public Object payload(){
        return payload;
    }

    public boolean asHtml() {
        return writable != null && (writable.id().contains("matrix") || writable.id().startsWith("file:"));
    }

    public String eol() {
        return asHtml() ? "<br>" : "\r\n";
    }
}
