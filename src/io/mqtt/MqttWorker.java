package io.mqtt;

import io.Writable;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.tinylog.Logger;
import util.data.*;
import worker.Datagram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.*;

/**
 * Non-blocking worker that handles the connection to a broker. How publish/subscribe works:
 * 1) Add the work to the queue
 * 2) Check if there's an active connection.
 * 		a) If not, start the doConnect thread and add the work to the queue
 * 		b) If so, go to 3 if publish
 * 3) Check if the worker is currently publishing
 *     a) If not, start the doPublish thread
 *     b) If so, do nothing

 * If a connection is established all subscriptions will be subscribed to and
 * doPublish will be started if any work is to be done.

 * For now nothing happens with the connection when no work is present and no subscriptions are made, an
 * option is to disconnect.
 */
public class MqttWorker implements MqttCallbackExtended,Writable {
	// Queue that holds the messages to publish
	private final BlockingQueue<MqttWork> mqttQueue = new LinkedBlockingQueue<>();
	private MqttClient client = null;
	private final MemoryPersistence persistence = new MemoryPersistence();
	MqttConnectOptions connOpts = null;

	private String id; // Name/if/title for this worker
	private String brokerAddress = ""; // The address of the broker
	private final String clientId; // Client id to use for the broker
	private boolean publishing = false; // Flag that shows if the worker is publishing data
	private boolean connecting = false; // Flag that shows if the worker is trying to connect to the broker

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(); // Scheduler for the publish and
																						// connect class
	private final Map<String, AbstractVal> valReceived = new HashMap<>(); // Map containing all the subscriptions
	private final ArrayList<String> subscriptions = new ArrayList<>();
	private final Map<String, String> provide = new HashMap<>();
	private final ArrayList<Writable> targets = new ArrayList<>();

	private RealtimeValues rtvals;
	private BlockingQueue<Datagram> dQueue;
	private String storeTopic="";

	public MqttWorker(String id, String address, String clientId, RealtimeValues rtvals, BlockingQueue<Datagram> dQueue) {
		this.id=id;
		setBrokerAddress(address);
		this.clientId=clientId;
		this.rtvals=rtvals;
		this.dQueue=dQueue;
	}
	/**
	 * Set the id of this worker
	 * 
	 * @param id The id for this worker
	 */
	public void setID(String id) {
		this.id = id;
	}

	/**
	 * Get the address of the broker
	 * 
	 * @return the address of the broker
	 */
	public String getBrokerAddress() {
		return this.brokerAddress;
	}
	public void setBrokerAddress( String addr){
		if(!addr.contains(":"))
			addr=addr+":1883";
		brokerAddress = switch(addr.substring(0,3)){
			case "tcp","ssl" -> addr;
			default -> "tcp://"+addr;
		};
	}
	/* ************************************ Q U E U E ************************************************************* **/
	/**
	 * Give work to the worker, it will be placed in the queue
	 * 
	 * @param work The work to do
	 * @return true if the work was accepted
	 */
	public boolean addWork(MqttWork work) {
		if( work.isInvalid() )
			return false;
		mqttQueue.add(work);
		if (!client.isConnected()) { // If not connected, try to connect
			if (!connecting) {
				connecting = true;
				scheduler.schedule( new Connector(0), 0, TimeUnit.SECONDS);
			}
		} else if (!publishing) { // If currently not publishing ( there's a 30s timeout) enable publishing
			publishing = true;
			scheduler.schedule( new Publisher(), 0, TimeUnit.SECONDS);
		}
		return true;
	}
	public void addWork(String topic, String value){
		addWork(new MqttWork(topic, value));
	}
	/* ************************************** S E T T I N G S ****************************************************** */
	/**
	 * Apply the settings read from the xml element
	 */
	public void applySettings() {

		connOpts = new MqttConnectOptions();
		connOpts.setCleanSession(false);
		if( !clientId.isBlank() )
			connOpts.setUserName(clientId);
		connOpts.setAutomaticReconnect(true); //works

		try {
			if( client != null ){
				client.disconnect();
			}
			client = new MqttClient( brokerAddress, MqttClient.generateClientId(), persistence);
			Logger.info( id+"(mqtt) -> Created client");
			client.setTimeToWait(10000);
			client.setCallback(this);
			if( !subscriptions.isEmpty() ){ // If we have subscriptions, connect.
				connecting=true;
				scheduler.schedule( new Connector(0), 0, TimeUnit.SECONDS );
			}
		} catch (MqttException e) {
			Logger.error(id+"(mqtt) -> "+e);
		}
	}	
	/* ****************************************** S U B S C R I B E  *********************************************** */
	/**
	 * Subscribe to a given topic on the associated broker
	 * @param topic The topic so subscribe to
	 * @return 0 if failed to add, 1 if ok, 2 if added but not send to broker
	 */
	public int addSubscription( String topic ){
		return addSubscription( topic,null );
	}
	/**
	 * Subscribe to a given topic on the associated broker and store the received data in the val.
	 * @param topic The topic so subscribe to
	 * @param val The rtval the data will be stored in.
	 * @return 0 if failed to add, 1 if ok, 2 if added but not send to broker
	 */
	public int addSubscription( String topic, AbstractVal val ){
		if( topic==null){
			Logger.error(id+"(mqtt) -> Invalid topic");
			return 0;
		}
		topic = topic.replace("\\","/"); // Make sure the correct one is used
		if( subscriptions.contains(topic) )
			return 2;

		subscriptions.add(topic);
		if( val != null)
			valReceived.put(topic,val);

		return subscribe( topic );
	}
	/**
	 * Unsubscribe from a topic
	 * @param topic The topic to unsubscribe from
	 * @return True if a subscription to that topic existed and was removed
	 */
	public boolean removeSubscription( String topic ){

		if( topic.equals("all")){
			subscriptions.removeIf(this::unsubscribe);
			return subscriptions.isEmpty();
		}else{
			if( subscriptions.remove(topic) ){
				unsubscribe( topic );
				return true;
			}
		}
		return false;
	}
	/**
	 * Obtain a list of all current subscriptions
	 * @param nl The delimiter to use for a newline
	 * @return Listing of the topics subscribed with the associated label
	 */
	public String getSubscriptions( String nl ){
		if( subscriptions.isEmpty() )
			return "No active subscriptions"+nl;
		StringJoiner join = new StringJoiner(nl,"  Subs"+nl,nl);
		subscriptions.forEach( sub -> join.add("  ==> "+ sub) );
		return join.toString();
	}
	/**
	 * Private method used to subscribe to the given topic
	 * @param topic The topic to subscribe to
	 * @return 0 if failed to add, 1 if ok, 2 if added but not send to broker
	 */	
	private int subscribe( String topic ){
		if( client == null)
			return 2;
		if (!client.isConnected() ) { // If not connected, try to connect
			if( !connecting ){
				connecting=true;
				scheduler.schedule( new Connector(0), 0, TimeUnit.SECONDS );
			}
		} else{
			try {
				Logger.info( id+"(mqtt) -> Subscribing to "+ topic);
				client.subscribe( topic );
				return 1;
			} catch (MqttException e) {
				Logger.error(e);
			}
		}
		return 2;
	}
	/**
	 * Unsubscribe from the given topic
	 * @param topic The topic to unsubscribe from
	 * @return True if this was successful
	 */	
	private boolean unsubscribe( String topic ){
		try {
			Logger.info( id+"(mqtt) -> Unsubscribing from "+ topic);
			client.unsubscribe(topic);
		} catch (MqttException e) {
			Logger.error(e);
			return false;
		}
		return true;
	}

