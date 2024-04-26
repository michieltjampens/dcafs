package util.data;

import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public class StoreCmds {

    private static final ArrayList<String> VALS = new ArrayList<>(List.of("real","int","text","flag"));
    private static final ArrayList<String> VAL_ATTR = new ArrayList<>(List.of("unit","group","options","def","op"));
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
                    .add(green+" store:streamid,map,true/false "+reg+"-> Alter the map attribute")
                    .add(green+" store:streamid,group,newgroup "+reg+"-> Alter the default group used");
            join.add("").add(cyan+"Alter val attributes"+reg)
                    .add(green+" store:streamid,alterval,valname,unit,value "+reg+"-> Set the unit attribute of the given val")
                    .add(green+" store:streamid,alterval,valname,op,value "+reg+"-> Add an op to an integer/real val.");
            join.add("").add(cyan+"Other"+reg)
                    .add(green+" store:streamid,astable,dbid "+reg+"-> Create a table in the given db according to the store (wip)");
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
            dig.digDown("streams");
            if( dig.isInvalid())
                return "! No streams yet";
            dig.digDown("stream","id",id);
            if( dig.isInvalid() )
                return "! No such stream yet "+id;
        }

        // At this point, the digger is pointing to the stream node for the given id
        var fabOpt = XMLfab.alterDigger(dig);
        if( fabOpt.isEmpty())
            return "! No valid fab created";
        var fab=fabOpt.get();

        fab.alterChild(tag).down(); // Go to store or make if not existing
        dig.digDown(tag);

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
        return doCmd("store:id,",request,fab,dig,settingsPath);
    }
    public static String replyToPathCmd(String request, Path xmlPath ){


        String id = request.split(",")[0];

        var dig = XMLdigger.goIn(xmlPath,"dcafs");
        if( dig.isInvalid())
            return "! No such file";

        if( xmlPath.toString().endsWith("settings.xml")) {
            dig.digDown("paths");
            if( dig.isInvalid())
                return "! No paths defined yet";
        }

        dig.digDown("path","id",id);
        if( dig.isInvalid() )
            return "! No such path yet "+id;

        // At this point, the digger is pointing to the path node for the given id
        // Now check if it's import or not
        var imp = dig.attr("import","");
        if( !imp.isEmpty() ){
            // It's an import, so the digger should actually be pointing to that?
            var impPath =Path.of(imp);
            if( Files.notExists(impPath) ) // Doesn't exist, but should... so inform
                return "! No such path file "+imp;
            dig = XMLdigger.goIn(impPath,"dcafs");
            dig.digDown("path","id",id);
            if( dig.isInvalid() )
                return "! No valid path inside "+imp+" yet.";
        }

        // Now determine if the last step in the path is a store...
        boolean startNew;
        // Check if something is already in the path
        if( !dig.hasPeek("*") ) { // path has no steps
            startNew = true;
        }else{
            dig.digDown("*");
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
            fab.addChild("store");//.attr("delimiter",deli);
            fab.down(); // make the store the parent
            dig = XMLdigger.goIn(fab.getCurrentElement()); // reset the digger
            return doCmd("path:id,store,",request,fab,dig,xmlPath);
        } // Work in existing one at the end
        return doCmd("path:id,store,",request,fab,dig,xmlPath);

    }
    private static String doCmd( String prefix, String request, XMLfab fab, XMLdigger dig, Path xml){
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
                if( dig.hasPeek("real","name",cmds[2])
                        || dig.peekAtContent("real",cmds[2]) )
                    return "! Already a real with that id, try something else?";

                fab.addChild("real",cmds[2]).attr("unit");
                addIndexOrMap( fab, dig, cmds, map );
                fab.build();
                return "Real added";
            }
            case "addint","addi" -> {
                if (cmds.length < 3)
                    return "! Wrong amount of arguments -> "+prefix+"addint,name<,index/group>";

                if( dig.hasPeek("int","name",cmds[2])
                        || dig.peekAtContent("int",cmds[2]) )
                    return "! Already an int with that id, try something else?";

                fab.addChild("int",cmds[2]).attr("unit");
                addIndexOrMap( fab, dig, cmds, map );
                fab.build();
                return "Int added";
            }
            case "addtext","addt" -> {
                if (cmds.length < 3)
                    return "! Wrong amount of arguments -> "+prefix+"addtext,name<,index/group>";
                if( dig.hasPeek("text","name",cmds[2])
                    || dig.peekAtContent("text",cmds[2]) )
                    return "! Already a text with that id, try something else?";

                fab.addChild("text",cmds[2]);
                addIndexOrMap( fab, dig, cmds, map );
                fab.build();
                return "Text added";
            }
            case "addflag","addf" -> {
                if (cmds.length < 3)
                    return "! Wrong amount of arguments -> "+prefix+"addflag,name<,index/group>";
                if( dig.hasPeek("flag","name",cmds[2])
                    || dig.peekAtContent("flag",cmds[2]) )
                    return "! Already a flag with that id, try something else?";

                fab.addChild("flag",cmds[2]).attr("unit");
                addIndexOrMap( fab, dig, cmds, map );
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
                return "Set the db for "+cmds[0]+" to "+request.substring(start);
            }
            case "alterval" -> {
                if (cmds.length < 5 && !( cmds.length==4 && request.endsWith(",")))
                    return "! Wrong amount of arguments -> "+prefix+"alterval,valname,attr,value";
                if( !VAL_ATTR.contains(cmds[3]))
                    return "! Not a valid attribute, accepted: "+String.join(", ",VAL_ATTR);
                // Find the val referenced?
                for( var valtype : VALS){
                    if( dig.hasPeek(valtype,"name",cmds[2]) ){
                        dig.digDown(valtype,"name",cmds[2]);
                    }else if( dig.peekAtContent(valtype,cmds[2]) ){
                        dig.digDown(valtype,cmds[2]);
                    }else{
                        continue;
                    }
                    if( cmds[3].equals("op")){
                        if( !dig.tagName("").equals("text")) {
                            XMLfab.alterDigger(dig).ifPresent(x -> {
                                x.attr("name",cmds[2])
                                    .content("")
                                    .removeChild("op")
                                    .addChild("op",cmds[4])
                                    .build();
                            });
                            return "Operation '"+cmds[4]+"' set to "+cmds[2];
                        }
                    }else {
                        XMLfab.alterDigger(dig).ifPresent(x -> x.attr(cmds[3], cmds.length == 5 ? cmds[4] : "").build());
                        return "Attribute altered";
                    }

                }
                return "! No such val found: "+cmds[2];
            }
            case "astable" -> {
                if (cmds.length < 3)
                    return "! Wrong amount of arguments -> "+prefix+"astable,dbid";
                // First check if the database actually exists
                var dbDig = XMLdigger.goIn(xml,"dcafs");
                dbDig.digDown("databases");
                if( dbDig.isInvalid()) // Any database?
                    return "! No databases defined yet.";
                // Now check for sqlite or server with the id...
                if( dbDig.hasPeek("sqlite","id",cmds[2]) ){ // SQLite
                    dbDig.usePeek();
                }else if( dbDig.hasPeek("server","id",cmds[2]) ){// Server
                    dbDig.usePeek();
                }else{
                    return "! No such database yet";
                }
                // Now check if the table already exists
                dbDig.hasPeek("table","name",cmds[0]);
                if( dbDig.hasValidPeek() )
                    return "! Already a table with that name";

                var dbFabOpt = XMLfab.alterDigger(dbDig); // fab is pointing to the database node
                if( dbFabOpt.isEmpty() )
                    return "! Failed to obtain fab from dig";
                fab = dbFabOpt.get(); // reuse the name
                // Everything ready for the import
                // Create the table node
                fab.addChild("table").attr("name",cmds[0]);
                // Now copy the childnodes from the store?
                fab.down();
                int cols=0;
                for( var ele : dig.currentSubs()){
                    fab.addChild( ele.getTagName(),ele.getTextContent());
                    cols++;
                }
                if( cols==0)
                    return "! Nothing imported, store still empty?";
                fab.build(); // Make the nodes
                // Maybe alter the store to refer to the db?
                var fabOpt = XMLfab.alterDigger(dig);
                if( fabOpt.isEmpty() )
                    return "! No valid fab made based on path dig";
                fabOpt.get().attr("db",cmds[2]+":"+cmds[0]).build();
                return "Table added with "+cols+" columns, applied with dbm:"+cmds[2]+",reload";
            }
        }
        return "! No such subcommand in "+prefix+" : "+request;
    }
    private static void addIndexOrMap( XMLfab fab, XMLdigger dig, String[] cmds, boolean map ){
        if( cmds.length==4 ) {
            if( map ){
                fab.attr("key",cmds[3]);
            }else {
                if (NumberUtils.isCreatable(cmds[3])) {
                    fab.attr("i", cmds[3]);
                } else if (!cmds[3].isEmpty()) {
                    fab.attr("group", cmds[3]);
                    int a = dig.currentSubs().size();
                    fab.attr("i", a-1); // -1 because just created one
                }
            }
        }else if( !map ){
            int a = dig.currentSubs().size();
            if( a == 1 ) {
                fab.attr("i", 0); // -1 because just created one
            }else{
                var index = dig.currentSubs().get(a-2).getAttribute("i");
                if( index.isEmpty())
                    index = dig.currentSubs().get(a-2).getAttribute("index");
                fab.attr("i",NumberUtils.toByte(index)+1);
            }
        }
    }
}
