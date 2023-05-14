package util.data;

import das.Commandable;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.taskblocks.CheckBlock;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import worker.Datagram;
import org.w3c.dom.Element;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class IssuePool implements Commandable {
    private final BlockingQueue<Datagram> dQueue;
    private final RealtimeValues rtvals;
    private final EventLoopGroup scheduler;
    private final HashMap<String,Issue> issues = new HashMap<>();
    private final Path xml;
    public IssuePool(BlockingQueue<Datagram> dQueue, RealtimeValues rtvals, EventLoopGroup scheduler, Path xml ){
        this.dQueue=dQueue;
        this.rtvals=rtvals;
        this.scheduler=scheduler;
        this.xml=xml;

        readFromXML();
    }
    private void readFromXML(){

        var dig = XMLdigger.goIn(xml,"dcafs");
        dig.goDown("issues");
        if( dig.isInvalid()) {
            Logger.info("No issues node in the xml");
            return;
        }
        while( dig.iterate() ){
            dig.current().ifPresent(this::processNode);
        }
    }
    private void processNode( Element issue ){
        var dig = XMLdigger.goIn(issue);
        if( dig.isInvalid())
            return;

        var id = dig.attr("id","");
        if( id.isEmpty()){
            Logger.error("No valid id given");
            return;
        }
        var is = new Issue(id);
        if( dig.peekAt("start").hasValidPeek() ){ // Check for a start
            dig.goDown("start"); // Go to start node
            var check = dig.attr("if","");
            if( !check.isEmpty() ){
                if( !is.addStartCheck(check) ){
                    Logger.error( "Failed to process 'if' for "+id);
                }
            }
            if( dig.peekAt("cmd").hasValidPeek()){// Has cmds
                while(dig.iterate()) {
                    dig.goDown("cmd");
                    is.addStartCmd( dig.value(""),dig.attr("after",""));
                }
            }
        }
        if( dig.peekAt("stop").hasValidPeek() ){ // Check for a stop
            dig.goDown("stop"); // Go to start node
            var check = dig.attr("if","");
            if( !check.isEmpty() ){
                if( !is.addStopCheck(check) ){
                    Logger.error( "Failed to process 'if' for "+id);
                }
            }
            if( dig.peekAt("cmd").hasValidPeek()){// Has cmds
                while(dig.iterate()) {
                    dig.goDown("cmd");
                    is.addStopCmd( dig.value(""),dig.attr("after",""));
                }
            }
        }
        issues.put(id,is);
    }

    @Override
    public String replyToCommand(String cmd, String args, Writable wr, boolean html) {

        var cmds = args.split(",");
        if( cmds[0].equalsIgnoreCase("add")){
            // add a new issue to the xml
        }else{
            var issue = issues.get(cmds[0]);
            if( issue==null) {
                Logger.warn("Tried to refer to non existing issue: "+cmds[0]);
                return "No such issue yet";
            }
            return switch( cmds[1]){
                case "start" -> {
                    issue.start();
                    yield "Issue started";
                }
                case "stop" -> {
                    issue.stop();
                    yield "Issue stopped";
                }
                default -> "! No such subcommand: "+cmds[1];
            };
        }
        return "! No such command";
    }

    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }

    private class Issue {
        int cnt=0;
        String id="";
        Instant startTime;
        long totalTime=0;
        boolean active=false;
        ArrayList<SlowCmd> startCmds = new ArrayList<>();
        ArrayList<SlowCmd> stopCmds = new ArrayList<>();
        CheckBlock startBlock=null;
        CheckBlock stopBlock=null;

        public Issue(String id){
            this.id=id;
        }
        public boolean addStartCheck( String check ){
            startBlock = new CheckBlock(rtvals,check);
            startBlock.build();
            return startBlock.isValid();
        }
        public boolean addStopCheck( String check ){
            stopBlock = new CheckBlock(rtvals,check);
            stopBlock.build();
            return stopBlock.isValid();
        }
        public void addStartCmd( String cmd, String period){
            var slow = new SlowCmd(cmd,period);
            if( slow.isValid()) {
                startCmds.add(slow);
            }else{
                Logger.error( "Failed to parse 'after' for start in "+id);
            }
        }
        public void addStopCmd( String cmd, String period){
            var slow = new SlowCmd(cmd,period);
            if( slow.isValid()) {
                stopCmds.add(slow);
            }else{
                Logger.error( "Failed to parse 'after' for stop cmd in "+id);
            }
        }
        public void start(){
            if( active ) // If already active, ignore it
                return;

            if( startBlock==null || startBlock.start(null) ){ // Check secondary req if any
                active=true; // Toggle active
                startTime = Instant.now(); // Keep track of when it started
                cnt++;
                startCmds.forEach(SlowCmd::run); // Execute commands
                stopCmds.forEach(SlowCmd::stop); // Make sure stop cmds are halted
            }
        }
        public void stop(){
            if( !active ) // If not active, ignore it
                return;

            if( stopBlock==null || stopBlock.start(null) ){ // Check secondary req if any
                active=false; // Toggle active
                totalTime += Duration.between(startTime,Instant.now()).getSeconds(); // Keep track of total issue time
                startCmds.forEach(SlowCmd::stop);  // Make sure startcmds that haven't started are cancelled
                stopCmds.forEach(SlowCmd::run);  // Execute commands
            }
        }
    }
    private class SlowCmd{
        ScheduledFuture<?> delay=null;
        String cmd="";
        long seconds=0;
        public SlowCmd( String cmd, String period ){
            this.cmd=cmd;
            seconds = TimeTools.parsePeriodStringToSeconds(period);
        }
        public boolean isValid(){
            return seconds!=-1;
        }
        public void run(){
            delay = scheduler.schedule(this::doCmd,seconds, TimeUnit.SECONDS);
        }
        public void stop(){
            if( delay!=null)
                delay.cancel(true);
        }
        private void doCmd(){
            dQueue.add(Datagram.system(cmd));
        }
    }
}
