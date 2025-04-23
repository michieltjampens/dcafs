package util.tasks;

import io.email.Email;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.data.vals.Rtvals;
import util.tasks.blocks.*;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.nio.file.Path;
import java.util.Optional;

public class TaskManagerFab {
    public static boolean reloadTaskManager( TaskManager tm ){
        var dig = XMLdigger.goIn(tm.getScriptPath(),"dcafs","tasklist");
        startDigging(dig,tm);
        return true;
    }

    public static Optional<TaskManager> buildTaskManager(String id, Path script, EventLoopGroup eventLoop, Rtvals rtvals) {
        var tm = new TaskManager(id,eventLoop,rtvals);
        tm.setScriptPath(script);

        var dig = XMLdigger.goIn(script,"dcafs","tasklist");
        if( startDigging(dig,tm) )
            return Optional.of(tm);
        return Optional.empty();
    }
    private static boolean startDigging( XMLdigger dig, TaskManager tm){
        if (dig.hasPeek("tasksets")) {
            dig.digDown("tasksets");
            AbstractBlock onFailure = null;
            // Process taskset
            for (var taskset : dig.digOut("taskset")) {
                if( !parseSet(taskset, onFailure, tm ) )
                    return false;
            }
        }
        dig.goUp("tasklist");
        dig.digDown("tasks");

        for (var task : dig.digOut("task")) {
            var processed = processTask(task, null, tm);
            if( processed==null)
                return false;
            tm.addStarter(processed);
        }
        return true;
    }
    
    private static boolean parseSet(XMLdigger taskset, AbstractBlock onFailure, TaskManager tm) {
        var eventLoop = tm.eventLoopGroup();
        var rtvals = tm.rtvals();

        var id = taskset.attr("id", "");
        var type = taskset.attr("type", "oneshot").split(":", 2);
        var info = taskset.attr("info", "");
        var start = new OriginBlock(id).setInfo(info);
        var isStep = type[0].equalsIgnoreCase("step");

        if (!isStep) {  // Everything starts at once so add splitter
            start.addNext(new SplitBlock(eventLoop).setInterval(type.length == 2 ? type[1] : ""));
            for (var task : taskset.digOut("task")) {
                if (start.addNext(processTask(task, null, tm)) == null) {
                    Logger.error(id + "(tm) -> Issue processing task, aborting.");
                    return false;
                }
            }
        } else {
            AbstractBlock prev = start;
            for (var task : taskset.digOut("*")) {
                switch (task.tagName("")) {
                    case "task" -> {
                        var first = processTask(task, prev, tm);
                        if( first==null)
                            return false;
                        if (onFailure != null) {
                            onFailure.setFailureBlock(first);
                            onFailure = null;
                        }
                        if (type.length == 2)
                            prev.addNext(new DelayBlock(eventLoop).useDelay(type[1]));
                    }
                    case "retry" -> {
                        var result = parseRetry(task, prev,tm);
                        if (result == null)
                            return false;
                        onFailure = result;
                    }
                    case "while" -> {
                        var result = parseWhile(task, prev, tm);
                        if (result == null)
                            return false;
                        prev = result;
                    }
                    default -> {
                        var result = parseNode(task, tm);
                        if (result == null)
                            return false;
                        prev.addNext(result);
                    }
                }

            }
        }
        start.updateChainId();
        tm.addStarter(start);
        return true;
    }

