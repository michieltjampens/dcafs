package io.mqtt;

import das.Core;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.tinylog.Logger;
import util.data.vals.*;
import util.tools.TimeTools;
import worker.Datagram;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

	//private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(); // Scheduler for the publish and
																						// connect class
	private final Map<String, BaseVal> valReceived = new HashMap<>(); // Map containing all the subscriptions
	private final ArrayList<String> subscriptions = new ArrayList<>();
	private final ArrayList<Long> subsRecStamp = new ArrayList<>();
	private final Map<String, String> provide = new HashMap<>();
	private final ArrayList<Writable> targets = new ArrayList<>();

	private final Rtvals rtvals;
	private String storeTopic="";
	private long ttl=-1L;
	private boolean debug=false;
	private final EventLoopGroup eventLoopGroup;
	private final ScheduledExecutorService publishService;

	public MqttWorker(String id, String address, String clientId, Rtvals rtvals, EventLoopGroup eventLoopGroup, ScheduledExecutorService publishService) {
		this.id=id;
		setBrokerAddress(address);
		this.clientId=clientId;
		this.rtvals=rtvals;
		this.eventLoopGroup = eventLoopGroup;
		this.publishService = publishService;
	}

	private void submitConnector(int attempt) {
        connecting = true;
		eventLoopGroup.submit(new Connector(attempt));
	}

	private void submitPublisher() {
        publishing = true;
		publishService.submit(new Publisher()); // Works with a blocking queue
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
	public void setDebug( boolean enabled){
		this.debug=enabled;
	}
	public boolean isDebugging(){
		return debug;
	}
	/* ************************************ Q U E U E ************************************************************* **/
	/**
	 * Give work to the worker, it will be placed in the queue
	 *
	 * @param work The work to do
	 */
	public void addWork(MqttWork work) {
		if( work.isInvalid() )
			return;
		if( debug ){
			Logger.info(id+"(mqtt) -> Processing work: "+work);
		}
		mqttQueue.add(work);
		if (!client.isConnected()) { // If not connected, try to connect
			if (!connecting) {
				submitConnector(0);
			}
		} else if (!publishing) { // If currently not publishing ( there's a 30s timeout) enable publishing
			submitPublisher();
		}
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
				submitConnector(0);
			}
		} catch (MqttException e) {
			Logger.error(id+"(mqtt) -> "+e);
		}
	}
	public void setTTL( long ttl ){
		this.ttl=ttl;
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
	public int addSubscription(String topic, BaseVal val) {
		if( topic==null){
			Logger.error(id+"(mqtt) -> Invalid topic");
			return 0;
		}
		topic = topic.replace("\\","/"); // Make sure the correct one is used
		if( subscriptions.contains(topic) )
			return 2;

		subscriptions.add(topic);
		subsRecStamp.add(-1L);
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
			subsRecStamp.clear();
			return subscriptions.isEmpty();
        }

        int index = subscriptions.indexOf(topic);
        subsRecStamp.remove(index);
        if (subscriptions.remove(index).equals(topic)) {
            unsubscribe(topic);
            return true;
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

		var ttlInfo = ttl > 0 ? " [ttl:" + TimeTools.convertPeriodToString(ttl, TimeUnit.MILLISECONDS) + "]" : "";

		StringJoiner join = new StringJoiner(nl,"  Subs"+ttlInfo+nl,nl);
		boolean toggle=false; // Toggle between gray or yellow line
		int max=0;

		// Find the length of the longest string
		for( var sub :subscriptions )
			max = Math.max(max,sub.length());
		max += 6; // Add a bit of space

		for( int a=0;a<subscriptions.size();a++ ){
			boolean old=false;
			// Figure out how much time passed since last data or subscription
			long passed = Instant.now().toEpochMilli()-Math.abs(subsRecStamp.get(a));
			if( ttl>0 && passed > ttl ) // If passed is longer than ttl, consider it old
				old=true;

			// Build the prefix, !! if data is old and alternating color lines
			var prefix = (old?"  !! ":"  ")+(toggle?TelnetCodes.TEXT_YELLOW:TelnetCodes.TEXT_DEFAULT);
			toggle = !toggle;

			// Build the suffix, showing the age of the data or -1 if none yet, color depends on old.
			String suffix;
			if( subsRecStamp.get(a)<0 ) {
				suffix = (old?TelnetCodes.TEXT_RED:TelnetCodes.TEXT_ORANGE)+"[-1]";
			}else{
				suffix = (old ? TelnetCodes.TEXT_RED : "") + "[" + TimeTools.convertPeriodToString(passed, TimeUnit.MILLISECONDS) + "]";
			}

			// Put it all together, add spaces between depending on the length of the longest sub
			join.add(prefix + "==> "+ subscriptions.get(a) + " ".repeat(max-subscriptions.get(a).length() +(old?-3:0)) + suffix + TelnetCodes.TEXT_DEFAULT);
		}
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
				submitConnector(0);
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
            return true;
		} catch (MqttException e) {
			Logger.error(e);
			return false;
		}
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

	/**
	 * Get info about the subs to val relations
	 * @return The info
	 */
	public String getSubStoreInfo(){
		var join = new StringJoiner("");
		boolean toggle=false;
		int topicLength=0;
		int idLength=0;

		// Determine the max length of all values in a column
		for( var set : valReceived.entrySet() ){
			topicLength=Math.max(set.getKey().length(),topicLength);
			idLength=Math.max(set.getValue().id().length(),idLength);
		}
		// Add spacing from longest to next
		topicLength+=3;
		idLength+=3;

		// Header
		join.add( TelnetCodes.TEXT_CYAN+"Topic").add(" ".repeat(topicLength-5))
			.add( "Val ID").add(" ".repeat(idLength-6))
			.add( "Last Value" )
			.add("\r\n");

		//Body with alternating colored lines
        var tops = new ArrayList<>(valReceived.keySet());
		Collections.sort(tops);
		for( var top : tops ){
			var val = valReceived.get(top);
			join.add( (toggle?TelnetCodes.TEXT_DEFAULT:TelnetCodes.TEXT_YELLOW)+top)
				.add( " ".repeat(topicLength-top.length())).add(val.id())
				.add( " ".repeat(idLength-val.id().length())).add(val.toString())
				.add("\r\n");
			toggle = !toggle;
		}
		return join.toString();
	}
	/* ****************************************** R E C E I V I N G  ************************************************ */

	@Override
	public void messageArrived(String topic, MqttMessage message) {

		String load = new String(message.getPayload());	// Get the payload
		if(debug)
			Logger.info("Rec: "+topic+" load:"+load);
		Logger.tag("RAW").warn(id+"\t"+topic+"\t"+load);  // Store it like any other received data

		// Update data timestamps taking wildcards in account
		for( int a=0;a<subscriptions.size();a++ ){
			if( topic.matches(subscriptions.get(a).replace("#",".*")) )
				subsRecStamp.set(a,Instant.now().toEpochMilli());
		}
        if (!targets.isEmpty())
            targets.removeIf(dt -> !dt.writeLine(id, load));

		// Process the message
		var rtval = valReceived.get(topic);

		if( rtval != null ){
			rtval.parseValue(load);
            return;
        }
        if (storeTopic.isEmpty())
            return;

        var key = storeTopic.substring(0, storeTopic.indexOf("#"));
        if (!(key.isEmpty() || topic.startsWith(key)))
            return;
        var split = topic.split("/"); // split it in parts, we only want last two
        if (split.length < 2) {
            Logger.warn(id + "(mqtt) -> Received topic, but less than two elements -> " + topic);
            return;
        }
        var group = split[split.length - 2];
        var name = split[split.length - 1];

		var val = rtvals.getBaseVal(group + "_" + name);
        if (val.isPresent()) {
            valReceived.put(topic, val.get());
            return;
        }
        // Figure out if its int,real or text?
        if (NumberUtils.isParsable(load)) { // So int or real
            if (load.contains(".") || load.contains(",")) { // so real
                var real = RealVal.newVal(group, name);
                real.parseValue(load);
                rtvals.addRealVal(real);
                valReceived.put(topic, real);
                Core.addToQueue(Datagram.system("mqtt:" + id + ",store,real," + real.id() + "," + topic));
            } else { // int
                var i = IntegerVal.newVal(group, name);
                i.parseValue(load);
                rtvals.addIntegerVal(i);
                valReceived.put(topic, i);
                Core.addToQueue(Datagram.system("mqtt:" + id + ",store,int," + i.id() + "," + topic));
            }
        } else { // So text
            var txt = TextVal.newVal(group, name).value(load);
            rtvals.addTextVal(txt);
            valReceived.put(topic, txt);
            Core.addToQueue(Datagram.system("mqtt:" + id + ",store,txt," + txt.id() + "," + topic));
        }
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
            return true;
		}catch( MqttException e){
			Logger.error(e);
			return false;
		}
	}

	/**
	 * Clear all data from the worker to be able to reload it.
	 * @param rtvals RealtimeValues that hold the ones used by this worker.
	 */
	public void clear(Rtvals rtvals) {
		subscriptions.forEach(this::unsubscribe);
		valReceived.values().forEach(rtvals::removeVal);
		targets.clear();
		disconnect();
	}
	@Override
	public void connectionLost(Throwable cause) {
		if (!mqttQueue.isEmpty() && !subscriptions.isEmpty()) {
			Logger.info( id+"(mqtt) -> Connection lost but still work to do, reconnecting...");
			submitConnector(0);
		}else{
			connecting=false;
			Logger.warn( id+"(mqtt) -> "+cause.getMessage() + "->" + cause);
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
				int index = subscriptions.indexOf(sub);
				if( index != -1)
					subsRecStamp.set( index, -1L*Instant.now().toEpochMilli() );
			}
		} catch (MqttException e) {
			Logger.error( id+"(mqtt) -> Failed to subscribe to: "+ subs);
		}
		if( !mqttQueue.isEmpty() )
			submitPublisher();
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

            if (client.isConnected()) {
                Logger.info(id + "(mqtt) -> Client already connected.");
                return;
            }
            try {
                client.connect(connOpts);
                Logger.info(id + "(mqtt) -> Connected");
            } catch (MqttException me) {
                attempt++;
                var time = Math.min(attempt * 25 + 25, 120);
                Logger.warn(id + "(mqtt) -> Failed to connect,  trying again in " + time + "s. Cause: " + me.getMessage());
				submitConnector(attempt);
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
	public boolean writeLine(String origin, String data) {
		var topic = provide.get(origin);
		topic = topic==null?origin.replace("_","/"):topic;
		addWork(topic,data);
		return true;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public boolean isConnectionValid() {
		return true;
	}

}
