package io.forward;

import das.Paths;
import org.apache.commons.lang3.ArrayUtils;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.store.StoreCmds;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

public class PathCmds {

    private static final String[] FILTERS = {"start","nostart","end","items","contain","c_start","c_end","minlength","maxlength","nmea","regex","math"};

    public static String replyToCommand(Datagram d) {

        var args = d.argList();

        String id = args[0];

        if( id.equalsIgnoreCase("?"))
            return doHelpCmd(d.asHtml());

        // Prepare the digger
        var dig = XMLdigger.goIn(Paths.settings(),"dcafs");
        if( !dig.hasPeek("paths")) { // If no paths node, add it
            XMLfab.alterDigger(dig).ifPresent(fab->fab.addChild("paths").build());
        }
        dig.digDown("paths");
        if( dig.isInvalid())
            return "! No paths yet";

        dig.digDown("path","id",id);
        if (args[1].equalsIgnoreCase("new")
                || args[1].equalsIgnoreCase("xml")) {
            if (dig.isInvalid())
                return createPath(args, Paths.settings(), id);
            return "! Already a path with that id, pick something else?";
        }
        if (dig.isInvalid())
            return "! No path with that id yet.";
        // At this point, the digger is pointing to the path node for the given id
        // But this might be an import....
        var fabOpt = XMLfab.alterDigger(dig); // Create a fab with parentnode the path node
        if( fabOpt.isEmpty())
            return "! No valid fab created";

        return processCommand(d, dig, fabOpt.get());
    }
    /* ***************************** H E L P *********************************************************** */
    private static String doHelpCmd(boolean html) {

        var help = new StringJoiner("\r\n");
        help.add("PathForward allows applying processing steps to received data.");
        help.add("Add/edit a path")
                .add("pf:pathid,new,src -> Create a new path with the given id and src")
                .add("pf:pathid,xml,src -> Add a path file with the given id and src (in default path folder)")
                .add("pf:pathid,delete -> Delete this path completely")
                .add("pf:pathid,clear -> Remove all the steps");
        help.add("Add new steps")
                .add("pf:pathid,addfilter/addf,type:rule -> Add a filter, with the given rule")
                .add("pf:pathid,addeditor/adde,type:value -> Add an editor,with the given edit")
                .add("pf:pathid,addmath/addm,operation -> Add a math, with given operation")
                .add("pf:pathid,addcmd/addc,operation -> Add a cmd, with given cmd")
                .add("pf:pathid,store,cmds -> Add/edit a store");
        help.add("Alter attributes")
                .add("pf:pathid,delimiter/delim,newdelimiter -> Change the delimiter")
                .add("pf:pathid,switchsrc,newsrc -> Alter the src at runtime, keeping xml as is.")
                .add("pf:pathid,src,newsrc -> Alter the src in xml");
        return LookAndFeel.formatHelpCmd(help.toString(), html);
    }
    /* ***************************** C R E A T E  P A T H ********************************************** */
    private static String createPath( String[] cmds, Path settingsPath, String id){
        return switch(cmds[1]){
            case "new" ->{
                if( cmds.length<3) {
                    yield "! To few arguments, expected pf:pathid,new,src";
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
                yield "Path created";
            }
            case "xml" -> {
                if( cmds.length<3)
                    yield "! To few arguments, expected pf:pathid,xml,src";
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
                // Actually create the file if it doesn't exist
                if( Files.notExists(pathPath.resolve(cmds[0]+".xml"))) {
                    XMLfab.withRoot(pathPath.resolve(cmds[0] + ".xml"), "dcafs")
                            .selectOrAddChildAsParent("path", "id", cmds[0])
                            .attr("delimiter", ",")
                            .build();
                    yield "Path created";
                }
                yield "Path with existing file linked.";
            }
            default -> "! No such path cmd yet " + id;
        };
    }
    /* ************************************************************************************************* */
    private static String processCommand(Datagram d, XMLdigger dig, XMLfab fab) {
        var args = d.argList();

        return switch (args[1]) {
            /* Commands that affect the path */
            case "delimiter", "delim" -> {
                if (args.length < 3)
                    yield "! Not enough arguments: pf:id,delim/delimiter,newdelimiter";
                var deli = args.length == 4 ? "," : args[2];
                fab.attr("delimiter", deli);
                fab.build();
                yield "Set the delimiter to '" + deli + "'";
            }
            case "clear" -> {
                fab.clearChildren();
                fab.build();
                yield "Removed all steps";
            }
            case "delete" -> {
                if (args.length < 3 || args[2].equals("all")) {
                    fab.up();
                    fab.removeChild("path", "id", args[0]);
                    fab.build();
                    yield "Deleted the path completely";
                }else{
                    if (fab.removeLastChild("*")) {
                        fab.build();
                        yield "Last node removed";
                    }
                    yield "! Failed to remove node";
                }
            }
            case "src" -> {
                if (args.length < 3)
                    yield "! Not enough arguments: pf:id,src,newsrc";
                fab.attr("src", args[2])
                        .build();
                yield "Set the src to '" + args[2] + "'";
            }
            /* Commands to add a simple step at the end */
            case "addif" -> addIfBranch(args, fab);
            case "addfilter", "addf" -> addFilterBranch(args, dig, fab);
            case "addeditor", "adde" -> addEditorBranch(args, d.args(), dig, fab);
            case "addmath", "addm" -> addMathBranch(args, dig, fab);
            case "addcmd", "addc" -> addCmdBranch(args, dig, fab);
            case "store" -> doStoreBranch(args);
            default -> "unknown command: pf:" + String.join(",", args);
        };
    }
    private static String addIfBranch(String[] cmds, XMLfab fab){
        if (cmds.length < 3)
            return "! Not enough arguments: pf:id,addif,check,value";

        if (cmds[2].split(",").length != 2)
            return "! Need a type and a rule separated with : (pf:id,addif,check,value)";
        var type = cmds[2].substring(0,cmds[2].indexOf(","));
        var value = cmds[2].substring(cmds[2].indexOf(",")+1);

        if (!List.of(FILTERS).contains(type))
            return "! No such if check, valid: " + String.join(",", FILTERS);

        fab.addChild("if").attr( type, value);
        fab.build();
        return "If added";
    }
    private static String addMathBranch( String[] cmds, XMLdigger dig, XMLfab fab ) {
        if (cmds.length < 3)
            return "! Not enough arguments: pf:id,addmath,operation";

        // fab is pointing at path node, needs to know if last item is a math or not
        dig.digDown("*").toLastSibling();
        var opt = dig.current();
        // Check if it has steps and if the last one isn't a math
        if (opt.isEmpty() || !opt.get().getTagName().equalsIgnoreCase("math")) {
            fab.addChild("math", cmds[2]);
        } else { // Last one is a math
            var fabOpt = XMLfab.alterDigger(dig);
            if (fabOpt.isEmpty())
                return "! Failed to get fab";
            fab = fabOpt.get();
            // fab pointing at the last math
            if (dig.digDown("op").isInvalid()) { // check if already contains a rule node
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
    private static String addFilterBranch(String[] cmds, XMLdigger dig, XMLfab fab){
        if (cmds.length < 3)
            return "! Not enough arguments: pf:id,addfilter,type:rule";
        var rule = cmds[2].split(":");
        if (rule.length != 2)
            return "! Need a type and a rule separated with : (pf:id,addfilter,type:rule)";

        if (!List.of(FILTERS).contains(rule[0]))
            return "! No such filter type, valid: " + String.join(",", FILTERS);

        // fab is pointing at path node, needs to know if last item is a filter or not
        dig.digDown("*").toLastSibling();
        var opt = dig.current();
        
        // Check if it has steps and if the last one isn't a filter
        if (opt.isEmpty() || !opt.get().getTagName().equalsIgnoreCase("filter")) {
            fab.addChild("filter", rule[1]).attr("type", rule[0]).build();
            return "Filter added";
        }

        // Last one is a filter
        var fabOpt = XMLfab.alterDigger(dig);
        if (fabOpt.isEmpty())
            return "! Failed to get fab";
        fab = fabOpt.get();
        // fab pointing at the last filter
        if (dig.digDown("rule").isInvalid()) { // check if already contains a rule node
            // Correct node but no rule subnodes... replace current
            var cur = opt.get();
            var content = cur.getTextContent();
            var type = cur.getAttribute("type");
            fab.content("").removeAttr("type"); // clear to replace with sub
            fab.addChild("rule", content).attr("type", type);
        }
        fab.addChild("rule", rule[1]).attr("type", rule[0]); // add new one
        fab.build();
        return "Filter added";
    }

    private static String addEditorBranch(String[] args, String fullArg, XMLdigger dig, XMLfab fab) {
        if (args.length < 4)
            return "! Not enough arguments: pf:id,addeditor,type,value";

        // Now get the value that can contain ,
        int a = fullArg.indexOf(args[2] + ",");
        a += args[2].length() + 1;
        var value = fullArg.substring(a);

        // fab is pointing at path node, needs to know if last item is an editor or not
        dig.digDown("*").toLastSibling();
        var opt = dig.current();
        if (opt.isPresent() && opt.get().getTagName().equalsIgnoreCase("editor")) {
            // Last one is a editor, so get a
            var fabOpt = XMLfab.alterDigger(dig);
            if (fabOpt.isEmpty())
                return "! Failed to get fab";
            fab = fabOpt.get();
        } else {
            fab.addChild("editor").down();
        }
        return EditorCmds.addEditor(fab, args[2], value);
    }
    private static String addCmdBranch( String[] cmds, XMLdigger dig, XMLfab fab ){
        if (cmds.length < 3)
            return "! Not enough arguments: pf:id,addcmd,cmd";
        // fab is pointing at path node, needs to know if last item is a cmd or not
        dig.digDown("*").toLastSibling();
        var opt = dig.current();
        // Check if it has steps and if the last one isn't a cmd
        if (opt.isPresent()) {
            var fabOpt = XMLfab.alterDigger(dig);
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
    private static String doStoreBranch( String[] cmds ){
        if (cmds.length == 3 && cmds[2].equals("?")) { // So request for info?
            var res = StoreCmds.replyToCommand("?", false);
            return res.replace("store:streamid,", "pf:" + cmds[0] + ",store,");
            }
            if( cmds.length < 4 )
                return "! Not enough arguments, need at least 4: pf:pathid,store,cmd,value(s)";
        var cmdString = String.join(",", ArrayUtils.remove(cmds, 1)); // Remove 'store'
        return StoreCmds.replyToPathCmd(cmdString, Paths.settings());
        }
}