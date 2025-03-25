package util.tasks;

import das.Commandable;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.RealtimeValues;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringJoiner;

public class BlockManager implements Commandable, Writable {
    HashMap<String, AbstractBlock> starters = new HashMap<>();
    ArrayList<AbstractBlock> startup = new ArrayList<>();
    EventLoopGroup eventLoop;
    RealtimeValues rtvals;
    Path scriptPath;
    String id;

    public BlockManager(String id, EventLoopGroup eventLoop, RealtimeValues rtvals) {
        this.eventLoop = eventLoop;
        this.rtvals = rtvals;
        this.id = id;
    }

    public void setScriptPath(Path scriptPath) {
        this.scriptPath = scriptPath;
        parseXML(XMLdigger.goIn(scriptPath, "dcafs", "tasklist"));
    }

    public Path getScriptPath() {
        return scriptPath;
    }

    public void start() {
        Logger.info(id + " -> Starting startups");
        startup.forEach(AbstractBlock::start);
    }
    public void addStarter(AbstractBlock start) {
        if (start == null)
            return;
        if (start.id().isEmpty()) { // No id means it's not addressed but run at startup
            start.id("startup" + startup.size());
            startup.add(start);
        } else {
            starters.put(start.id(), start);
        }
    }

    public void parseXML(XMLdigger dig) {
        if (dig.hasPeek("tasksets")) {
            dig.digDown("tasksets");
            AbstractBlock onFailure = null;
            // Process taskset
            for (var taskset : dig.digOut("taskset")) {
                var id = taskset.attr("id", "");
                var type = taskset.attr("type", "oneshot").split(":", 2);
                var info = taskset.attr("info", "");
                var start = new OriginBlock(id).setInfo(info);
                var isStep = type[0].equalsIgnoreCase("step");

                if (!isStep) {  // Everything starts at once so add splitter
                    start.addNext(new SplitBlock(eventLoop).setInterval(type.length == 2 ? type[1] : ""));
                    for (var task : taskset.digOut("task")) {
                        if (start.addNext(processTask(task, null)) == null) {
                            Logger.error(id + "(tm) -> Issue processing task, aborting.");
                            return;
                        }
                    }
                } else {
                    for (var task : taskset.digOut("*")) {
                        switch (task.tagName("")) {
                            case "task" -> {
                                processTask(task, start);
                                if (onFailure != null) {
                                    onFailure.setFailureBlock(start.getLastBlock());
                                    onFailure = null;
                                }
                                if (type.length == 2)
                                    start.addNext(new DelayBlock(eventLoop).useDelay(type[1]));
                            }
                            case "retry" -> {
                                var result = parseRetry(task, start);
                                if (result == null)
                                    return;
                                if (result[1] != null) {
                                    onFailure = result[1];
                                }
                            }
                            case "while" -> {

                            }
                            default -> start.addNext(parseNode(task));
                        }
                    }
                }
                start.updateChainId();
                addStarter(start);
            }
            //dig.goUp(); // reverse the digout
            //dig.goUp(); // Go out of tasksets
        }
        dig.goUp("tasklist");
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

        // Handle req
        var reqAttr = task.attr("req", "");
        ConditionBlock req = null;
        if (!reqAttr.isEmpty())
            req = new ConditionBlock(rtvals).setCondition(reqAttr);

        // Handle target
        var target = handleTarget(task);
        if (target == null) {
            Logger.error(this.id + "(tm) -> Task '" + id + "' needs a valid target!");
            return null;
        }
        // Check if it's delayed
        if (handleDelayAndCombine(task, start, req, target))
            return start;
        return null;
    }

    /**
     * Parses and processes the delay information in a task node
     * @param task Digger pointing to the node
     * @param start The block to attach to
     */
    private boolean handleDelayAndCombine(XMLdigger task, AbstractBlock start, ConditionBlock req, AbstractBlock target) {
        var delay = task.attr("delay", "-1s");
        var interval = task.attr("interval", "");
        var whileDelay = task.attr("while", "");

        var block = new DelayBlock(eventLoop);

        if (!delay.equals("-1s")) {
            block.useDelay(delay);
        } else if (!interval.isEmpty()) {
            var periods = Tools.splitList(interval);
            var initial = periods[0];
            var recurring = periods.length == 2 ? periods[1] : periods[0];
            var repeats = task.attr("repeats", -1);

            block.useInterval(initial, recurring, repeats);
        } else if (!whileDelay.isEmpty()) { // While loop returns to delay block after target if successful
            var split = Tools.splitList(whileDelay);
            if (split.length != 2) {
                Logger.error(id + "(tm) -> Missing period in " + whileDelay);
                return false;
            }
            int retries = NumberUtils.toInt(split[1]);
            block.useInterval(split[0], split[1], retries);
            target.addNext(block); // Make sure the target returns to the delay
        } else { // Without delay
            if (req != null) // Add the req if any
                start.addNext(req);
            start.addNext(target); // Add the target
            return true;
        }
        // Common
        start.addNext(block);
        if (req != null) // Add the req if any
            start.addNext(req);
        start.addNext(target); // Add the target
        return true;
    }

