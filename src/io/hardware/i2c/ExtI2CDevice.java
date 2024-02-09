package io.hardware.i2c;

import com.diozero.api.I2CDevice;
import com.diozero.api.RuntimeIOException;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import util.data.RealtimeValues;
import util.tools.TimeTools;
import util.xml.XMLfab;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Extension for the I2CDevice class that adds das relevant functionality
 */
public class ExtI2CDevice extends I2CDevice {

	private final String id;
	private final String script;
	private Instant timestamp;
	private final ArrayList<Writable> targets = new ArrayList<>();
	private boolean debug=false;
	private final HashMap<String,I2COpSet> ops = new HashMap<>();
	private boolean busy=false;
	private final ArrayList<String> queue = new ArrayList<>();
	private I2COpFinished worker;
	/**
	 * Extension of the @see I2CDevice class that adds the command functionality
	 * 
	 * @param bus The controller on which this device is connected
	 * @param address The address of the device
	 */
	public ExtI2CDevice (String id,int bus, int address, String script){
		super(bus,address);
		this.id=id;
		this.script=script;
		Logger.info("Connecting to controller:"+bus +" and address:"+address);
	}
	public void setI2COpFinished( I2COpFinished worker){
		this.worker=worker;
	}
	public void addOpSet( I2COpSet opset ){
		ops.put(opset.id(),opset);
	}
	public void clearOpSets(RealtimeValues rtvals ){
		// Make sure the rtvals are also removed from the central repo
		ops.values().forEach( set -> set.removeRtvals(rtvals));
		ops.clear();
	}
	public String id(){
		return id;
	}
	public boolean probeIt(){
		try {
			if (!this.probe()){
				Logger.warn("(i2c) -> Probe failed for "+id);
				return false;
			}
		}catch(RuntimeIOException e ){
			Logger.warn("(i2c) -> Probe failed for "+id+" -> "+e.getMessage());
			return false;
		}
		return true;
	}
	public String toString(){
		return "@"+getController()+":0x"+String.format("%02x", getAddress())
				+" using script "+script+ (probeIt()?" [OK]":"[NOK]");
	}
	public String getStatus(String id){
		String age = getAge()==-1?"Not used yet": TimeTools.convertPeriodtoString(getAge(), TimeUnit.SECONDS);
		return (probeIt()?"":"!!")+"I2C ["+id+"] "+getAddr()+"\t"+age+" [-1]";
	}
	public String getAddr(){
		return "0x"+String.format("%02x", getAddress())+"@"+getController();
	}
	public String getScript(){
		return script;
	}
	public void setDebug( boolean state){
		debug=state;
	}
	public boolean isDebug(){
		return debug;
	}
	/**
	 * Add a @Writable to which data received from this device is send
	 * @param wr Where the data will be send to
	 */
	public void addTarget(Writable wr){
		if( wr!=null&&!targets.contains(wr))
			targets.add(wr);
	}
	public boolean removeTarget(Writable wr ){
		return targets.remove(wr);
	}
	/**
	 * Get the list containing the writables
	 * @return The list of writables
	 */
	public List<Writable> getTargets(){
		return targets;
	}
	public String getWritableIDs(){
		StringJoiner join = new StringJoiner(", ");
		join.setEmptyValue("None yet.");
		targets.forEach(wr -> join.add(wr.id()));
		return join.toString();
	}
	public void updateTimestamp(){
		timestamp = Instant.now();
	}
	public long getAge(){
		if( timestamp==null)
			return -1;
		return Duration.between(timestamp,Instant.now()).getSeconds();
	}
	public void storeInXml(XMLfab fab){
		fab.selectOrAddChildAsParent("bus","controller",getController())
				.selectOrAddChildAsParent("device","id",id)
				.attr("address","0x"+String.format("%02x", getAddress()))
				.attr("script",script).build();
	}
	public String getOpsInfo( boolean full){
		String last="";
		StringJoiner join = new StringJoiner("\r\n");
		for( var entry : ops.entrySet() ){
			String[] split = entry.getKey().split(":");
			var cmd = entry.getValue();
			if( !last.equalsIgnoreCase(split[0])){
				if( !last.isEmpty())
					join.add("");
				join.add(TelnetCodes.TEXT_GREEN+split[0]+TelnetCodes.TEXT_DEFAULT);
				last = split[0];
			}
			join.add(cmd.getOpsInfo("\t   ",full));
		}
		return join.toString();
	}
	public boolean hasOp( String id ){
		return ops.containsKey(id);
	}
	public void doNext( EventLoopGroup scheduler){
		if( queue.isEmpty() ) {
			busy = false;
			worker.deviceDone();
			if( debug )
				Logger.info(id()+"(i2c) -> Finished queue");
			return;
		}
		Logger.info(id()+"(i2c) -> Starting next in queue");
		startOp(queue.remove(0),scheduler);
	}
	public void doOp( String setId, EventLoopGroup scheduler ){
		if( debug )
			Logger.info(id()+"(i2c) Trying to do "+setId);
		if( !ops.containsKey(setId) ) {
			Logger.error(id()+" (i2c) -> Tried to add '"+setId+"' but no such op.");
			return;
		}
		if( busy ){
			if( debug )
				Logger.info(id()+"(i2c) -> Device already busy, adding "+setId+" to queue.");
			queue.add(setId);
			return;
		}
		if( debug )
			Logger.info(id()+"(i2c) -> Not busy, starting "+setId);
		busy=true;
		startOp(setId,scheduler);
	}
	public void queueOp( String setId ){
		if( !ops.containsKey(setId) ) {
			Logger.error(id()+" (i2c) -> Tried to queue '"+setId+"' but no such op.");
			return;
		}
		queue.add(setId);
	}
	private void startOp(String id, EventLoopGroup scheduler ){

		if( !probeIt() )  // First check if the device is still there?
			return;

		ops.get(id).startOp(this,scheduler);
		updateTimestamp(); // Update last used timestamp, because we had comms
	}
	public boolean isBusy(){
		return busy;
	}
}