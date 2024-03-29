package io.hardware.i2c;

import io.forward.MathForward;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.data.RealtimeValues;
import util.data.ValStore;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class I2COpSet {
    public enum OUTPUT_TYPE{DEC,HEX,BIN,CHAR,NONE}
    private final ArrayList<Double> received = new ArrayList<>(); // Data received through i2c read ops
    private final ArrayList<Object> ops = new ArrayList<>(); // Collection of ops, maths and stores
    private int index=0;                // Next op that will be executed
    private ScheduledFuture<?> future;  // Future about the next op
    private String id="";           // The id of this set of operations
    private String info="";         // Info about what this group is supposed to do
    private OUTPUT_TYPE outType = OUTPUT_TYPE.NONE;
    boolean valid = true;
    public I2COpSet(XMLdigger dig, RealtimeValues rtvals, BlockingQueue<Datagram> dQueue, String deviceId){
        readFromXml(dig,rtvals,dQueue,deviceId);
    }
    public boolean isInvalid(){
        return !valid;
    }
    public void readFromXml(XMLdigger digger, RealtimeValues rtvals, BlockingQueue<Datagram> dQueue, String deviceId){

        id = digger.attr("id","");
        info = digger.attr("info","");  // Info abo
        var msb = digger.attr("msbfirst",true);

        // Default amount of bits to combine
        int bits = digger.attr("bits", 8);
        setOutputType( digger.attr("output","") );

        long nextDelay=0;
        for( var dig : digger.digOut("*")){
            var fabOpt = XMLfab.alterDigger(dig);
            if( fabOpt.isEmpty()) {
                Logger.error(id+"(i2c) -> Failed to convert digger to fab");
                continue;
            }
            var fab = fabOpt.get();
            if( nextDelay!=0){
                fab.attr("delay",nextDelay+"ms");
                nextDelay=0;
            }
            if( !fab.getCurrentElement().hasAttribute("msbfirst")) {
                fab.attr("msbfirst", String.valueOf(msb));
            }
            fab.attr("id",id);

            // The group attribute used for store might contain $id that needs to be replaced with the device id
            if( dig.hasAttr("group") ){
                var group = dig.attr("group","");
                if( group.startsWith("$") && group.endsWith("id")){
                    fab.attr("group",deviceId);
                }
            }else{
                fab.attr("group",deviceId);
            }
            var altDig = XMLdigger.goIn(fab.getCurrentElement());

            switch( dig.tagName("")){
                case "read" -> ops.add( new I2CRead(altDig, bits));
                case "write" -> ops.add( new I2CWrite(altDig));
                case "alter"-> ops.add( new I2CAlter(altDig));
                case "math" -> altDig.current().ifPresent( x -> {
                    var mf = new MathForward(x,dQueue,rtvals);
                    if( mf.isReadOk() ) {
                        ops.add(mf);
                    }else{
                        Logger.info(id+"(i2cop) -> Failed to read math node");
                        valid=false;
                    }
                });
                case "store" -> {
                    var store = new ValStore(id, altDig.currentTrusted(),rtvals);
                    if( store.isInvalid()){
                        valid=false;
                    }else {
                        if (ops.get(ops.size() - 1) instanceof MathForward mf) {
                            mf.setStore(store);
                            var ids = store.dbIds().split(",");
                            for (var dbid : ids) {
                                dQueue.add(Datagram.system("dbm:" + dbid + ",tableinsert," + store.dbTable()).payload(mf));
                            }
                        } else { // Not added to a math
                            ops.add(store);
                        }
                    }
                }
                case "wait" -> nextDelay = dig.value(0); // The next op needs to be delayed
            }
            if( !valid ) // If something went wrong stop processing
                break;
        }
    }
    public String id(){
        return id;
    }
    public void setOutputType( String radix ){
        if( radix.isEmpty()||outType!=OUTPUT_TYPE.NONE) // don't overwrite
            return;
        outType = switch( radix.toLowerCase()){
            case "hex" -> OUTPUT_TYPE.HEX;
            case "bin" -> OUTPUT_TYPE.BIN;
            case "char" -> OUTPUT_TYPE.CHAR;
            default -> OUTPUT_TYPE.DEC;
        };
    }
    public String getInfo(){
        return id+" -> "+info;
    }

    /**
     * Start this opset on the given device
     * @param device The device to run it on
     * @param scheduler The scheduler to use for the threads
     */
    public void startOp(ExtI2CDevice device, EventLoopGroup scheduler){
        Logger.debug(id+" -> Starting on "+device.id());
        index=0;
        received.clear();
        if( future != null)
            future.cancel(true);
        runOp(device,scheduler);
    }

    /**
     * Execute the next step in the opset
     * @param device The device to run the set on
     * @param scheduler The scheduler to get a thread from to use it
     */
    private void runOp(ExtI2CDevice device, EventLoopGroup scheduler ){
        long delay = 0;
        var lastOp = index+1 == ops.size(); // Check if the op to execute is the last one

        if( ops.get(index) instanceof I2COp op){
            Logger.debug(id+"(i2c) ->  Executing op: "+op);
            received.addAll(op.doOperation(device));
            delay = op.getDelay();
        }else if( ops.get(index) instanceof MathForward mf){
            Logger.debug(id+"(i2c) ->  Executing mf:"+mf.id());
            var res = mf.addData(received); // Note that a math can contain a store, this will work with bigdecimals
            if( lastOp ) { // Meaning that was last operation
                forwardDoubleResult(device, res);
                device.doNext(scheduler);
                return;
            }else{ // Not the last operation, replace the int arraylist with the calculated doubles
                received.clear();
                received.addAll(res); // Refill with altered values
            }
        }else if( ops.get(index) instanceof ValStore st){
            st.apply(received);
        }

        if( !lastOp) {
            index++;    // Increment to go to the next op
            delay = Math.max(delay,1);  // delay needs to be at least 1ms
            Logger.debug(id+" -> Scheduling next op in "+delay+"ms");
            future = scheduler.schedule(() -> runOp(device, scheduler), delay, TimeUnit.MILLISECONDS);
        }else{
            forwardDoubleResult(device,received);
            device.doNext(scheduler);
        }
    }

    /**
     * Take the results of an opset and parse the doubles to the chosen output type
     * @param device The device the results came from
     * @param altRes The result of the opset
     */
    private void forwardDoubleResult(ExtI2CDevice device, ArrayList<Double> altRes){

        StringJoiner output = new StringJoiner(";",device.id()+";"+id+";","");
        switch (outType) {
            case DEC,NONE -> altRes.forEach(x -> {
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
        forwardData(device,output.toString());
    }
    /**
     * Actually forward the result to the registered targets
     * @param device The device that was used
     * @param output The formatted output
     */
    private void forwardData( ExtI2CDevice device, String output){
        try {
            device.getTargets().forEach(wr -> wr.writeLine(output));
            device.getTargets().removeIf(wr -> !wr.isConnectionValid()); // Remove unresponsive targets
        }catch(Exception e){
            Logger.error(e);
        }
        Logger.tag("RAW").warn( device.id() + "\t" + output );
    }
    public String getOpsInfo(String prefix,boolean full){
        if( !full )
            return getInfo();

        StringJoiner join = new StringJoiner("\r\n");
        join.add(getInfo());
        ops.forEach( op -> join.add(prefix+op.toString()));
        /*if( math !=null) {
            join.add(prefix+"Math ops");
            join.add( prefix+math.getRules().replace("\r\n","\r\n"+prefix));
        }*/
        return join.toString();
    }
    public void removeRtvals( RealtimeValues rtvals){
        for( var op : ops ){
            if( op instanceof MathForward mf ){
                mf.getStore().ifPresent( store -> store.removeRealtimeValues(rtvals));
            }else if( op instanceof ValStore store ){
                store.removeRealtimeValues(rtvals);
            }
        }
    }
}
