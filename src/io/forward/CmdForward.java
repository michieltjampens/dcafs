package io.forward;

import das.Core;
import io.Writable;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.AbstractVal;
import util.data.RealtimeValues;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class CmdForward extends AbstractForward implements Writable {
    private final ArrayList<Cmd> cmds = new ArrayList<>();
    private final String delimiter="";

    public CmdForward(Element ele, RealtimeValues rtvals  ){
        super(rtvals);
        readOk = readFromXML(ele);
    }
    @Override
    protected boolean addData(String data) {

        String[] split = data.split(delimiter); // Split the data according to the delimiter

        cmds.forEach( cmd -> {
            // Replace the i's with rt data if any and send it away
            Core.addToQueue(Datagram.system(cmd.applyData(split)));
        });
        targets.forEach(t->t.writeLine(id(), data));
        applyDataToStore(data);
        return true;
    }

    @Override
    public boolean readFromXML(Element fwd) {
        var dig = XMLdigger.goIn(fwd);
        // First read the basics (things common to all forwards)
        if( !readBasicsFromXml(dig) )
            return false;

        if( fwd.getTagName().equals("cmds")){ // meaning multiple
            dig.peekOut("cmd").forEach( cmd -> cmds.add(new Cmd(cmd.getTextContent())));
        }else{
            cmds.add(new Cmd(fwd.getTextContent()));
        }
        valid=true;
        return true;
    }
    @Override
    public boolean writeLine(String origin, String data) {
        return addData(data);
    }
    @Override
    protected String getXmlChildTag() {
        return "cmd";
    }
    @Override
    public boolean noTargets(){
        return false;
    }
    @Override
    public String toString() {
        StringJoiner join = new StringJoiner("\r\n");
        join.add("Executing cmds:");
        cmds.forEach( x->join.add(" |-> "+x.cmd));
        if( store!=null)
            join.add(store.toString());
        return join.toString();
    }
    private class Cmd{
        Integer[] is=null;
        ArrayList<AbstractVal> vals = new ArrayList<>();
        String cmd="";
        String ori="";
        int highestI=-1;

        public Cmd( String cmd ){
            this.cmd=cmd;
            this.ori=cmd;
            // Find and replace the rtvals if any
            var iss = Pattern.compile("\\{\\w*}")
                    .matcher(cmd)
                    .results()
                    .map(MatchResult::group)
                    .map( x->x.substring(1,x.length()-1))
                    .sorted()
                    .toArray(String[]::new);

            if( iss.length!=0 ){
                for( int a=0;a<iss.length;a++) {
                    var val = rtvals.getAbstractVal(iss[a]);
                    if( val.isEmpty()){
                        Logger.error( "Didn't find a match for "+iss[a]+" as part of "+cmd);
                    }else{
                        vals.add(val.get());
                        this.cmd = this.cmd.replace("{"+iss[a]+"}","{"+a+"}");
                    }
                }
            }
            // Find the highest used 'i' index
            iss = Pattern.compile("i[0-9]{1,2}")
                    .matcher(cmd)
                    .results()
                    .map(MatchResult::group)
                    .sorted()
                    .toArray(String[]::new);

            if( iss.length!=0 ) {
                is = new Integer[iss.length];
                for( int a=0;a<iss.length;a++) {
                    is[a] = NumberUtils.toInt(iss[a].substring(1));
                    highestI = Math.max(highestI, is[a]);
                }
            }
        }
        public String applyData( String[] data ){
            if( is==null)
                return cmd;
            var alter = cmd;
            if( !vals.isEmpty()){
                for( int a=0;a<vals.size();a++)
                    alter = alter.replace("{"+a+"}",vals.get(a).stringValue());
            }
            for (Integer i : is) {
                alter = alter.replace("i" + i, data[i]);
            }
            return alter;
        }
        public String toString(){
            return ori;
        }
    }
}
