package util.data;

import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.nio.file.Path;
import java.util.StringJoiner;

public class StoreCmds {
    public static String replyToCommand(String[] request, boolean html, Path settingsPath ){
        if( request.length == 1 )
            return "Not enough arguments, need at least two";

        var cmds= request[1].split(",");
        String id = cmds[0];

        if( id.equalsIgnoreCase("?")){
            String cyan = html?"": TelnetCodes.TEXT_CYAN;
            String green=html?"":TelnetCodes.TEXT_GREEN;
            String ora = html?"":TelnetCodes.TEXT_ORANGE;
            String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

            StringJoiner join = new StringJoiner("\r\n");

            join.add(TelnetCodes.TEXT_RESET+ora+"Notes"+reg)
                    .add("- a / in the command means both options are valid.")
                    .add("- If no store exists yet, any command will create it first with default delimiter of ','");

            join.add("").add(cyan+"Add new vals"+reg)
                    .add(green+" store:id,addreal/addr,name<,index> "+reg+"-> Add a RealVal to the store, with optional index")
                    .add(green+" store:id,addflag/addf,name<,index> "+reg+"-> Add a FlagVal to the store, with optional index")
                    .add(green+" store:id,addtext/addt,name<,index> "+reg+"-> Add a TextVal to the store, with optional index")
                    .add(green+" store:id,addint/addi,name<,index> "+reg+"-> Add a IntVal to the store, with optional index")
                    .add(green+" store:id,addb/addblank "+reg+"-> Add a blank spot incase index isn't used but a item needs to be skipped");
            join.add("").add(cyan+"Alter attributes"+reg)
                    .add(green+" store:id,delimiter/delim,newdelimiter "+reg+"-> Change the delimiter of the store")
                    .add(green+" store:id,db,dbids:table "+reg+"-> Alter the database/table ");
            return join.toString();
        }

        var dig = XMLdigger.goIn(settingsPath,"dcafs").goDown("streams");
        if( dig.isInvalid())
            return "No streams yet";

        dig.goDown("stream","id",id);
        if( dig.isInvalid() )
            return "No such stream yet "+id;

        // At this point, the digger is pointing to the stream node for the given id
        var fabOpt = XMLfab.alterDigger(dig);
        if( fabOpt.isEmpty())
            return "No valid fab created";
        var fab=fabOpt.get();

        fab.alterChild("store");
        dig.goDown("store");
        if( !dig.current().get().hasAttribute("delimiter"))
            fab.attr("delimiter",",");

        // At this point 'store' certainly exists in memory and dig is pointing to it

        switch (cmds[1]) {
            case "addblank", "addb" -> { // Adds an ignore node
                fab.down();
                fab.addChild("ignore");
                fab.build();
                return "Blank added";
            }
            case "addreal","addr" -> {
                if (cmds.length < 3)
                    return "Not enough arguments: store:id,addreal,name<,index>";
                fab.down();
                if( dig.peekAt("real","id",cmds[2]).hasValidPeek())
                    return "Already a real with that id, try something else?";

                fab.addChild("real").attr("id",cmds[2]).attr("unit");
                if( cmds.length==4 ) {
                    if(NumberUtils.isCreatable(cmds[3])) {
                        fab.attr("index", cmds[3]);
                    }else{
                        return "Not a valid index: "+cmds[3];
                    }
                }
                fab.build();
                return "Real added";
            }
            case "addint","addi" -> {
                if (cmds.length < 3)
                    return "Not enough arguments: store:id,addint,name<,index>";
                fab.down();
                if( dig.peekAt("int","id",cmds[2]).hasValidPeek())
                    return "Already an int with that id, try something else?";

                fab.addChild("int").attr("id",cmds[2]).attr("unit");
                if( cmds.length==4 ) {
                    if(NumberUtils.isCreatable(cmds[3])) {
                        fab.attr("index", cmds[3]);
                    }else{
                        return "Not a valid index: "+cmds[3];
                    }
                }
                fab.build();
                return "Int added";
            }
            case "addtext","addt" -> {
                if (cmds.length < 3)
                    return "Not enough arguments: store:id,addtext,name<,index>";
                fab.down();
                if( dig.peekAt("text","id",cmds[2]).hasValidPeek())
                    return "Already a text with that id, try something else?";

                fab.addChild("text").attr("id",cmds[2]);
                if( cmds.length==4 ) {
                    if(NumberUtils.isCreatable(cmds[3])) {
                        fab.attr("index", cmds[3]);
                    }else{
                        return "Not a valid index: "+cmds[3];
                    }
                }
                fab.build();
                return "Text added";
            }
            case "addflag","addf" -> {
                if (cmds.length < 3)
                    return "Not enough arguments: store:id,addflag,name<,index>";
                fab.down();
                if( dig.peekAt("flag","id",cmds[2]).hasValidPeek())
                    return "Already a flag with that id, try something else?";

                fab.addChild("flag").attr("id",cmds[2]).attr("unit");
                if( cmds.length==4 ) {
                    if(NumberUtils.isCreatable(cmds[3])) {
                        fab.attr("index", cmds[3]);
                    }else{
                        return "Not a valid index: "+cmds[3];
                    }
                }
                fab.build();
                return "Flag added";
            }
            case "delim", "delimiter" -> {
                if (cmds.length < 3)
                    return "Not enough arguments: store:id,delim,delimiter";
                var deli = cmds.length == 4 ? "," : cmds[2];
                fab.attr("delimiter", deli);
                fab.build();
                return "Set the delimiter to '"+deli+"'";
            }
            case "db" -> {
                if (cmds.length < 3 || !request[1].contains(":"))
                    return "Not enough arguments or missing table: store:id,db,dbids:table";
                int start = request[1].indexOf(",db,")+4;
                fab.attr("db", request[1].substring(start));
                fab.build();
                return "Set the db to "+request[1].substring(start);
            }
        }

        return "Unknown command: "+request[0]+":"+request[1];
    }
}
