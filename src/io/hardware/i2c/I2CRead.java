package io.hardware.i2c;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class I2CRead implements I2COp{
    int bits;
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
          //  digger.goUp();
            Logger.warn("No reg defined for a read in "+digger.attr("id","?id?"));
        }else {
            reg = NumberUtils.createInteger(hexReg);
        }

        msbFirst = digger.attr("msbfirst", true);
        signed = digger.attr("signed",false);
        delay = TimeTools.parsePeriodStringToMillis(digger.attr("delay","0s"));

        if( digger.hasAttr("return") ){
            var ret = digger.attr("return",0);
            recBytes = (ret * bits)/8;
            if( bits%8!=0)
                recBytes++;
        }else if( digger.hasAttr("bitsplit")){
            var bs = digger.attr("bitsplit","").split(",");
            bitsets = Arrays.stream(bs).mapToInt(Integer::parseInt).toArray();
            int sum = Arrays.stream(bitsets).sum();
            recBytes = sum/8;
            if( sum%8!=0)
                recBytes++;
        }else{
            Logger.error("(i2c) -> No return defined, need either 'return' or 'bitsplit'");
            return;
        }
        if( recBytes > 32 ){
            Logger.error("(i2c) -> To many bytes requested in a single op");
            return;
        }
        valid=true;
        Logger.info("Read op ok");
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
    public ArrayList<Double> doOperation(I2cDevice device,ArrayList<Double> received) {

        if( !valid ) {
            Logger.error(device.id()+"(i2c) -> Read not valid, aborting");
            return new ArrayList<>();
        }
        Logger.debug("Reading block...");
        byte[] rec = new byte[recBytes];
        if (reg == -1) {
            rec = device.getDevice().readBytes(recBytes);
        }else if( recBytes==1 ){
            rec[0] = device.getDevice().readByteData((byte)reg);
        } else {
            device.getDevice().readNoStop((byte) reg, rec, false);
        }
        if( device.inDebug())
            Logger.info(device.id()+"(i2c) -> Read: "+Tools.fromBytesToHexString(rec));
        if (bitsets != null)
            return convertNibblesToDouble(rec, bitsets);
        return convertBytesToDouble(rec,bits,msbFirst,signed);
    }
    /**
     * Converts an array of bytes to a list of ints according to the bits. Note that bytes aren't split in their nibbles
     *   fe. 10bits means take the first two bytes and shift right, then take the next two
     * @param bytes The array with the bytes to convert
     * @param bits The amount of bits the int should use
     * @param msbFirst If the msb byte comes first
     * @param signed If the resulting int is signed
     * @return The resulting integers
     */
    public static ArrayList<Double> convertBytesToDouble(byte[] bytes, int bits, boolean msbFirst, boolean signed){

        int[] intResults = new int[bytes.length];
        for (int a = 0; a < bytes.length; a++)
            intResults[a] = bytes[a] < 0 ? bytes[a] + 256 : bytes[a];

        int match = bytes.length%(bits/8);
        if( match !=0 )
            System.out.println("Mismatch between bytes received and bits aggregated, bytes mismatch: "+match);

        if (bits % 8 == 0) // works up to 32bit
            return multipleOfEight(intResults, bytes, bits, msbFirst, signed);
        return notMultipleOfEight(intResults, bytes, bits, msbFirst, signed);
    }

    private static ArrayList<Double> multipleOfEight(int[] intResults, byte[] bytes, int bits, boolean msbFirst, boolean signed) {
        ArrayList<Double> dbs = new ArrayList<>();
        int msbMod = msbFirst ? 1 : -1;
        int first = msbFirst ? 0 : bits / 8 - 1;

        for (int a = first; a < bytes.length; a += bits / 8) {
            double total = 0;
            for (int b = 0; b < bits / 8 && (a + b) < intResults.length && (a + b) > -1; b++) {
                total += intResults[a + msbMod * b];
                if (b != bits / 8 - 1)
                    total *= 256;
            }
            if (signed) {
                total = switch (bits) {
                    case 8 -> MathUtils.toSigned8bit((int) total);
                    case 16 -> MathUtils.toSigned16bit((int) total);
                    case 24 -> MathUtils.toSigned24bit((int) total);
                    case 32 -> MathUtils.toSigned32bit((int) total);
                    default -> total;
                };
            }
            dbs.add(total);
        }
        return dbs;
    }

    private static ArrayList<Double> notMultipleOfEight(int[] intResults, byte[] bytes, int bits, boolean msbFirst, boolean signed) {
        int temp;
        ArrayList<Double> dbs = new ArrayList<>();
        switch (bits) {
            //TODO Shift in the other direction and LSB/MSB first
            case 10 -> { // take the full first byte and only the 2 MSB of the second
                for (int a = 0; a < bytes.length; a += 2) {
                    temp = intResults[a] * 4 + intResults[a + 1] / 64;
                    dbs.add((double) (signed ? MathUtils.toSigned10bit(temp) : temp));
                }
            }
            //TODO Shift in the other direction and LSB/MSB first
            case 12 -> { // take the full first byte and only the MSB nibble of the second
                for (int a = 0; a < bytes.length; a += 2) {
                    temp = intResults[a] * 16 + intResults[a + 1] / 16;
                    dbs.add( (double) (signed ? MathUtils.toSigned12bit(temp) : temp));
                }
            }
            //TODO Shift in the other direction?
            case 20 -> {
                return shiftTwenty(intResults, bytes, msbFirst, signed);
            } // Concatenate two bytes and take the msb nibble of the third
            default -> Logger.error("Tried to use an undefined amount of bits " + bits);
        }
        return dbs;
    }

    private static ArrayList<Double> shiftTwenty(int[] intResults, byte[] bytes, boolean msbFirst, boolean signed) {
        int temp;
        ArrayList<Double> dbs = new ArrayList<>();
        for (int a = 0; a < bytes.length; a += 3) {
            if (msbFirst) {
                temp = (intResults[a] * 256 + intResults[a + 1]) * 16 + intResults[a + 2] / 16;
            } else {
                temp = (intResults[a + 2] * 256 + intResults[a + 1]) * 16 + intResults[a] / 16;
            }
            dbs.add((double) (signed ? MathUtils.toSigned20bit(temp) : temp));
        }
        return dbs;
    }
    public static ArrayList<Double> convertNibblesToDouble(byte[] bytes, int[] bits) {

        // Convert the bytes to a hex string to easily work with nibbles
        String nibs = Tools.fromBytesToHexString(bytes);
        nibs = nibs.replace("0x","").replace(" ","");

        int n = Arrays.stream(bits).sum()/4;
        if( n > nibs.length() ) {
            Logger.error("Not enough nibbles to convert, needed "+n+" ori: "+nibs);
            return new ArrayList<>();
        }

        int pos=0;
        ArrayList<Double> dbs = new ArrayList<>();
        for( int nibbles : bits ){
            nibbles /=4; // Single character
            var hex = "0x"+ nibs.substring(pos,pos+nibbles);
            dbs.add( (double) NumberUtils.createLong(hex) );
            pos+=nibbles;
        }
        return dbs;
    }
    public String toString(  ){
        String info = delay == 0 ? "" : "Wait for " + TimeTools.convertPeriodToString(delay, TimeUnit.MILLISECONDS) + ". ";
        return info+"Read "+(recBytes==1?"a single byte":recBytes+" bytes")+(reg==-1?"":" starting at reg 0x"+(reg<16?"0":"")+Integer.toHexString(reg));
    }
}