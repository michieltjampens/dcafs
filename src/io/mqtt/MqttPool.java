package io.mqtt;

import io.Writable;
import io.telnet.TelnetCodes;
import das.Commandable;
import util.data.AbstractVal;
import util.data.RealtimeValues;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;

public class MqttPool implements Commandable {

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
        StringJoiner join = new StringJoiner("\r\n");
        join.setEmptyValue("No brokers yet");
        mqttWorkers.forEach( (id, worker) -> join
                .add( (worker.isConnected() ? "" : "!! ")+id + " -> " + worker.getBrokerAddress() )
                .add( worker.getSubscriptions("\r\n")) );
        return join.toString();
    }
    /**
     * Reload the settings from the settings.xml
     *
     * @return True if this was successful
     */
    public boolean readXMLsettings() {

        // Disconnect current ones if any
        mqttWorkers.values().forEach( worker -> worker.clear(rtvals));
        mqttWorkers.clear(); // Clear to start fresh

        var dig = XMLdigger.goIn(settingsFile,"dcafs","mqtt");
        if( dig.isInvalid())
            return false;

        dig.digOut("broker").forEach( broker -> {
            var id = broker.attr("id","general");
            var addr = broker.peekAt("address").value("");
            var clientid = broker.peekAt("clientid").value("");

            var worker = new MqttWorker( id, addr, clientid, rtvals, dQueue );
            var ttl = TimeTools.parsePeriodStringToMillis( broker.attr("ttl",broker.peekAt("ttl").value("")));
            worker.setTTL(ttl);

            broker.peekOut("subscribe").forEach( sub -> {
                worker.addSubscription(sub.getTextContent());
            });

            if( broker.hasPeek( "store")){
                broker.digDown("store");
                worker.setGenerateStore(broker.attr("generate",""));

                broker.digOut("*").forEach( rtval -> {
                    if( rtval.tagName("").equalsIgnoreCase("group")){
                        rtval.digOut("*").forEach( val -> processVal(worker,val) );
                    }else{
                        processVal(worker,rtval);
                    }
                });
                broker.goUp();
            }
            broker.digDown("provide");
            if( broker.isValid() ){
                broker.peekOut("rtval").forEach( sub -> {
                    if( !rtvals.addRequest(worker,sub.getTextContent())) {
                        Logger.error(id + " -> Tried requesting " + sub.getTextContent() + ", but no such rtval.");
                    }else{
                        var topic = sub.getAttribute("provide");
                        if( !topic.isEmpty())
                            worker.addProvide(topic,sub.getTextContent());
                    }
                });
            }
            worker.applySettings();
            mqttWorkers.put(id,worker);
        });
        return true;
    }
    private void processVal( MqttWorker worker, XMLdigger rtval ){
        var topic = rtval.attr("topic",rtval.peekAt("topic").value("")); // Attr or val
        if( topic.isEmpty() ) {
            Logger.error(worker.id() + "(mqtt) -> No valid topic");
            return;
        }
        var groupName = AbstractVal.readGroupAndName(rtval.currentTrusted(),worker.id());
        if( groupName == null )
            return;

        var gn = groupName[0]+"_"+groupName[1];
        if(rtvals.hasAbstractVal(gn) ) {// doesn't exist yet, add it
            var val = rtvals.getAbstractVal( gn);
            val.ifPresent(abstractVal -> worker.addSubscription(topic, abstractVal));
        }else {
            var valOpt = rtvals.processRtvalElement(rtval.currentTrusted(), groupName[0]);
            if ( valOpt.isPresent()) {
                if (worker.addSubscription(topic, valOpt.get()) == 0) {
                    Logger.error(worker.id() + " (mqtt) -> Failed to add subscription to " + topic);
                }
            } else {
                Logger.error(worker.id() + " (mqtt) -> Failed to read the rtval " + gn);
            }
        }
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
                    join.add("Terminology info: https://www.freertos.org/mqtt/mqtt_terminology.html");
                    join.add(cyan + "General" + reg)
                            .add(green + "   mqtt:?" + reg + " -> Show this message")
                            .add(green + "   mqtt:addbroker,id,address,topic " + reg + "-> Add a new broker with the given id found at the address")
                            .add(green + "   mqtt:brokers " + reg + "-> Get a listing of the current registered brokers")
                            .add(green + "   mqtt:id,reload " + reg + "-> Reload the settings for the broker from the xml.");
                    join.add(cyan + "Subscriptions" + reg)
                            .add(green + "   mqtt:brokerid,subscribe,topic " + reg + "-> Subscribe to a topic with given label on given broker. Mqtt wildcard is #.")
                            .add(green + "   mqtt:brokerid,unsubscribe,topic " + reg + "-> Unsubscribe from a topic on given broker")
                            .add(green + "   mqtt:brokerid,unsubscribe,all " + reg + "-> Unsubscribe from all topics on given broker");
                    join.add(cyan + "Rtvals" + reg)
                            .add(green + "   mqtt:brokerid,provide,rtval<,topic> " + reg + "-> Provide a certain rtval to the broker, topic is group/name by default.")
                            .add(green + "   mqtt:brokerid,store,type,topic<,rtval> " + reg + "-> Store a certain topic as a rtval, if no rtval is specified topic is used as rtval id")
                            .add(green + "   mqtt:brokerid,stores "+ reg +"-> Get info on all the active sub to val links")
                            .add(green + "   mqtt:brokerid,generate,topic "+reg+"-> Generate store entries based on received messages after subscribing to topic.");
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
                        return "Settings reloaded.";
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
                    }
                    worker.registerWritable(wr);
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
            fab.addChild("roottopic",cmds[3]);
            fab.build();
            readXMLsettings();
            return "Broker added";
        }else{
            var worker = mqttWorkers.get(cmds[0]);
            if( worker == null)
                return "! Not a valid id: "+cmds[0];

            var dig = XMLdigger.goIn(settingsFile,"dcafs","mqtt");
            XMLfab fab;
            if( dig.hasPeek("broker","id",cmds[0]) ) {
                dig.usePeek();
                var fabOpt = XMLfab.alterDigger(dig);
                if( fabOpt.isEmpty())
                    return "! Failed to create fab?";
                fab =fabOpt.get();
            }else{
                return "! No valid broker found in xml";
            }

            switch (cmds[1]) {
                case "subscribe" -> {
                    if (cmds.length != 3)
                        return "! Wrong amount of arguments -> mqtt:brokerid,subscribe,topic";
                    int res = worker.addSubscription(cmds[2]);
                    if(  res != 0 ) {
                        fab.addChild("subscribe").content(cmds[2]);
                        if( fab.build() )
                            return "Subscription added";
                        return "! Failed to add subscription to xml";
                    }
                    return "! Failed to add subscription";
                }
                case "unsubscribe" -> {
                    if (cmds.length != 3)
                        return "! Wrong amount of arguments -> mqtt:brokerid,unsubscribe,topic";
                    if( worker.removeSubscription(cmds[2])) {
                        if( fab.removeChild("subscribe",cmds[2])){
                            fab.build();
                            return "Subscription removed";
                        }
                        return "! Failed to remove subscription from xml.";
                    }
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
                case "provide" ->{
                    if (cmds.length < 3)
                        return "! Wrong amount of arguments -> mqtt:id,provide,rtval<,topic>";
                    var val = rtvals.getAbstractVal(cmds[2]);
                    if( val.isEmpty() )
                        return "! No such rtval: "+cmds[2];
                    var topic = cmds.length==4?cmds[3]:"";

                    fab.alterChild("provide").down();
                    fab.addChild("rtval").content(cmds[2]);
                    if( !topic.isEmpty() )
                        fab.attr("topic",topic);
                    fab.build();
                    return "Provide added";
                }
                case "generate" -> {
                    if (cmds.length < 3)
                        return "! Wrong amount of arguments -> mqtt:id,generate,topic";
                    //cmds[2] += cmds[2].endsWith("#")?"":"#"; // Make sure it ends on the wildcard
                    fab.alterChild("store").attr("generate",cmds[2]).build();
                    worker.setGenerateStore(cmds[2]);
                    if( cmds[2].endsWith("#"))
                        return "Generating store on receiving topic updates.";
                    return "Generating store on receiving topic updates, Note: no wildcard in topic!";
                }
                case "stores" -> {
                    return worker.getSubStoreInfo();
                }
                case "store" ->{
                    if (cmds.length < 4)
                        return "! Wrong amount of arguments -> mqtt:id,store,type,rtval<,topic>";

                    var topic = cmds.length==5?cmds[4]:cmds[3].replace("_","/");
                    var group = cmds[3].contains("_")?cmds[3].substring(0,cmds[3].indexOf("_")):"";
                    var name =  cmds[3].contains("_")?cmds[3].substring(cmds[3].indexOf("_")+1):cmds[3];

                    fab.alterChild("store").down();
                    fab.selectOrAddChildAsParent("group","id",group);
                    fab.addChild(cmds[2]).attr("name",name).down();
                    fab.addChild("topic",topic);
                    fab.build();
                    return "Store added";
                }
                case "debug" -> {
                    if (cmds.length == 2)
                        return "Debug enabled: "+worker.isDebugging();
                    var deb = Tools.parseBool(cmds[2],false);
                    worker.setDebug(deb);
                    return "Changing debug to "+(deb?"enabled":"disabled");
                }
                default -> {
                    Logger.error("(mqtt) -> No such command "+ cmd + ": " + cmds[0]);
                    return "! No such subcommand in " + cmd + ": " + cmds[0];
                }
            }
        }
    }
    public String payloadCommand( String cmd, String args, Object payload){
        return "! No such cmds in "+cmd;
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
