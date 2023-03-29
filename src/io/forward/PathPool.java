package io.forward;

import util.cmds.PathCmds;
import util.data.RealtimeValues;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import io.telnet.TelnetCodes;
import das.Commandable;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;

public class PathPool implements Commandable {

    private final HashMap<String, PathForward> paths = new HashMap<>();
    private final BlockingQueue<Datagram> dQueue;
    private final Path settingsPath;
    private final RealtimeValues rtvals;
    private final EventLoopGroup nettyGroup;

    public PathPool(BlockingQueue<Datagram> dQueue, Path settingsPath, RealtimeValues rtvals, EventLoopGroup group){
        this.dQueue=dQueue;
        this.settingsPath=settingsPath;
        this.rtvals=rtvals;
        nettyGroup=group;
        readPathsFromXML();
    }
    /* **************************************** G E N E R A L ************************************************** */
    /**
     * Read the paths stored in the settings.xml and imported
     */
    public void readPathsFromXML(){
        var xmlOpt = XMLtools.readXML(settingsPath);
        if( xmlOpt.isEmpty()) {
            dQueue.add(Datagram.build("ForwardPool -> Failed to read xml at "+settingsPath).label("fail"));
            return;
        }

        Logger.info("Loading paths...");
        // Reset the rtval stores
        clearStores();

        // From the paths section
        XMLfab.getRootChildren(settingsPath,"dcafs","paths","path").forEach(
                pathEle -> {
                    PathForward path = new PathForward(rtvals,dQueue,nettyGroup);
                    path.readFromXML( pathEle,settingsPath.getParent() );
                    var p = paths.get(path.id());
                    if( p!=null) {
                        p.stop();
                        paths.remove(p.id());
                    }
                    paths.put(path.id(),path);
                }
        );

        // From the streams section
        XMLfab.getRootChildren(settingsPath,"dcafs","streams","stream").stream()
                .filter( e -> XMLtools.hasChildByTag(e,"path")) // Only those with a path node
                .map( e -> XMLtools.getFirstChildByTag(e,"path").get())
                .forEach(
                        pathEle -> {
                            PathForward path = new PathForward(rtvals,dQueue,nettyGroup);
                            var parentId = XMLtools.getStringAttribute((Element) pathEle.getParentNode(), "id", "");
                            // The functionality to import a path, relies on an attribute while this will be a content instead but may be...
                            if( !pathEle.hasAttribute("import")) {
                                var importPath = pathEle.getTextContent();
                                if (importPath.isEmpty()) {
                                    Logger.error("Empty content in path node for " + parentId);
                                    return;
                                }
                                pathEle.setAttribute("import", pathEle.getTextContent());
                                pathEle.setTextContent("");
                            }
                            pathEle.setAttribute("src","raw:"+parentId);
                            path.readFromXML(pathEle,settingsPath.getParent());
                        });
        Logger.info("Finished loading paths");
    }
    @Override
    public String replyToCommand(String cmd, String args, Writable wr, boolean html) {
        // Regular ones
        switch (cmd) {
            // Path
            case "paths", "pf" -> {
                return replyToPathCmd(args, wr, html);
            }
            case "path" -> {
                if( args.startsWith("!")){
                    var p = paths.get(args.substring(1));
                    if( p!=null)
                        p.removeTarget(wr);
                    return "Remove received";
                }else {
                    var p = paths.get(args);
                    if (p == null)
                        return "No such path (yet): " + args;
                    p.addTarget(wr);
                    return "Request received.";
                }
            }
            case "" -> {
                paths.values().forEach( p->p.removeTarget(wr));
                return "";
            }
            default -> {
                return "! No such subcommand in "+cmd+": "+args;
            }
        }
    }

