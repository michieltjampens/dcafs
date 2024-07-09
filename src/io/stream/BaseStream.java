package io.stream;

import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

public abstract class BaseStream {

    protected BlockingQueue<Datagram> dQueue;
    
    /* Pretty much the local descriptor */
	protected int priority = 1;				// Priority of the messages received, used by DataWorker
	protected String label = "";			// The label that determines what needs to be done with a message
	protected String id="";				    // A readable name for the handler
    protected long readerIdleSeconds =-1;				    // Time in seconds before the connection is killed due to idle

    /* Things regarding the connection*/
    protected long timestamp = System.currentTimeMillis();  // Timestamp of the last received message, init so startup doesn't show error message
    protected long openedStamp;
    protected long passed = -1;							    // Time passed (in ms) between the last two received messages

    protected ArrayList<Writable> targets = new ArrayList<>();
    protected ArrayList<StreamListener> listeners = new ArrayList<>();

    protected String eol="\r\n";

    protected boolean reconnecting=false;
    protected int connectionAttempts=0;

    protected boolean clean=true;
    protected boolean log=true;
    protected boolean echo=false;
    protected boolean addDataOrigin=false;
    protected ScheduledFuture<?> reconnectFuture=null;
    protected ArrayList<TriggerAction> triggeredActions = new ArrayList<>();

    public enum TRIGGER{OPEN,IDLE,CLOSE,HELLO,WAKEUP, IDLE_END}

    protected EventLoopGroup eventLoopGroup;		    // Eventloop used by the netty stuff
    protected boolean readerIdle=false;

    protected BaseStream( String id, BlockingQueue<Datagram> dQueue){
        this.id=id;
        this.dQueue=dQueue;
    }
    protected BaseStream( BlockingQueue<Datagram> dQueue, Element stream ){
        this.dQueue=dQueue;
        readFromXML(stream);
    }
    public void setEventLoopGroup( EventLoopGroup eventLoopGroup ){
        this.eventLoopGroup = eventLoopGroup;
    }

    protected boolean readFromXML( Element stream ){

        if (!stream.getAttribute("type").equalsIgnoreCase(getType())) {
            Logger.error("Not a "+getType()+" stream element.");
            return false;
        }

        var dig = XMLdigger.goIn(stream);

        id = dig.attr("id", "");
        label = dig.peekAt("label").value("");    // The label associated fe. nmea,sbe38 etc
        priority = dig.peekAt("priority").value( 1);	 // Determine priority of the sensor
        log = dig.peekAt("log").value(true);
        addDataOrigin = dig.peekAt("prefixorigin").value(false);
        // delimiter
        String deli = dig.peekAt("eol").value("\r\n");
        if( deli.equalsIgnoreCase("\\0"))
            deli="";// Delimiter used, default carriage return + line feed
        eol = Tools.getDelimiterString(deli);

        // ttl
		String ttlString = dig.peekAt("ttl").value("-1");
        if( !ttlString.equals("-1") ){
			if( Tools.parseInt(ttlString, -999) != -999) // Meaning no time unit was added, use the default s
                ttlString += "s";
			readerIdleSeconds = TimeTools.parsePeriodStringToSeconds(ttlString);
        }
        if( dig.attr("echo", false) ){
            enableEcho();
        }
        // cmds
        triggeredActions.clear();
        if( dig.hasPeek("cmd") ) {
            for (var cmd : dig.digOut("cmd")) {
                var c = cmd.value("");
                if (!c.isEmpty())
                    triggeredActions.add(new TriggerAction(dig.attr("when", "open"), c));
            }
            dig.goUp();
        }
        for( var write : dig.digOut("write") ){
            String c = write.value("");
            if( !c.isEmpty())
                triggeredActions.add(new TriggerAction( write.attr("when","hello"), c));
        }
        return readExtraFromXML(stream);
    }
    protected abstract boolean readExtraFromXML( Element stream );

    // Abstract methods
    public abstract boolean connect();
    public abstract boolean disconnect();
    public abstract boolean isConnectionValid();
    public abstract long getLastTimestamp();
    public abstract String getInfo();
    protected abstract String getType();

    /* Getters & Setters */
    public void setLabel( String label ){
        this.label=label;
    }
    public String getLabel( ){
        return label;
    }
    public void setPriority(int priority ){
		this.priority=priority;
    }
    public void showOriginAsPrefix( boolean show ){
        addDataOrigin=show;
    }
    public void addListener( StreamListener listener ){
		listeners.add(listener);
    }

    public void id(String id ){
        this.id=id;
    }
    public String id(){
        return id;
    }
    public boolean isWritable(){
        return this instanceof Writable;
    }

