package io.hardware.i2c;

import org.tinylog.Logger;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class I2CWrite implements I2COp{

    long delay=0;
    byte[] towrite;
    boolean valid=false;

    public I2CWrite( XMLdigger dig ){
        readFromXML(dig);
    }
    public void readFromXML( XMLdigger digger ) {

        delay = TimeTools.parsePeriodStringToMillis(digger.attr("delay", "0s"));

        var reg = digger.attr("reg", "");
        var content = digger.value("");
        if( content.isEmpty() ){
            Logger.error("No data to write");
        }
        if( !reg.isEmpty())
            content = reg+" "+content;

        if( content.contains("i")){ // Check if it contains a reference to an args
            valid = decodeContent(content);
        }else{ // No references, so plain data
            towrite = Tools.fromHexStringToBytes( content );
        }

    }
    private boolean decodeContent( String wr ){
        wr = wr.toLowerCase().replace("0x", "");
        var list = Tools.splitList(wr);
        towrite = new byte[list.length];
        var args = new int[list.length];

        for( int a=0;a<list.length;a++ ){
            if( list[a].startsWith("i")){ // so reference to an arg
                towrite[a]=0;
                if( list[a].length()>1) {
                    args[a] = Integer.parseInt(list[a].substring(1));
                }else{
                    Logger.error("Invalid index given in "+wr);
                    return false;
                }
            }else{ // so an actual number
                var opt = Tools.fromBaseToByte(16,list[a]);
                if( opt.isEmpty()) {
                    Logger.error("Failed to convert "+wr);
                    return false;
                }else{
                    towrite[a]=opt.get();
                    args[a]=-1;
                }
            }
        }
        return true;
    }
    @Override
    public ArrayList<Double> doOperation(ExtI2CDevice device) {
        if( device.isDebug())
            Logger.info(device.id()+"(i2c) -> Writing "+Tools.fromBytesToHexString(towrite));
        try{
            device.writeBytes(towrite);
        }catch( RuntimeException e){
            Logger.error(device.id()+"(i2c) -> Failed to write "+Tools.fromBytesToHexString(towrite)+" -> "+e.getMessage());
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
        String info = delay==0?"":"Wait for "+TimeTools.convertPeriodtoString(delay, TimeUnit.MILLISECONDS)+" then, ";
        return info+"Write "+Tools.fromBytesToHexString(towrite,1, towrite.length)+" to reg 0x"+Integer.toHexString(towrite[0]>0?towrite[0]:towrite[0]+256).toUpperCase();
    }
}
