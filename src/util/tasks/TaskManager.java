package util.tasks;

import io.Writable;
import io.email.Email;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.NumericVal;
import util.data.RealtimeValues;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringJoiner;

public class TaskManager implements Writable {
    HashMap<String, AbstractBlock> starters = new HashMap<>();
    ArrayList<AbstractBlock> startup = new ArrayList<>();
    EventLoopGroup eventLoop;
    RealtimeValues rtvals;
    Path scriptPath;
    String id;
    ArrayList<NumericVal> sharedMem = new ArrayList<>();

    public TaskManager(String id, EventLoopGroup eventLoop, RealtimeValues rtvals) {
        this.eventLoop = eventLoop;
        this.rtvals = rtvals;
        this.id = id;
    }

    public void setScriptPath(Path scriptPath) {
        this.scriptPath = scriptPath;
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

    public void readFromXml(XMLdigger dig) {
        if (dig.hasPeek("tasksets")) {
            dig.digDown("tasksets");
            AbstractBlock onFailure = null;
            // Process taskset
            for (var taskset : dig.digOut("taskset")) {
                parseSet(taskset, onFailure);
            }
        }
        dig.goUp("tasklist");
        dig.digDown("tasks");
        for (var task : dig.digOut("task"))
            addStarter(processTask(task, null));
    }

    private void parseSet(XMLdigger taskset, AbstractBlock onFailure) {
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
            AbstractBlock prev = start;
            for (var task : taskset.digOut("*")) {
                switch (task.tagName("")) {
                    case "task" -> {
                        var first = processTask(task, prev);
                        if (onFailure != null) {
                            onFailure.setFailureBlock(first);
                            onFailure = null;
                        }
                        if (type.length == 2)
                            prev.addNext(new DelayBlock(eventLoop).useDelay(type[1]));
                    }
                    case "retry" -> {
                        var result = parseRetry(task, prev);
                        if (result != null)
                            onFailure = result;
                    }
                    case "while" -> {
                        var result = parseWhile(task, prev);
                        if (result != null)
                            prev = result;
                    }
                    default -> prev.addNext(parseNode(task));
                }
            }
        }
        start.updateChainId();
        addStarter(start);
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

        // Handle req
        var reqAttr = task.attr("req", "");
        ConditionBlock req = null;
        if (!reqAttr.isEmpty())
            req = new ConditionBlock(rtvals, sharedMem).setCondition(reqAttr);

        // Check if it's delayed
        var delay = handleDelay(task);

        // Handle target
        var target = handleTarget(task);
        if (target == null) {
            Logger.error(this.id + "(tm) -> Task '" + id + "' needs a valid target!");
            return null;
        }
        // Combine it all
        if (start != null) {
            start.addNext(delay).addNext(req).addNext(target);
            return start.getNext();
        } else if (delay != null) { // with delay
            delay.addNext(req).addNext(target);
            return delay;
        } else if (req != null) { // No delay but req
            req.addNext(target);
            return req;
        }
        return target; // No delay and no req
    }

    /**
     * Parses and processes the delay information in a task node
     * @param task Digger pointing to the node
     */
    private AbstractBlock handleDelay(XMLdigger task) {
        var delay = task.attr("delay", "-1s");
        var interval = task.attr("interval", "");
        var timeAttr = task.matchAttr("time", "localtime");
        var time = task.attr(timeAttr, "");
        var delayBlock = new DelayBlock(eventLoop);

        if (!delay.equals("-1s")) {
            delayBlock.useDelay(delay);
        } else if (!time.isEmpty()) {
            delayBlock.useClock(time, timeAttr.contains("local"));
        } else if (!interval.isEmpty()) {
            var periods = Tools.splitList(interval);
            var initial = periods.length == 2 ? periods[0] : "0s";
            var recurring = periods.length == 2 ? periods[1] : periods[0];
            var repeats = task.attr("repeats", -1);

            delayBlock.useInterval(initial, recurring, repeats);
        } else { // Without delay
            return null;
        }
        return delayBlock;
    }

    /**
     * Parses and processes the type attribute of a task node
     * @param task Digger pointing to the node
     */
    private AbstractBlock handleTarget(XMLdigger task) {
        var target = task.attr("output", "system").split(":",2);
        var content = task.value("");

        return switch (target[0]) {
            case "system", "cmd" -> new CmdBlock(Datagram.system(content));
            case "stream" -> handleStreamTarget(task, target, content);
            case "file" -> new WritableBlock().setMessage(target[0] + ":" + target[1], content);
            case "email" -> {
                var subs = content.split(";", 2);
                var attachment = task.attr("attachment", "");
                yield new EmailBlock(Email.to(target[1]).subject(subs[0]).content(subs[1]).attachment(attachment));
            }
            case "manager" -> new ControlBlock(this).setMessage(content);
            case "telnet" -> new CmdBlock(Datagram.system("telnet", "broadcast," + target[1] + "," + content));
            default -> {
                Logger.error(id + " (tm) -> Unknown target " + target[0]);
                yield null;
            }
        };
    }

