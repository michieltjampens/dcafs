package util.tasks;

import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.NumericVal;
import util.data.RealtimeValues;
import util.tasks.blocks.AbstractBlock;
import util.tasks.blocks.OriginBlock;
import util.tools.TimeTools;

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
    public RealtimeValues rtvals(){
        return rtvals;
    }
    public EventLoopGroup eventLoopGroup(){
        return eventLoop;
    }
    public ArrayList<NumericVal> sharedMem(){
        return sharedMem;
    }
    public void setScriptPath(Path scriptPath) {
        this.scriptPath = scriptPath;
    }

    public Path getScriptPath() {
        return scriptPath;
    }
    public void reset(){
        stopAll();
        starters.clear();
        startup.clear();
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
        TaskManagerFab.reloadTaskManager(this);
        if( !startup.isEmpty())
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
                .forEach(ori -> LookAndFeel.formatHelpLine(ori.id() + " -> " + ori.getInfo(), false, join));
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

    public String getLastError() {
        return "TODO";
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
