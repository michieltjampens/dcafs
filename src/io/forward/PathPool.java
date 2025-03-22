package io.forward;

import das.Commandable;
import das.Paths;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.LookAndFeel;
import util.data.RealtimeValues;
import util.database.QueryWriting;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.util.HashMap;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;

public class PathPool implements Commandable {

    private final HashMap<String, PathForward> paths = new HashMap<>();
    private final BlockingQueue<Datagram> dQueue;
    private final RealtimeValues rtvals;
    private final EventLoopGroup nettyGroup;
    private final QueryWriting qw;

    public PathPool(BlockingQueue<Datagram> dQueue, RealtimeValues rtvals, EventLoopGroup group, QueryWriting qw){
        this.dQueue=dQueue;
        this.rtvals=rtvals;
        this.qw=qw;
        nettyGroup=group;
        readPathsFromXML();
    }
    /* **************************************** G E N E R A L ************************************************** */
    /**
     * Read the paths stored in the settings.xml and imported
     */
    public void readPathsFromXML(){
        var xmlOpt = XMLtools.readXML(Paths.settings());
        if( xmlOpt.isEmpty()) {
            dQueue.add(Datagram.build("ForwardPool -> Failed to read xml at "+Paths.settings()).label("fail"));
            return;
        }

        Logger.info("Loading paths...");
        // Reset the rtval stores
        clearStores();

        // From the paths section
        XMLdigger.goIn(Paths.settings(),"dcafs","paths").peekOut("path").forEach(
                pathEle -> {
                    PathForward path = new PathForward(rtvals,dQueue,nettyGroup,qw);
                    path.readFromXML( pathEle,Paths.settings().getParent() );
                    var p = paths.get(path.id());
                    if( p!=null) {
                        p.lastTargets().forEach(path::addTarget); // Pass targets on to the new path
                        p.getTargets().forEach(path::addTarget);
                        p.stop();
                        paths.remove(p.id());
                    }
                    paths.put(path.id(),path);
                }
        );

        // From the streams section
        XMLdigger.goIn(Paths.settings(),"dcafs","streams").peekOut("stream").stream()
                .filter( e -> XMLtools.hasChildByTag(e,"path")) // Only those with a path node
                .map( e -> XMLtools.getFirstChildByTag(e,"path").get())
                .forEach(
                        pathEle -> {
                            PathForward path = new PathForward(rtvals,dQueue,nettyGroup,qw);
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
                            path.readFromXML(pathEle,Paths.settings().getParent());
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
                    var sp = args.split(",");
                    var p = paths.get(sp[0]);
                    if (p == null)
                        return "No such path (yet): " + args;
                    if( sp.length==2){
                        p.addTarget(wr,sp[1]);
                    }else{
                        p.addTarget(wr);
                    }
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
    public String payloadCommand( String cmd, String args, Object payload){
        return "! No such cmds in "+cmd;
    }
    @Override
    public boolean removeWritable( Writable wr) {
        paths.values().forEach( p -> p.removeTarget(wr));
        return false;
    }
    /* ******************************************** P A T H ******************************************************** */
    public String replyToPathCmd(String cmd, Writable wr, boolean html ){
        var args =cmd.split(",");

        switch (args[0]) {
            case "?" -> {
                StringJoiner help = new StringJoiner("\r\n");
                help.add("Commands related to general paths actions");
                help.add( PathCmds.replyToCommand("?",html));
                help.add("Other" )
                        .add( "pf:reload/reloadall -> Reload all the paths")
                        .add( "pf:id,reload -> reload the path with the given id")
                        .add( "pf:list -> List all the currently loaded paths")
                        .add("pf:id,list -> List all the steps in the chosen path")
                        .add( "pf:id,debug<,stepnr/stepid> -> Request the data from a single step in the path (nr:0=first; -1=custom src)")
                        .add( "pf:clear -> Remove all the paths from XML!");
                return LookAndFeel.formatCmdHelp(help.toString(),html);
            }
            case "reload", "reloadall" -> { // Reload all tha paths
                if (args.length == 1 || args[0].endsWith("all")) {
                    readPathsFromXML();
                    return "All paths reloaded.";
                }
                var dig = Paths.digInSettings("paths");
                if ( !dig.hasPeek("path","id",args[0]))
                    return "! No such path " + args[0];
                var result = paths.get(args[1]).readFromXML( dig.currentTrusted(), Paths.storage());
                return result.isEmpty() ? "Path reloaded" : result;
            }
            case "clear" -> { // Clear the path node and reload
                XMLfab.withRoot(Paths.settings(), "dcafs", "paths").clearChildren().build();
                paths.values().forEach(PathForward::stop);
                paths.values().forEach(PathForward::clearStores);
                paths.clear();
                return "Paths cleared";
            }
            case "stop" -> { // Stop receiving data from this path
                paths.values().forEach(pf -> pf.removeTarget(wr));
                return "Stopped sending to " + wr.id();
            }
            case "list" -> { // Get a listing of all the steps in the path
                String green=html?"":TelnetCodes.TEXT_GREEN;
                String reg=html?"":TelnetCodes.TEXT_DEFAULT;
                StringJoiner join = new StringJoiner(html ? "<br>" : "\r\n");
                join.setEmptyValue("No paths yet");
                paths.forEach((key, value) -> {
                    String src = key + " src: " + value.src();
                    join.add(green+ "Path: " + src + reg);//.add(value.toString()).add("");
                });
                return join.toString();
            }
            default -> {
                return doTwoArgsCmds(args,cmd,wr,html);
            }
        }
    }
    private String doTwoArgsCmds( String[] args, String cmd, Writable wr, boolean html ){
        if (args.length < 2)
            return "Not enough arguments";

        var pf = paths.get(args[0]);

        switch (args[1]) {
            case "debug" -> {
                return doDebugCmd(args,wr,pf);
            }
            case "reload" -> { // Reload the given path
                var dig = Paths.digInSettings("paths");
                if ( !dig.hasPeek("path","id",args[0]))
                    return "! No such path " + args[0];
                var result = paths.get(args[1]).readFromXML( dig.usePeek().currentTrusted(), Paths.storage() );
                return result.isEmpty() ? "Path reloaded" : result;
            }
            case "list" -> {
                return pf == null
                            ? "! No such path: " + args[0]
                            : "Path: " + pf.id() + (html ? "<br>" : "\r\n") + pf;
            }
            default -> {
                return doRequest(args,cmd,html);
            }
        }
    }
    private String doDebugCmd( String[] args, Writable wr, PathForward pf){
        if (pf == null)
            return "! No such path: " + args[0];
        if (wr == null)
            return "! No valid writable";

        if( args.length==2)
            return pf.debugStep("*", wr);

        if (args.length != 3)
            return "! Incorrect number of arguments, needs to be pf:id,debug<,stepnr/stepid> (from 0 or -1 for customsrc)";

        int nr = NumberUtils.toInt(args[2], -2);
        if (nr == -2)
            return pf.debugStep(args[2], wr);
        return pf.debugStep(nr, wr);
    }
    private String doRequest( String[] args, String cmd, boolean html){
        if (args[1].equalsIgnoreCase("switchsrc")) {
            var path = paths.get(args[0]);
            if (path == null)
                return "! No such path yet.";
            path.src(args[2]).reloadSrc();
            return "Src altered to " + args[2];
        }

        var res = PathCmds.replyToCommand( cmd, html );
        if (res.startsWith("Table added with ")) {
            dQueue.add(Datagram.system(res.substring(res.indexOf("dbm"))));
        }else if( !res.startsWith("!") ){ // If the command worked
            if (res.startsWith("Deleted ")) { // meaning the path was removed from xml, remove it from paths
                paths.remove(args[0]);
            } else {
                var dig = Paths.digInSettings("paths");
                if ( !dig.hasPeek("path","id",args[0]))
                    return "! No such path: " + args[0] ;
                dig.usePeek();
                if (!paths.containsKey(args[0])) // Exists in xml but not in map
                    paths.put( args[0], new PathForward(rtvals, dQueue, nettyGroup, qw) );
                var rep = paths.get(args[0]).readFromXML(dig.currentTrusted(), Paths.storage() );
                dQueue.add(Datagram.system("dbm:reloadstores"));
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
