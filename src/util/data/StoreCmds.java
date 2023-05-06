package util.data;

import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public class StoreCmds {

    private static final ArrayList<String> VALS = new ArrayList<>(List.of("real","int","text","flag"));
    private static final ArrayList<String> VAL_ATTR = new ArrayList<>(List.of("unit","group","options","def"));
    public static String replyToCommand(String request, boolean html, Path settingsPath ){

        var cmds =request.split(",");
        String id = cmds[0];

        if( id.equalsIgnoreCase("?") ){
            String cyan = html?"": TelnetCodes.TEXT_CYAN;
            String green=html?"":TelnetCodes.TEXT_GREEN;
            String ora = html?"":TelnetCodes.TEXT_ORANGE;
            String reg=html?"":TelnetCodes.TEXT_DEFAULT;

            StringJoiner join = new StringJoiner("\r\n");

            join.add(TelnetCodes.TEXT_RESET+ora+"Notes"+reg)
                    .add("- a / in the command means both options are valid.")
                    .add("- after a command is processed, the store will be reloaded and changes applied, to reload manually ss:reloadstore,id")
                    .add("- If no store exists yet, any command will create it first with default delimiter of ','")
                    .add("- Regular mode works with indexes after split on the delimiter")
                    .add("- Map is when the data consists of a key, value pair with the given delimiter");
            join.add("").add(cyan+"Add new vals"+reg)
                    .add(green+" store:streamid,addreal/addr,name<,index/group> "+reg+"-> Add a RealVal to the store, with optional index/group/key")
                    .add(green+" store:streamid,addflag/addf,name<,index/group> "+reg+"-> Add a FlagVal to the store, with optional index/group/key")
                    .add(green+" store:streamid,addtext/addt,name<,index/group> "+reg+"-> Add a TextVal to the store, with optional index/group/key")
                    .add(green+" store:streamid,addint/addi,name<,index/group> "+reg+"-> Add a IntVal to the store, with optional index/group/key")
                    .add(green+" store:streamid,addb/addblank "+reg+"-> Add a blank spot (if index isn't used but a item needs to be skipped)");
            join.add("").add(cyan+"Alter attributes"+reg)
                    .add(green+" store:streamid,delimiter/delim,newdelimiter "+reg+"-> Change the delimiter of the store")
                    .add(green+" store:streamid,db,dbids:table "+reg+"-> Alter the database/table ")
                    .add(green+" store:streamid,map,true/false"+reg+"-> Alter the map attribute")
                    .add(green+" store:streamid,group,newgroup"+reg+"-> Alter the default group used");
            join.add("").add(cyan+"Alter val attributes"+reg)
                    .add(green+" store:streamid,alterval,valname,unit,value "+reg+"-> Set the unit attribute of the given val");
            return join.toString();
        }else if( id.isEmpty()){
            return "! Empty id is not valid";
        }
        if( cmds.length<2 )
            return "! Not enough arguments, check store:?";

        if( cmds.length<3 && !cmds[1].startsWith("addb"))
            return "! Not enough arguments, check store:?";

        String tag = id.equalsIgnoreCase("global")?"rtvals":"store";

        if( tag.equals("rtvals")){
            if( cmds.length<4 )
                return "! Not enough arguments, probably missing group?";
        }

        var dig = XMLdigger.goIn(settingsPath,"dcafs");
        if( !id.equalsIgnoreCase("global")){
            dig.goDown("streams");
            if( dig.isInvalid())
                return "! No streams yet";
            dig.goDown("stream","id",id);
            if( dig.isInvalid() )
                return "! No such stream yet "+id;
        }

        // At this point, the digger is pointing to the stream node for the given id
        var fabOpt = XMLfab.alterDigger(dig);
        if( fabOpt.isEmpty())
            return "! No valid fab created";
        var fab=fabOpt.get();


        fab.alterChild(tag).down(); // Go to store or make if not existing
        dig.goDown(tag);

        if(  tag.equals("store")) {
            if (!dig.current().get().hasAttribute("delimiter"))
                fab.attr("delimiter", ",");
        }else{
            fab.alterChild("group","id",cmds[3]).down();
            dig = XMLdigger.goIn(fab.getCurrentElement());
            cmds[3]=""; // clear the group, so it won't be added as attribute
            request=String.join(",",cmds);
        }
        // At this point 'store' certainly exists in memory and dig is pointing to it
        return doCmd("store:id,",request,fab,dig);
    }
    public static String replyToPathCmd(String request, Path xmlPath ){

        String id = request.split(",")[0];

        var dig = XMLdigger.goIn(xmlPath,"dcafs");
        if( dig.isInvalid())
            return "! No such file";

        if( xmlPath.toString().endsWith("settings.xml")) {
            dig.goDown("paths");
            if( dig.isInvalid())
                return "! No paths defined yet";
        }

        dig.goDown("path","id",id);
        if( dig.isInvalid() )
            return "! No such path yet "+id;

        // At this point, the digger is pointing to the path node for the given id
        // Now determine if the last step in the path is a store...
        boolean startNew;
        dig.peekAt("*");  // Check if something is already in the path
        if( !dig.hasValidPeek() ) { // path has no steps
            startNew = true;
        }else{
            dig.goDown("*");
            dig.toLastSibling();
            startNew = !dig.tagName("").equalsIgnoreCase("store");
        }
        var fabOpt = XMLfab.alterDigger(dig);
        if( fabOpt.isEmpty())
            return "! No valid fab created";
        var fab=fabOpt.get();

        if( startNew ){
            fab.up(); // dig was pointing to the last step which wasn't a store, go back up
            var deli = XMLtools.getStringAttribute(fab.getCurrentElement(),"delimiter",",");
            fab.addChild("store").attr("delimiter",deli);
            fab.down(); // make the store the parent
            //fab.build();
            dig = XMLdigger.goIn(fab.getCurrentElement()); // reset the digger
            return doCmd("path:id,store,",request,fab,dig);
        } // Work in existing one at the end
        return doCmd("path:id,store,",request,fab,dig);

    }
    private static String doCmd( String prefix, String request, XMLfab fab, XMLdigger dig){
        var cmds = request.split(",");
        boolean map = dig.attr("map",false);

        switch (cmds[1]) {
            case "addblank", "addb" -> { // Adds an ignore node
                fab.addChild("ignore");
                fab.build();
                return "Blank added";
            }
            case "addreal","addr" -> {
                if (cmds.length < 3)
                    return "! Wrong amount of arguments -> "+prefix+"addreal,name<,index/group>";
                if( dig.peekAt("real","name",cmds[2]).hasValidPeek()
                        || dig.peekAtContent("real",cmds[2]).hasValidPeek() )
                    return "! Already a real with that id, try something else?";

                fab.addChild("real",cmds[2]).attr("unit");
                if( cmds.length==4 ) {
                    if( map ){
                        fab.attr("key",cmds[3]);
                    }else {
                        if (NumberUtils.isCreatable(cmds[3])) {
                            fab.attr("index", cmds[3]);
                        } else if (!cmds[3].isEmpty()) {
                            fab.attr("group", cmds[3]);
                        }
                    }
                }
                fab.build();
                return "Real added";
            }
            case "addint","addi" -> {
                if (cmds.length < 3)
                    return "! Wrong amount of arguments -> "+prefix+"addint,name<,index/group>";
                if( dig.peekAt("int","name",cmds[2]).hasValidPeek()
                        || dig.peekAtContent("int",cmds[2]).hasValidPeek() )
                    return "! Already an int with that id, try something else?";

                fab.addChild("int",cmds[2]).attr("unit");
                if( cmds.length==4 ) {
                    if( map ){
                        fab.attr("key",cmds[3]);
                    }else {
                        if (NumberUtils.isCreatable(cmds[3])) {
                            fab.attr("index", cmds[3]);
                        } else if (!cmds[3].isEmpty()) {
                            fab.attr("group", cmds[3]);
                        }
                    }
                }
                fab.build();
                return "Int added";
            }
            case "addtext","addt" -> {
                if (cmds.length < 3)
                    return "! Wrong amount of arguments -> "+prefix+"addtext,name<,index/group>";
                if( dig.peekAt("text","name",cmds[2]).hasValidPeek()
                    || dig.peekAtContent("text",cmds[2]).hasValidPeek() )
                    return "! Already a text with that id, try something else?";

                fab.addChild("text",cmds[2]);
                if( cmds.length==4 ) {
                    if( map ){
                        fab.attr("key",cmds[3]);
                    }else {
                        if (NumberUtils.isCreatable(cmds[3])) {
                            fab.attr("index", cmds[3]);
                        } else if (!cmds[3].isEmpty()) {
                            fab.attr("group", cmds[3]);
                        }
                    }
                }
                fab.build();
                return "Text added";
            }
            case "addflag","addf" -> {
                if (cmds.length < 3)
                    return "! Wrong amount of arguments -> "+prefix+"addflag,name<,index/group>";
                if( dig.peekAt("flag","name",cmds[2]).hasValidPeek()
                    || dig.peekAtContent("flag",cmds[2]).hasValidPeek() )
                    return "! Already a flag with that id, try something else?";

                fab.addChild("flag",cmds[2]).attr("unit");
                if( cmds.length==4 ) {
                    if( map ){
                        fab.attr("key",cmds[3]);
                    }else {
                        if (NumberUtils.isCreatable(cmds[3])) {
                            fab.attr("index", cmds[3]);
                        } else if (!cmds[3].isEmpty()) {
                            fab.attr("group", cmds[3]);
                        }
                    }
                }
                fab.build();
                return "Flag added";
            }
            case "delim", "delimiter" -> {
                if (cmds.length < 3)
                    return "! Wrong amount of arguments -> "+prefix+"delim,newdelimiter";
                var deli = cmds.length == 4 ? "," : cmds[2];
                fab.attr("delimiter", deli);
                fab.build();
                return "Set the delimiter to '"+deli+"'";
            }
            case "map", "mapped" -> {
                if (cmds.length < 3)
                    return "! Wrong amount of arguments -> "+prefix+"map,true/false";
                if( !Tools.validBool(cmds[2])){
                    return "! Not a valid boolean: "+cmds[2];
                }
                fab.attr("map", cmds[2]);
                fab.build();
                return "Set map to '"+cmds[2]+"'";
            }
            case "group" -> {
                if (cmds.length < 3)
                    return "! Wrong amount of arguments -> "+prefix+"group,newgroup";
                fab.attr("group", cmds[2]);
                fab.build();
                return "Set the group to '"+cmds[2]+"'";
            }
            case "db" -> {
                if (cmds.length < 3 || !cmds[2].contains(":"))
                    return "! Wrong amount of arguments or missing table: "+prefix+"db,dbids:table";
                int start = request.indexOf(",db,")+4;
                fab.attr("db", request.substring(start));
                fab.build();
                return "Set the db to "+request.substring(start);
            }
            case "alterval" -> {
                if (cmds.length < 5 && !( cmds.length==4 && request.endsWith(",")))
                    return "! Wrong amount of arguments -> "+prefix+"alterval,valname,attr,value";
                if( !VAL_ATTR.contains(cmds[3]))
                    return "! Not a valid attribute, accepted: "+String.join(", ",VAL_ATTR);
                // Find the val referenced?
                for( var valtype : VALS){
                    if( dig.peekAt(valtype,"name",cmds[2]).hasValidPeek() ){
                        dig.goDown(valtype,"name",cmds[2]);
                    }else if( dig.peekAtContent(valtype,cmds[2]).hasValidPeek() ){ // Found a flag
                        dig.goDown(valtype,cmds[2]);
                    }else{
                        continue;
                    }
                    XMLfab.alterDigger(dig).ifPresent( x -> x.attr(cmds[3],cmds.length==5?cmds[4]:"").build());
                    return "Attribute altered";
                }
                return "! No such val found: "+cmds[2];
            }
        }
        return "! No such subcommand in "+prefix+" : "+request;
    }
}