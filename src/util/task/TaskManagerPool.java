package util.task;

import io.email.EmailSending;
import io.stream.StreamManager;
import io.Writable;
import das.CommandPool;
import das.Commandable;
import io.telnet.TelnetCodes;
import util.data.RealtimeValues;
import org.tinylog.Logger;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.StringJoiner;

public class TaskManagerPool implements Commandable {

    private final String workPath;
    HashMap<String, TaskManager> tasklists = new HashMap<>();
    RealtimeValues rtvals;
    CommandPool cmdReq;
    StreamManager streamManager;
    EmailSending emailSender;

    public TaskManagerPool(String workpath, RealtimeValues rtvals, CommandPool cmdReq){
        this.workPath=workpath;
        this.rtvals=rtvals;
        this.cmdReq=cmdReq;

    }
    public void setStreamPool(StreamManager streamManager){
        this.streamManager = streamManager;
    }
    public void setEmailSending(EmailSending emailSender ){
        this.emailSender=emailSender;
    }
    public void readFromXML() {
        Path xml = Path.of(workPath, "settings.xml");

        var dig = XMLdigger.goIn(xml,"dcafs","taskmanagers");
        if( dig.isInvalid() ) {
            dig = XMLdigger.goIn(xml,"dcafs","settings");
        }
        if( dig.isInvalid() ) {
            return;
        }
        dig.peekOut("taskmanager").forEach( tm -> {
            Logger.info("Found reference to TaskManager in xml.");
            var p = Path.of(tm.getTextContent());
            if( !p.isAbsolute())
                p = Path.of(workPath).resolve(p);

            if (Files.exists(p)) {
                addTaskList(tm.getAttribute("id"), p);
            } else {
                Logger.error("No such task xml: " + p);
            }
        });
    }
    public TaskManager addTaskList( String id, TaskManager tl){
        tl.setStreamPool(streamManager);
        tl.setCommandReq(cmdReq);
        tl.setWorkPath(workPath);
        tl.setEmailSending(emailSender);

        tasklists.put(id,tl);
        return tl;
    }
    public TaskManager addTaskList( String id, Path scriptPath){
        var tm = new TaskManager(id,rtvals,cmdReq);
        tm.setScriptPath(scriptPath);
        return addTaskList(id,tm);
    }
    /**
     * Check the TaskManager for tasks with the given keyword and start those
     *
     * @param keyword The keyword to look for
     */
    public void startKeywordTask(String keyword) {
        Logger.info("Checking for tasklists with keyword " + keyword);
        tasklists.forEach( (k, v) -> v.startKeywordTask(keyword) );
    }
    /**
     * Try to start the given taskset in all the tasklists
     * @param taskset The taskset to start
     */
    public void startTaskset( String taskset ){
        tasklists.values().forEach( tl -> tl.startTaskset(taskset));
    }

    /**
     * Reload all the tasklists
     */
    public String reloadAll(){
        StringJoiner errors = new StringJoiner("\r\n");
        for (TaskManager tl : tasklists.values()) {
            if( !tl.reloadTasks())
                errors.add(tl.getId()+" -> "+tl.getLastError());
        }
        return errors.toString();
    }

