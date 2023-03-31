package io.mqtt;

import io.Writable;
import io.telnet.TelnetCodes;
import das.Commandable;
import util.data.RealtimeValues;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;

public class MqttPool implements Commandable, MqttWriting {

    Map<String, MqttWorker> mqttWorkers = new HashMap<>();
    Path settingsFile;
    RealtimeValues rtvals;
    BlockingQueue<Datagram> dQueue;

    public MqttPool(Path settingsFile, RealtimeValues rtvals, BlockingQueue<Datagram> dQueue ){
        this.settingsFile=settingsFile;
        this.rtvals=rtvals;
        this.dQueue=dQueue;

        if( !readXMLsettings() )
            Logger.info("No MQTT settings in settings.xml");
    }
    public boolean sendToBroker( String id, String device, String param, double value) {
        MqttWorker worker = mqttWorkers.get(id);

        if (worker != null) {
            if (value != -999) {
                return worker.addWork( new MqttWork(device, param, value) );
            }
        }
        return false;
    }
    /**
     * Get The @see MQTTWorker based on the given id
     *
     * @param id The id of the MQTT worker requested
     * @return The worder requested or null if not found
     */
    public Optional<MqttWorker> getMqttWorker(String id) {
        return Optional.ofNullable( mqttWorkers.get(id) );
    }

    /**
     * Get a list of all the MQTTWorker id's
     *
     * @return Set of all the id's
     */
    public Set<String> getMqttWorkerIDs() {
        return mqttWorkers.keySet();
    }

    /**
     * Get a descriptive listing of the current brokers/workers and their
     * subscriptions
     *
     * @return The earlier mentioned descriptive listing
     */
    public String getMqttBrokersInfo() {
        StringJoiner join = new StringJoiner("\r\n", "id -> broker -> online?\r\n", "");
        join.setEmptyValue("No brokers yet");
        mqttWorkers.forEach((id, worker) -> join
                .add(id + " -> " + worker.getBrokerAddress() + " -> " + (worker.isConnected() ? "online" : "offline"))
                .add(worker.getSubscriptions("\r\n")));
        return join.toString();
    }

    /**
     * Adds a subscription to a certain MQTTWorker
     *
     * @param id    The id of the worker to add it to
     * @param label The label associated wit the data, this will be given to @see
     *              BaseWorker when data is recevied
     * @param topic The topic to subscribe to
     * @return True if a subscription was successfully added
     */
    public boolean addMQTTSubscription(String id, String label, String topic) {
        MqttWorker worker = mqttWorkers.get(id);
        if (worker == null)
            return false;
        return worker.addSubscription(topic, label);
    }
    public boolean addBroker( String id, String address, String defTopic){
        mqttWorkers.put( id, new MqttWorker(address,defTopic,dQueue) );
        return updateMQTTsettings(id);
    }
    /**
     * Remove a subscription from a certain MQTTWorker
     *
     * @param id    The id of the worker
     * @param topic The topic to remove
     * @return True if it was removed, false if it wasn't either because not found
     *         or no such worker
     */
    public boolean removeMQTTSubscription(String id, String topic) {
        MqttWorker worker = mqttWorkers.get(id);
        if (worker == null)
            return false;
        return worker.removeSubscription(topic);
    }

    /**
     * Update the settings in the xml for a certain MQTTWorker based on id
     *
     * @param id The worker of which the settings need to be altered
     * @return True if updated
     */
    public boolean updateMQTTsettings(String id) {
        XMLfab fab = XMLfab.withRoot(settingsFile,"settings")
                            .selectOrAddChildAsParent("mqtt")
                            .down();

        MqttWorker worker = mqttWorkers.get(id);
        if (worker != null ){
            worker.updateXMLsettings(fab, true);
            return true;
        }
        return false;
    }

    /**
     * Reload the settings for a certain MQTTWorker from the settings.xml
     *
     * @param id The worker for which the settings need to be reloaded
     * @return True if this was successful
     */
    public boolean reloadMQTTsettings(String id) {
        MqttWorker worker = mqttWorkers.get(id);
        if (worker == null)
            return false;

        var mqttOpt = XMLtools.getFirstElementByTag( settingsFile, "mqtt");

        if( mqttOpt.isEmpty())
            return false;

        for (Element broker : XMLtools.getChildElements(mqttOpt.get(), "broker")) {
            if (XMLtools.getStringAttribute(broker, "id", "general").equals(id)) {
                worker.readSettings(broker);
                return true;
            }
        }
        return false;
    }
    /**
     * Reload the settings from the settings.xml
     *
     * @return True if this was successful
     */
    public boolean readXMLsettings() {

        var mqttOpt = XMLtools.getFirstElementByTag(settingsFile, "mqtt");

        if( mqttOpt.isEmpty())
            return false;

        for (Element broker : XMLtools.getChildElements(mqttOpt.get(), "broker")) {
            String id = XMLtools.getStringAttribute(broker, "id", "general");
            Logger.info("Adding MQTT broker called " + id);
            mqttWorkers.put(id, new MqttWorker(broker, dQueue));
        }
        return true;
    }

