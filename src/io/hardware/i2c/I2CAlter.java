package io.hardware.i2c;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.util.ArrayList;

public class I2CAlter implements I2COp{
    long delay=0;
    byte reg=-1;
    byte bits=8;
    OPERAND operand = OPERAND.NONE;
    enum OPERAND {OR,AND,NOT,XOR,NONE}

    private final ArrayList<Operation> ops = new ArrayList<>();

    public I2CAlter( XMLdigger dig ){
        readFromXML(dig);
    }
    public void readFromXML( XMLdigger digger ){
        delay = TimeTools.parsePeriodStringToMillis(digger.attr("delay","0s"));
        var hexReg = digger.attr( "reg","");
        bits = (byte) digger.attr( "bits",8);

        if( hexReg.isEmpty() ){
            digger.goUp();
            Logger.error("No reg defined for a alter in "+digger.attr("id","?id?"));
            return;
        }
        reg = NumberUtils.createInteger(hexReg).byteValue();

        switch( digger.attr("operand","") ){
            case "or" -> operand=OPERAND.OR;
            case "not" -> operand=OPERAND.NOT;
            case "and" -> operand=OPERAND.AND;
            case "xor" -> operand=OPERAND.XOR;
            default -> operand=OPERAND.NONE;
        }
        if (operand != OPERAND.NONE) {
            ops.add(new Operation(operand, digger.value("0x00")));
            return;
        }
        // Meaning childnodes?
        for (var d : digger.digOut("*")) {
            if (d.value("+").equalsIgnoreCase("+")) {
                Logger.error("(i2c) -> No value given in alter op node, aborting");
                ops.clear();
                return;
            }
            switch (d.tagName("")) {
                case "or" -> ops.add(new Operation(OPERAND.OR, d.value("0x00")));
                case "not" -> ops.add(new Operation(OPERAND.NOT, d.value("0x00")));
                case "and" -> ops.add(new Operation(OPERAND.AND, d.value("0x00")));
                case "xor" -> ops.add(new Operation(OPERAND.XOR, d.value("0x00")));
                default -> {
                    Logger.error("(i2c) -> Alter wasn't given correct tag name: '" + d.tagName("") + "'");
                    operand = OPERAND.NONE;
                    ops.clear();
                    return;
                }
            }
            if (!ops.get(ops.size() - 1).correctLength(bits / 8)) {
                Logger.error("(i2c) -> Not enough bytes in alter op for " + Integer.toHexString(reg) + "h, aborting.");
                ops.clear();
                return;
            }
        }
    }
    @Override
    public void setDelay(long millis){
        delay=millis;
    }
    @Override
    public long getDelay() {
        return delay;
    }
    @Override
    public ArrayList<Double> doOperation(I2cDevice device,ArrayList<Double> received) {
        if( ops.isEmpty()){
            Logger.error("(i2c) -> Tried doing an alter on "+Integer.toHexString(reg)+"h without ops");
            return new ArrayList<>();
        }
        device.getDevice().writeByte(reg); // Register to alter
        byte[] rec = new byte[bits/8];
        device.getDevice().readBytes(rec);
        byte[] ori = ArrayUtils.clone(rec);

        if (device.inDebug()) {
            Logger.info(device.id() + "(i2c) -> Read from " + Integer.toHexString(reg) + "h: " + Tools.fromBytesToHexString(rec));
            Logger.info( "(i2c) -> Executing "+ops.size()+" ops in alter");
        }
        for( var op : ops) // Go through all the op's in order
            op.doOp(rec);

        if (device.inDebug())
            Logger.info(device.id() + "(i2c) -> After alter:" + Tools.fromBytesToHexString(rec));

        boolean changed=false;
        for (int a = 0; a < ori.length && !changed; a++)
            changed = ori[a] != rec[a];

        if( changed ) {
            rec = ArrayUtils.insert(0, rec, reg);   // Add the register at the front
            device.getDevice().writeBytes(rec);
        }else{
            Logger.info("(i2c) -> Alter wouldn't have affect, so not applying.");
        }
        return new ArrayList<>();
    }

    private static class Operation{
        OPERAND operand = OPERAND.NONE;
        byte[] alter;

        Operation(OPERAND op, String value ){
            if( value.contains("0x")|| value.contains("h")){ // meaning hex
                alter = Tools.fromHexStringToBytes( value );
            }else{ // binary?
                alter = Tools.fromBinaryStringToBytes( value );
            }
            Logger.info("Added op: "+op+" -> "+Tools.fromBytesToHexString(alter));
            this.operand=op;
        }
        boolean correctLength(int length){
            return alter.length==length;
        }
        byte[] doOp( byte[] ori ){
            for (int a = 0; a < ori.length; a++) {
                try {
                    switch (operand) {
                        case OR -> ori[a] |= alter[a];
                        case AND -> ori[a] &= alter[a];
                        case NOT -> ori[a] ^= (byte) 0xFF;
                        case XOR -> ori[a] ^= alter[a];
                    }
                }catch( ArrayIndexOutOfBoundsException e){
                    Logger.error("(i2c) -> Tried altering something that doesn't exist");
                }
            }
            return ori;
        }
    }
}
