package io.hardware.i2c;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class I2CWrite implements I2COp{

    long delay=0;
    ToWrite write = new ToWrite();
    boolean valid=false;

    public I2CWrite( XMLdigger dig ){
        readFromXML(dig);
    }
    public void readFromXML( XMLdigger digger ) {

        delay = TimeTools.parsePeriodStringToMillis(digger.attr("delay", "0s"));
        write.setRegister( digger.attr("reg", "") );
        var addCnt = digger.attr("addsize",false);

        var dtype = digger.attr("datatype","hex");
        var content = digger.value("");
        if( content.isEmpty() ){
            Logger.error("No data to write");
            return;
        }
        byte[] towrite = new byte[0];
        if( content.startsWith("i")) {// Check if it contains a reference
            var bytes = digger.attr("bits",16)/8;
            write.setIndex(NumberUtils.toInt(content.substring(1)),bytes);
            return;
        }
        switch( dtype ){
            case "hex" -> towrite = Tools.fromHexStringToBytes(content);
            case "dec" -> towrite = Tools.fromDecStringToBytes(content);
            case "ascii" ->  towrite = Tools.fromStringToBytes(content);
        }
        // If the count needs to be added, do so.
        if( addCnt ) {
            byte size = (byte) towrite.length;
            towrite = ArrayUtils.insert(0, towrite,size);
        }
        write.setData(towrite);
    }
    @Override
    public ArrayList<Double> doOperation(I2cDevice device,ArrayList<Double> received) {
        if( device.inDebug())
            Logger.info(device.id()+"(i2c) -> Writing "+Tools.fromBytesToHexString(write.getData(received)));
        try{
            device.getDevice().writeBytes(write.getData(received));
        }catch( RuntimeException e){
            Logger.error(device.id()+"(i2c) -> Failed to write "+Tools.fromBytesToHexString(write.getData(received))+" -> "+e.getMessage());
        }
        return new ArrayList<>();
    }

    @Override
    public long getDelay() {
        return delay;
    }
    @Override
    public void setDelay(long millis){
        delay=millis;
    }
    @Override
    public String toString(){
        String info = delay == 0 ? "" : "Wait for " + TimeTools.convertPeriodToString(delay, TimeUnit.MILLISECONDS) + " then, ";
        return info+"Write "+Tools.fromBytesToHexString(write.data)+" to reg "+write.reg;
    }

    private static class ToWrite{
        RegAddress reg;

        byte[] data;
        int index=-1;
        int bytes=0;

        void setRegister( String r ){
            this.reg=new RegAddress(r);
        }
        void setData(byte[] d){
            this.data=d;
        }
        void setIndex(int i, int bytes){
            this.index=i;
            this.bytes=bytes;
        }
        byte[] getData(ArrayList<Double> received) {
            if( index !=-1 ){
                data = fromDoubleToIntegerBytes(received.get(index),bytes);
            }
            Byte regData = reg.getRegister(received);
            if ( regData  != null)
                return ArrayUtils.insert(0, data, regData);
            return data;
        }
    }
    private static class RegAddress {
        Byte reg=null;
        int index=-1;

        public RegAddress(String ori){
            if(ori.startsWith("i")){
                index = NumberUtils.toInt(ori.substring(1));
            }else{
                Tools.fromBaseToByte(16,ori).ifPresent( b -> reg=b);
            }
        }
        public Byte getRegister( ArrayList<Double> received ){
            if( index==-1)
                return reg;
            Logger.info(index +" <-> "+received.size());
            if( index < received.size())
                return fromDoubleToIntegerBytes(received.get(index),1)[0];
            return null;
        }
        public String toString(){
            if( index ==-1 ){
                if( reg==null)
                    return "invalid";
                return "0x"+Integer.toHexString(reg);
            }
            return "i"+index+" from params";
        }
    }
    private static byte[] fromDoubleToIntegerBytes( double var,int size ){
        try {
            var val = (int) Math.rint(var); // Make it integer
            var data = BigInteger.valueOf(val).toByteArray();
            while( data.length > size && size > 0){ // To many bytes, remove leading
                data=ArrayUtils.remove(data,0);
            }
            while( data.length != size ){ // To few bytes, add leading
                data=ArrayUtils.insert(0,data,(byte)0);
            }
            return data;
        }catch( ArrayIndexOutOfBoundsException e){
            Logger.error("(i2c) -> Array of out bounds when retrieving value to write");
        }
        return new byte[0];
    }
}