    @Override
    public String replyToCommand(String cmd, String args, Writable wr, boolean html) {
        String[] cmds = args.split(",");
        String nl = html ? "<br>" : "\r\n";

        String cyan = html?"":TelnetCodes.TEXT_CYAN;
        String green=html?"":TelnetCodes.TEXT_GREEN;
        String reg=html?"":TelnetCodes.TEXT_DEFAULT+TelnetCodes.UNDERLINE_OFF;

        if( cmds.length==1 ) {
            switch (cmds[0]) {
                case "?" -> {
                    StringJoiner join = new StringJoiner(nl);
                    join.add(TelnetCodes.TEXT_RED + "Purpose" + reg);
                    join.add("The MQTT manager manages the workers that connect to brokers").add("");
                    join.add(cyan + "General" + reg)
                            .add(green + "   mqtt:?" + reg + " -> Show this message")
                            .add(green + "   mqtt:addbroker,id,address " + reg + "-> Add a new broker with the given id found at the address")
                            .add(green + "   mqtt:brokers " + reg + "-> Get a listing of the current registered brokers")
                            .add(green + "   mqtt:id,reload " + reg + "-> Reload the settings for the broker from the xml.")
                            .add(green + "   mqtt:id,store" + reg + " -> Store the current settings of the broker to the xml.");
                    join.add(cyan + "Subscriptions" + reg)
                            .add(green + "   mqtt:id,subscribe,label,topic " + reg + "-> Subscribe to a topic with given label on given broker")
                            .add(green + "   mqtt:id,unsubscribe,topic " + reg + "-> Unsubscribe from a topic on given broker")
                            .add(green + "   mqtt:id,unsubscribe,all " + reg + "-> Unsubscribe from all topics on given broker");
                    join.add(cyan + "Send & Receive" + reg)
                            .add(green + "   mqtt:id " + reg + "-> Forwards the data received from the given broker to the issuing writable")
                            .add(green + "   mqtt:id,send,topic:value " + reg + "-> Sends the value to the topic of the brokerid");
                    return join.toString();
                }
                case "brokers" -> {
                    return getMqttBrokersInfo();
                }
                default -> {
                    var worker = mqttWorkers.get(cmds[0]);
                    if (worker == null)
                        return "! Not a valid id or command: " + cmds[0];
                    if (wr == null) {
                        Logger.error("Not a valid writable asking for " + cmds[0]);
                        return "! Not a valid writable asking for " + cmds[0];
                    } worker.registerWritable(wr);
                    return "Sending data from " + cmds[0] + " to " + wr.id();
                }
            }
        }else if( cmds[0].equalsIgnoreCase("addbroker")){
            if (cmds.length != 4)
                return "! Wrong amount of arguments -> mqtt:addbroker,id,address,deftopic";
            return addBroker(cmds[1], cmds[2], cmds[3]) ? "Broker added" : "! Failed to add broker";
        }else{
            var worker = mqttWorkers.get(cmds[0]);
            if( worker == null)
                return "! Not a valid id: "+cmds[0];

            switch (cmds[1]) {
                case "subscribe" -> {
                    if (cmds.length != 4)
                        return "! Wrong amount of arguments -> mqtt:id,subscribe,label,topic";
                    if( worker.addSubscription(cmds[3], cmds[2]) )
                        return "Subscription added, send 'mqtt:store," + cmds[0] + "' to save settings to xml";
                    return "! Failed to add subscription";
                }
                case "unsubscribe" -> {
                    if (cmds.length != 3)
                        return "! Wrong amount of arguments -> mqtt:unsubscribe,brokerid,topic";
                    if( worker.removeSubscription(cmds[2]))
                        return "Subscription removed, send 'mqtt:"+cmds[0]+",store' to save settings to xml";
                    return "! Failed to remove subscription, probably typo?";
                }
                case "reload" -> {
                    if (reloadMQTTsettings(cmds[0]))
                        return "Settings for " + cmds[0] + " reloaded.";
                    return "! Failed to reload settings.";
                }
                case "store" -> {
                    updateMQTTsettings(cmds[0]);
                    return "Settings updated";
                }
                case "send" -> {
                    if (cmds.length != 3)
                        return "! Wrong amount of arguments -> mqtt:id,send,topic:value";
                    if (!cmds[2].contains(":"))
                        return "! No proper topic:value given, got " + cmds[2] + " instead.";

                    String[] topVal = cmds[2].split(":");
                    double val = rtvals.getReal(topVal[1], -999);
                    worker.addWork(topVal[0], "" + val);
                    return "Data send to " + cmds[0];
                }
                default -> {
                    return "! No such subcommand in " + cmd + ": " + cmds[0];
                }
            }
        }
    }

    @Override
    public boolean removeWritable(Writable wr) {
        int cnt=0;
        for( MqttWorker worker:mqttWorkers.values()){
            cnt += worker.removeWritable(wr)?1:0;
        }
        return cnt!=0;
    }
}