    /**
     * Parses and processes the type attribute of a task node
     * @param task Digger pointing to the node
     */
    private AbstractBlock handleTarget(XMLdigger task) {
        var target = task.attr("output", "system").split(":",2);
        var content = task.value("");

        return switch (target[0]) {
            case "system", "cmd" -> new CmdBlock().setCmd(content);
            case "stream", "file" -> new WritableBlock().setMessage(target[0] + ":" + target[1], content);
            case "email" -> {
                var subs = content.split(";", 2);
                yield new EmailBlock( target[1] ).subject(subs[0]).content(subs[1]);
            }
            case "manager" -> new ControlBlock(this).setMessage(content);
            case "telnet" -> new CmdBlock().setCmd("telnet:broadcast," + target[1] + "," + content);
            default -> {
                Logger.error(id + " (tm) -> Unknown target " + target[0]);
                yield null;
            }
        };
    }

    private AbstractBlock[] parseRetry(XMLdigger task, AbstractBlock prev) {
        int retries = task.attr("retries", -1);
        String onFail = task.attr("onfail", "stop");

        AbstractBlock first = null;
        var counter = new CounterBlock(retries);

        if (task.hasChilds()) {
            // Do the internal ones
            for (var node : task.digOut("*"))
                first = addToOrBecomeFirst(first, counter, parseNode(node));

            if (first == null)
                return null;

            var last = first.getLastBlock();
            prev.addNext(first);
            if (onFail.equalsIgnoreCase("stop"))
                return new AbstractBlock[]{last, null};
            return new AbstractBlock[]{last, counter};
        }
        // No child nodes
        // <retry interval="20s" retries="-1">{f:icos_sol4} equals 1</retry>
        var interval = task.attr("interval", "0s");
        var condition = task.value("");

        var cond = new ConditionBlock(rtvals).setCondition(condition);
        var delay = new DelayBlock(eventLoop).useDelay(interval);
        cond.setFailureBlock(delay); // If condition fails, go to delay
        delay.setNext(counter);      // After delay go to the counter
        counter.setNext(cond);       // After counter try condition again

        prev.addNext(cond);          // Add these blocks to the chain
        return new AbstractBlock[]{null, null}; // nothing special, order is maintained
    }

    private AbstractBlock parseNode(XMLdigger node) {
        var content = node.value("");
        return switch (node.tagName("")) {
            case "delay" -> new DelayBlock(eventLoop).useDelay(content);
            case "req" -> new ConditionBlock(rtvals).setCondition(content);
            case "stream" -> {
                var id = node.attr("to", "");
                yield new WritableBlock().setMessage(id, content);
            }
            case "email" -> {
                var to = node.attr("to", "");
                var subject = node.attr("subject", "");

                yield new EmailBlock(to).subject(subject).content(content);
            }
            case "cmd", "system" -> new CmdBlock().setCmd(content);
            case "receive" -> {
                var from = node.attr("from", "");
                var timeout = node.attr("timeout", "0s");
                yield new ReadingBlock(eventLoop).setMessage(from, content, timeout);
            }
            default -> {
                Logger.error("Unknown tag:" + node.tagName(""));
                yield null;
            }
        };
    }

    private AbstractBlock addToOrBecomeFirst(AbstractBlock first, AbstractBlock failure, AbstractBlock added) {
        if (first == null) {
            first = added;
            first.setFailureBlock(failure);
            failure.setNext(first); // After the failure process, go to  the first block again
        } else {
            added.setFailureBlock(failure);
            first.addNext(added);
        }
        return first;
    }

    public boolean startTask(String id) {
        var task = starters.get(id);
        if (task == null)
            return false;
        eventLoop.submit(task::start);
        return true;
    }

    public int stopAll() {
        starters.values().forEach(AbstractBlock::reset);
        startup.forEach(AbstractBlock::reset);

        return starters.size() + startup.size();
    }

    public boolean reloadTasks() {
        // First stop all of them
        stopAll();

        // Then remove
        starters.clear();
        startup.clear();

        // Then reload
        parseXML(XMLdigger.goIn(scriptPath, "dcafs", "tasklist"));
        startup.forEach(AbstractBlock::start);
        return true;
    }

    public boolean hasTask(String id) {
        return starters.get(id) != null;
    }

    public String getStartupTasks(String eol) {
        var join = new StringJoiner(eol);
        startup.forEach(block -> join.add(block.getInfo(new StringJoiner(eol), "")));
        return join.toString();
    }

    public String getTaskSetListing(String eol) {
        var join = new StringBuilder();

        starters.values().stream()
                .filter(bl -> bl instanceof OriginBlock)
                .map(bl -> (OriginBlock) bl)
                .forEach(ori -> LookAndFeel.formatHelpLine(ori.id() + " -> " + ori.info, false, join));
        return join.toString();
    }

    /**
     * Looks for a set/task based on a regex
     *
     * @param regex The regex to match the id with
     * @return Info on the found sets
     */
    public String getTaskInfo(String regex) {
        var join = new StringJoiner("\r\n");
        join.setEmptyValue("No Set id matches.");
        starters.entrySet().stream().filter(set -> set.getKey().matches(regex))
                .forEach(set -> set.getValue().getInfo(join, ""));
        return join.toString();
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

    /**
     * Add a blank TaskSet to this TaskManager
     *
     * @param id The id of the taskset
     * @return True if successful
     */
    public boolean addBlankTaskset(String id) {
        var fab = XMLfab.withRoot(scriptPath, "dcafs", "tasklist", "tasksets");
        fab.addParentToRoot("taskset").attr("id", id).attr("run", "oneshot").attr("name", "More descriptive info");
        fab.addChild("task", "Hello").attr("output", "log:info").attr("trigger", "delay:0s");
        return fab.build();
    }

    public String getLastError() {
        return "TODO";
    }
    @Override
    public String replyToCommand(Datagram d) {
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
        return id;
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