    @Override
    public boolean removeWritable( Writable wr) {
        paths.values().forEach( p -> p.removeTarget(wr));
        return false;
    }
    /* ******************************************** P A T H ******************************************************** */
    public String replyToPathCmd(String cmd, Writable wr, boolean html ){
        var cmds =cmd.split(",");

        String cyan = html?"":TelnetCodes.TEXT_CYAN;
        String green=html?"":TelnetCodes.TEXT_GREEN;
        String reg=html?"":TelnetCodes.TEXT_DEFAULT;
        var or = html?"":TelnetCodes.TEXT_ORANGE;
        switch (cmds[0]) {
            case "?" -> {
                StringJoiner help = new StringJoiner("\r\n");
                help.add(TelnetCodes.TEXT_RESET + TelnetCodes.TEXT_ORANGE + "Notes" + reg)
                        .add(" - A / in the command means both options are valid.")
                        .add(" - Paths are used to combine multiple different forwards in a single structure, this allows for a")
                        .add("   lot of attributes to be either generated by dcafs fe. src or omitted (id's) which reduces")
                        .add("   the amount of xml the user needs to write.");
                help.add( PathCmds.replyToCommand("?",html,settingsPath));
                help.add("").add(cyan + "Other" + reg)
                        .add(green + " pf:reload/reloadall " + reg + "-> Reload all the paths")
                        .add(green + " pf:pathid,reload " + reg + "-> reload the path with the given id")
                        .add(green + " pf:list " + reg + "-> List all the currently loaded paths")
                        .add(green + " pf:pathid,debug<,stepnr/stepid> " + reg + "-> Request the data from a single step in the path (nr:0=first; -1=custom src)")
                        .add(green + " pf:clear " + reg + "-> Remove all the paths from XML!");

                return help.toString();
            }
            case "reload", "reloadall" -> {
                if (cmds.length == 1 || cmds[0].endsWith("all")) {
                    readPathsFromXML();
                    return "All paths reloaded.";
                }
                var ele = XMLfab.withRoot(settingsPath, "dcafs", "paths")
                        .getChild("path", "id", cmds[1]);
                if (ele.isEmpty())
                    return "! No such path " + cmds[1];
                var result = paths.get(cmds[1]).readFromXML(ele.get(), settingsPath.getParent());
                return result.isEmpty() ? "Path reloaded" : result;
            }
            case "clear" -> {
                XMLfab.withRoot(settingsPath, "dcafs", "paths").clearChildren().build();
                paths.values().forEach(PathForward::stop);
                paths.values().forEach(PathForward::clearStores);
                paths.clear();
                return "Paths cleared";
            }
            case "stop" -> {
                paths.values().forEach(pf -> pf.removeTarget(wr));
                return "Stopped sending to " + wr.id();
            }
            case "list" -> {
                StringJoiner join = new StringJoiner(html ? "<br>" : "\r\n");
                join.setEmptyValue("No paths yet");
                paths.forEach((key, value) -> {
                    String src = key + " src: " + value.src();
                    join.add(green+ "Path: " + src + reg).add(value.toString()).add("");
                });
                return join.toString();
            }
            default -> {
                if (cmds.length < 2)
                    return "Not enough arguments";
                var pp = paths.get(cmds[0]);
                switch (cmds[1]) {
                    case "debug" -> {
                        if( cmds.length==2)
                            return pp.debugStep("*", wr);
                        if (cmds.length != 3)
                            return "Incorrect number of arguments, needs to be pf:pathid,debug<,stepnr/stepid> (from 0 or -1 for customsrc)";
                        if (pp == null)
                            return or+"! No such path: " + cmds[1]+reg;
                        int nr = NumberUtils.toInt(cmds[2], -2);
                        if (wr == null)
                            return or+"! No valid writable"+reg;
                        if (nr == -2)
                            return pp.debugStep(cmds[2], wr);
                        return pp.debugStep(nr, wr);
                    }
                    case "reload" -> {
                        var ele = XMLfab.withRoot(settingsPath, "dcafs", "paths")
                                .getChild("path", "id", cmds[1]);
                        if (ele.isEmpty())
                            return or+"! No such path " + cmds[1]+reg;
                        var result = paths.get(cmds[1]).readFromXML(ele.get(), settingsPath.getParent());
                        return result.isEmpty() ? "Path reloaded" : result;
                    }
                    default -> {
                        var res = PathCmds.replyToCommand(cmd, html, settingsPath);
                        // Reload the path
                        var pEle = XMLfab.withRoot(settingsPath, "dcafs", "paths")
                                .getChild("path", "id", cmds[0]);
                        if (!res.startsWith("!")) { // If cmd worked
                            if (pEle.isPresent()) { // If an existing path was altered
                                if( res.equalsIgnoreCase("path created")){
                                    PathForward path = new PathForward(rtvals,dQueue,nettyGroup);
                                    path.readFromXML( pEle.get(),settingsPath.getParent() );
                                    paths.put(path.id(),path);
                                }
                                var pat = paths.get(cmds[0]);
                                if( pat!=null) {
                                    pat.readFromXML(pEle.get(), settingsPath.getParent());
                                }else{
                                    return or +"! No valid path found to reload"+reg;
                                }
                            } else if (cmds[1].equalsIgnoreCase("delete")) { // meaning the path was removed from xml, remove it from paths
                                removePath(cmds[0]);
                            }
                        }
                        // Add some color based on good or bad result
                        if (res.startsWith("!") || res.startsWith("unknown"))
                            return or + res + reg;
                        return green + res + reg;
                    }
                }
            }
        }
    }
    private void removePath(String id){
        var p = paths.get(id);
        if( p!=null){
            p.clearStores(); // reset the used stores
            paths.remove(id);
        }
    }
    private void clearStores(){
        paths.values().forEach(PathForward::clearStores);
    }
}
