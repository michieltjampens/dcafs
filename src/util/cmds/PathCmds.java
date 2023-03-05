package util.cmds;

import io.forward.PathForward;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
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
            join.add("").add(cyan+"Add/edit a path"+reg)
                    .add(green+" pf:pathid,new,src "+reg+"-> Create a new path with the given id and src")
                    .add(green+" pf:pathid,delete "+reg+"-> Delete this path completely")
                    .add(green+" pf:pathid,clear "+reg+"-> Remove all the steps");
            join.add("").add(cyan+"Add new steps"+reg)
                    .add(green+" pf:pathid,addfilter/addf,rule "+reg+"-> Add a filter to the path with the given rule")
                    .add(green+" pf:pathid,addeditor/adde,name<,index> "+reg+"-> Add an editor to the store with the given rule")
                    .add(green+" pf:pathid,addtext/addt,name<,index> "+reg+"-> Add a TextVal to the store, with optional index")
                    .add(green+" pf:pathid,addmath/addm,name<,index> "+reg+"-> Add a IntVal to the store, with optional index")
                    .add(green+" pf:pathid,store,cmds "+reg+"-> Add/edit a store");
            join.add("").add(cyan+"Alter attributes"+reg)
                    .add(green+" pf:pathid,delimiter/delim,newdelimiter "+reg+"-> Change the delimiter of the path")
                    .add(green+" pf:pathid,src,newsrc "+reg+"-> Alter the src");
            return join.toString();
        }

        var dig = XMLdigger.goIn(settingsPath,"dcafs").goDown("paths");
        if( dig.isInvalid())
            return "! No paths yet";

        dig.goDown("path","id",id);
        if( dig.isInvalid() ) {
            if( cmds[1].equalsIgnoreCase("new")){
                if( cmds.length<3) {
                    return "! To few arguments, expected pf:pathid,new,src";
                }
                var fab = XMLfab.withRoot(settingsPath,"dcafs","paths")
                        .selectOrAddChildAsParent("path","id",cmds[0])
                        .attr("delimiter",",");

                if( cmds[2].startsWith("file:")) {
                    fab.addChild("customsrc", cmds[2]).attr("type", "file").attr("interval", "1s");
                }else{
                    fab.attr("src",cmds[2]);
                }
                fab.build();
                return "Path created";
            }else {
                return "! No such path yet " + id;
            }
        }else if( cmds[1].equalsIgnoreCase("new")){
            return "! Already a path with that id, pick something else?";
        }
        // At this point, the digger is pointing to the path node for the given id
        // But this might be an import....
        var fabOpt = XMLfab.alterDigger(dig); // Create a fab with parentnode the path node
        if( fabOpt.isEmpty())
            return "! No valid fab created";
        var fab=fabOpt.get();

        switch( cmds[1]){
            case "delimiter","delim" ->{
                if (cmds.length < 3)
                    return "! Not enough arguments: pf:id,delim/delimiter,newdelimiter";
                var deli = cmds.length == 4 ? "," : cmds[2];
                fab.attr("delimiter", deli);
                fab.build();
                return "Set the delimiter to '"+deli+"'";
            }
            case "clear" ->{
                fab.clearChildren();
                fab.build();
                return "Removed all steps";
            }
            case "delete" -> {
                fab.up();
                fab.removeChild("path","id",cmds[0]);
                fab.build();
                return "Deleted the path completely";
            }
            case "src" ->{
                if (cmds.length < 3)
                    return "! Not enough arguments: pf:id,src,newsrc";
                fab.attr("src", cmds[2]);
                fab.build();
                return "Set the src to '"+cmds[2]+"'";
            }
            case "addfilter","addf" -> {
                if( cmds.length < 3 )
                    return "! Not enough arguments: pf:id,addfilter,type:rule";
                var rule = cmds[2].split(":");
                if( rule.length != 2)
                    return "! Need a type and a rule separated with : (pf:id,addfilter,type:rule)";
                fab.addChild("filter").attr("type",rule[0]).content(rule[1]);
                fab.build();
                return "Filter added";
            }
            case "addeditor","adde" -> {
                if( cmds.length < 3 )
                    return "! Not enough arguments: pf:id,addeditor,type:value";
                var rule = cmds[2].split(":");
                if( rule.length < 2)
                    return "! Need a type and a value separated with : (pf:id,addeditor,type:value)";
                // Now the value can contain : and , that messes up the general split, so redo it
                int a = request.indexOf(",adde"); // get the position of ,adde
                a += request.substring(a+1).indexOf(",");// alter this to the first , after is (because addeditor exists)
                var typval = request.substring(a);// new get that part from the original
                a = typval.indexOf(":"); // split is done on the first :
                EditorCmds.addEditor(fab,typval.substring(0,a),typval.substring(a+1));
                fab.build();
                return "Filter added";
            }
            case "addmath","addm" -> {
                if( cmds.length < 3 )
                    return "! Not enough arguments: pf:id,addmath,operation";

                fab.addChild("math").down()
                        .addChild("op",cmds[2]);
                fab.build();
                return "Math added";
            }
            case "store" -> {
                if( cmds.length <4 )
                    return "! Not enough arguments, need atleast 4: pf:pathid,store,cmd,value";
                // pf:id,store,addi,rolled,4
                return StoreCmds.replyToPathCmd(cmds[0]+","+cmds[2]+","+cmds[3]+(cmds.length>4?","+cmds[4]:""),settingsPath);
            }
        }
        return "unknown command: pf:"+request;
    }

}