    /**
     * Set the maximum time passed since data was received before the connection is considered idle
     * @param seconds The time in seconds
     */
    public void setReaderIdleTime(long seconds){
        this.readerIdleSeconds = seconds;
    }
    public long getReaderIdleTime(){
        return readerIdleSeconds;
    }
    /* Requesting data */
    public synchronized boolean addTarget(Writable writable ){
        if( writable == null){
            Logger.error("Tried adding request to "+id+" but writable is null");
            return false;
        }
        if( targets.contains(writable)){
            Logger.info(id +" -> Already has "+writable.id()+" as target, not adding.");
            return true;
        }

        if( writable.id().startsWith("telnet")) {
            targets.add(0,writable);
        }else{
            targets.removeIf( x -> x.id().equals(writable.id())&&writable.id().contains(":")); // if updated
            targets.add(writable);
        }

        Logger.info("Added request from "+writable.id()+ " to "+id);
        return true;
    }
    public boolean removeTarget(Writable wr ){
		return targets.remove(wr);
	}
	public int clearTargets(){
        int total=targets.size();
        targets.clear();
        return total;
    }
	public int getRequestsSize(){
		return targets.size();
    }
    public String listTargets(){
        StringJoiner join = new StringJoiner(", ");
        targets.forEach(wr -> join.add(wr.id()));
        return join.toString();
    }
    /* Echo */
    public void enableEcho(){
        if( this instanceof Writable ){
            targets.add((Writable)this );
            echo=true;
        }
    }
    public void disableEcho(){
        if( this instanceof Writable ){
            echo=false;
            targets.removeIf(r -> r.id().equalsIgnoreCase(id));
        }
    }
    /* ******************************** TRIGGERED ACTIONS *****************************************************/
    public void flagAsIdle(){
        applyTriggeredAction(BaseStream.TRIGGER.IDLE);
        applyTriggeredAction(BaseStream.TRIGGER.WAKEUP);
        flagIdle();
        readerIdle=true;
    }
    public void flagAsActive(){
        applyTriggeredAction(BaseStream.TRIGGER.IDLE_END);
        readerIdle=false;
    }
    protected abstract void flagIdle();
    public boolean isIdle(){
        return readerIdle;
    }
    public void addTriggeredAction(String when, String action ){
        var t = new TriggerAction(when, action);
        if( t.trigger==null)
            return;
        triggeredActions.add(t);
    }
    public void applyTriggeredAction(TRIGGER trigger ){
        for( TriggerAction cmd : triggeredActions){
            if( cmd.trigger!=trigger) // Check if the trigger presented matched this actions trigger
                continue; // If not, check the next one

            if( cmd.trigger==TRIGGER.HELLO || cmd.trigger==TRIGGER.WAKEUP ){ // These trigger involves writing to remote
                Logger.info(id+" -> "+cmd.trigger+" => "+cmd.data);
                if( this instanceof Writable )
                    ((Writable) this).writeLine(cmd.data);
                continue;
            }
            Logger.info(id+" -> "+cmd.trigger+" => "+cmd.data);
            if( this instanceof Writable ){ // All the other triggers are executing cmds
                dQueue.add( Datagram.system(cmd.data).writable((Writable)this) );
            }else{
                dQueue.add( Datagram.system(cmd.data) );
            }
        }
    }
    public List<String> getTriggeredActions(TRIGGER trigger ){
        return triggeredActions.stream().filter(x -> x.trigger==trigger).map(x -> x.data).collect(Collectors.toList());
    }
    private static TRIGGER convertTrigger( String trigger ){
        switch (trigger.toLowerCase()) {
            case "open" -> {
                return TRIGGER.OPEN;
            }
            case "close" -> {
                return TRIGGER.CLOSE;
            }
            case "idle" -> {
                return TRIGGER.IDLE;
            }
            case "!idle" -> {
                return TRIGGER.IDLE_END;
            }
            case "hello" -> {
                return TRIGGER.HELLO;
            }
            case "wakeup", "asleep" -> {
                return TRIGGER.WAKEUP;
            }
            default -> {
                Logger.error("Unknown trigger requested : " + trigger);
                return null;
            }
        }
    }
    protected static class TriggerAction {
        String data;
        public TRIGGER trigger;

        TriggerAction(TRIGGER trigger, String data ){
            this.trigger=trigger;
            this.data =data;
            Logger.info("Added action : "+trigger+" -> "+data);
        }
        public String data(){
            return data;
        }
        TriggerAction(String trigger, String command){
            this(convertTrigger(trigger),command);
        }
    }
}
