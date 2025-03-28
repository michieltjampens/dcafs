package io.forward;

import das.Core;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.data.ValStore;
import util.data.ValTools;
import util.database.QueryWriting;
import util.database.SQLiteDB;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import util.xml.XMLtools;
import worker.Datagram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class PathForward {

    private String src = ""; // The source of the data for the path

    private final ArrayList<Writable> targets = new ArrayList<>(); // The targets to send the final result of the path to
    private final ArrayList<CustomSrc> customs=new ArrayList<>(); // The custom data sources
    private final HashMap<String,String> defines = new HashMap<>();

    enum SRCTYPE {REG,PLAIN,RTVALS,CMD,FILE,SQLITE,INVALID} // Possible custom sources
    RealtimeValues rtvals; // Reference to the realtimevalues
    EventLoopGroup nettyGroup; // Threaded processing is done with this

    String id; // The id of the path
    private final ArrayList<AbstractForward> stepsForward = new ArrayList<>(); // The steps to take in the path
    Path workPath; // The path to the working folder of dcafs

    private int maxBufferSize=5000; // maximum size of read buffer (if the source is a file)
    String error=""; // Last error that occurred
    boolean valid=false; // Whether this path is valid (xml processing went ok)
    QueryWriting db;

    public PathForward(RealtimeValues rtvals, EventLoopGroup nettyGroup, QueryWriting db){
        this.rtvals = rtvals;
        this.nettyGroup=nettyGroup;
        this.db=db;
    }
    public String id(){
        return id;
    }
    public PathForward src( String src){
        this.src=src;
        return this;
    }

    public String src(){
        return src;
    }

    public String readFromXML(XMLdigger dig, Path workpath) {

        // Reset things
        var oldTargets = new ArrayList<>(targets);
        targets.clear();
        clearStores();

        this.workPath=workpath;

        // if any future is active, stop it
        customs.forEach(CustomSrc::stop);

        if (!stepsForward.isEmpty()) {// If this is a reload, reset the steps
            stepsForward.forEach( step -> Core.addToQueue(Datagram.system("nothing").writable(step))); // stop asking for data
            stepsForward.forEach( AbstractForward::invalidate ); // Make sure these get removed as targets
            lastStep().ifPresent(ls -> oldTargets.addAll(ls.getTargets())); // retain old targets
            stepsForward.clear();
        }

        id = dig.attr("id","");
        src = dig.attr("src", "");
        String delimiter = dig.attr("delimiter","");

        var importPathOpt = dig.attr("import",null,null);
        if (importPathOpt.isPresent()) { // If present overwrite the digger
            var importPath = importPathOpt.get();
            if( !importPath.isAbsolute()) // If the path isn't absolute
                importPath = workPath.resolve(importPath); // Make it so

            dig = XMLdigger.goIn(importPath,"dcafs","path");
            if (!dig.isValid()) {
                Logger.error(id + "(pf) -> No valid path script found: " + importPath);
                error = "No valid path script found: " + importPath;
                String xmlError = XMLtools.checkXML(importPath);
                if (!xmlError.isEmpty())
                    Core.addToQueue(Datagram.system("telnet:error,PathForward: " + error));
                return error;
            }
            // Using a new digger, so check id and delimiter that might not have been set
            id = id.isEmpty() ? dig.attr("id", "") : id; // Earlier id has priority
            delimiter = dig.attr("delimiter", delimiter); // This delimiter has priority
            Logger.info(id + "(pf) -> Valid path script found at " + importPath);
        }
        var steps = dig.peekOut("*");
        if (steps.isEmpty()) {
            error = "No child nodes found";
            return error;
        }

        // If the step doesn't have a source and it's the first step
        if (stepsForward.isEmpty() && XMLtools.getStringAttribute(steps.get(0), "src", "").isEmpty())
            steps.get(0).setAttribute("src", src);

        // Now process all the steps
        var validData = addSteps(steps, delimiter,null);
        // Restore old requests
        if (!oldTargets.isEmpty() && !stepsForward.isEmpty())
            oldTargets.forEach(this::addTarget);

        if (!lastStep().map(AbstractForward::noTargets).orElse(false) || validData)
            reloadSrc();

        customs.trimToSize();
        stepsForward.trimToSize();

        stepsForward.forEach(AbstractForward::requestSource);
        valid=true;
        error="";

        if (src.isEmpty() && customs.isEmpty()) {
            Logger.error(id() + "(pf) -> This path doesn't have a src!");
        }
        return "";
    }

    public void reloadSrc() {
        if (customs.isEmpty()) { // If no custom sources
            if (stepsForward.isEmpty()) {
                Logger.error(id + "(pf) -> No steps to take, this often means something went wrong processing it");
                return;
            }
            Core.addToQueue(Datagram.system("stop").writable(stepsForward.get(0)));
            Core.addToQueue(Datagram.system(src).writable(stepsForward.get(0))); // Request it
        } else if (!stepsForward.isEmpty()) { // and there are steps
            targets.add(stepsForward.get(0));
            customs.forEach(CustomSrc::start);
        }
    }

    private boolean addSteps( ArrayList<Element> steps, String delimiter, AbstractForward parent ){
        boolean reqData=false;

        String prevTag = "";
        for( Element step : steps ){
            var dig = XMLdigger.goIn(step);
            if(step.getTagName().endsWith("src")){
                maxBufferSize = dig.attr("buffer",2500);
                customs.add(new CustomSrc(step));
                continue;
            }
            // If this step doesn't have a delimiter, alter it
            if( !step.hasAttribute("delimiter") && !delimiter.isEmpty())
                step.setAttribute("delimiter",delimiter);
            // If this step doesn't have an id, alter it
            if( !step.hasAttribute("id")) {
                var altId = parent == null ? id + "_" + stepsForward.size() : parent.firstParentId() + "_" + parent.siblings();
                step.setAttribute("id", altId);
            }
            parent = switch( step.getTagName() ){
                case "case", "if" -> {
                    FilterForward ff = new FilterForward(step);
                    // Now link to the source
                    checkParent(parent, ff, prevTag);
                    // Go deeper
                    reqData |= addSteps(new ArrayList<>(dig.currentSubs()), delimiter, ff);
                    yield step.getTagName().equals("case") ? parent : ff; // This doesn't alter the parent
                }
                case "filter" -> checkParent( parent,new FilterForward(step), prevTag );
                case "math" ->   checkParent( parent,new MathForward(step, rtvals,defines), prevTag );
                case "editor" -> checkParent( parent,new EditorForward(step, rtvals), prevTag );
                case "cmd" -> checkParent( parent,new CmdForward(step, rtvals), prevTag );
                case "defines" -> {
                    dig.currentSubs().forEach(ele -> defines.put(ele.getTagName(), ele.getTextContent()));
                    yield parent; // This doesn't alter the parent
                }
                case "store" -> addStoreStep(parent, step);
                default -> parent;
            };
            prevTag=step.getTagName();
        }
        return reqData;
    }

    private AbstractForward addStoreStep(AbstractForward parent, Element step) {
        if (parent == null) {// No parent node, so it's the only node in the path...?
            Logger.warn("Still to implement a path that only contains a store, put the store in the stream instead");
            return null;
        }

        var store = ValStore.build(step, id, rtvals).orElse(null);
        if (store == null)
            return parent;

        parent.setStore(store);
        for (var db : store.dbInsertSets())
            Core.addToQueue(Datagram.system("dbm:" + db[0] + ",tableinsert," + db[1]).payload(parent));

        return parent; // This doesn't alter the parent
    }
    private AbstractForward checkParent( AbstractForward parent, AbstractForward child, String prevTag){
        if (parent==null){ // No parent so root of the path, so get the source of the path and it's a step
            stepsForward.add(child);
            child.addSource(src); // It's in the root, so add the path source
        }else{ // Not the root, so add it to the parent
            if( prevTag.equalsIgnoreCase("if")){
                var ff = (FilterForward)parent;
                ff.addReverseTarget(child);
            }else {
                parent.addNextStep(child);
            }
        }
        return child;
    }
    public void clearStores(){
        stepsForward.forEach(x -> x.removeStoreVals(rtvals));
    }

    public boolean isValid(){
        return valid;
    }

    public String debugStep( String step, Writable wr ){
        var join = new StringJoiner(", ", "Request for ", " received");
        join.setEmptyValue("No such step");
        var ok = false;
        for( var sf : stepsForward ) {
            sf.removeTarget(wr);
            if( step.equals("*") || sf.id.equalsIgnoreCase(step)) {
                sf.addTarget(wr);
                join.add(sf.id());
                ok=true;
            }
        }
        if(ok) {
            if (!targets.contains(stepsForward.get(0))) // Check if the first step is a target, if not
                targets.add(stepsForward.get(0)); // add it
            enableSource();
            if( step.equals("*"))
                Core.addToQueue( Datagram.system(src).writable(wr));
        }
        return join.toString();
    }
    public String debugStep( int step, Writable wr ){
        if( wr==null )
            return "! No proper writable received";
        if( step >= stepsForward.size() )
            return "! Wanted step " + step + " but only " + stepsForward.size() + " available";

        for( var ab : stepsForward )
            ab.removeTarget(wr);

        if( !customs.isEmpty() ){
            if( step == -1){
                targets.add(wr);
                customs.forEach(CustomSrc::start);
            }else if( step < stepsForward.size()){
                stepsForward.get(step).addTarget(wr);
            }
        }else if(step !=-1 && step < stepsForward.size() ){
            stepsForward.get(step).addTarget(wr);
        }else{
            return "! Failed to request data, bad index given";
        }
        var s = stepsForward.get(step);

        return "Request for "+s.getXmlChildTag()+":"+s.id+" received";
    }
    public Optional<AbstractForward> lastStep(){
        if (stepsForward.isEmpty())
            return Optional.empty();
        var step = stepsForward.get(stepsForward.size()-1);
        return Optional.ofNullable(step.getLastStep());
    }

    public ArrayList<Writable> lastTargets() {
        return lastStep().map(af -> af.targets).orElse(new ArrayList<>());
    }
    public String toString(){
        if (customs.isEmpty() && stepsForward.isEmpty())
            return "! Nothing in the path yet";

        var join = new StringJoiner("\r\n");
        customs.forEach(c->join.add(c.toString()));

        if (stepsForward.isEmpty())
            return join.toString();

        for (var af : stepsForward)
            join.add("|-> " + af.toString()).add("");

        join.add("=> gives the data from " + stepsForward.get(stepsForward.size() - 1).id());
        return join.toString();
    }
    public ArrayList<Writable> getTargets(){
        return targets;
    }
    public void addTarget(Writable wr){
        if (!stepsForward.isEmpty()) {
            var target = stepsForward.get(stepsForward.size() - 1).getLastStep();
            target.addTarget(wr);
        } else {
            if (targets.isEmpty()) {
                customs.forEach(CustomSrc::start);
            }
            targets.add(wr);
        }
    }
    public void addTarget(Writable wr, String id){
        for( var step : stepsForward){
            var fw = step.getStepById(id);
            if( fw!=null) {
                fw.addTarget(wr);
                return;
            }
        }
        Logger.warn("No matching step found for "+id);
    }
    private void enableSource(){
        if( targets.size()==1 ){
            if( customs.isEmpty()){
                Core.addToQueue( Datagram.system(src).writable(stepsForward.get(0)));
            }else{
                customs.forEach(CustomSrc::start);
            }
        }
    }

    /**
     * Remove a writable as a target from any part of the path
     * @param wr The writable to remove
     */
    public void removeTarget( Writable wr){
        if (stepsForward.isEmpty()) {
            targets.remove(wr);// Stop giving data
        }else{
            for( var step : stepsForward )
                step.removeTarget(wr);

            lastStep().ifPresent( ls -> ls.removeTarget(wr));
        }
    }

    public void stop(){
        lastStep().ifPresent(AbstractForward::removeTargets);
        targets.clear();
        customs.forEach(CustomSrc::stop);
        customs.clear();
    }

    private class CustomSrc{
        String pathOrData;
        String path;
        SRCTYPE srcType;
        long intervalMillis;
        long delayMillis=0;
        ScheduledFuture<?> future;
        ArrayList<String> buffer;
        ArrayList<Path> files;
        long lineCount=1;
        long sendLines=0;
        int multiLine=1;
        String label="";
        boolean readOnce=false;
        static long skipLines = 0; // How many lines to skip at the beginning of a file (fe to skip header)

        public CustomSrc( Element node){
            readFromElement(node);
        }

        public void readFromElement( Element sub ){

            var dig = XMLdigger.goIn(sub);
            var data = sub.getTextContent();

            intervalMillis = TimeTools.parsePeriodStringToMillis( dig.attr("interval","1s") );
            delayMillis = TimeTools.parsePeriodStringToMillis( dig.attr("delay","10ms") );

            switch (sub.getTagName().replace("src","")) {
                case "rtvals" -> {
                    srcType =SRCTYPE.RTVALS;
                    pathOrData = data;
                }
                case "cmd" -> {
                    srcType = SRCTYPE.CMD;
                    pathOrData = data;
                }
                case "plain" -> {
                    srcType = SRCTYPE.PLAIN;
                    pathOrData = data;
                }
                case "file","files" ->  {
                    srcType = SRCTYPE.FILE;
                    skipLines = dig.attr("skip",0);
                    lineCount=skipLines+1; // We'll skip these lines
                    files = new ArrayList<>();
                    var p = Path.of(data);
                    if (!p.isAbsolute()) {
                        p = workPath.resolve(data);
                    }
                    if (Files.isDirectory(p)) {
                        try {
                            try( var str = Files.list(p) ){
                                str.forEach(files::add);
                            }
                        } catch (IOException e) {
                            Logger.error(e);
                        }
                    } else {
                        files.add(p);
                    }
                    buffer = new ArrayList<>();
                    multiLine = dig.attr("multiline",1);
                }
                case "sqlite" -> {
                    path = data;
                    buffer = new ArrayList<>();
                    srcType = SRCTYPE.SQLITE;
                }
                default -> {
                    srcType = SRCTYPE.INVALID;
                    Logger.error(id + "(pf) -> no valid srctype '" + sub.getTagName() + "'");
                }
            }
        }
        public void start(){
            if( future==null || future.isDone())
                future = nettyGroup.scheduleAtFixedRate(this::write,delayMillis,intervalMillis, TimeUnit.MILLISECONDS);
        }
        public void stop(){
            if( future!=null && !future.isCancelled())
                future.cancel(true);
        }
        public void write(){
            targets.removeIf( x -> !x.isConnectionValid());
            if( targets.isEmpty() )
                stop();

            switch (srcType) {
                case CMD ->
                        targets.forEach(t -> Core.addToQueue(Datagram.system(pathOrData).writable(t).toggleSilent()));
                case RTVALS -> {
                    var write = ValTools.parseRTline(pathOrData, "-999", rtvals);
                    targets.forEach(x -> x.writeLine(id, write));
                }
                case PLAIN -> targets.forEach(x -> x.writeLine(id, pathOrData));
                case SQLITE -> writeFromSQLite();
                case FILE -> writeFromFile();
            }
        }

        private void writeFromSQLite() {
            if (!buffer.isEmpty()) {
                String line = buffer.remove(0);
                targets.forEach(wr -> wr.writeLine(id, line));
                return;
            }
            if (readOnce) {
                stop();
                return;
            }
            var lite = SQLiteDB.createDB("custom", Path.of(path));
            var dataOpt = lite.doSelect(pathOrData);
            lite.disconnect(); //disconnect the database after retrieving the data

            if (dataOpt.isEmpty()) {
                Logger.error("Tried to read from db but failed: " + path);
                return;
            }
            readOnce = true;
            for (var record : dataOpt.get()) {
                buffer.add(record.stream().map(Object::toString).collect(Collectors.joining(";")));
            }
        }

        private void writeFromFile() {
            try {
                for (int a = 0; a < multiLine; a++) {
                    if (buffer.isEmpty()) {
                        // If the list of files is empty, stop
                        if (files.isEmpty()) {
                            future.cancel(true);
                            Core.addToQueue(Datagram.system("telnet:broadcast,info," + id + " finished at " + Instant.now()));
                            return;
                        }
                        var currentFile = files.get(0);
                        buffer.addAll(FileTools.readLines(currentFile, lineCount, maxBufferSize));
                        lineCount += buffer.size();
                        if (lineCount / 1000 == 0)
                            Logger.info("Read " + lineCount + " lines");

                        if (buffer.size() < maxBufferSize) { // Buffer wasn't full, so file read till end
                            Core.addToQueue(Datagram.system("telnet:broadcast,info," + id + " processed " + currentFile + " at " + Instant.now()));
                            Logger.info("Finished processing " + currentFile);
                            files.remove(0);
                            lineCount = 1 + skipLines; // First line is at 1, so add any that need to be skipped

                            if (buffer.isEmpty()) {
                                if (files.isEmpty()) {
                                    future.cancel(true);
                                    Core.addToQueue(Datagram.system("telnet:broadcast,info," + id + " finished at " + Instant.now()));
                                    return;
                                }

                                Logger.info("Started processing " + files.get(0) + " at " + Instant.now());
                                // Buffer isn't full, so fill it up
                                buffer.addAll(FileTools.readLines(files.get(0), lineCount, maxBufferSize - buffer.size()));
                                lineCount += buffer.size();
                            }
                        }
                    }
                    String line = buffer.remove(0);
                    targets.forEach(wr -> wr.writeLine(id, line));
                    if (!label.isEmpty())
                        Core.addToQueue(Datagram.build(line).label(label));

                    sendLines++;
                }
            } catch (Exception e) {
                Logger.error(e);
            }
        }
        public String toString(){
            String shortData="";
            if( pathOrData!=null)
                shortData = pathOrData.substring(0,pathOrData.length()>20?20:pathOrData.length()-1);

            var interval = TimeTools.convertPeriodToString(intervalMillis, TimeUnit.MILLISECONDS);
            return switch( srcType ){
                case REG,PLAIN,RTVALS -> "Shows "+shortData+" every "+interval;
                case CMD -> "Show result of '"+shortData+"' every "+interval;
                case FILE -> "Reads from "+files.size()+" files, every "+interval;
                case SQLITE -> "Shows the result of a query";
                case INVALID -> "Invalid src";
            };
        }
    }
}
