package io.hardware.i2c;

import io.forward.MathForward;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.data.RealtimeValues;
import util.data.ValStore;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class I2COpSet {
    public enum OUTPUT_TYPE{DEC,HEX,BIN,CHAR}
    ArrayList<Integer> received = new ArrayList<>();
    ArrayList<I2COp> ops = new ArrayList<>();
    MathForward math;
    int index=0;
    ScheduledFuture<?> future;
    private String id="";           // The id of this set of operations
    private String info="";         // Info about what this group is supposed to do

    OUTPUT_TYPE outType = OUTPUT_TYPE.DEC;
    public I2COpSet(XMLdigger dig, RealtimeValues rtvals, BlockingQueue<Datagram> dQueue){
        readFromXml(dig,rtvals,dQueue);
    }
    public void readFromXml(XMLdigger digger, RealtimeValues rtvals, BlockingQueue<Datagram> dQueue){

        id = digger.attr("id","");
        info = digger.attr("info","");  // Info abo
        // Default amount of bits to combine
        int bits = digger.attr("bits", 8);
        outType = switch( digger.attr("radix","dec").toLowerCase()){
            case "hex" -> OUTPUT_TYPE.HEX;
            case "bin" -> OUTPUT_TYPE.BIN;
            case "char" -> OUTPUT_TYPE.CHAR;
            default -> OUTPUT_TYPE.DEC;
        };

        long nextDelay=0;
        for( var dig : digger.digOut("*")){
            var fab = XMLfab.alterDigger(dig).get();
            if( nextDelay!=0){
                fab.attr("delay",nextDelay+"ms");
                nextDelay=0;
            }
            fab.attr("id",id);
            var altDig = XMLdigger.goIn(fab.getCurrentElement());

            switch( dig.tagName("")){
                case "read" -> ops.add( new I2CRead(altDig, bits));
                case "write" -> ops.add( new I2CWrite(altDig));
                case "alter"-> ops.add( new I2CAlter(altDig));
                case "math" -> altDig.current().ifPresent( x -> math = new MathForward(x,dQueue,rtvals));
                case "store" -> {
                    var store = new ValStore(id);
                    store.reload(altDig.currentTrusted(),rtvals);
                    if( math != null ) {
                        math.setStore(store);
                        var ids = store.dbIds().split(",");
                        for( var dbid : ids ){
                            dQueue.add( Datagram.system("dbm:"+dbid+",tableinsert,"+store.dbTable()).payload(math));
                        }
                    }
                }
                case "wait" -> nextDelay = dig.value(0);
            }
        }
    }
    public String getInfo(){
        return id+" -> "+info;
    }
    public void startOp(ExtI2CDevice device, EventLoopGroup scheduler){
        Logger.info("Starting "+id+" on "+device.getID());
        index=0;
        received.clear();
        if( future != null)
            future.cancel(true);
        runOp(device,scheduler);
    }
    private void runOp(ExtI2CDevice device, EventLoopGroup scheduler ){

        received.addAll( ops.get(index).doOperation(device) );
        index++;    // Increment to go to the next op
        if( index < ops.size()) {
            long delay = Math.max(ops.get(index).getDelay(),1);
            Logger.debug("Scheduling next op in "+delay+"ms");
            future = scheduler.schedule(() -> runOp(device, scheduler), delay, TimeUnit.MILLISECONDS);
        }else{ // Meaning last operation was done
            if( math!=null) {
                var res = math.addData(received);
                forwardDoubleResult(device,res);
            }else{
                forwardIntegerResult(device);
            }
            received.clear();
        }
    }
    private void forwardDoubleResult(ExtI2CDevice device, ArrayList<Double> altRes){

        StringJoiner output = new StringJoiner(";",device.getID()+";"+id+";","");
        switch (outType) {
            case DEC -> altRes.forEach(x -> {
                if (x.toString().endsWith(".0")) {
                    output.add( String.valueOf(x.intValue()) );
                } else {
                    output.add( String.valueOf(x) );
                }
            });
            case HEX -> altRes.forEach(x -> {
                String val = Integer.toHexString(x.intValue()).toUpperCase();
                output.add("0x" + (val.length() == 1 ? "0" : "") + val);
            });
            case BIN -> altRes.forEach(x -> output.add("0b" + Integer.toBinaryString(x.intValue())));
            case CHAR -> {
                var line = new StringJoiner("");
                altRes.forEach(x -> line.add(String.valueOf((char) x.intValue())));
                output.add(line.toString());
            }
        }
        try {
            device.getTargets().forEach(wr -> wr.writeLine(output.toString()));
            device.getTargets().removeIf(wr -> !wr.isConnectionValid());
        }catch(Exception e){
            Logger.error(e);
        }
        Logger.tag("RAW").warn( device.getID() + "\t" + output );
    }
    private void forwardIntegerResult(ExtI2CDevice device){
        StringJoiner output = new StringJoiner(";",device.getID()+";"+id+";","");
        switch (outType) {
            case DEC -> received.stream().map(String::valueOf).forEach(output::add);
            case HEX -> received.stream().map( i->Integer.toHexString(i).toUpperCase())
                            .forEach( val -> output.add("0x" + (val.length() == 1 ? "0" : "") + val) );
            case BIN -> received.forEach(x -> output.add("0b" + Integer.toBinaryString(x)));
            case CHAR -> {
                var line = new StringJoiner("");
                received.forEach(x -> line.add(String.valueOf((char) x.intValue())));
                output.add(line.toString());
            }
        }
        try {
            device.getTargets().forEach(wr -> wr.writeLine(output.toString()));
            device.getTargets().removeIf(wr -> !wr.isConnectionValid());
        }catch(Exception e){
            Logger.error(e);
        }
        Logger.tag("RAW").warn( device.getID() + "\t" + output );
    }
    public String getOpsInfo(String prefix,boolean full){
        if( !full )
            return getInfo();

        StringJoiner join = new StringJoiner("\r\n");
        join.add(getInfo());
        ops.forEach( op -> join.add(prefix+op.toString()));
        if( math !=null) {
            join.add(prefix+"Math ops");
            join.add( prefix+math.getRules().replace("\r\n","\r\n"+prefix));
        }
        return join.toString();
    }
}