	/**
	 * @param topic The topic the rtval data is attached to.
	 * @param rtval The rtval to send to the broker.
	 */
	public void addProvide( String topic,String rtval ){
		if( !topic.equalsIgnoreCase(rtval))
			provide.put(rtval,topic);
	}
	public void setGenerateStore( String topic ){
		if( topic.isEmpty() )
			return;
		topic = topic.replace("\\","/"); // Make sure the correct slash is used.
		addSubscription(topic);
		storeTopic=topic;
	}
	/* ****************************************** R E C E I V I N G  ************************************************ */

	@Override
	public void messageArrived(String topic, MqttMessage message) {

		String load = new String(message.getPayload());	// Get the payload
		Logger.info("Rec: "+topic+" load:"+load);
		Logger.tag("RAW").warn(id+"\t"+topic+"\t"+load);  // Store it like any other received data

		var rtval = valReceived.get(topic);
		if( rtval != null ){
			rtval.parseValue(load);
		}else if( !storeTopic.isEmpty()){ //
			var key = storeTopic.substring(0,storeTopic.indexOf("#"));
			if( key.isEmpty() || topic.startsWith(key)){
				var split = topic.split("/"); // split it in parts, we only want last two
				if(split.length<2) {
					Logger.warn(id+"(mqtt) -> Received topic, but less than two elements -> "+topic);
				}else {
					var group = split[split.length - 2];
					var name = split[split.length - 1];

					var val = rtvals.getAbstractVal(group + "_" + name);
					if (val.isPresent()) {
						valReceived.put(topic, val.get());
					} else {
						// Figure out if its int,real or text?
						if (NumberUtils.isParsable(load)) { // So int or real
							if (load.contains(".") || load.contains(",")) { // so real
								var real = RealVal.newVal(group, name);
								real.parseValue(load);
								rtvals.addRealVal(real);
								valReceived.put(topic, real);
								dQueue.add(Datagram.system("mqtt:" + id + ",store,real," + real.id() + "," + topic));
							} else { // int
								var i = IntegerVal.newVal(group, name);
								i.parseValue(load);
								rtvals.addIntegerVal(i);
								valReceived.put(topic, i);
								dQueue.add(Datagram.system("mqtt:" + id + ",store,int," + i.id() + "," + topic));
							}
						} else { // So text
							var txt = TextVal.newVal(group, name).value(load);
							rtvals.addTextVal(txt);
							valReceived.put(topic, txt);
							dQueue.add(Datagram.system("mqtt:" + id + ",store,txt," + txt.id() + "," + topic));
						}
					}
				}
			}
		}
		if( !targets.isEmpty() )
			targets.removeIf(dt -> !dt.writeLine( load ) );

	}

