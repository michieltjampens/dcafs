package io.forward;

import io.Writable;
import org.apache.commons.lang3.math.NumberUtils;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.data.ValTools;
import util.xml.XMLtools;
import worker.Datagram;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class CmdForward extends AbstractForward implements Writable {
    private ArrayList<Cmd> cmds = new ArrayList<>();
    private String delimiter="";

    protected CmdForward(String id, String source, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals) {
        super(id, source, dQueue, rtvals);
    }
    public CmdForward(Element ele, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals  ){
        super(dQueue,rtvals);
        readOk = readFromXML(ele);
    }
    @Override
    protected boolean addData(String data) {

        String[] split = data.split(delimiter); // Split the data according to the delimiter

        cmds.forEach( cmd -> {
            // Replace the i's with rt data if any and send it away
            dQueue.add(Datagram.system(cmd.applyData(split,rtvals)));
        });
        String finalData = data;
        targets.forEach(t->t.writeLine(id(), finalData));
        return true;
    }

    @Override
    public boolean readFromXML(Element fwd) {
        if( !readBasicsFromXml(fwd) )
            return false;

        var tag = fwd.getTagName();
        delimiter = XMLtools.getStringAttribute(fwd,"delimiter",delimiter);

        if( tag.equalsIgnoreCase("cmds")){ // meaning multiple
            XMLtools.getChildElements(fwd,"cmd").forEach( cmd -> cmds.add(new Cmd(cmd.getTextContent())));
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
        return join.toString();
    }
    private static class Cmd{
        Integer[] is=null;
        String cmd="";
        int highestI=-1;

        public Cmd( String cmd ){
            this.cmd=cmd;

            // Find the highest used 'i' index
            var iss = Pattern.compile("i[0-9]{1,2}")
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
            for( int a=0;a<is.length;a++){
                alter = alter.replace("i"+is[a],data[is[a]]);
            }
            return alter;
        }
        public String toString(){
            return cmd;
        }
    }
}
