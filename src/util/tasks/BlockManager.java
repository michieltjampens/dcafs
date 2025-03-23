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

public class BlockManager implements Commandable {
    HashMap<String, AbstractBlock> starters = new HashMap<>();
    ArrayList<AbstractBlock> startup = new ArrayList<>();
    EventLoopGroup eventLoop;
    BlockingQueue<Datagram> dQueue;
    RealtimeValues rtvals;

    public BlockManager(EventLoopGroup eventLoop, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals) {
        this.eventLoop = eventLoop;
        this.dQueue = dQueue;
        this.rtvals = rtvals;

        //createTestChain();
        //Logger.info( starters.get("test").getInfo(new StringJoiner("\r\n")) );
        //starters.get("test").start();
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

    public void createTestChain() {
        var start = new DelayBlock("test", eventLoop).useDelay("5s");
        addStarter(start);
        var hello = new WritableBlock(dQueue)
                .setMessage("stream:dice", "Hello World?").setAttempts(2)
                .setFailure(new WritableBlock(dQueue).setMessage("stream:dice", "goodbye :("));
        start.addNext(hello)
                .addNext(new ReadingBlock(eventLoop, dQueue).setMessage("stream:dice", "yes?", "5s").setFailure(hello));
        start.addNext(new ConditionBlock(rtvals).setCondition("{i:dice_rolled} above 10"));
        start.addNext(new WritableBlock(dQueue).setMessage("stream:dice", "Rolled above 10!"));
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

    private void handleTarget(XMLdigger task, AbstractBlock start) {
        var target = task.attr("output", "system");
        var content = task.value("");

        if (target.equals("system")) {
            start.addNext(new CmdBlock(dQueue).setCmd(content));
        } else if (target.startsWith("stream") || target.startsWith("file")) {
            start.addNext(new WritableBlock(dQueue).setMessage(target, content));
        }
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
}
