package io.mqtt;

import io.Writable;
import io.telnet.TelnetCodes;
import das.Commandable;
import util.data.RealtimeValues;
import org.tinylog.Logger;
import util.xml.XMLdigger;
import util.xml.XMLfab;
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
     * Reload the settings from the settings.xml
     *
     * @return True if this was successful
     */
    public boolean readXMLsettings() {

        // Disconnect current ones if any
        mqttWorkers.values().forEach(MqttWorker::disconnect);
        mqttWorkers.clear(); // Clear to start fresh

        var dig = XMLdigger.goIn(settingsFile,"dcafs","mqtt");
        if( dig.isInvalid())
            return false;

        dig.digOut("broker").forEach( broker -> {
            var id = broker.attr("id","general");
            var addr = broker.peekAt("address").value("");
            var clientid = broker.peekAt("clientid").value("");
            var defTopic = broker.peekAt("defaulttopic").value("");

            var worker = new MqttWorker(id,addr,clientid,defTopic);

            broker.peekOut("subscribe").forEach( sub -> {
                worker.addSubscription(sub.getTextContent(),sub.getAttribute("label"));
            });
            worker.applySettings();
            mqttWorkers.put(id,worker);
        });
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
                            .add(green + "   mqtt:addbroker,id,address,topic " + reg + "-> Add a new broker with the given id found at the address")
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
                case "reload" -> {
                    if (readXMLsettings())
                        return "Settings for " + cmds[0] + " reloaded.";
                    return "! Failed to reload settings.";
                }
                case "test" -> {
                    mqttWorkers.values().forEach( w -> {
                        w.addWork("dice/d20","10");
                    });
                    return "Testing";
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
            if( mqttWorkers.containsKey(cmds[1]))
                return "ID already in use";

            var fab = XMLfab.withRoot(settingsFile,"dcafs","mqtt");
            fab.addParentToRoot("broker").attr("id",cmds[1]); // make broker root
            fab.addChild("address",cmds[2]);
            fab.addChild("defaulttopic",cmds[3]);
            readXMLsettings();
            return "Broker added";
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
                case "send" -> {
                    if (cmds.length != 3)
                        return "! Wrong amount of arguments -> mqtt:id,send,topic:value";
                    if (!cmds[2].contains(":"))
                        return "! No proper topic:value given, got " + cmds[2] + " instead.";

                    String[] topVal = cmds[2].split(":");
                    double val = rtvals.getReal(topVal[1], -999);
                    worker.addWork(topVal[0], String.valueOf(val));
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