    private AbstractBlock handleStreamTarget(XMLdigger task, String[] target, String content) {

        AbstractBlock block;
        if (task.attr("interval", "").isEmpty()) { // If not an interval, not many repeats so don't use writable
            block = new CmdBlock(Datagram.system(target[1], content));
        } else {
            block = new WritableBlock().setMessage(target[0] + ":" + target[1], content);
        }
        var reply = task.attr("reply", "");
        if (!reply.isEmpty()) {
            reply = reply.replace("**", content); // ** means the data send
            var read = new ReadingBlock(eventLoop).setMessage("raw:" + target[1], reply, "2s");
            var count = new CounterBlock(3); // Default is 3 attempts
            read.setFailureBlock(count); // If read fails, go to count
            count.addNext(block);  // If count isn't 0 go back to sender
            block.addNext(read); // After sending, wait for read
        }
        return block;
    }

    /**
     * Processes a retry node, either single node or with child nodes
     *
     * @param task This points to the retry node
     * @param prev The previous block in the chain
     * @return The next block should become the failure block of this one
     */
    private AbstractBlock parseRetry(XMLdigger task, AbstractBlock prev) {

        String onFail = task.attr("onfail", "stop");
        var counter = new CounterBlock(task.attr("retries", -1));
        AbstractBlock first = null;
        if (task.hasChilds()) {
            // Do the internal ones
            for (var node : task.digOut("*"))
                first = addToOrBecomeFirst(first, counter, parseNode(node));
            prev.addNext(first);
        } else {
            // No child nodes
            // <retry interval="20s" retries="-1">{f:icos_sol4} equals 1</retry>
            var cond = new ConditionBlock(rtvals, sharedMem).setCondition(task.value(""));
            var delay = new DelayBlock(eventLoop).useDelay(task.attr("interval", "1s"));

            cond.setFailureBlock(counter); // If condition fails, go to delay
            counter.addNext(delay).addNext(cond);  // After counter go to the delay and then back to condition

            prev.addNext(cond);          // Add these blocks to the chain
        }
        if (onFail.equalsIgnoreCase("stop"))
            return null;
        return counter;
    }

    /**
     * Processes a retry node, either single node or with child nodes
     *
     * @param task This points to the retry node
     * @param prev The previous block in the chain
     * @return This block is the end of the updated chain
     */
    private AbstractBlock parseWhile(XMLdigger task, AbstractBlock prev) {

        AbstractBlock first = null;
        var counter = new CounterBlock(task.attr("maxruns", -1));
        var dummy = new DummyBlock();
        counter.setFailureBlock(dummy); // If out of maxruns go to dummy to reverse failure to ok

        if (task.hasChilds()) {
            // Do the internal ones
            for (var node : task.digOut("*")) {
                if (first == null) {
                    first = parseNode(node);
                    counter.setNext(first);
                } else {
                    first.addNext(parseNode((node)));
                }
                if (first != null)
                    first.getLastBlock().setFailureBlock(dummy);
            }
            if (first == null)
                return null;

            first.addNext(counter);

            prev.addNext(first);
        } else {
            // No child nodes
            // <while interval="20s" maxruns="-1">{f:icos_sol4} equals 1</retry>
            var cond = new ConditionBlock(rtvals, sharedMem).setCondition(task.value(""));
            var delay = new DelayBlock(eventLoop).useDelay(task.attr("interval", "1s"));

            cond.setNext(counter).setFailureBlock(dummy);  // If ok, go to counter, if not, dummy
            counter.setNext(delay);    // After counter go to delay
            delay.setNext(cond);       // After delay, try again

            prev.addNext(cond);        // Add these blocks to the chain
        }
        return dummy;
    }
    private AbstractBlock parseNode(XMLdigger node) {
        var content = node.value("");
        return switch (node.tagName("")) {
            case "delay" -> new DelayBlock(eventLoop).useDelay(content);
            case "req" -> new ConditionBlock(rtvals, sharedMem).setCondition(content);
            case "stream" -> {
                var id = node.attr("to", "");
                yield new WritableBlock().setMessage(id, content);
            }
            case "email" -> {
                var to = node.attr("to", "");
                var subject = node.attr("subject", "");
                var attach = node.attr("attachment", "");
                yield new EmailBlock(Email.to(to).subject(subject).content(content).attachment(attach));
            }
            case "cmd", "system" -> new CmdBlock(content);
            case "telnet" -> new CmdBlock("telnet:broadcast," + node.attr("level", "info") + "," + content);
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

    /**
     * Builds the sequence of nodes
     * @param first The first node in the sequence
     * @param failure The node to go to on failure
     * @param added The new node to add
     * @return The first node
     */
    private AbstractBlock addToOrBecomeFirst(AbstractBlock first, AbstractBlock failure, AbstractBlock added) {
        if (first == null) {
            first = added;
            first.setFailureBlock(failure);
            failure.setNext(first); // After the failure process, go to  the first block again
        } else {
            if (!(added instanceof WritableBlock))
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
        readFromXml(XMLdigger.goIn(scriptPath, "dcafs", "tasklist"));
        startup.forEach(AbstractBlock::start);
        return true;
    }

    public boolean hasTask(String id) {
        return starters.get(id) != null;
    }

    public String getStartupTasks(String eol) {
        var join = new StringJoiner(eol);
        join.setEmptyValue("! No startup tasks yet.");
        if (!startup.isEmpty()) {
            join.add("Status at " + TimeTools.formatLongNow() + " (local)");
            startup.forEach(block -> join.add(block.getInfo(new StringJoiner(eol), "")));
        }
        return join.toString();
    }

    public String getTaskSetListing() {
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
    public boolean writeString(String data) {
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

}
