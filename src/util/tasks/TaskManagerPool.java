package util.tasks;

import das.Commandable;
import das.Core;
import das.Paths;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.RealtimeValues;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.util.StringJoiner;

public class TaskManagerPool implements Commandable {

    HashMap<String, TaskManager> tasklists = new HashMap<>();
    RealtimeValues rtvals;
    final Path scriptPath = Paths.storage().resolve("tmscripts");
    EventLoopGroup eventLoop;

    public TaskManagerPool(RealtimeValues rtvals, EventLoopGroup eventLoop) {
        this.rtvals = rtvals;
        this.eventLoop = eventLoop;
        readFromXML();
    }

    public void readFromXML() {
        // By default, taskmanagers are in their own node
        var dig = XMLdigger.goIn(Paths.settings(), "dcafs", "taskmanagers");
        if (dig.isInvalid())  // alternatively they might be in the settings
            dig = XMLdigger.goIn(Paths.settings(), "dcafs", "settings");
        if (dig.isInvalid() || !dig.hasPeek("taskmanager"))
            dig = XMLdigger.goIn(Paths.settings(), "dcafs"); // Or even just under dcafs

        dig.peekOut("taskmanager").forEach(tm -> {
            Logger.info("Found reference to TaskManager in xml.");
            var p = Path.of(tm.getTextContent());
            if (!p.isAbsolute())
                p = Paths.storage().resolve(p);

            if (Files.exists(p)) {
                addTaskList(tm.getAttribute("id"), p);
            } else {
                Logger.error("No such task xml: " + p);
            }
        });
    }

    public TaskManager addTaskList(String id, TaskManager tl) {
        tasklists.put(id, tl);
        return tl;
    }

    public TaskManager addTaskList(String id, Path scriptPath) {
        var tm = new TaskManager(id, eventLoop, rtvals);
        tm.setScriptPath(scriptPath);
        return addTaskList(id, tm);
    }

    public void startStartups() {
        for (var tm : tasklists.values())
            tm.start();
    }

    public void startTask(String id) {
        tasklists.values().forEach(tm -> tm.startTask(id));
    }
    /**
     * Reload all the tasklists
     */
    public String reloadAll() {
        StringJoiner errors = new StringJoiner("\r\n");
        for (TaskManager tl : tasklists.values()) {
            if (!tl.reloadTasks())
                errors.add(tl.id() + " -> " + tl.getLastError());
        }
        return errors.toString();
    }

    public Set<String> getTasKManagerIds() {
        return tasklists.keySet();
    }
    /* ******************************************* C O M M A N D A B L E ******************************************* */
    @Override
    public String replyToCommand(Datagram d) {
        String nl = d.eol();
        StringJoiner response = new StringJoiner(nl);

        String[] args = d.argList();
        if (tasklists.isEmpty() && !args[0].equalsIgnoreCase("addnew") && !args[0].equalsIgnoreCase("add") && !args[0].equalsIgnoreCase("load"))
            return "! No TaskManagers active, only tm:add,id and tm:load,id available.";

        if (!d.cmd().equals("tm")) { // If a taskmanager is addressed directly
            switch (d.argList()[0]) {
                case "?", "list" -> d.args(d.cmd() + ",sets"); // Change cmd
                case "reload", "startups" -> d.args(d.cmd() + "," + d.args()); // Retain the cmd but move around
                default -> d.args(d.cmd() + ",run," + d.args()); // Run a taskset
            }
            d.cmd("tm");
        }
        args = d.argList();

        switch (args[0]) {
            case "?":
                return doHelpCmd(d.asHtml());
            case "add":
                return doAddCmd(args);
            case "load":
                if (args.length != 2)
                    return "! Not enough parameters, tm:load,id";
                if (tasklists.get(args[1]) != null)
                    return "! Already a taskmanager with that id";
                if (Files.notExists(scriptPath.resolve(args[1] + ".xml")))
                    return "! No such script in the default location";
                if (addTaskList(args[1], scriptPath.resolve(args[1] + ".xml")).reloadTasks()) {
                    XMLfab.withRoot(Paths.settings(), "dcafs", "settings")
                            .addChild("taskmanager", "tmscripts" + File.separator + args[1] + ".xml").attr("id", args[1]).build();
                    return "Loaded " + args[1];
                }
                return "! Failed to load tasks from " + args[1];
            case "reloadall":
            case "reload":
                var join = new StringJoiner(nl);
                for (TaskManager tam : tasklists.values())
                    join.add(tam.id() + " -> " + (tam.reloadTasks() ? "Reloaded ok" : "Todo feedback"));
                return join.toString();
            case "stopall":
                for (TaskManager tam : tasklists.values())
                    tam.stopAll();
                return "Stopped all TaskManagers.";
            case "list":
                response.add("Currently active TaskManagers:");
                tasklists.keySet().forEach(response::add);
                return response.toString();
            default:
                return doSubCmd(args, nl);
        }
    }