    public void recheckAllIntervalTasks(){
        tasklists.values().forEach(TaskManager::recheckIntervalTasks);
    }
    public Optional<TaskManager> getTaskManager(String id){
        return Optional.ofNullable( tasklists.get(id));
    }
    /* ******************************************* C O M M A N D A B L E ******************************************* */
    @Override
    public String replyToCommand(String cmd, String args, Writable wr, boolean html) {
        String nl = html?"<br>":"\r\n";
        StringJoiner response = new StringJoiner(nl);
        String[] cmds = args.split(",");

        if( tasklists.isEmpty() && !cmds[0].equalsIgnoreCase("addblank") && !cmds[0].equalsIgnoreCase("add") && !cmds[0].equalsIgnoreCase("load"))
            return "! No TaskManagers active, only tm:addblank,id/tm:add,id and tm:load,id available.";

        TaskManager tl;
        String cyan = html?"":TelnetCodes.TEXT_CYAN;
        String green=html?"":TelnetCodes.TEXT_GREEN;
        String reg=html?"":TelnetCodes.TEXT_DEFAULT;
        var join = new StringJoiner(nl);

        Path settingsPath = Path.of(workPath, "settings.xml");
        switch( cmds[0] ) {
            case "?":
                response.add(cyan + "Addition" + reg)
                        .add(green + "tm:add,id " + reg + "-> Add a new taskmanager, creates a file etc")
                        .add(green + "tm:load,id " + reg + "-> Load an existing taskmanager from the default folder")
                        .add(cyan + "Global" + reg)
                        .add(green + "tm:reloadall " + reg + "-> Reload all the taskmanagers")
                        .add(green + "tm:stopall " + reg + "-> Stop all the taskmanagers")
                        .add(green + "tm:list " + reg + "-> Get a list of currently active TaskManagers")
                        .add(cyan + "Interact with a certain taskmanager" + reg)
                        .add(green + "tm:id,addtaskset,tasksetid " + reg + "-> Adds an empty taskset to the given taskmanager")
                        .add(green + "tm:id,reload " + reg + "-> Reload the specific taskmanager")
                        .add(green + "tm:id,forcereload " + reg + "-> Reload the specific taskmanager even if it's running uninterruptable tasks")
                        .add(green + "tm:id,remove " + reg + "-> Remove the manager with the given id")
                        .add(green + "tm:id,x " + reg + "-> Send command x to taskmanager with given id")
                        .add(green + "tm:id,getpath" + reg + " -> Get the path to the given taskmanager")
                        .add(green + "tm:id,tasks " + reg + "-> Get a list of all tasks in the taskmanager")
                        .add(green + "tm:id,sets " + reg + "-> Get a list of all tasksets in the taskmanager")
                        .add(green + "tm:id,stop " + reg + "-> Cancel all scheduled actions of this taskmanager");
                return response.toString();
            case "add":
                if (cmds.length != 2)
                    return "! Not enough parameters, need tm:add,id";

                // Add to the settings xml
                try {
                    Files.createDirectories(Path.of(workPath, "tmscripts"));
                } catch (IOException e) {
                    Logger.error(e);
                }

                var p = Path.of(workPath, "tmscripts", cmds[1] + ".xml");
                if (Files.notExists(p)) {
                    XMLfab tmFab = XMLfab.withRoot(settingsPath, "dcafs", "settings");
                    tmFab.addChild("taskmanager", "tmscripts" + File.separator + cmds[1] + ".xml").attr("id", cmds[1]).build();
                    tmFab.build();

                    // Create an empty file
                    XMLfab.withRoot(p, "dcafs","tasklist")
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
                            .addChild("task", "Goodbye :(").attr("output", "telnet:error").attr("trigger", "delay:2s")
                            .up()
                            .comment("id is how the taskset is referenced and info is a some info on what the taskset does,")
                            .comment("this will be shown when using " + cmds[1] + ":list")
                            .addParentToRoot("tasks", "Tasks are single commands to execute")
                            .comment("Below is an example task, this will be called on startup or if the script is reloaded")
                            .addChild("task", "taskset:example").attr("output", "system").attr("trigger", "delay:1s")
                            .comment("This task will wait a second and then start the example taskset")
                            .comment("A task doesn't need an id but it's allowed to have one")
                            .comment("Possible outputs: stream:id , system (default), log:info, email:ref, manager, telnet:info/warn/error")
                            .comment("Possible triggers: delay, interval, while, ...")
                            .comment("For more extensive info and examples, check Reference Guide - Taskmanager in the manual")
                            .build();
                } else {
                    return "Already a file in the tmscripts folder with that name, load it with tm:load," + cmds[1];
                }
                // Add it to das
                addTaskList(cmds[1], p);
                return "Tasklist added, use tm:reload," + cmds[1] + " to run it.";
            case "load":
                if (cmds.length != 2)
                    return "! Not enough parameters, tm:load,id";
                if (tasklists.get(cmds[1]) != null)
                    return "! Already a taskmanager with that id";
                if (Files.notExists(Path.of(workPath, "tmscripts", cmds[1] + ".xml")))
                    return "! No such script in the default location";
                if (addTaskList(cmds[1], Path.of(workPath, "tmscripts", cmds[1] + ".xml")).reloadTasks()) {
                    XMLfab.withRoot(settingsPath, "dcafs", "settings")
                            .addChild("taskmanager", "tmscripts" + File.separator + cmds[1] + ".xml").attr("id", cmds[1]).build();
                    return "Loaded " + cmds[1];
                }
                return "! Failed to load tasks from " + cmds[1];
            case "reloadall":
                for(TaskManager tam : tasklists.values() ) {
                    var res = tam.reloadTasks();
                    if( !res ){
                        join.add( tam.getId()+" -> "+tam.getLastError());
                    }else{
                        join.add(tam.getId()+" -> Reloaded ok");
                    }
                }
                return join.toString();
            case "stopall":
                for(TaskManager tam : tasklists.values() )
                    tam.stopAll("baseReqManager");
                return "Stopped all TaskManagers.";
            case "list":
                response.add("Currently active TaskManagers:");
                tasklists.keySet().forEach(response::add);
                return response.toString();

            case "restored":
                if( tasklists.isEmpty())
                    return "No taskmanagers active";
                if( cmds.length != 2)
                    return "Missing id";

                 tasklists.values().forEach( tm -> join.add(tm.notifyRestored(cmds[1])));
                 return join.toString();
            default:
                if( cmds.length==1)
                    return "! No such subcommand in tm: "+cmds[0];

                tl = tasklists.get(cmds[0]);
                if( tl != null || (cmds[0].equals("*")&&cmds[1].equals("run"))){

                    return switch (cmds[1]) {
                        case "remove" -> {
                            if (tasklists.remove(cmds[0]) == null)
                                yield "! Failed to remove the TaskManager, unknown key";
                            yield "Removed the TaskManager";
                        }
                        case "reload" -> {
                                if( !tl.reloadTasks() )
                                    yield "! "+tl.getLastError();
                                yield "\r\nTasks reloaded";
                        }
                        case "forcereload" -> "Forced a reload of "+tl.forceReloadTasks();
                        case "getpath" -> tl.getXMLPath().toString();
                        case "addtaskset" -> {
                            if (cmds.length != 3)
                                yield "! Not enough parameters, need tm:id,addtaskset,tasksetid";
                            tl = tasklists.get(cmds[1]);

                            if (tl.addBlankTaskset(cmds[2])) {
                                yield "Taskset added";
                            }
                            yield "! Failed to add taskset";
                        }
                        case "tasks" -> tl.getTaskListing(nl);
                        case "sets" -> tl.getTaskSetListing(nl);
                        case "stop" -> "Cancelled "+tl.stopAll("cmd req")+" futures.";
                        case "run" -> {
                            if (cmds.length < 3)
                                yield "! Not enough parameters, need tm:id,run,task(set)id";

                            if (cmds[0].equals("*")) {
                                int a = 0;
                                for (var t : tasklists.values()) {
                                    if (t.hasTaskset(cmds[2])) {
                                        a += t.startTaskset(cmds[2]).isEmpty() ? 0 : 1;
                                    } else {
                                        a += t.startTask(cmds[2], null) ? 1 : 0;
                                    }
                                }
                                if (a == 0)
                                    yield "! Nothing started";
                                yield "Started " + a + " task(set)s";
                            } else {
                                if (tl.hasTaskset(cmds[2])) {
                                    yield tl.startTaskset(cmds[2]);
                                } else {
                                    String[] arg = null;
                                    arg = Arrays.copyOfRange(cmds, 2, cmds.length);
                                    yield tl.startTask(cmds[2], arg) ? "Task ok" : "! Failed/invalid " + cmds[2];
                                }
                            }
                        }
                        default -> "! No such subcommand";
                    };
                }
                return "! No such TaskManager: "+cmds[0];
        }
    }
    public String payloadCommand( String cmd, String args, Object payload){
        return "! No such cmds in "+cmd;
    }
    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
}
