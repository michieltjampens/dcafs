package util.tasks;

import das.Commandable;
import das.Paths;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.data.RealtimeValues;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;

public class BlockManager implements Commandable, Writable {
    HashMap<String, AbstractBlock> starters = new HashMap<>();
    ArrayList<AbstractBlock> startup = new ArrayList<>();
    EventLoopGroup eventLoop;
    BlockingQueue<Datagram> dQueue;
    RealtimeValues rtvals;

    public BlockManager(EventLoopGroup eventLoop, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals) {
        this.eventLoop = eventLoop;
        this.dQueue = dQueue;
        this.rtvals = rtvals;

        parseXML(XMLdigger.goIn(Paths.storage().resolve("tmscripts").resolve("blocks.xml"), "dcafs", "tasklist"));

        Logger.info("Starting startups");
        startup.forEach(AbstractBlock::start);
    }

    public void addStarter(AbstractBlock start) {
        if (start.id().isEmpty()) { // No id means it's not addressed but run at startup
            start.id("startup" + startup.size());
            startup.add(start);
        } else {
            starters.put(start.id(), start);
        }
        Logger.info(start.getInfo(new StringJoiner("\r\n"), ""));
    }

    public void parseXML(XMLdigger dig) {
        if (dig.hasPeek("tasksets")) {
            dig.digDown("tasksets");
            // Process taskset
            for (var taskset : dig.digOut("taskset")) {
                var id = taskset.attr("id", "");
                var type = taskset.attr("type", "oneshot").split(":", 2);
                var info = taskset.attr("info", "");
                var start = new OriginBlock(id).setInfo(info);
                var isStep = type[0].equalsIgnoreCase("step");

                if (!isStep) {  // Everything starts at once so add splitter
                    start.addNext(new SplitBlock(eventLoop).setInterval(type.length == 2 ? type[1] : ""));
                    for (var task : taskset.digOut("task"))
                        start.addNext(processTask(task, null));
                } else {
                    for (var task : taskset.digOut("task")) {
                        processTask(task, start);
                        if (type.length == 2) {
                            start.addNext(new DelayBlock(eventLoop).useDelay(type[1]));
                        }
                    }
                }
                addStarter(start);
            }
            dig.goUp();
        }
        dig.digDown("tasks");
        for (var task : dig.digOut("task"))
            addStarter(processTask(task, null));
    }

    /**
     * Processes a task node
     *
     * @param task  Digger pointing to the node
     * @param start The block to attach to
     * @return The resulting block
     */
    private AbstractBlock processTask(XMLdigger task, AbstractBlock start) {
        var id = task.attr("id", "");
        if (start == null)
            start = new OriginBlock(id);

        var req = task.attr("req", "");
        // Check if it's delayed
        handleDelay(task, start);

        // Handle req
        if (!req.isEmpty())
            start.addNext(new ConditionBlock(rtvals).setCondition(req));

        // Handle target
        handleTarget(task, start);

        return start;
    }

    /**
     * Parses and processes the delay information in a task node
     * @param task Digger pointing to the node
     * @param start The block to attach to
     */
    private void handleDelay(XMLdigger task, AbstractBlock start) {
        var delay = task.attr("delay", "0s");
        var interval = task.attr("interval", "");
        var repeats = task.attr("repeats", -1);

        if (!delay.equals("0s")) {
            start.addNext(new DelayBlock(eventLoop).useDelay(delay));
        } else if (!interval.isEmpty()) {
            var periods = interval.split(";");
            var initial = periods[0];
            var recurring = periods.length == 2 ? periods[1] : periods[0];
            start.addNext(new DelayBlock(eventLoop).useInterval(initial, recurring, repeats));
        }
    }

    /**
     * Parses and processes the type attribute of a task node
     * @param task Digger pointing to the node
     * @param start The block to attach to
     */
    private void handleTarget(XMLdigger task, AbstractBlock start) {
        var target = task.attr("output", "system").split(":",2);

        var content = task.value("");

        switch (target[0]) {
            case "system" -> start.addNext(new CmdBlock(dQueue).setCmd(content));
            case "stream", "file" ->
                    start.addNext(new WritableBlock(dQueue).setMessage(target[0] + ":" + target[1], content));
            case "email" -> {
                var subs = content.split(";", 2);
                start.addNext(new EmailBlock(dQueue, target[1]).subject(subs[0]).content(subs[1]));
            }
            case "manager" -> start.addNext(new ControlBlock(this).setMessage(content));
        }
    }

    @Override
    public boolean writeLine(String origin, String data) {
        String[] cmd = data.split(":");
        var task = starters.get(cmd[1]);
        if (task == null) {
            Logger.error(id() + " -> Got a command for " + cmd[1] + " but that doesn't exist");
            return false;
        }
        switch (cmd[0]) {
            case "start" -> eventLoop.submit(task::start);
            case "stop" -> eventLoop.submit(task::reset);
            default -> Logger.error(id() + " -> No such command yet: " + cmd[0]);
        }
        return false;
    }

    @Override
    public String replyToCommand(String cmd, String args, Writable wr, boolean html) {
        return "";
    }

    @Override
    public String payloadCommand(String cmd, String args, Object payload) {
        return "";
    }

    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }

    @Override
    public boolean writeString(String data) {
        return false;
    }

    @Override
    public boolean writeLine(String data) {
        return false;
    }

    @Override
    public boolean writeBytes(byte[] data) {
        return false;
    }

    @Override
    public String id() {
        return "";
    }

    @Override
    public boolean isConnectionValid() {
        return false;
    }

    @Override
    public Writable getWritable() {
        return null;
    }

    @Override
    public boolean giveObject(String info, Object object) {
        return false;
    }
}