	/**
	 * Add a target for the received data
	 * @param wr The writable to write to
	 */
	public void registerWritable(Writable wr ){
		if(!targets.contains(wr))
			targets.add(wr);
	}

	/**
	 * Remove a writable from the list of targets
	 * @param wr The writable to remove
	 * @return True if it was removed.
	 */
	public boolean removeWritable( Writable wr ){
		return targets.remove(wr);
	}
  	/* ***************************************** C O N N E C T  ***************************************************** */
	/**
	 * Checks whether there's a connection to the broker
	 * @return True if connected
	 */
	public boolean isConnected() {
		if (client == null)
			return false;
		return client.isConnected();
	}

	/**
	 * Disconnect the client from the broker
	 * @return True if disconnected.
	 */
	public boolean disconnect(){
		if( client==null)
			return false;
		try {
			client.disconnect();
			Logger.info(id+"(mqtt) -> Disconnected after request.");
		}catch( MqttException e){
			Logger.error(e);
			return false;
		}
		return true;
	}

	/**
	 * Clear all data from the worker to be able to reload it.
	 * @param rtvals RealtimeValues that hold the ones used by this worker.
	 */
	public void clear( RealtimeValues rtvals ){
		subscriptions.forEach(this::unsubscribe);
		valReceived.values().forEach(rtvals::removeVal);
		targets.clear();
		disconnect();
	}
	@Override
	public void connectionLost(Throwable cause) {
		if (!mqttQueue.isEmpty() && !subscriptions.isEmpty()) {
			Logger.info( id+"(mqtt) -> Connection lost but still work to do, reconnecting...");
			scheduler.schedule(new Connector(0), 0, TimeUnit.SECONDS);
		}else{
			connecting=false;
			Logger.warn( id+"(mqtt) -> "+cause.getMessage());
		}
	}
	@Override
	public void connectComplete(boolean reconnect, String serverURI) {
		if( reconnect ){
			Logger.info( id+"(mqtt) -> Reconnecting." );
		}else{
			Logger.info( id+"(mqtt) -> First connection established" );
		}		
		connecting=false;
		String subs="";
		try {
			for( String sub:subscriptions ){
				subs=sub; // Purely to know when the error occurred
				client.subscribe( sub );
				Logger.info(id+"(mqtt) -> Subscribed to "+sub);
			}
		} catch (MqttException e) {
			Logger.error( id+"(mqtt) -> Failed to subscribe to: "+ subs);
		}
		if( !mqttQueue.isEmpty() )
			scheduler.schedule( new Publisher(),0,TimeUnit.SECONDS);
	}
	/**
	 * Small class that handles connection to the broker, so it's not blocking.
	 */
	private class Connector implements Runnable {
		int attempt;

		public Connector(int attempt) {
			this.attempt = attempt;
		}

		@Override
		public void run() {

			if (!client.isConnected()) {
				try {
					client.connect(connOpts);
					Logger.info( id+"(mqtt) -> Connected");
				} catch (MqttException me) {					
					attempt++;
					var time = Math.min(attempt*25+25,120);
					Logger.warn( id+"(mqtt) -> Failed to connect,  trying again in "+ time+"s. Cause: "+me.getMessage());
					scheduler.schedule( new Connector(attempt), time, TimeUnit.SECONDS );
				}
			}else{
				Logger.info( id+"(mqtt) -> Client already connected.");
			}
		}
	}
	/* ***************************************** P U B L I S H  ******************************************************/
	private class Publisher implements Runnable {
		@Override
		public void run() {
			boolean goOn=true;
			while (goOn) {
				MqttWork work=null;
				try {
					work = mqttQueue.poll(30, TimeUnit.SECONDS);
					if( work == null ){
						goOn=false;
						continue;
					}
					if( work.isInvalid())
						continue;
					client.publish( work.getTopic(), work.getMessage() );
				} catch (InterruptedException e) {
					Logger.error(e);
					// Restore interrupted state...
    				Thread.currentThread().interrupt();
				} catch (MqttException e) {
					Logger.error(e.getMessage());
					goOn=false;
					work.incrementAttempt();
					mqttQueue.add(work);
				} 
			}
			publishing=false;
		}
	}
	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
		//Logger.warn("This shouldn't be called...");
	}
	/* ***************************************** W R I T A B L E  ******************************************************/
	@Override
	public boolean writeString(String data) {
		return false;
	}

	@Override
	public boolean writeLine(String data) {
		return false;
	}

	@Override
	public boolean writeLine(String origin, String data) {
		var topic = provide.get(origin);
		topic = topic==null?origin.replace("_","/"):topic;
		addWork(topic,data);
		return true;
	}

	@Override
	public boolean writeBytes(byte[] data) {
		return false;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public boolean isConnectionValid() {
		return true;
	}

	@Override
	public Writable getWritable() {
		return this;
	}
}
