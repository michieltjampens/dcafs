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
	private boolean failedProbe=false;
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
	public void probeIt() throws RuntimeIOException{
		failedProbe = !this.probe();
		if( failedProbe )
			throw new RuntimeIOException( "Probe failed for "+getAddr() );
	}
	public String toString(){
		return "@"+getController()+":0x"+String.format("%02x ", getAddress())
				+" using script "+script;
	}
	public String getStatus(String id){
		String age = getAge()==-1?"Not used yet": TimeTools.convertPeriodtoString(getAge(), TimeUnit.SECONDS);
		return (failedProbe?"!!":"")+"I2C ["+id+"] "+getAddr()+"\t"+age+" [-1]";
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
			return;
		}
		startOp(queue.remove(0),scheduler);
	}
	public void doOp( String id, EventLoopGroup scheduler ){
		if( busy ){
			Logger.info("Device already busy, adding to queue: "+id);
			queue.add(id);
			return;
		}
		startOp(id,scheduler);
	}
	public void queueOp( String id){
		queue.add(id);
	}
	private void startOp(String id, EventLoopGroup scheduler ){

		var op = ops.get(id);
		if( op == null ) {
			Logger.error(id+" (i2c) -> Tried to run '"+id+"' but no such op.");
			return;
		}
		try {
			Logger.debug("Probing device...");
			probeIt(); // First check if the device is actually there?
			op.startOp(this,scheduler);
			updateTimestamp(); // Update last used timestamp, because we had comms
		}catch( RuntimeIOException e ){
			Logger.error(id+" (i2c) -> Failed to run command for "+getAddr()+":"+e.getMessage());
		}
	}

    public int getBusNr() {
		return this.getController();
    }
	public boolean isBusy(){
		return busy;
	}
}