package util.taskv2;

import das.CommandPool;
import io.netty.channel.EventLoop;
import util.data.RealtimeValues;
import util.taskblocks.CheckBlock;
import util.tools.TimeTools;
import util.xml.XMLdigger;

import java.time.DayOfWeek;
import java.util.ArrayList;

public class TaskStep {

    enum OUTPUT {STREAM,EMAIL,SYSTEM};
    OUTPUT output = OUTPUT.SYSTEM;
    String target = "";

    enum TRIGGER {DELAY,INTERVAL,CLOCK,WHILE,DOWHILE};
    TRIGGER trigger = TRIGGER.DELAY;
    int initialDelayMs=0;
    int intervalDelayMs=0;

    ArrayList<DayOfWeek> taskDays;   // On which days the task is to be executed

    CheckBlock preReq;
    ArrayList<Cmd> cmds = new ArrayList<>();
    CheckBlock postReq;
    boolean continueAfterFailedCheck = false;
    int repeats = 0; // 0=don't repeat, -1=infinite repeat, x>0=limited repeat
    TaskStep next;


    CommandPool cmdPool;
    EventLoop eventLoop;
    RealtimeValues rtvals;

    public TaskStep(CommandPool cmdPool, EventLoop el, RealtimeValues rtvals, XMLdigger dig ){
        this.cmdPool=cmdPool;
        this.eventLoop=el;
        this.rtvals=rtvals;

        buildStep(dig);
    }
    public boolean buildStep( XMLdigger dig ){

        preReq = CheckBlock.prepBlock(rtvals, dig.attr("req",""));

        convertOutput( dig.attr("output","system") );
        converTrigger( dig.attr("trigger", "delay:0s") );
        postReq = CheckBlock.prepBlock(rtvals, dig.attr("postreq",""));
        taskDays = TimeTools.convertDAY( dig.attr("days","") );

        var value = dig.value(""); // Single or multiple actions
        if( value.isEmpty() ){ // empty so multiple or error
            if( !dig.hasPeek("action") )
                return false;
            for( var actionDig : dig.digOut("action"))
                addCmd(actionDig.value(""),actionDig.attr("delay","0s"));

        }
        return true;
    }
    public void addCmd(String action, String period){
        if( action.isEmpty())
            return;
        var delay = (int)TimeTools.parsePeriodStringToMillis(period);
        var cmd = switch( output ){
            case STREAM -> target+":"+action;
            case EMAIL -> {
                var subject = action.substring(0,action.indexOf(";"));
                var content = action.substring(action.indexOf(";")+1);
                yield "email:send,"+target+","+subject+","+content;
            }
            case SYSTEM -> action;
        };
        cmds.add(new Cmd(cmd,delay));
    }
    private void convertOutput( String out ){
        var out_target = out.split(":");
        output = switch( out_target[0].toLowerCase() ){
            case "system" -> OUTPUT.SYSTEM;
            case "stream" -> {
                target = out_target[1];
                yield OUTPUT.STREAM;
            }
            case "email" -> {
                target = out_target[1];
                yield OUTPUT.EMAIL;
            }
            default -> OUTPUT.SYSTEM;
        };
    }
    public void converTrigger( String trig ){
        var trig_delays = trig.split(":");
        trigger = switch( trig_delays[0].toLowerCase()){
            case "delay" -> {
                initialDelayMs = (int) TimeTools.parsePeriodStringToMillis(trig_delays[1]);
                yield TRIGGER.DELAY;
            }
            case "interval" -> {
                if( !trig_delays[1].contains(";") )
                    trig_delays[1] = trig_delays[1]+";"+trig_delays[1];
                var delays = trig_delays[1].split(";");
                initialDelayMs = (int) TimeTools.parsePeriodStringToMillis(delays[0]);
                intervalDelayMs = (int) TimeTools.parsePeriodStringToMillis(delays[1]);
                yield TRIGGER.DELAY;
            }
            case "clock" -> {
                initialDelayMs = (int) TimeTools.
                intervalDelayMs = (int) TimeTools.parsePeriodStringToMillis(delays[1]);
                yield TRIGGER.CLOCK;
            }
            default -> TRIGGER.DELAY;
        };
    }
    public class Cmd{
        int delay=0;
        String cmd;

        public Cmd( String cmd,int delay){
            this.cmd=cmd;
            this.delay=delay;
        }
    }
}
