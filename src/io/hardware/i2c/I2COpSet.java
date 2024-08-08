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
                            for( var db : store.dbInsertSets() )
                                dQueue.add( Datagram.system("dbm:"+db[0]+",tableinsert,"+db[1]).payload(mf));
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
     * Execute the next step in the opset
     * @param device The device to run the set on
     */
    public long runOp(I2cDevice device){

        if( index == 0 ){ // Running first op, so reset the received buffer
            received.clear();
        }
        var lastOp = index+1 == ops.size(); // Check if the op to execute is the last one

        if( ops.get(index) instanceof I2COp op){
            try {
                received.addAll(op.doOperation(device));
            }catch( Exception e){
                Logger.error(id+"(i2c) -> Op failed! "+ e);
                index=0;
                return -1;
            }
        }else if( ops.get(index) instanceof MathForward mf){
            var res = mf.addData(received); // Note that a math can contain a store, this will work with bigdecimals
            if( !lastOp ) {  // Not the last operation, replace the int arraylist with the calculated doubles
                received.clear();
                received.addAll(res); // Refill with altered values
            }
        }else if( ops.get(index) instanceof ValStore st){
            st.apply(received);
        }

        if( !lastOp) {
            index++;    // Increment to go to the next op
            if( ops.get(index) instanceof I2COp op) {
                return op.getDelay(); // Return the wait time till the next op
            }
            return 0;
        }
        index=0; // Set finished, reset the index
        return -1;
    }
    public String getResult(){
        return parseDoubleResult(received);
    }
    /**
     * Take the results of an opset and parse the doubles to the chosen output type
     * @param altRes The result of the opset
     */
    private String parseDoubleResult(ArrayList<Double> altRes){
        StringJoiner output = new StringJoiner(";",id+";","");
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
        return output.toString();
    }
    public String getOpsInfo(String prefix,boolean full){
        if( !full )
            return getInfo();

        StringJoiner join = new StringJoiner("\r\n");
        join.add(getInfo());
        ops.forEach( op -> join.add(prefix+op.toString()));
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
