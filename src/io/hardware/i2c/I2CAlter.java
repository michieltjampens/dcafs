package io.hardware.i2c;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import java.util.ArrayList;

public class I2CAlter implements I2COp{
    long delay=0;
    byte reg=-1;
    byte alter=0;
    OPERAND operand = OPERAND.NONE;
    enum OPERAND {OR,AND,NOT,XOR,NONE};


    public I2CAlter( XMLdigger dig ){
        readFromXML(dig);
    }
    public void readFromXML( XMLdigger digger ){
        delay = TimeTools.parsePeriodStringToMillis(digger.attr("delay","0s"));
        var hexReg = digger.attr( "reg","");

        if( hexReg.isEmpty() ){
            digger.goUp();
            Logger.error("No reg defined for a alter in "+digger.attr("id","?id?"));
            return;
        }
        reg = NumberUtils.createInteger(hexReg).byteValue();

        alter = (byte)digger.value(0);

        switch( digger.attr("operand","") ){
            case "or" -> operand=OPERAND.OR;
            case "not" -> operand=OPERAND.NOT;
            case "and" -> operand=OPERAND.AND;
            case "xor" -> operand=OPERAND.XOR;
            default -> operand=OPERAND.NONE;
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
    public ArrayList<Integer> doOperation(ExtI2CDevice device) {
        device.writeByte(reg);
        var rec = device.readByte();
        if( device.isDebug() )
            Logger.info(device.id() +"(i2c) -> Read 0x"+Integer.toHexString(rec));
        switch(operand){
            case OR -> rec |= alter;
            case AND -> rec &= alter;
            case NOT ->  rec ^= 0xFF;
            case XOR -> rec ^= alter;
        }
        device.writeByte( rec );
        return new ArrayList<>();
    }


}
