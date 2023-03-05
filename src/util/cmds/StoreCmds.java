package util.cmds;

import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.util.StringJoiner;

public class StoreCmds {
    public static String replyToCommand(String request, boolean html, Path settingsPath ){

        String id = request.split(",")[0];

        if( id.equalsIgnoreCase("?") ){
            String cyan = html?"": TelnetCodes.TEXT_CYAN;
            String green=html?"":TelnetCodes.TEXT_GREEN;
            String ora = html?"":TelnetCodes.TEXT_ORANGE;
            String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;

            StringJoiner join = new StringJoiner("\r\n");

            join.add(TelnetCodes.TEXT_RESET+ora+"Notes"+reg)
                    .add("- a / in the command means both options are valid.")
                    .add("- If no store exists yet, any command will create it first with default delimiter of ','");

            join.add("").add(cyan+"Add new vals"+reg)
                    .add(green+" store:streamid,addreal/addr,name<,index> "+reg+"-> Add a RealVal to the store, with optional index")
                    .add(green+" store:streamid,addflag/addf,name<,index> "+reg+"-> Add a FlagVal to the store, with optional index")
                    .add(green+" store:streamid,addtext/addt,name<,index> "+reg+"-> Add a TextVal to the store, with optional index")
                    .add(green+" store:streamid,addint/addi,name<,index> "+reg+"-> Add a IntVal to the store, with optional index")
                    .add(green+" store:streamid,addb/addblank "+reg+"-> Add a blank spot incase index isn't used but a item needs to be skipped");
            join.add("").add(cyan+"Alter attributes"+reg)
                    .add(green+" store:streamid,delimiter/delim,newdelimiter "+reg+"-> Change the delimiter of the store")
                    .add(green+" store:streamid,db,dbids:table "+reg+"-> Alter the database/table ")
                    .add(green+" store:streamid,group,newgroup"+reg+"-> Alter the group used");
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
        fab.down(); // make store the parent node instead of stream
        // At this point 'store' certainly exists in memory and dig is pointing to it
        return doCmd(request,fab,dig);
    }
    public static String replyToPathCmd(String request, Path xmlPath ){

        String id = request.split(",")[0];

        var dig = XMLdigger.goIn(xmlPath,"dcafs");
        if( dig.isInvalid())
            return "No such file";

        if( xmlPath.toString().endsWith("settings.xml")) {
            dig.goDown("paths");
            if( dig.isInvalid())
                return "No paths defined yet";
        }

        dig.goDown("path","id",id);
        if( dig.isInvalid() )
            return "No such path yet "+id;

        // At this point, the digger is pointing to the path node for the given id
        // Now determine if the last step in the path is a store...
        boolean startNew = false;
        dig.peekAt("*");  // Check if something is already in the path
        if( !dig.hasValidPeek() ) { // path has no steps
            startNew = true;
        }else{
            dig.goDown("*");
            dig.toLastChild();
            startNew = !dig.tagName("").equalsIgnoreCase("store");
        }
        if( startNew ){
            var fabOpt = XMLfab.alterDigger(dig);
            if( fabOpt.isEmpty())
                return "No valid fab created";
            var fab=fabOpt.get();
            fab.up(); // dig was pointing to the last step which wasn't a store, go back up
            var deli = XMLtools.getStringAttribute(fab.getCurrentElement(),"delimiter",",");
            fab.addChild("store").attr("delimiter",deli);
            fab.down(); // make the store the parent
            //fab.build();
            dig = XMLdigger.goIn(fab.getCurrentElement()); // reset the digger
            return doCmd(request,fab,dig);
        }else{ // Work in existing one at the end
            var fabOpt = XMLfab.alterDigger(dig);
            if( fabOpt.isEmpty())
                return "No valid fab created";
            var fab=fabOpt.get();
            return doCmd(request,fab,dig);
        }
    }
    private static String doCmd( String request, XMLfab fab, XMLdigger dig){
        var cmds = request.split(",");

        switch (cmds[1]) {
            case "addblank", "addb" -> { // Adds an ignore node
                fab.addChild("ignore");
                fab.build();
                return "Blank added";
            }
            case "addreal","addr" -> {
                if (cmds.length < 3)
                    return "Not enough arguments: store:id,addreal,name<,index>";
                if( dig.peekAt("real","name",cmds[2]).hasValidPeek())
                    return "Already a real with that id, try something else?";

                fab.addChild("real").attr("name",cmds[2]).attr("unit");
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
                if( dig.peekAt("int","name",cmds[2]).hasValidPeek())
                    return "Already an int with that id, try something else?";

                fab.addChild("int").attr("name",cmds[2]).attr("unit");
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
                if( dig.peekAt("text","name",cmds[2]).hasValidPeek())
                    return "Already a text with that id, try something else?";

                fab.addChild("text").attr("name",cmds[2]);
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
                if( dig.peekAt("flag","name",cmds[2]).hasValidPeek())
                    return "Already a flag with that id, try something else?";

                fab.addChild("flag").attr("name",cmds[2]).attr("unit");
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
                    return "Not enough arguments: store:id,delim,newdelimiter";
                var deli = cmds.length == 4 ? "," : cmds[2];
                fab.attr("delimiter", deli);
                fab.build();
                return "Set the delimiter to '"+deli+"'";
            }
            case "group" -> {
                if (cmds.length < 3)
                    return "Not enough arguments: store:id,group,newgroup";
                fab.attr("group", cmds[2]);
                fab.build();
                return "Set the group to '"+cmds[2]+"'";
            }
            case "db" -> {
                if (cmds.length < 3 || !cmds[2].contains(":"))
                    return "Not enough arguments or missing table: store:id,db,dbids:table";
                int start = request.indexOf(",db,")+4;
                fab.attr("db", request.substring(start));
                fab.build();
                return "Set the db to "+request.substring(start);
            }
        }
        return "Unknown command: store:"+request;
    }
}
