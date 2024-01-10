package io.hardware.i2c;

import com.diozero.api.I2CDevice;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

public class I2CRead implements I2COp{
    int bits = 8;
    int reg=-1;
    int recBytes=0;
    int[] bitsets=null;
    boolean msbFirst=true;
    boolean signed = false;
    boolean valid=false;

    long delay=0;

    public I2CRead( XMLdigger dig, int globalbits ){
        bits=globalbits;
        readFromXML(dig);
    }
    public void readFromXML( XMLdigger digger ){

        bits = digger.attr("bits",bits);
        var hexReg = digger.attr( "reg","");

        if( hexReg.isEmpty() ){
            digger.goUp();
            Logger.error("No reg defined for a read in "+digger.attr("id","?id?"));
            return;
        }
        reg = NumberUtils.createInteger(hexReg);

        msbFirst = digger.attr("msbfirst", true);
        signed = digger.attr("signed",false);
        delay = TimeTools.parsePeriodStringToMillis(digger.attr("delay","0s"));

        if( digger.hasAttr("return") ){
            recBytes = digger.attr("return",0);
        }else if( digger.hasAttr("bitsplit")){
            var bs = digger.attr("bitsplit","").split(",");
            bitsets = Arrays.stream(bs).mapToInt(Integer::parseInt).toArray();
            int sum = Arrays.stream(bitsets).sum();
            recBytes = sum/8;
            if( sum%8!=0)
                recBytes++;
        }else{
            Logger.error("No return defined, need either 'return' or 'bitsplit'");
            return;
        }
        if( recBytes > 32 ){
            Logger.error("To many bytes requested in a single op");
            return;
        }
        valid=true;
    }
    @Override
    public long getDelay(){
        return delay;
    }
    @Override
    public void setDelay(long millis){
        delay=millis;
    }
    @Override
    public ArrayList<Integer> doOperation(I2CDevice device) {

        if( !valid ) {
            Logger.error("Read not valid, aborting");
            return new ArrayList<>();
        }
        Logger.debug("Reading block...");
        byte[] rec = new byte[recBytes];
        device.readI2CBlockData(reg, rec);
        Logger.debug("Read: "+Tools.fromBytesToHexString(rec));
        if( bitsets!=null ){
            return convertNibblesToInt(rec,bitsets,msbFirst);
        }
        return convertBytesToInt(rec,bits,msbFirst,signed);
    }
    /**
     * Converts an array of bytes to a list of ints according to the bits. Note that bytes aren't split in their nibbles
     *   fe. 10bits means take the first two bytes and shift right, then take the next two
     * @param bytes The array with the bytes to convert
     * @param bits The amount of bits the int should use
     * @param msbFirst If the msb byte comes first
     * @param signed If the resulting int is signed
     * @return The resulting ints
     */
    public static ArrayList<Integer> convertBytesToInt(byte[] bytes, int bits, boolean msbFirst, boolean signed){

        int[] intResults = new int[bytes.length];
        ArrayList<Integer> ints = new ArrayList<>();

        for( int a=0;a<bytes.length;a++){
            intResults[a]=bytes[a]<0?bytes[a]+256:bytes[a];
        }
        int temp;
        switch (bits) {
            case 8 -> { // Direct byte -> unsigned int conversion
                for (int a : intResults) {
                    ints.add(signed ? MathUtils.toSigned8bit(a) : a);
                }
            }
            //TODO Shift in the other direction and LSB/MSB first
            case 10 -> { // take the full first byte and only the 2 MSB of the second
                for (int a = 0; a < bytes.length; a += 2) {
                    temp = intResults[a] * 4 + intResults[a + 1] / 64;
                    ints.add(signed ? MathUtils.toSigned12bit(temp) : temp);
                }
            }
            //TODO Shift in the other direction and LSB/MSB first
            case 12 -> { // take the full first byte and only the MSB nibble of the second
                for (int a = 0; a < bytes.length; a += 2) {
                    temp = intResults[a] * 16 + intResults[a + 1] / 16;
                    ints.add(signed ? MathUtils.toSigned12bit(temp) : temp);
                }
            }
            case 16 -> { // Concatenate two bytes
                for (int a = 0; a < bytes.length; a += 2) {
                    if (msbFirst) {
                        temp = intResults[a] * 256 + intResults[a + 1];
                    } else {
                        temp = intResults[a] + intResults[a + 1] * 256;
                    }
                    ints.add(signed ? MathUtils.toSigned16bit(temp) : temp);
                }
            }
            //TODO Shift in the other direction?
            case 20 -> { // Concatenate two bytes and take the msb nibble of the third
                for (int a = 0; a < bytes.length; a += 3) {
                    if (msbFirst) {
                        temp = (intResults[a] * 256 + intResults[a + 1]) * 16 + intResults[a + 2] / 16;
                    } else {
                        temp = (intResults[a + 2] * 256 + intResults[a + 1]) * 16 + intResults[a] / 16;
                    }
                    ints.add(signed ? MathUtils.toSigned20bit(temp) : temp);
                }
            }
            case 24 -> { // Concatenate three bytes
                for (int a = 0; a < bytes.length; a += 3) {
                    if (msbFirst) {
                        temp = (intResults[a] * 256 + intResults[a + 1]) * 256 + intResults[a + 2];
                    } else {
                        temp = (intResults[a + 2] * 256 + intResults[a + 1]) * 256 + intResults[a];
                    }
                    ints.add(signed ? MathUtils.toSigned24bit(temp) : temp);
                }
            }
            default -> Logger.error("Tried to use an undefined amount of bits " + bits);
        }
        return ints;
    }
    public static ArrayList<Integer> convertNibblesToInt(byte[] bytes, int[] bits, boolean msbFirst){

        ArrayList<Integer> ints = new ArrayList<>();

        // Convert the bytes to a hex string to easily work with nibbles
        String nibs = Tools.fromBytesToHexString(bytes);
        nibs = nibs.replace("0x","").replace(" ","");

        int n = Arrays.stream(bits).sum()/4;
        if( n > nibs.length() ) {
            Logger.error("Not enough nibbles to convert, needed "+n+" ori: "+nibs);
            return ints;
        }
        int pos=0;
        for( int nibbles : bits ){
            nibbles /=4; // Single character
            var hex = "0x"+ nibs.substring(pos,pos+nibbles);
            ints.add( NumberUtils.createInteger(hex));
            pos+=nibbles;
        }
        return ints;
    }
    public String toString(  ){
        String info = delay==0?"":"Wait for "+TimeTools.convertPeriodtoString(delay, TimeUnit.MILLISECONDS)+" then, ";
        return info+"Read "+(recBytes==1?"a single byte":recBytes+" bytes")+" from reg 0x"+Integer.toHexString(reg);
    }
}