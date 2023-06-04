package io.collector;

import das.Commandable;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.RealtimeValues;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import worker.Datagram;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;

public class CollectorPool implements Commandable, CollectorFuture {

    private final Map<String, FileCollector> fileCollectors = new HashMap<>();

    private final Path workPath;
    private final BlockingQueue<Datagram> dQueue;
    private final EventLoopGroup nettyGroup;
    private final RealtimeValues rtvals;

    public CollectorPool(Path workpath, BlockingQueue<Datagram> dQueue, EventLoopGroup nettyGroup, RealtimeValues rtvals ){
        this.workPath=workpath;
        this.dQueue=dQueue;
        this.nettyGroup=nettyGroup;
        this.rtvals=rtvals;

        // Load the collectors
        loadFileCollectors();
    }

    @Override
    public String replyToCommand(String cmd, String args, Writable wr, boolean html) {
        return switch( cmd ) {
            case "fc" -> doFileCollectorCmd(args, html);
            case "mc" -> "No commands yet";
            default -> "Wrong commandable...";
        };
    }
    public void flushAll(){
        fileCollectors.values().forEach(FileCollector::flushNow);
    }

    @Override
    public void collectorFinished(String id, String message, Object result) {
        String[] ids = id.split(":");
        if(ids[0].equalsIgnoreCase("math"))
            rtvals.updateReal(message,(double)result);
    }
    /* *************************************** F I L E C O L L E C T O R ************************************ */

    /**
     * Check the settings.xml for filecollectors and load them
     */
    private void loadFileCollectors(){
        fileCollectors.clear();
        FileCollector.createFromXml(
                XMLfab.getRootChildren(workPath.resolve("settings.xml"),"dcafs","collectors","file"),
                        nettyGroup,
                        dQueue,
                        workPath.toString() )
                .forEach( this::addFileCollector );
    }

    /**
     * Add a filecollector to the pool, init the source
     * @param fc The filecollector to add
     */
    private void addFileCollector( FileCollector fc ){
        Logger.info("Created "+fc.id());
        fileCollectors.put(fc.id().substring(3),fc); // remove the fc: from the front
        dQueue.add( Datagram.system(fc.getSource()).writable(fc) ); // request the data
    }

    /**
     * Get a list off all active file collectors
     * @param eol The end of line characters to use
     * @return The list with the chosen eol characters
     */
    private String getFileCollectorsList( String eol ){
        StringJoiner join = new StringJoiner(eol);
        join.setEmptyValue("None yet");
        fileCollectors.forEach((key, value) -> join.add(key + " -> " + value.toString()));
        return join.toString();
    }