    private String doHelpCmd(boolean html) {
        var help = new StringJoiner("\r\n");
        help.add("This is the hub for all the global interaction with the taskmanagers.")
                .add("Addition")
                .add("tm:add,id -> Add a new taskmanager, creates a file etc")
                .add("tm:load,id -> Load an existing taskmanager from the default folder")
                .add("Global")
                .add("tm:reloadall -> Reload all the taskmanagers")
                .add("tm:stopall -> Stop all the taskmanagers")
                .add("tm:list -> Get a list of currently active TaskManagers")
                .add("Interact with a certain taskmanager")
                .add("tm:id,addtaskset,tasksetid -> Adds an empty taskset to the given taskmanager")
                .add("tm:id,reload -> Reload the specific taskmanager")
                .add("tm:id,forcereload -> Reload the specific taskmanager even if it's running uninterruptable tasks")
                .add("tm:id,remove -> Remove the manager with the given id")
                .add("tm:id,x -> Send command x to taskmanager with given id")
                .add("tm:id,getpath -> Get the path to the given taskmanager")
                .add("tm:id,tasks -> Get a list of all tasks in the taskmanager")
                .add("tm:id,sets -> Get a list of all tasksets in the taskmanager")
                .add("tm:id,stop -> Cancel all scheduled actions of this taskmanager")
                .add("tm:id,taskinfo,taskid -> Give detailed information about a specific task of this taskmanager");
        return LookAndFeel.formatHelpCmd(help.toString(), html);
    }

    private String doAddCmd(String[] args) {
        if (args.length != 2)
            return "! Not enough parameters, need tm:add,id";

        // Add to the settings xml
        try {
            Files.createDirectories(scriptPath);
        } catch (IOException e) {
            Logger.error(e);
        }

        var newScriptPath = scriptPath.resolve(args[1] + ".xml");
        if (Files.notExists(newScriptPath)) {
            createBlankScript(args, newScriptPath);
        } else {
            return "Already a file in the tmscripts folder with that name, load it with tm:load," + args[1];
        }
        // Add it to das
        addTaskList(args[1], newScriptPath);
        Core.addToQueue(Datagram.system("commandable", args[1]).payload(this)); // Create  the commandable reference
        return "Tasklist added, use tm:reload," + args[1] + " to run it.";
    }

