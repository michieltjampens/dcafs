package util.cmds;

import io.telnet.TelnetCodes;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.nio.file.Path;
import java.util.StringJoiner;

public class PathCmds {
    public static String replyToCommand(String request, boolean html, Path settingsPath ){

        var cmds= request.split(",");
        String id = cmds[0];

        if( id.equalsIgnoreCase("?")){
            String cyan = html?"": TelnetCodes.TEXT_CYAN;
            String green=html?"":TelnetCodes.TEXT_GREEN;
            String ora = html?"":TelnetCodes.TEXT_ORANGE;
            String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

            StringJoiner join = new StringJoiner("\r\n");

            join.add(TelnetCodes.TEXT_RESET+ora+"Notes"+reg)
                    .add("- a / in the command means both options are valid.");

            join.add("").add(cyan+"Add new steps"+reg)
                    .add(green+" pf:pathid,addfilter/addf,rule "+reg+"-> Add a filter to the path with the given rule")
                    .add(green+" pf:pathid,addeditor/adde,name<,index> "+reg+"-> Add an editor to the store with the given rule")
                    .add(green+" pf:pathid,addtext/addt,name<,index> "+reg+"-> Add a TextVal to the store, with optional index")
                    .add(green+" pf:pathid,addint/addi,name<,index> "+reg+"-> Add a IntVal to the store, with optional index")
                    .add(green+" pf:pathid,addb/addblank "+reg+"-> Add a blank spot incase index isn't used but a item needs to be skipped");
            join.add("").add(cyan+"Alter attributes"+reg)
                    .add(green+" pf:pathid,delimiter/delim,newdelimiter "+reg+"-> Change the delimiter of the store")
                    .add(green+" pf:pathid,db,dbids:table "+reg+"-> Alter the database/table ")
                    .add(green+" pf:pathid,group,newgroup"+reg+"-> Alter the group used");
            return join.toString();
        }

        var dig = XMLdigger.goIn(settingsPath,"dcafs").goDown("paths");
        if( dig.isInvalid())
            return "No paths yet";

        dig.goDown("path","id",id);
        if( dig.isInvalid() )
            return "No such path yet "+id;

        // At this point, the digger is pointing to the path node for the given id
        // But this might be an import....
        var fabOpt = XMLfab.alterDigger(dig); // Create a fab with parentnode the path node
        if( fabOpt.isEmpty())
            return "No valid fab created";
        var fab=fabOpt.get();

        switch( cmds[1]){
            case "addfilter","addf" -> {
                var rule = cmds[2].split(":");
                fab.addChild("filter").attr("type",rule[0]).content(rule[1]);
                fab.build();
                return "Filter added";
            }
            case "store" -> {
                if( cmds.length <4 )
                    return "Not enough arguments, need atleast 4: pf:pathid,store,cmd,value";
                // pf:id,store,addi,rolled,4
                return StoreCmds.replyToPathCmd(cmds[0]+","+cmds[2]+","+cmds[3]+(cmds.length>4?","+cmds[4]:""),settingsPath);
            }
        }
        return "unknown command: pf:"+request;
    }
}
