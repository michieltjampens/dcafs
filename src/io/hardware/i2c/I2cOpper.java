package io.hardware.i2c;

import io.netty.channel.EventLoopGroup;
import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import util.data.RealtimeValues;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.*;
import java.util.concurrent.BlockingQueue;

/**
 * Extension for the I2CDevice class that adds das relevant functionality
 */
public class I2cOpper extends I2cDevice{

	private String script;
	private final HashMap<String,I2COpSet> ops = new HashMap<>();
	private boolean busy=false;
	private final ArrayList<String> queue = new ArrayList<>();
	private I2COpFinished worker;
	/**
	 * Extension of the @see I2CDevice class that adds the command functionality
	 * @param dev The digger pointing to the xml node about this
	 * @param bus The controller on which this device is connected
	 */
	public I2cOpper(XMLdigger dev, I2cBus bus, BlockingQueue<Datagram> dQeueu ){
		super(dev,bus,dQeueu);

		script = dev.attr("script", "").toLowerCase();;
		if( script.isEmpty()) // Might be a node instead of an attribute
			script =  dev.peekAt("script").value("");
	}
	public I2cOpper( String id, I2cBus bus, int address, String script, BlockingQueue<Datagram> dQeueu){
		super(id,address,bus,dQeueu);
		this.script=script;
		Logger.info("I2cOpper created for "+bus.id()+":"+address+" with script "+script+".");
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

	public String toString(){
		return "@"+bus+":0x"+String.format("%02x", address)
				+" using script "+script + " queue:"+queue.size()
				+(probeIt()?" [OK]":"[NOK]");
	}

	public String getScript(){
		return script;
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
		if( debug )
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
		ops.get(id).startOp(this,scheduler);
		updateTimestamp(); // Update last used timestamp, because we had comms
	}
	public boolean isBusy(){
		return busy;
	}
}