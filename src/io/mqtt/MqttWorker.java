package io.mqtt;

import io.Writable;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.tinylog.Logger;

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
 * 
 * If a connection is established all subscriptions will be subscribed to and
 * doPublish will be started if any work is to be done.
 *
 * For now nothing happens with the connection when no work is present and no subscriptions are made, an
 * option is to disconnect.
 */
public class MqttWorker implements MqttCallbackExtended {
	// Queue that holds the messages to publish
	private final BlockingQueue<MqttWork> mqttQueue = new LinkedBlockingQueue<>();
	private MqttClient client = null;
	private final MemoryPersistence persistence = new MemoryPersistence();
	MqttConnectOptions connOpts = null;

	private String id; // Name/if/title for this worker
	private String brokerAddress = ""; // The address of the broker
	private final String clientId; // Client id to use for the broker
	private final String defTopic; // The defaulttopic for this broker, will be prepended to publish/subscribe etc

	enum MQTT_FLAVOUR {
		UBIDOTS, MOSQUITO
	}

	MQTT_FLAVOUR flavour; // In case the message layout is different depending on te broker

	private boolean publishing = false; // Flag that shows if the worker is publishing data
	private boolean connecting = false; // Flag that shows if the worker is trying to connect to the broker

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(); // Scheduler for the publish and
																						// connect class
	private final Map<String, String> subscriptions = new HashMap<>(); // Map containing all the subscriptions
	private final ArrayList<Writable> targets = new ArrayList<>();

	public MqttWorker( String id, String address, String clientId, String defTopic ) {
		this.id=id;
		setBrokerAddress(address);
		this.clientId=clientId;
		this.defTopic=defTopic;

		if (address.contains("ubidots")) {
			flavour = MqttWorker.MQTT_FLAVOUR.UBIDOTS;
		} else {
			flavour = MqttWorker.MQTT_FLAVOUR.MOSQUITO;
		}
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
		if( work.isEmpty() )
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
		
		Logger.info("Creating client for "+brokerAddress);

		try {
			if( client != null ){
				client.disconnect();
			}
			client = new MqttClient( brokerAddress, MqttClient.generateClientId(), persistence);
			client.setTimeToWait(10000);
			client.setCallback(this);
			
		} catch (MqttException e) {
			Logger.error(e);
		}
		// Subscriptions
		for( String key : subscriptions.keySet() ){
			subscribe( key );
		}	
	}	
	/* ****************************************** S U B S C R I B E  *********************************************** */
	/**
	 * Subscribe to a given topic on the associated broker
	 * @param topic The topic so subscribe to
	 * @param label The label used by the BaseWorker to process the received data
	 * @return True if added
	 */
	public boolean addSubscription( String topic, String label ){
		if( topic==null){
			Logger.error(id+"(mqtt) -> Invalid topic");
			return false;
		}
		if( defTopic.endsWith("/") && topic.startsWith("/") )
			topic = topic.substring(1);			
		
		subscriptions.put(topic, label);
		return subscribe( topic );
	}
	/**
	 * Unsubscribe from a topic
	 * @param topic The topic to unsubscribe from
	 * @return True if a subscription to that topic existed and was removed
	 */
	public boolean removeSubscription( String topic ){

		if( topic.equals("all")){
			subscriptions.keySet().removeIf(this::unsubscribe);
			return subscriptions.isEmpty();
		}else{
			if( subscriptions.remove(topic) != null){
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
		StringJoiner join = new StringJoiner(nl,"",nl);
		subscriptions.forEach(
			(topic,label) -> join.add("==> "+topic+" -> "+label)
		);
		return join.toString();
	}
	/**
	 * Private method used to subscribe to the given topic
	 * @param topic The topic to subscribe to
	 */	
	private boolean subscribe( String topic ){	
		if( client == null)
			return false;			
		if (!client.isConnected() ) { // If not connected, try to connect
			if( !connecting ){
				connecting=true;
				scheduler.schedule( new Connector(0), 0, TimeUnit.SECONDS );
			}
		} else{
			try {
				Logger.info("Subscribing to "+defTopic+topic);
				client.subscribe( defTopic + topic );
				return true;
			} catch (MqttException e) {
				Logger.error(e);
			}
		}
		return false;
	}
	/**
	 * Unsubscribe from the given topic
	 * @param topic The topic to unsubscribe from
	 * @return True if this was successful
	 */	
	private boolean unsubscribe( String topic ){
		try {
			Logger.info("Unsubscribing from "+defTopic+topic);
			client.unsubscribe(defTopic + topic);
		} catch (MqttException e) {
			Logger.error(e);
			return false;
		}
		return true;
	}
	/* ****************************************** R E C E I V I N G  ************************************************ */

	@Override
	public void messageArrived(String topic, MqttMessage message) {

		String load = new String(message.getPayload());	// Get the payload
		String topicName = subscriptions.get(topic.substring(defTopic.length()));
		Logger.info("Rec: "+topic+" load:"+load);
		Logger.tag("RAW").warn(topicName+"\t"+load);  // Store it like any other received data

		if( !targets.isEmpty() )
			targets.removeIf(dt -> !dt.writeLine( load ) );

	}	
	public void registerWritable(Writable wr ){
		if(!targets.contains(wr))
			targets.add(wr);
	}
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
	public boolean disconnect(){
		if( client==null)
			return false;
		try {
			client.disconnect();
		}catch( MqttException e){
			Logger.error(e);
			return false;
		}
		return true;
	}
	@Override
	public void connectionLost(Throwable cause) {
		if (!mqttQueue.isEmpty()) {
			Logger.debug("Connection lost but still work to do, reconnecting");
			scheduler.schedule(new Connector(0), 0, TimeUnit.SECONDS);
		}
	}
	@Override
	public void connectComplete(boolean reconnect, String serverURI) {
		if( reconnect ){
			Logger.info("Reconnected to "+serverURI );
		}else{
			Logger.debug("First connection to "+serverURI);
		}		
		connecting=false;
		String subs="";
		try {
			for( String sub:subscriptions.keySet() ){
				subs=sub;
				client.subscribe( defTopic+sub );
			}
		} catch (MqttException e) {
			Logger.error("Failed to subscribe to: "+defTopic+subs);
		}
		if( !mqttQueue.isEmpty() )
			scheduler.schedule( new Publisher(),0,TimeUnit.SECONDS);
	}
	/**
	 * Small class that handles connection to the broker so it's not blocking 
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
					Logger.info("Connected to broker: " + brokerAddress + " ...");
				} catch (MqttException me) {					
					attempt++;
					var time = Math.max(attempt*5,60);
					Logger.warn("Failed to connect to "+ brokerAddress +",  trying again in "+ time+"s");
					scheduler.schedule( new Connector(attempt), Math.max(attempt * 5, 60), TimeUnit.SECONDS );
				}
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
					if( flavour==MQTT_FLAVOUR.UBIDOTS){
						client.publish(defTopic + work.getDevice(), work.getUbidotsMessage(1));
					}else if( flavour==MQTT_FLAVOUR.MOSQUITO){
						client.publish(defTopic + "dice/di", new MqttMessage(Integer.toString(10).getBytes()));
					}
				} catch (InterruptedException e) {
					Logger.error(e);
					// Restore interrupted state...
    				Thread.currentThread().interrupt();
				} catch (MqttException e) {
					Logger.error(e.getMessage());
					goOn=false;
					mqttQueue.add(work);
				} 
			}
			publishing=false;
		}
	}
	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
		Logger.warn("This shouldn't be called...");
	}
}