    /**
     * Processes a task node
     *
     * @param task  Digger pointing to the node
     * @param start The block to attach to
     * @return The resulting block
     */
    private static AbstractBlock processTask(XMLdigger task, AbstractBlock start, TaskManager tm) {
        var eventLoop = tm.eventLoopGroup();
        var rtvals = tm.rtvals();
        var sharedMem = tm.sharedMem();

        var id = task.attr("id", "");

        // Handle req
        var reqAttr = task.attr("req", "");
        var req = ConditionBlock.build(reqAttr, rtvals, sharedMem).orElse(null);

        // Check if it's delayed
        var delay = handleDelay( task, eventLoop );

        // Handle target
        var target = handleTarget( task, tm );
        if (target == null) {
            Logger.error("(tm) -> Task '" + id + "' needs a valid target!");
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
     *
     * @param task Digger pointing to the node
     */
    private static AbstractBlock handleDelay(XMLdigger task, EventLoopGroup eventLoop) {
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
     *
     * @param task Digger pointing to the node
     */
    private static AbstractBlock handleTarget(XMLdigger task, TaskManager tm ) {
        var eventLoop = tm.eventLoopGroup();

        var target = task.attr("output", "system").split(":", 2);
        var content = task.value("");

        return switch (target[0]) {
            case "system", "cmd" -> new CmdBlock(Datagram.system(content));
            case "stream" -> handleStreamTarget(task, target, content, eventLoop);
            case "file" -> new WritableBlock().setMessage(target[0] + ":" + target[1], content);
            case "email" -> {
                var subs = content.split(";", 2);
                var attachment = task.attr("attachment", "");
                yield new EmailBlock(Email.to(target[1]).subject(subs[0]).content(subs[1]).attachment(attachment));
            }
            case "manager" -> new ControlBlock(tm).setMessage(content);
            case "telnet" -> new CmdBlock(Datagram.system("telnet", "broadcast," + target[1] + "," + content));
            default -> {
                Logger.error(" (tm) -> Unknown target " + target[0]);
                yield null;
            }
        };
    }

    private static AbstractBlock handleStreamTarget(XMLdigger task, String[] target, String content, EventLoopGroup eventLoop) {

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
    private static AbstractBlock parseRetry(XMLdigger task, AbstractBlock prev, TaskManager tm) {
        var rtvals = tm.rtvals();
        var eventLoop = tm.eventLoopGroup();
        var sharedMem = tm.sharedMem();

        String onFail = task.attr("onfail", "stop");
        var counter = new CounterBlock(task.attr("retries", -1));
        AbstractBlock first = null;
        if (task.hasChilds()) {
            // Do the internal ones
            for (var node : task.digOut("*"))
                first = addToOrBecomeFirst(first, counter, parseNode(node,tm));
            prev.addNext(first);
        } else {
            // No child nodes
            // <retry interval="20s" retries="-1">{f:icos_sol4} equals 1</retry>
            var cond = ConditionBlock.build(task.value(""), rtvals, sharedMem).orElse(null);
            if (cond == null)
                return null;

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
     * Builds the sequence of nodes
     * @param first The first node in the sequence
     * @param failure The node to go to on failure
     * @param added The new node to add
     * @return The first node
     */
    private static AbstractBlock addToOrBecomeFirst(AbstractBlock first, AbstractBlock failure, AbstractBlock added) {
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
    /**
     * Processes a retry node, either single node or with child nodes
     *
     * @param task This points to the retry node
     * @param prev The previous block in the chain
     * @return This block is the end of the updated chain
     */
    private static AbstractBlock parseWhile(XMLdigger task, AbstractBlock prev, TaskManager tm ) {
        var rtvals = tm.rtvals();
        var eventLoop = tm.eventLoopGroup();
        var sharedMem = tm.sharedMem();

        AbstractBlock first = null;
        var counter = new CounterBlock(task.attr("maxruns", -1));
        var dummy = new DummyBlock();
        counter.setFailureBlock(dummy); // If out of maxruns go to dummy to reverse failure to ok

        if (task.hasChilds()) {
            // Do the internal ones
            for (var node : task.digOut("*")) {
                if (first == null) {
                    first = parseNode(node,tm);
                    counter.setNext(first);
                } else {
                    first.addNext(parseNode(node,tm));
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
            var cond = ConditionBlock.build(task.value(""), rtvals, sharedMem).orElse(null);
            if (cond == null)
                return null;
            var delay = new DelayBlock(eventLoop).useDelay(task.attr("interval", "1s"));

            cond.setNext(counter).setFailureBlock(dummy);  // If ok, go to counter, if not, dummy
            counter.setNext(delay);    // After counter go to delay
            delay.setNext(cond);       // After delay, try again

            prev.addNext(cond);        // Add these blocks to the chain
        }
        return dummy;
    }

    private static AbstractBlock parseNode(XMLdigger node, TaskManager tm) {
        var rtvals = tm.rtvals();
        var eventLoop = tm.eventLoopGroup();
        var sharedMem = tm.sharedMem();

        var content = node.value("");
        return switch (node.tagName("")) {
            case "delay" -> new DelayBlock(eventLoop).useDelay(content);
            case "req" -> ConditionBlock.build(content, rtvals, sharedMem).orElse(null);
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
     * Add a blank TaskSet to this TaskManager
     *
     * @param id The id of the taskset
     * @return True if successful
     */
    public static boolean addBlankTaskset(String id, Path scriptPath) {
        var fab = XMLfab.withRoot(scriptPath, "dcafs", "tasklist", "tasksets");
        fab.addParentToRoot("taskset").attr("id", id).attr("run", "oneshot").attr("name", "More descriptive info");
        fab.addChild("task", "Hello").attr("output", "log:info").attr("trigger", "delay:0s");
        return fab.build();
    }
}
