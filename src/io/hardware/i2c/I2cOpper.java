package io.hardware.i2c;

import io.netty.channel.EventLoopGroup;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.ArrayUtils;
import org.tinylog.Logger;
import util.data.RealtimeValues;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Extension for the I2CDevice class that adds das relevant functionality
 */
public class I2cOpper extends I2cDevice{

	private String script;
	private final HashMap<String,I2COpSet> ops = new HashMap<>();
	private final ArrayList<String[]> queue = new ArrayList<>();
	/**
	 * Extension of the @see I2CDevice class that adds the command functionality
	 * @param dev The digger pointing to the xml node about this
	 * @param bus The controller on which this device is connected
	 */
	public I2cOpper(XMLdigger dev, I2cBus bus, BlockingQueue<Datagram> dQeueu ){
		super(dev,bus,dQeueu);

		script = dev.attr("script", "").toLowerCase();
		if( script.isEmpty()) // Might be a node instead of an attribute
			script =  dev.peekAt("script").value("");
	}
	public I2cOpper( String id, I2cBus bus, int address, String script, BlockingQueue<Datagram> dQeueu){
		super(id,address,bus,dQeueu);
		this.script=script;
		Logger.info("I2cOpper created for "+bus.id()+":"+address+" with script "+script+".");
	}

	public void addOpSet( I2COpSet opset ){
		ops.put(opset.id(),opset);
	}
	public int opsetCount( ){
		return ops.size();
	}
	public void clearOpSets(RealtimeValues rtvals ){
		// Make sure the rtvals are also removed from the central repo
		ops.values().forEach( set -> set.removeRtvals(rtvals));
		ops.clear();
	}

	public String getScript(){
		return script;
	}
	public String getOpsInfo( boolean full){
		String last="";
		var join = new StringJoiner("\r\n");
		join.setEmptyValue("No sets yet.");
		Logger.info("Opsets contained:"+ops.size());
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
	public void useBus(EventLoopGroup scheduler){

		if( queue.isEmpty() ){
			Logger.info(id()+"(i2c) -> Bus granted but nothing to do...?");
			bus.doNext();
			return;
		}

		String[] setParams = queue.get(0);
		var setId = setParams[0];
		setParams = ArrayUtils.remove(setParams,0); // Remove the set id
		if (debug)
			Logger.info(id() + "(i2c) Trying to do " + setId);

		long res = 0;
		while( res == 0 ) {
			res = ops.get(setId).runOp(this,setParams );
		}
		bus.doNext(); // Release the bus
		if (res == -1) { // Means this was the last one in the set
			queue.remove(0); // Remove it from the queue
			forwardData(id + ";" + ops.get(setId).getResult());
		} else { // Schedule slot for next one
			if( debug )
				Logger.info(id()+"(i2c) -> Scheduling delayed op in set "+setId+" for "+res+"ms" );
			scheduler.schedule(()->bus.requestSlot(this),res, TimeUnit.MILLISECONDS);
		}
		updateTimestamp(); // Update last used timestamp, because we had comms
	}
	public boolean queueSet( String[] setIdParams ){
		var id=setIdParams[0];
		if( !ops.containsKey(id) ) {
			Logger.error(id()+" (i2c) -> Tried to queue '"+id+"' but no such op.");
			return false;
		}
		queue.add(setIdParams);
		bus.requestSlot(this);
		return true;
	}
}