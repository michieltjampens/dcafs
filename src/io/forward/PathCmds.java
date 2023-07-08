package io.forward;

import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import util.data.StoreCmds;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;

public class PathCmds {

    private static String[] FILTERS = {"start","nostart","end","items","contain","c_start","c_end","min_length","max_length","nmea","regex","math"};
    public static String replyToCommand(String request, boolean html, Path settingsPath ){

        var cmds= request.split(",");
        String id = cmds[0];

        if( id.equalsIgnoreCase("?")){
            String cyan = html?"": TelnetCodes.TEXT_CYAN;
            String green=html?"":TelnetCodes.TEXT_GREEN;
            String ora = html?"":TelnetCodes.TEXT_ORANGE;
            String reg=html?"":TelnetCodes.TEXT_DEFAULT;

            StringJoiner join = new StringJoiner("\r\n");

            join.add("").add(cyan+"Add/edit a path"+reg)
                    .add(green+" pf:pathid,new,src "+reg+"-> Create a new path with the given id and src")
                    .add(green+" pf:pathid,xml,src "+reg+"-> Add a path file with the given id and src (in default path folder)")
                    .add(green+" pf:pathid,delete "+reg+"-> Delete this path completely")
                    .add(green+" pf:pathid,clear "+reg+"-> Remove all the steps");
            join.add("").add(cyan+"Add new steps"+reg)
                    .add(green+" pf:pathid,addfilter/addf,type:rule "+reg+"-> Add a filter, with the given rule")
                    .add(green+" pf:pathid,addeditor/adde,type:value "+reg+"-> Add an editor,with the given edit")
                    .add(green+" pf:pathid,addmath/addm,operation "+reg+"-> Add a math, with given operation")
                    .add(green+" pf:pathid,addcmd/addc,operation "+reg+"-> Add a cmd, with given cmd")
                    .add(green+" pf:pathid,store,cmds "+reg+"-> Add/edit a store");
            join.add("").add(cyan+"Alter attributes"+reg)
                    .add(green+" pf:pathid,delimiter/delim,newdelimiter "+reg+"-> Change the delimiter")
                    .add(green+" pf:pathid,src,newsrc "+reg+"-> Alter the src");
            return join.toString();
        }

        var dig = XMLdigger.goIn(settingsPath,"dcafs");
        if( !dig.peekAt("paths").hasValidPeek()){
            XMLfab.alterDigger(dig).ifPresent(fab->fab.addChild("paths").build());
        }
        dig.goDown("paths");
        if( dig.isInvalid())
            return "! No paths yet";

        dig.goDown("path","id",id);
        if( dig.isInvalid() ) {
            switch(cmds[1]){
                case "new" ->{
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
                }
                case "xml" -> {
                    if( cmds.length<3)
                        return "To few arguments, expected pf:pathid,xml,src";
                    var pathPath = settingsPath.getParent().resolve("paths");
                    try {
                        Files.createDirectories( pathPath );
                    } catch (IOException e) {
                        Logger.error(e);
                    }
                    // Add it to the settings.xml
                    XMLfab.withRoot(settingsPath,"dcafs","paths")
                            .selectOrAddChildAsParent("path","id",cmds[0])
                            .attr("src",cmds[2])
                            .attr("delimiter",",")
                            .attr("import","paths"+ File.separator+cmds[0]+".xml")
                            .build();
                    // Actually create the file
                    XMLfab.withRoot( pathPath.resolve(cmds[0]+".xml"),"dcafs")
                            .selectOrAddChildAsParent("path","id",cmds[0])
                            .attr("delimiter",",")
                            .build();
                    return "Path created";
                }
            }
            return "! No such path yet " + id;
        }else if( cmds[1].equalsIgnoreCase("new")
                    || cmds[1].equalsIgnoreCase("xml")){
            return "! Already a path with that id, pick something else?";
        }
        // At this point, the digger is pointing to the path node for the given id
        // But this might be an import....
        var fabOpt = XMLfab.alterDigger(dig); // Create a fab with parentnode the path node
        if( fabOpt.isEmpty())
            return "! No valid fab created";
        var fab=fabOpt.get();

        switch( cmds[1]) {
            /* Commands that affect the path */
            case "delimiter", "delim" -> {
                if (cmds.length < 3)
                    return "! Not enough arguments: pf:id,delim/delimiter,newdelimiter";
                var deli = cmds.length == 4 ? "," : cmds[2];
                fab.attr("delimiter", deli);
                fab.build();
                return "Set the delimiter to '" + deli + "'";
            }
            case "clear" -> {
                fab.clearChildren();
                fab.build();
                return "Removed all steps";
            }
            case "delete" -> {
                if (cmds.length < 3)
                    return "! Not enough arguments: pf:id,delete,all/last";
                switch (cmds[2]) {
                    case "all" -> {
                        fab.up();
                        fab.removeChild("path", "id", cmds[0]);
                        fab.build();
                        return "Deleted the path completely";
                    }
                    case "last" -> {
                        if (fab.removeLastChild("*")) {
                            fab.build();
                            return "Node removed";
                        }
                        return "! Failed to remove node";
                    }
                }
            }
            case "src" -> {
                if (cmds.length < 3)
                    return "! Not enough arguments: pf:id,src,newsrc";
                fab.attr("src", cmds[2]);
                fab.build();
                return "Set the src to '" + cmds[2] + "'";
            }
            /* Commands to add a simple step at the end */
            case "addfilter", "addf" -> {
                if (cmds.length < 3)
                    return "! Not enough arguments: pf:id,addfilter,type:rule";
                var rule = cmds[2].split(":");
                if (rule.length != 2)
                    return "! Need a type and a rule separated with : (pf:id,addfilter,type:rule)";

                if (!List.of(FILTERS).contains(rule[0]))
                    return "! No such filter type, valid: " + String.join(",", FILTERS);

                // fab is pointing at path node, needs to know if last item is a filter or not
                dig.goDown("*").toLastSibling();
                var opt = dig.current();
                // Check if it has steps and if the last one isn't a filter
                if (opt.isEmpty() || !opt.get().getTagName().equalsIgnoreCase("filter")) {
                    fab.addChild("filter", rule[1]).attr("type", rule[0]);
                } else { // Last one is a filter
                    fabOpt = XMLfab.alterDigger(dig);
                    if (fabOpt.isEmpty())
                        return "! Failed to get fab";
                    fab = fabOpt.get();
                    // fab pointing at the last filter
                    if (dig.goDown("rule").isInvalid()) { // check if already contains a rule node
                        // Correct node but no rule subnodes... replace current
                        var cur = opt.get();
                        var content = cur.getTextContent();
                        var type = cur.getAttribute("type");
                        fab.content("").removeAttr("type"); // clear to replace with sub
                        fab.addChild("rule", content).attr("type", type);
                    }
                    fab.addChild("rule", rule[1]).attr("type", rule[0]); // add new one
                }
                fab.build();
                return "Filter added";
            }
            case "addeditor", "adde" -> {
                if (cmds.length < 4)
                    return "! Not enough arguments: pf:id,addeditor,type,value";

                // Now get the value that can contain ,
                int a = request.indexOf(cmds[2] + ",");
                a += cmds[2].length() + 1;
                var value = request.substring(a);

                // fab is pointing at path node, needs to know if last item is an editor or not
                dig.goDown("*").toLastSibling();
                var opt = dig.current();
                if (opt.isPresent() && opt.get().getTagName().equalsIgnoreCase("editor")) {
                    // Last one is a editor, so get a
                    fabOpt = XMLfab.alterDigger(dig);
                    if (fabOpt.isEmpty())
                        return "! Failed to get fab";
                    fab = fabOpt.get();
                } else {
                    fab.addChild("editor").down();
                }
                return EditorCmds.addEditor(fab, cmds[2], value);
            }
            case "addmath", "addm" -> {
                if (cmds.length < 3)
                    return "! Not enough arguments: pf:id,addmath,operation";

                // fab is pointing at path node, needs to know if last item is a math or not
                dig.goDown("*").toLastSibling();
                var opt = dig.current();
                // Check if it has steps and if the last one isn't a math
                if (opt.isEmpty() || !opt.get().getTagName().equalsIgnoreCase("math")) {
                    fab.addChild("math", cmds[2]);
                } else { // Last one is a math
                    fabOpt = XMLfab.alterDigger(dig);
                    if (fabOpt.isEmpty())
                        return "! Failed to get fab";
                    fab = fabOpt.get();
                    // fab pointing at the last math
                    if (dig.goDown("op").isInvalid()) { // check if already contains a rule node
                        // Correct node but no subnodes... replace current
                        var cur = opt.get();
                        var content = cur.getTextContent();
                        fab.content("");
                        fab.addChild("op", content);
                    }
                    fab.addChild("op", cmds[2]); // and add new
                }
                fab.build();
                return "Math added";
            }
            case "addcmd", "addc" -> {
                if (cmds.length < 3)
                    return "! Not enough arguments: pf:id,addcmd,cmd";
                // fab is pointing at path node, needs to know if last item is a cmd or not
                dig.goDown("*").toLastSibling();
                var opt = dig.current();
                // Check if it has steps and if the last one isn't a cmd
                if (opt.isPresent()) {
                    fabOpt = XMLfab.alterDigger(dig);
                    if (fabOpt.isEmpty())
                        return "! Failed to get fab";
                    fab = fabOpt.get();
                    var tag = opt.get().getTagName();
                    if (tag.equalsIgnoreCase("cmd")) {
                        var old = opt.get().getTextContent();
                        opt.get().setTextContent("");
                        fab.renameParent("cmds")
                                .addChild("cmd", old);
                    }
                }
                fab.addChild("cmd", cmds[2]);
                fab.build();
                return "Cmd added";
            }
            case "store" -> {
                if( cmds.length <4 )
                    return "! Not enough arguments, need atleast 4: pf:pathid,store,cmd,value(s)";
                // pf:id,store,addi,rolled,4

                return StoreCmds.replyToPathCmd(cmds[0]+","+cmds[2]+","+cmds[3]+(cmds.length>4?","+cmds[4]:""),settingsPath);
            }
        }
        return "unknown command: pf:"+request;
    }
}
