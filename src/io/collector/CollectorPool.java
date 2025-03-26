package io.collector;

import das.Commandable;
import das.Core;
import das.Paths;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.RealtimeValues;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

public class CollectorPool implements Commandable, CollectorFuture {

    private final Map<String, FileCollector> fileCollectors = new HashMap<>();

    private final EventLoopGroup nettyGroup;
    private final RealtimeValues rtvals;

    public CollectorPool(EventLoopGroup nettyGroup, RealtimeValues rtvals ){

        this.nettyGroup=nettyGroup;
        this.rtvals=rtvals;

        // Load the collectors
        loadFileCollectors();
    }

    @Override
    public String replyToCommand(Datagram d) {
        return switch (d.cmd()) {
            case "fc" -> doFileCollectorCmd(d);
            case "mc" -> "No commands yet";
            default -> "Wrong commandable...";
        };
    }
    public String payloadCommand( String cmd, String args, Object payload){
        return "! No such cmds in "+cmd;
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
                        XMLdigger.goIn(Paths.storage().resolve("settings.xml"),"dcafs","collectors")
                                .peekOut("file"),
                        nettyGroup,
                        Paths.storage().toString() )
                .forEach( this::addFileCollector );
    }

    /**
     * Add a filecollector to the pool, init the source
     * @param fc The filecollector to add
     */
    private void addFileCollector( FileCollector fc ){
        Logger.info("Created "+fc.id());
        fileCollectors.put(fc.id().substring(3),fc); // remove the fc: from the front
        Core.addToQueue( Datagram.system(fc.getSource()).writable(fc) ); // request the data
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
        var fc = new FileCollector(id,"1m",nettyGroup);
        fileCollectors.put(id, fc);
        return fc;
    }

    private String doFileCollectorCmd(Datagram d) {
        String[] cmds = d.argList();

        if( cmds.length==1){
            return singleArgCommands(cmds[0], d.asHtml());
        }else if( cmds[0].equalsIgnoreCase("addnew")||cmds[0].equalsIgnoreCase("add") ){
           return addNewCommand(cmds);
        }else{
            var fco = fileCollectors.get(cmds[0]);
            if( fco == null )
                return "! Invalid id given: "+cmds[0];
            return doGeneralCommands(d, fco);
        }
    }
    private String singleArgCommands(String cmd, boolean html){
        return switch (cmd) {
            case "?" -> getHelp(html);
            case "reload" -> {
                loadFileCollectors();
                yield "Reloaded all filecollectors";
            }
            case "list" -> getFileCollectorsList(html ? "<br" : "\r\n");
            default -> "! No such subcommand in fc : "+cmd;
        };
    }

    /**
     * The help of the fc global command that lists all the options
     * @param html Whether to format it in html or not (and thus telnet)
     * @return The formatted help info
     */
    private String getHelp(boolean html){
        StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
        join.add( "The FileCollectors store data from sources in files with custom headers and optional rollover.");
        join.add( "Create/Reload the FileCollector" )
                .add( "fc:addnew,id,src,filepath -> Create a blank filecollector with given id, source and filepath")
                .add( "fc:reload  -> Reload the file collectors")
                .add( "fc:id,reload  -> Reload the given file collector");
        join.add( "Add optional parts" )
                .add( "fc:id,addrollover,period,format,zip?  -> Add rollover (period unit options:min,hour,day,week,month,year")
                .add( "fc:id,addcmd,trigger:cmd  -> Add a triggered command, triggers: maxsize,idle,rollover")
                .add( "fc:id,addheader,headerline  -> Adds the header to the given fc")
                .add( "fc:id,addsizelimit,size,zip?  -> Adds a limit of the given size with optional zipping");
        join.add( "Alter attributes" )
                .add(  "fc:id,filepath,newpath -> Change the filepath")
                .add(  "fc:id,sizelimit,newlimit -> Change the file sizelimit")
                .add(  "fc:id,eol,neweol -> Change the eol sequence")
                .add(  "fc:id,charset,newcharset -> Change the charset used")
                .add(  "fc:id,src,newsrc -> Change the src");
        join.add( "Get info" )
                .add( "fc:list  -> Get a list of all active File Collectors")
                .add( "fc:?  -> Show this message");
        return LookAndFeel.formatCmdHelp(join.toString(),html);
    }

    /**
     * Process the add or addnew command
     * @param cmds The full cmd
     * @return The result
     */
    private String addNewCommand( String[] cmds ){
        if (cmds.length < 4)
            return "! Not enough arguments given: fc:addnew,id,src,path";

        FileCollector.addBlankToXML(XMLfab.withRoot(Paths.settings(), "dcafs"), cmds[1], cmds[2], cmds[3]);
        var fc = createFileCollector(cmds[1]);
        fc.addSource(cmds[2]);
        if (Path.of(cmds[3]).isAbsolute()) {
            fc.setPath(Path.of(cmds[3]));
        } else {
            fc.setPath(Paths.storage().resolve(cmds[3]));
        }
        return "FileCollector " + cmds[1] + " created and added to xml.";
    }
    /* ********************************** G E N E R A L  C O M M A N D S ******************************************* */
    private String doGeneralCommands(Datagram d, FileCollector fco) {
        var cmds = d.argList();

        var fab = Paths.fabInSettings("collectors")
                    .selectOrAddChildAsParent("file", "id", fco.id());

        return switch (cmds[1]) {
            case "addrollover" ->  doRollOverCmd( fco,cmds,Paths.settings() );
            case "addheader" -> {
                if (cmds.length < 3)
                    yield "! Wrong amount of arguments -> fc:addheader,id,header";
                fco.flushNow();
                fco.addHeaderLine(cmds[2]);
                fab.addChild("header", cmds[2]).build();
                yield "Header line added to " + fco.id();
            }
            case "addcmd" -> doAddcmdCmd( fco, cmds, fab );
            case "addsizelimit" -> {
                if (cmds.length != 4)
                    yield "! Wrong amount of arguments -> fc:id,addsizelimit,size,zip?";
                fco.setMaxFileSize(cmds[2], Tools.parseBool(cmds[3], false));
                fab.addChild("sizelimit", cmds[2]).attr("zip", cmds[3]).build();
                yield "Size limit added to " + fco.id();
            }
            case "path" -> {
                fco.setPath(Path.of(cmds[2]), Paths.storage().toString());
                fab.alterChild("path", cmds[2]).build();
                yield "Altered the path";
            }
            case "sizelimit" -> {
                fco.setMaxFileSize(cmds[2]);
                fab.alterChild("sizelimit", cmds[2]).build();
                yield "Altered the size limit to " + cmds[2];
            }
            case "eol" -> {
                fco.setLineSeparator(Tools.fromEscapedStringToBytes(cmds[2]));
                yield alterAttribute(fab,cmds[1],cmds[2]);
            }
            case "charset" -> alterAttribute(fab,cmds[1],cmds[2]);
            case "src" -> {
                fco.addSource(cmds[2]);
                yield alterAttribute(fab,cmds[1],cmds[2]);
            }
            case "reload" -> {
                var opt = XMLfab.withRoot(Paths.settings(), "dcafs", "collectors")
                        .getChild("file", "id", cmds[1]);

                if( opt.isEmpty() )
                    yield "! Couldn't find file";

                fco.flushNow();
                fco.readFromXML(XMLdigger.goIn(opt.get()), Paths.storage().toString());
                yield "Reloaded";
            }
            case "perms" -> {
                fileCollectors.values().forEach(f -> FileTools.setAllPermissions(f.getPath().getParent()));
                yield "Tried to alter permissions";
            }
            case "reqwritable" -> {
                d.getWritable().giveObject("writable", fco.getWritable());
                yield "Writable given";
            }
            default -> "! No such subcommand for fc:id : " + cmds[1];
        };
    }
    private String alterAttribute( XMLfab fab, String attr, String value){
        fab.attr( attr,value).build();
        return "Attribute "+ attr+" altered to " + value;
    }
    private String doRollOverCmd( FileCollector fco, String[] cmds, Path settingsPath ){
        if (cmds.length < 6)
            return "! Wrong amount of arguments -> fc:id,addrollover,period,format,zip?";
        var rollCount = NumberUtils.toInt(cmds[2].replaceAll("\\D",""));
        var unit = TimeTools.parseToChronoUnit( cmds[2].replaceAll("[^a-z]","") );
        if (fco.setRollOver(cmds[4], rollCount,unit, Tools.parseBool(cmds[5], false))) {
            XMLfab.withRoot(settingsPath, "dcafs", "collectors")
                    .selectOrAddChildAsParent("file", "id", fco.id() )
                    .alterChild("rollover", cmds[4])
                        .attr("count", cmds[2]).attr("unit", cmds[3]).attr("zip", cmds[5]).build();
            return "Rollover added to " + fco.id();
        }
        return "! Failed to add rollover";
    }
    private String doAddcmdCmd( FileCollector fc, String[] cmds, XMLfab fab ){
        if (cmds.length < 3)
            return "! Wrong amount of arguments -> fc:id,adcmd,trigger:cmd";
        if (!cmds[2].contains(":"))
            return "! No valid trigger:cmd pair in: " + cmds[2];

        String[] sub = {"", ""};
        sub[0] = cmds[2].substring(0, cmds[2].indexOf(":"));
        sub[1] = cmds[2].substring(sub[0].length() + 1);

        if (fc.addTriggerCommand(sub[0], sub[1])) {
            fab.addChild("cmd", sub[1]).attr("trigger", sub[0]).build();
            return "Triggered command added to " + fc.id();
        }
        return "! Failed to add command, unknown trigger?";
    }

    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
}
