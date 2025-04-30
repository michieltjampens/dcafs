package io.mqtt;

import das.Commandable;
import das.Paths;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.vals.Rtvals;
import util.data.vals.ValFab;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class MqttPool implements Commandable {

    Map<String, MqttWorker> mqttWorkers = new HashMap<>();
    Rtvals rtvals;
    final EventLoopGroup eventLoopGroup;
    ScheduledExecutorService publishService = Executors.newSingleThreadScheduledExecutor(new DefaultThreadFactory("mqtt-publish"));

    public MqttPool(Rtvals rtvals, EventLoopGroup eventLoopGroup) {
        this.rtvals=rtvals;
        this.eventLoopGroup = eventLoopGroup;

        if (!readFromXML())
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
    public boolean readFromXML() {

        // Disconnect current ones if any
        mqttWorkers.values().forEach( worker -> worker.clear(rtvals));
        mqttWorkers.clear(); // Clear to start fresh

        var dig = Paths.digInSettings("mqtt");
        if( dig.isInvalid())
            return false;

        dig.digOut("broker").forEach( broker -> {
            var id = broker.attr("id","general");
            var address = broker.peekAt("address").value("");
            var clientId = broker.peekAt("clientid").value("");

            var worker = new MqttWorker(id, address, clientId, rtvals, eventLoopGroup, publishService);
            var ttl = TimeTools.parsePeriodStringToMillis( broker.attr("ttl",broker.peekAt("ttl").value("")));
            worker.setTTL(ttl);

            broker.peekOut("subscribe").forEach( sub -> worker.addSubscription(sub.getTextContent()) );

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
        var groupName = ValFab.readBasics(rtval, worker.id());
        if( groupName == null )
            return;

        var gn = groupName.group() + "_" + groupName.name();
        if (rtvals.hasBaseVal(gn)) {// doesn't exist yet, add it
            var val = rtvals.getBaseVal(gn);
            val.ifPresent(baseVal -> worker.addSubscription(topic, baseVal));
        }else {
            var val = ValFab.buildVal(rtval, groupName.group(), rtvals);
            if (val != null) {
                if (worker.addSubscription(topic, val) == 0) {
                    Logger.error(worker.id() + " (mqtt) -> Failed to add subscription to " + topic);
                }
            } else {
                Logger.error(worker.id() + " (mqtt) -> Failed to read the rtval " + gn);
            }
        }
    }
    @Override
    public String replyToCommand(Datagram d) {
        String[] args = d.argList();

        if (args.length == 1)
            return doNoArgCmds(args[0], d.getWritable(), d.asHtml());
        if (args[0].equalsIgnoreCase("addbroker"))
            return doAddCmd(args);
        return doArgCmds(d.cmd(), args);
    }
    private String doNoArgCmds( String cmd, Writable wr, boolean html ){
        return switch (cmd) {
            case "?" -> doHelpCmd(html);
            case "brokers" -> getMqttBrokersInfo();
            case "reload" -> readFromXML() ? "Settings reloaded." : "! Failed to reload settings.";
            case "test" -> {
                mqttWorkers.values().forEach( w -> w.addWork("dice/d20","10") );
                yield "Testing";
            }
            default -> {
                var worker = mqttWorkers.get(cmd);
                if (worker == null)
                    yield "! Not a valid id or command: " + cmd;
                if (wr == null) {
                    Logger.error("Not a valid writable asking for " + cmd);
                    yield "! Not a valid writable asking for " + cmd;
                }
                worker.registerWritable(wr);
                yield "Sending data from " + cmd + " to " + wr.id();
            }
        };
    }

    private static String doHelpCmd(boolean html) {
        var help = new StringJoiner("\r\n");
        help.add("The MQTT manager manages the workers that connect to brokers").add("");
        help.add("General")
                .add("mqtt:? -> Show this message")
                .add("mqtt:addbroker,id,address,topic -> Add a new broker with the given id found at the address")
                .add("mqtt:brokers -> Get a listing of the current registered brokers")
                .add("mqtt:id,reload -> Reload the settings for the broker from the xml.");
        help.add("Subscriptions")
                .add("mqtt:brokerid,subscribe,topic -> Subscribe to a topic with given label on given broker. Mqtt wildcard is #.")
                .add("mqtt:brokerid,unsubscribe,topic -> Unsubscribe from a topic on given broker")
                .add("mqtt:brokerid,unsubscribe,all -> Unsubscribe from all topics on given broker");
        help.add("Rtvals")
                .add("mqtt:brokerid,provide,rtval<,topic> -> Provide a certain rtval to the broker, topic is group/name by default.")
                .add("mqtt:brokerid,store,type,topic<,rtval> -> Store a certain topic as a rtval, if no rtval is specified topic is used as rtval id")
                .add("mqtt:brokerid,stores " +"-> Get info on all the active sub to val links")
                .add("mqtt:brokerid,generate,topic -> Generate store entries based on received messages after subscribing to topic.");
        help.add("Send & Receive")
                .add("mqtt:id -> Forwards the data received from the given broker to the issuing writable")
                .add("mqtt:id,send,topic:value -> Sends the value to the topic of the brokerid");
        return LookAndFeel.formatHelpCmd(help.toString(), html);
    }
    private String doAddCmd( String[] cmds ){
        if (cmds.length != 4)
            return "! Wrong amount of arguments -> mqtt:addbroker,id,address,deftopic";
        if( mqttWorkers.containsKey(cmds[1]))
            return "ID already in use";

        var fab = XMLfab.withRoot(Paths.settings(),"dcafs","mqtt");
        fab.addParentToRoot("broker").attr("id",cmds[1]); // make broker root
        fab.addChild("address",cmds[2]);
        fab.addChild("roottopic",cmds[3]);
        fab.build();
        readFromXML(); // reload
        return "Broker added";
    }
    private String doArgCmds( String cmd, String[] args ){
        var worker = mqttWorkers.get(args[0]);
        if( worker == null)
            return "! Not a valid id: "+args[0];

        var dig = XMLdigger.goIn(Paths.settings(),"dcafs","mqtt");
        XMLfab fab;
        if( dig.hasPeek("broker","id",args[0]) ) {
            dig.usePeek();
            var fabOpt = XMLfab.alterDigger(dig);
            if( fabOpt.isEmpty())
                return "! Failed to create fab?";
            fab =fabOpt.get();
        }else{
            return "! No valid broker found in xml";
        }

        return switch (args[1]) {
            case "subscribe" -> doSubscribeCmd(args,worker,fab);
            case "unsubscribe" -> doUnsubscribeCmd(args,worker,fab);
            case "send" -> doSendCmd( args, worker );
            case "provide" -> doProvideCmd(args, fab);
            case "generate" -> doGenerateCmd(args,worker,fab);
            case "stores" -> worker.getSubStoreInfo();
            case "store" -> doStoreCmd( args,fab );
            case "debug" -> {
                if (args.length == 2)
                    yield "Debug enabled: "+worker.isDebugging();
                var deb = Tools.parseBool(args[2],false);
                worker.setDebug(deb);
                yield "Changing debug to "+(deb?"enabled":"disabled");
            }
            default -> {
                Logger.error("(mqtt) -> No such command "+ cmd + ": " + args[0]);
                yield "! No such subcommand in " + cmd + ": " + args[0];
            }
        };
    }
    private String doSubscribeCmd( String[] args,MqttWorker worker, XMLfab fab ){
        if (args.length != 3)
            return "! Wrong amount of arguments -> mqtt:brokerid,subscribe,topic";
        int res = worker.addSubscription(args[2]);
        if (res == 0)
            return "! Failed to add subscription";
        fab.addChild("subscribe").content(args[2]);
        if (fab.build())
            return "Subscription added";
        return "! Failed to add subscription to xml";
    }
    private String doUnsubscribeCmd( String[] args,MqttWorker worker,XMLfab fab ){
        if (args.length != 3)
            return "! Wrong amount of arguments -> mqtt:brokerid,unsubscribe,topic";
        if (!worker.removeSubscription(args[2]))
            return "! Failed to remove subscription, probably typo?";

        if (!fab.removeChild("subscribe", args[2]))
            return "! Failed to remove subscription from xml.";

        fab.build();
        return "Subscription removed";
    }
    private String doSendCmd( String[] args, MqttWorker worker ){
        if (args.length != 3)
            return "! Wrong amount of arguments -> mqtt:id,send,topic:value";
        if (!args[2].contains(":"))
            return "! No proper topic:value given, got " + args[2] + " instead.";

        String[] topVal = args[2].split(":");
        double val = rtvals.getReal(topVal[1], -999);
        worker.addWork(topVal[0], String.valueOf(val));
        return "Data send to " + args[0];
    }
    private String doProvideCmd( String[] args, XMLfab fab ){
        if (args.length < 3)
            return "! Wrong amount of arguments -> mqtt:id,provide,rtval<,topic>";
        var val = rtvals.getBaseVal(args[2]);
        if( val.isEmpty() )
            return "! No such rtval: "+args[2];
        var topic = args.length==4?args[3]:"";

        fab.alterChild("provide").down();
        fab.addChild("rtval").content(args[2]);
        if( !topic.isEmpty() )
            fab.attr("topic",topic);
        fab.build();
        return "Provide added";
    }
    private String doGenerateCmd(String[] args, MqttWorker worker,XMLfab fab){
        if (args.length < 3)
            return "! Wrong amount of arguments -> mqtt:id,generate,topic";
        //cmds[2] += cmds[2].endsWith("#")?"":"#"; // Make sure it ends on the wildcard
        fab.alterChild("store").attr("generate",args[2]).build();
        worker.setGenerateStore(args[2]);
        if( args[2].endsWith("#"))
            return "Generating store on receiving topic updates.";
        return "Generating store on receiving topic updates, Note: no wildcard in topic!";
    }
    private String doStoreCmd( String[] args, XMLfab fab ){
        if (args.length < 4)
            return "! Wrong amount of arguments -> mqtt:id,store,type,rtval<,topic>";

        var topic = args.length==5?args[4]:args[3].replace("_","/");
        var group = args[3].contains("_")?args[3].substring(0,args[3].indexOf("_")):"";
        var name =  args[3].contains("_")?args[3].substring(args[3].indexOf("_")+1):args[3];

        fab.alterChild("store").down();
        fab.selectOrAddChildAsParent("group","id",group);
        fab.addChild(args[2]).attr("name",name).down();
        fab.addChild("topic",topic);
        fab.build();
        return "Store added";
    }
    @Override
    public boolean removeWritable(Writable wr) {
        int cnt=0;
        for (MqttWorker worker : mqttWorkers.values())
            cnt += worker.removeWritable(wr)?1:0;

        return cnt!=0;
    }
}