    /**
     * Create a file collector with the given id and add it to the pool
     * @param id The id for the filecollector
     * @return The created object
     */
    private FileCollector createFileCollector(String id ){
        var fc = new FileCollector(id,"1m",nettyGroup,dQueue);
        fileCollectors.put(id, fc);
        return fc;
    }
    private String doFileCollectorCmd( String args, boolean html ) {
        String[] cmds = args.split(",");
        StringJoiner join = new StringJoiner(html?"<br":"\r\n");

        String cyan = html?"":TelnetCodes.TEXT_CYAN;
        String green=html?"":TelnetCodes.TEXT_GREEN;
        String reg=html?"":TelnetCodes.TEXT_DEFAULT;

        var settingsPath = workPath.resolve("settings.xml");

        if( cmds.length==1){
            switch (cmds[0]) {
                case "?" -> {
                    join.add(TelnetCodes.TEXT_MAGENTA + "The FileCollectors store data from sources in files with custom headers and optional rollover");
                    join.add(cyan + "Create/Reload the FileCollector" + reg)
                            .add(green + "   fc:addnew,id,src,filepath" + reg + " -> Create a blank filecollector with given id, source and filepath")
                            .add(green + "   fc:reload " + reg + " -> Reload the file collectors")
                            .add(green + "   fc:id,reload " + reg + " -> Reload the given file collector");
                    join.add(cyan + "Add optional parts" + reg)
                            .add(green + "   fc:id,addrollover,count,unit,format,zip? " + reg + " -> Add rollover (unit options:min,hour,day,week,month,year")
                            .add(green + "   fc:id,addcmd,trigger:cmd " + reg + " -> Add a triggered command, triggers: maxsize,idle,rollover")
                            .add(green + "   fc:id,addheader,headerline " + reg + " -> Adds the header to the given fc")
                            .add(green + "   fc:id,addsizelimit,size,zip? " + reg + " -> Adds a limit of the given size with optional zipping");
                    join.add(cyan + "Alter attributes" + reg)
                            .add( green + "  fc:id,filepath,newpath"+reg+" -> Change the filepath")
                            .add( green + "  fc:id,sizelimit,newlimit"+reg+" -> Change the file sizelimit")
                            .add( green + "  fc:id,eol,neweol"+reg+" -> Change the eol sequence")
                            .add( green + "  fc:id,charset,newcharset"+reg+" -> Change the charset used")
                            .add( green + "  fc:id,src,newsrc"+reg+" -> Change the src");
                    join.add(cyan + "Get info" + reg)
                            .add(green + "   fc:list " + reg + " -> Get a list of all active File Collectors")
                            .add(green + "   fc:? " + reg + " -> Show this message");
                    return join.toString();
                }
                case "reload" -> {
                    loadFileCollectors();
                    return "Reloaded all filecollectors";
                }
                case "list" -> {
                    return getFileCollectorsList(html ? "<br" : "\r\n");
                }
                default -> {
                    return "! No such subcommand in fc : "+args;
                }
            }
        }else if(cmds[0].equalsIgnoreCase("addnew")||cmds[0].equalsIgnoreCase("add")){
            if (cmds.length < 4)
                return "! Not enough arguments given: fc:addnew,id,src,path";
            FileCollector.addBlankToXML(XMLfab.withRoot(workPath.resolve("settings.xml"), "dcafs"), cmds[1], cmds[2], cmds[3]);
            var fc = createFileCollector(cmds[1]);
            fc.addSource(cmds[2]);
            if (Path.of(cmds[3]).isAbsolute()) {
                fc.setPath(Path.of(cmds[3]));
            } else {
                fc.setPath(Path.of(settingsPath.getParent().toString()).resolve(cmds[3]));
            }
            return "FileCollector " + cmds[1] + " created and added to xml.";
        }else{
            var fco = fileCollectors.get(cmds[0]);
            if( fco == null )
                return "! Invalid id given: "+cmds[0];
            var id = cmds[0];

            var fab = XMLfab.withRoot(settingsPath, "dcafs", "collectors")
                            .selectOrAddChildAsParent("file", "id", id);

            switch (cmds[1]) {
                case "addrollover" -> {
                    if (cmds.length < 6)
                        return "! Wrong amount of arguments -> fc:id,addrollover,count,unit,format,zip?";
                    if (fco.setRollOver(cmds[4], NumberUtils.toInt(cmds[2]), TimeTools.convertToRolloverUnit(cmds[3]), Tools.parseBool(cmds[5], false))) {
                        XMLfab.withRoot(settingsPath, "dcafs", "collectors")
                                .selectOrAddChildAsParent("file", "id", id)
                                .alterChild("rollover", cmds[4]).attr("count", cmds[2]).attr("unit", cmds[3]).attr("zip", cmds[5]).build();
                        return "Rollover added to " + id;
                    }
                    return "! Failed to add rollover";
                }
                case "addheader" -> {
                    if (cmds.length < 3)
                        return "! Wrong amount of arguments -> fc:addheader,id,header";
                    fco.flushNow();
                    fco.addHeaderLine(cmds[2]);
                    fab.addChild("header", cmds[2]).build();
                    return "Header line added to " + id;
                }
                case "addcmd" -> {
                    if (cmds.length < 3)
                        return "! Wrong amount of arguments -> fc:id,adcmd,trigger:cmd";
                    if (!cmds[2].contains(":"))
                        return "! No valid trigger:cmd pair in: " + cmds[2];

                    String[] sub = {"", ""};
                    sub[0] = cmds[2].substring(0, cmds[2].indexOf(":"));
                    sub[1] = cmds[2].substring(sub[0].length() + 1);

                    if (fco.addTriggerCommand(sub[0], sub[1])) {
                        fab.addChild("cmd", sub[1]).attr("trigger", sub[0]).build();
                        return "Triggered command added to " + id;
                    }
                    return "Failed to add command, unknown trigger?";
                }
                case "addsizelimit" -> {
                    if (cmds.length != 4)
                        return "! Wrong amount of arguments -> fc:id,addsizelimit,size,zip?";
                    fco.setMaxFileSize(cmds[2], Tools.parseBool(cmds[3], false));
                    fab.addChild("sizelimit", cmds[2]).attr("zip", cmds[3]).build();
                    return "Size limit added to " + id;
                }
                case "path" -> {
                    fco.setPath(Path.of(cmds[2]), workPath.toString());
                    fab.alterChild("path", cmds[2]).build();
                    return "Altered the path";
                }
                case "sizelimit" -> {
                    fco.setMaxFileSize(cmds[2]);
                    fab.alterChild("sizelimit", cmds[2]).build();
                    return "Altered the size limit to " + cmds[2];
                }
                case "eol" -> {
                    fco.setLineSeparator(Tools.fromEscapedStringToBytes(cmds[2]));
                    fab.attr("eol", cmds[2]).build();
                    return "Altered the eol string to " + cmds[2];
                }
                case "charset" -> {
                    fab.attr("charset", cmds[2]).build();
                    return "Altered the charset to " + cmds[2];
                }
                case "src" -> {
                    fco.addSource(cmds[2]);
                    fab.attr("src", cmds[2]).build();
                    return "Source altered to " + cmds[2];
                }
                case "reload" -> {
                    var opt = XMLfab.withRoot(settingsPath, "dcafs", "collectors")
                            .getChild("file", "id", cmds[1]);
                    if (opt.isPresent()) {
                        fco.flushNow();
                        fco.readFromXML(opt.get(), workPath.toString());
                    }
                    return "Reloaded";
                }
                case "perms" -> {
                    fileCollectors.values().forEach(f -> FileTools.setAllPermissions(f.getPath().getParent()));
                    return "Tried to alter permissions";
                }
                default -> {
                    return "! No such subcommand for fc:id : " + cmds[1];
                }
            }
        }
    }
    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
}