    private void createBlankScript(String[] cmds, Path scriptPath) {
        XMLfab tmFab = XMLfab.withRoot(Paths.settings(), "dcafs", "settings");
        tmFab.addChild("taskmanager", "tmscripts" + File.separator + cmds[1] + ".xml").attr("id", cmds[1]).build();
        tmFab.build();

        // Create an empty file
        XMLfab.withRoot(scriptPath, "dcafs", "tasklist")
                .comment("Any id is case insensitive")
                .comment("Reload the script using tm:reload," + cmds[1])
                .comment("If something is considered default, it can be omitted")
                .comment("There's no hard limit to the amount of tasks or tasksets")
                .comment("Task debug info has a separate log file, check logs/taskmanager.log")
                .addParentToRoot("tasksets", "Tasksets are sets of tasks")
                .comment("Below is an example taskset")
                .addChild("taskset").attr("run", "oneshot").attr("id", "example").attr("info", "Example taskset that says hey and bye")
                .comment("run can be either oneshot (start all at once) or step (one by one), default is oneshot")
                .down().addChild("task", "Hello World from " + cmds[1]).attr("output", "telnet:info")
                .addChild("task", "Goodbye :(").attr("output", "telnet:error").attr("delay", "2s")
                .up()
                .comment("id is how the taskset is referenced and info is a some info on what the taskset does,")
                .comment("this will be shown when using " + cmds[1] + ":list")
                .addParentToRoot("tasks", "Tasks are single commands to execute")
                .comment("Below is an example task, this will be called on startup or if the script is reloaded")
                .addChild("task", "tm:" + cmds[1] + ",run,example").attr("output", "system").attr("delay", "1s")
                .comment("This task will wait a second and then start the example taskset")
                .comment("A task doesn't need an id but it's allowed to have one")
                .comment("Possible outputs: stream:id , system (default), log:info, email:ref, manager, telnet:info/warn/error")
                .comment("Possible triggers: delay, interval, while, ...")
                .comment("For more extensive info and examples, check Reference Guide - Taskmanager in the manual")
                .build();
    }

    private String doSubCmd(String[] cmds, String nl) {

        if (cmds.length == 1)
            return "! No such subcommand in tm: " + cmds[0];

        var tl = tasklists.get(cmds[0]);
        if (tl == null && !(cmds[0].equals("*") && cmds[1].equals("run")))
            return "! No such TaskManager: " + cmds[0];

        return switch (cmds[1]) {
            case "remove" -> {
                if (tasklists.remove(cmds[0]) == null)
                    yield "! Failed to remove the TaskManager, unknown key";
                yield "Removed the TaskManager";
            }
            case "reload" -> {
                tl.reloadTasks();
                yield "\r\nTasks reloaded";
            }
            case "getpath" -> tl.getScriptPath().toString();
            case "addtaskset" -> {
                if (cmds.length != 3)
                    yield "! Not enough parameters, need tm:id,addtaskset,tasksetid";
                tl = tasklists.get(cmds[1]);

                if (tl.addBlankTaskset(cmds[2])) {
                    yield "Taskset added";
                }
                yield "! Failed to add taskset";
            }
            case "startups" -> tl.getStartupTasks(nl);
            case "sets" -> tl.getTaskSetListing(nl);
            case "stop" -> "Cancelled " + tl.stopAll() + " futures.";
            case "run" -> doRunCmd(cmds, tl);
            case "taskinfo" -> {
                if (cmds.length != 3)
                    yield "! Not enough arguments : tm:tmid,taskinfo,taskid";
                yield tl.getTaskInfo(cmds[2]);
            }
            default -> "! No such subcommand";
        };

    }

    private String doRunCmd(String[] cmds, TaskManager tm) {
        if (cmds.length < 3)
            return "! Not enough parameters, need tm:id,run,task(set)id";

        if (cmds[0].equals("*")) {
            int a = 0;
            for (var t : tasklists.values()) {
                if (t.hasTask(cmds[2])) {
                    a += t.startTask(cmds[2]) ? 0 : 1;
                }
            }
            if (a == 0)
                return "! Nothing started";
            return "Started " + a + " task(set)s";
        } else {
            if (tm.hasTask(cmds[2])) {
                return tm.startTask(cmds[2]) ? "Task Started" : "!Failed to start";
            } else {
                String[] arg = Arrays.copyOfRange(cmds, 3, cmds.length);
                return tm.startTask(cmds[2]) ? "Task ok" : "! Failed/invalid " + cmds[2];
            }
        }
    }

    public String payloadCommand(String cmd, String args, Object payload) {
        return "! No such cmds in " + cmd;
    }

    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
}
