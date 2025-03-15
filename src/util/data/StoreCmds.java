package util.data;

import das.Paths;
import org.apache.commons.lang3.math.NumberUtils;
import util.LookAndFeel;
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

    public static String replyToCommand(String request, boolean html ){

        var args = request.split(",");
        String id = args[0];

        if (id.equalsIgnoreCase("?"))
            return doStoreCmdHelp(html);

        if (id.isEmpty())
            return "! Empty id is not valid";

        if (args.length < 2)
            return "! Not enough arguments, check store:?";

        if (args.length < 3 && !args[1].startsWith("addb"))
            return "! Not enough arguments, check store:?";

        String tag = id.equalsIgnoreCase("global")?"rtvals":"store";

        if (tag.equals("rtvals") && args.length < 4)
            return "! Not enough arguments, probably missing group?";


        var dig = XMLdigger.goIn(Paths.settings(),"dcafs");
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

        if (tag.equals("store")) {
            if (!dig.currentTrusted().hasAttribute("delimiter"))
                fab.attr("delimiter", ",");
        }else{
            fab.alterChild("group", "id", args[3]).down();
            dig = XMLdigger.goIn(fab.getCurrentElement());
            args[3] = ""; // clear the group, so it won't be added as attribute
            request = String.join(",", args);
        }
        // At this point 'store' certainly exists in memory and dig is pointing to it
        return doCmd("store:id,",request,fab,dig,Paths.settings());
    }

    private static String doStoreCmdHelp(boolean html) {
        StringJoiner join = new StringJoiner("\r\n");
        join.add("Commands to create and edit the store nodes.");
        join.add("Notes")
                .add("- a / in the command means both options are valid.")
                .add("- after a command is processed, the store will be reloaded and changes applied, to reload manually ss:reloadstore,id")
                .add("- If no store exists yet, any command will create it first with default delimiter of ','")
                .add("- Regular mode works with indexes after split on the delimiter")
                .add("- Map is when the data consists of a key, value pair with the given delimiter");
        join.add("Add new vals")
                .add("store:streamid,addreal/addr,group,name<,index/key> -> Add a RealVal to the store, with optional index/key")
                .add("store:streamid,addflag/addf,group,name<,index/key> -> Add a FlagVal to the store, with optional index/key")
                .add("store:streamid,addtext/addt,group,name<,index/key> -> Add a TextVal to the store, with optional index/key")
                .add("store:streamid,addint/addi,group,name<,index/key> -> Add a IntegerVal to the store, with optional index/key")
                .add("store:streamid,addb/addblank -> Add a blank spot (if index isn't used but a item needs to be skipped)");
        join.add("Alter attributes")
                .add("store:streamid,delimiter/delim,newdelimiter -> Change the delimiter of the store")
                .add("store:streamid,db,dbids:table -> Alter the database/table ")
                .add("store:streamid,map,true/false -> Alter the map attribute")
                .add("store:streamid,idlereset,true/false -> Alter the idlereset attribute, if true rtvals are reset on idle.")
                .add("store:streamid,group,newgroup -> Alter the default group used");
        join.add("Alter val attributes")
                .add("store:streamid,alterval,valname,unit,value -> Set the unit attribute of the given val")
                .add("store:streamid,alterval,valname,op,value -> Add an op to an integer/real val.");
        join.add("Other")
                .add("store:streamid,astable,dbid -> Create a table in the given db according to the store (wip)");
        return LookAndFeel.formatCmdHelp(join.toString(), html);
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
            var impPath = Path.of(imp);
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

        return switch (cmds[1]) {
            case "addblank", "addb" -> { // Adds an ignore node
                fab.addChild("ignore").build();
                yield "Blank added";
            }
            case "addreal", "addr" -> doAddValCmd(cmds, prefix, dig, fab, "real", map);
            case "addint", "addi" -> doAddValCmd(cmds, prefix, dig, fab, "int", map);
            case "addtext", "addt" -> doAddValCmd(cmds, prefix, dig, fab, "text", map);
            case "addflag", "addf" -> doAddValCmd(cmds, prefix, dig, fab, "flag", map);
            case "delim", "delimiter" -> {
                if (cmds.length < 3)
                    yield "! Wrong amount of arguments -> " + prefix + "delim,newdelimiter";
                var deli = cmds.length == 4 ? "," : cmds[2];
                fab.attr("delimiter", deli).build();
                yield "Set the delimiter to '" + deli + "'";
            }
            case "map", "mapped" -> {
                if (cmds.length < 3)
                    yield "! Wrong amount of arguments -> " + prefix + "map,true/false";
                if( !Tools.validBool(cmds[2])){
                    yield "! Not a valid boolean: " + cmds[2];
                }
                fab.attr("map", cmds[2]).build();
                yield "Set map to '" + cmds[2] + "'";
            }
            case "idlereset" -> {
                if (cmds.length < 3)
                    yield "! Wrong amount of arguments -> " + prefix + "idlereset,true/false";
                if (!Tools.isBoolean(cmds[2]))
                    yield "! Not a valid boolean state: " + cmds[2];
                fab.attr("idlereset", cmds[2]).build();
                yield "Set the idlereset to '" + cmds[2] + "'";
            }
            case "group" -> {
                if (cmds.length < 3)
                    yield "! Wrong amount of arguments -> " + prefix + "group,newgroup";
                fab.attr("group", cmds[2]).build();
                yield "Set the group to '" + cmds[2] + "'";
            }
            case "db" -> {
                if (cmds.length < 3 || !cmds[2].contains(":"))
                    yield "! Wrong amount of arguments or missing table: " + prefix + "db,dbids:table";
                int start = request.indexOf(",db,")+4;
                fab.attr("db", request.substring(start)).build();
                yield "Set the db for " + cmds[0] + " to " + request.substring(start);
            }
            case "alterval" -> doAlterValCmd(cmds, dig, prefix, request);
            case "astable" -> doAsTableCmd(cmds, prefix, dig, xml);
            default -> "! No such subcommand in " + prefix + " : " + request;
        };
    }

    private static String doAddValCmd(String[] cmds, String prefix, XMLdigger dig, XMLfab fab, String numtype, boolean map) {
        if (cmds.length < 3)
            return "! Wrong amount of arguments -> " + prefix + "add" + numtype + ",group,name<,index/key>";

        var group = dig.attr("group", "");
        if (dig.peekAtBoth(numtype, "group", cmds[2], cmds[3]) || // group attr and name as content
                (group.equalsIgnoreCase(cmds[2]) && dig.peekAtBoth(numtype, "group", "", cmds[3])) || // group global, name as content
                (group.equalsIgnoreCase(cmds[2]) && dig.peekAtMulAttr(numtype, "group", "", "name", cmds[3])) ||// group global, name as attr
                dig.peekAtMulAttr(numtype, "group", cmds[2], "name", cmds[3])) { // group attr, name as attr
            return "! Already a " + numtype + " with that id, try something else?";
        }
        boolean newGroup = false;
        if (group.isEmpty()) {
            fab.attr("group", cmds[2]);
            newGroup = true;
        }
        fab.addChild(numtype, cmds[3]).attr("unit");    // Add name and unit
        if (!group.equalsIgnoreCase(cmds[2]) && !newGroup)
            fab.attr("group", cmds[2]);             // Add group if it's different then global
        addIndexOrMap(fab, dig, cmds, map);             // Add index or map key
        fab.build();                                    // Create it
        return numtype + " added";
    }

    private static String doAlterValCmd(String[] cmds, XMLdigger dig, String prefix, String request) {
        if (cmds.length < 5 && !(cmds.length == 4 && request.endsWith(",")))
            return "! Wrong amount of arguments -> " + prefix + "alterval,valname,attr,value";
        if (!VAL_ATTR.contains(cmds[3]))
            return "! Not a valid attribute, accepted: " + String.join(", ", VAL_ATTR);
        // Find the val referenced?
        for (var valtype : VALS) {
            if (dig.hasPeek(valtype, "name", cmds[2])) {
                dig.digDown(valtype, "name", cmds[2]);
            } else if (dig.peekAtContent(valtype, cmds[2])) {
                dig.digDown(valtype, cmds[2]);
            } else {
                continue;
            }

            if (cmds[3].equals("op")) {
                if (!dig.tagName("").equals("text")) {
                    XMLfab.alterDigger(dig).ifPresent(x -> {
                        x.attr("name", cmds[2])
                                .content("")
                                .removeChild("op")
                                .addChild("op", cmds[4])
                                .build();
                    });
                    return "Operation '" + cmds[4] + "' set to " + cmds[2];
                }
            } else {
                XMLfab.alterDigger(dig).ifPresent(x -> x.attr(cmds[3], cmds.length == 5 ? cmds[4] : "").build());
                return "Attribute altered";
            }
        }
        return "! No such val found: " + cmds[2];
    }

    private static String doAsTableCmd(String[] cmds, String prefix, XMLdigger dig, Path xml) {
        if (cmds.length < 3)
            return "! Wrong amount of arguments -> " + prefix + "astable,dbid";
        // First check if the database actually exists
        var dbDig = XMLdigger.goIn(xml, "dcafs");
        dbDig.digDown("databases");
        if (dbDig.isInvalid()) // Any database?
            return "! No databases defined yet.";
        // Now check for sqlite or server with the id...
        if (dbDig.hasPeek("sqlite", "id", cmds[2])) { // SQLite
            dbDig.usePeek();
        } else if (dbDig.hasPeek("server", "id", cmds[2])) {// Server
            dbDig.usePeek();
        } else {
            return "! No such database yet";
        }
        // Now check if the table already exists
        dbDig.hasPeek("table", "name", cmds[0]);
        if (dbDig.hasValidPeek())
            return "! Already a table with that name";

        var dbFabOpt = XMLfab.alterDigger(dbDig); // fab is pointing to the database node
        if (dbFabOpt.isEmpty())
            return "! Failed to obtain fab from dig";
        var fab = dbFabOpt.get(); // reuse the name
        // Everything ready for the import
        // Create the table node
        fab.addChild("table").attr("name", cmds[0]);
        // Now copy the childnodes from the store?
        fab.down();
        int cols = 0;
        for (var ele : dig.currentSubs()) {
            fab.addChild(ele.getTagName(), ele.getTextContent());
            cols++;
        }
        if (cols == 0)
            return "! Nothing imported, store still empty?";
        fab.build(); // Make the nodes
        // Maybe alter the store to refer to the db?
        var fabOpt = XMLfab.alterDigger(dig);
        if (fabOpt.isEmpty())
            return "! No valid fab made based on path dig";
        fabOpt.get().attr("db", cmds[2] + ":" + cmds[0]).build();
        return "Table added with " + cols + " columns, applied with dbm:" + cmds[2] + ",reload";
    }
    private static void addIndexOrMap( XMLfab fab, XMLdigger dig, String[] cmds, boolean map ){
        // store:streamid,addreal/addr,group,name<,index/key>
        if (cmds.length == 5) {
            if( map ){
                fab.attr("key", cmds[4]);
            }else {
                if (NumberUtils.isCreatable(cmds[4])) {
                    fab.attr("i", cmds[4]);
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
