package io.forward;

import das.Commandable;
import das.Core;
import das.Paths;
import io.Writable;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.vals.Rtvals;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.util.HashMap;
import java.util.StringJoiner;

public class PathPool implements Commandable {

    private final HashMap<String, PathForward> paths = new HashMap<>();
    private final Rtvals rtvals;
    private final EventLoopGroup eventLoop = new DefaultEventLoopGroup(2, new DefaultThreadFactory("PathForward-group"));

    public PathPool(Rtvals rtvals) {
        this.rtvals=rtvals;

        readPathsFromXML();
    }
    /* **************************************** G E N E R A L ************************************************** */
    /**
     * Read the paths stored in the settings.xml and imported
     */
    public void readPathsFromXML(){

        Logger.info("Loading paths...");
        // Reset the rtval stores
        clearStores();

        // From the paths section
        XMLdigger.goIn(Paths.settings(), "dcafs", "paths").digOut("path").forEach(
                pathDig -> {
                    PathForward path = new PathForward(rtvals, eventLoop);
                    path.readFromXML(pathDig, Paths.storage());

                    var oldPath = paths.get(path.id());
                    if (oldPath != null) { //meaning it exists already
                        oldPath.getTargets().forEach(path::addTarget);
                        oldPath.stop();
                        paths.remove(oldPath.id());
                    }
                    paths.put(path.id(),path);
                }
        );
        Logger.info("Finished loading paths");
    }
    @Override
    public String replyToCommand(Datagram d) {
        // Regular ones
        switch (d.cmd()) {
            // Path
            case "paths", "pf" -> {
                return replyToPathCmd(d);
            }
            case "path" -> {
                if (d.args().startsWith("!")) {
                    var p = paths.get(d.args().substring(1));
                    if( p!=null)
                        p.removeTarget(d.getWritable());
                    return "Remove received";
                }else {
                    var args = d.argList();
                    var p = paths.get(args[0]);
                    if (p == null)
                        return "! No such path (yet): " + d.args();
                    if (args.length == 2) {
                        p.addTarget(d.getWritable(), args[1]);
                    }else{
                        p.addTarget(d.getWritable());
                    }
                    return "Request received.";
                }
            }
            case "" -> {
                paths.values().forEach(p -> p.removeTarget(d.getWritable()));
                return "";
            }
            default -> {
                return "! No such subcommand in " + d.getData();
            }
        }
    }
    @Override
    public boolean removeWritable( Writable wr) {
        paths.values().forEach( p -> p.removeTarget(wr));
        return false;
    }
    /* ******************************************** P A T H ******************************************************** */
    public String replyToPathCmd(Datagram d) {
        var args = d.argList();

        switch (args[0]) {
            case "?" -> {
                return doHelpCmd(d);
            }
            case "reload", "reloadall" -> { // Reload all tha paths
                if (args.length == 1 || args[0].endsWith("all")) {
                    readPathsFromXML();
                    return "All paths reloaded.";
                }
                var dig = Paths.digInSettings("paths");
                if ( !dig.hasPeek("path","id",args[0]))
                    return "! No such path " + args[0];
                var result = paths.get(args[1]).readFromXML(dig, Paths.storage());
                return result.isEmpty() ? "Path reloaded." : result;
            }
            case "clear" -> { // Clear the path node and reload
                XMLfab.withRoot(Paths.settings(), "dcafs", "paths").clearChildren().build();
                paths.values().forEach(PathForward::stop);
                paths.values().forEach(PathForward::clearStores);
                paths.clear();
                return "Paths cleared";
            }
            case "stop" -> { // Stop receiving data from this path
                paths.values().forEach(pf -> pf.removeTarget(d.getWritable()));
                return "Stopped sending to " + d.originID();
            }
            case "list" -> { // Get a listing of all the steps in the path
                String green = d.asHtml() ? "" : TelnetCodes.TEXT_GREEN;
                String reg = d.asHtml() ? "" : TelnetCodes.TEXT_DEFAULT;
                StringJoiner join = new StringJoiner(d.eol());
                join.setEmptyValue("No paths yet");
                paths.forEach((id, pf) -> {
                    join.add(green + "Path: " + id + " src: " + pf.src() + reg);//.add(value.toString()).add("");
                });
                return join.toString();
            }
            default -> {
                return doTwoArgsCmds(d);
            }
        }
    }

    private static String doHelpCmd(Datagram d) {
        var help = new StringJoiner("\r\n");
        help.add("Commands related to general paths actions");
        help.add(PathCmds.replyToCommand(d));
        help.add("Other")
                .add("pf:reload/reloadall -> Reload all the paths")
                .add("pf:id,reload -> reload the path with the given id")
                .add("pf:list -> List all the currently loaded paths")
                .add("pf:id,list -> List all the steps in the chosen path")
                .add("pf:id,debug<,stepnr/stepid> -> Request the data from a single step in the path (nr:0=first; -1=custom src)")
                .add("pf:clear -> Remove all the paths from XML!");
        return LookAndFeel.formatHelpCmd(help.toString(), d.asHtml());
    }

    private String doTwoArgsCmds(Datagram d) {
        if (d.argList().length < 2)
            return "Not enough arguments";
        var args = d.argList();
        var pf = paths.get(args[0]);

        return switch (args[1]) {
            case "debug" -> doDebugCmd(args, d.getWritable(), pf);
            case "reload" -> { // Reload the given path
                var dig = Paths.digInSettings("paths");
                if ( !dig.hasPeek("path","id",args[0]))
                    yield "! No such path " + args[0];
                var result = paths.get(args[1]).readFromXML(dig.usePeek(), Paths.storage());
                yield result.isEmpty() ? "Path reloaded" : result;
            }
            case "list" -> pf == null ? "! No such path: " + args[0] : "Path: " + pf.id() + d.eol() + pf;
            case "mathdebug" -> pf == null ? "! No such path " + args[0] : pf.getMathDebug("*");
            default -> doRequest(d);
        };
    }
    private String doDebugCmd( String[] args, Writable wr, PathForward pf){
        if (pf == null)
            return "! No such path: " + args[0];
        if (wr == null)
            return "! No valid writable";

        return "";
    }

    private String doRequest(Datagram d) {
        var args = d.argList();

        if (args[1].equalsIgnoreCase("switchsrc")) {
            var path = paths.get(args[0]);
            if (path == null)
                return "! No such path yet.";
            path.src(args[2]).reloadSrc();
            return "Src altered to " + args[2];
        }

        var res = PathCmds.replyToCommand(d);
        if (res.startsWith("Table added with ")) {
            Core.addToQueue( Datagram.system(res.substring(res.indexOf("dbm"))) );
        }else if( !res.startsWith("!") ){ // If the command worked
            if (res.startsWith("Deleted ")) { // meaning the path was removed from xml, remove it from paths
                paths.remove(args[0]);
            } else {
                var dig = Paths.digInSettings("paths");
                if ( !dig.hasPeek("path","id",args[0]))
                    return "! No such path: " + args[0] ;
                dig.usePeek();
                if (!paths.containsKey(args[0])) // Exists in xml but not in map
                    paths.put(args[0], new PathForward(rtvals, eventLoop));
                var rep = paths.get(args[0]).readFromXML(dig, Paths.storage());
                Core.addToQueue(Datagram.system("dbm","reloadstores"));
                if (!rep.isEmpty() && !res.startsWith("Path ") && !res.startsWith("Set ")) // empty is good, starting means new so not full
                    res = rep;
            }
        }
        return res;
    }
    private void clearStores(){
        paths.values().forEach(PathForward::clearStores);
    }

}
