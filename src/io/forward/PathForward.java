package io.forward;

import das.Core;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.data.ValStore;
import util.data.ValTools;
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
import java.util.StringJoiner;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class PathForward implements Writable {

    private String src = ""; // The source of the data for the path

    private final ArrayList<Writable> targets = new ArrayList<>(); // The targets to send the final result of the path to
    private final ArrayList<CustomSrc> customs=new ArrayList<>(); // The custom data sources

    enum SRCTYPE {REG,PLAIN,RTVALS,CMD,FILE,SQLITE,INVALID} // Possible custom sources
    RealtimeValues rtvals; // Reference to the realtimevalues
    EventLoopGroup nettyGroup; // Threaded processing is done with this

    String id; // The id of the path
    private final ArrayList<AbstractStep> stepsForward = new ArrayList<>(); // The steps to take in the path
    Path workPath; // The path to the working folder of dcafs

    private int maxBufferSize=5000; // maximum size of read buffer (if the source is a file)
    String error=""; // Last error that occurred
    boolean valid=false; // Whether this path is valid (xml processing went ok)
    boolean selfTarget = false;
    boolean active = false;
    private String delimiter = ",";

    ThreadPoolExecutor executor = new ThreadPoolExecutor(1,
            Math.min(3, Runtime.getRuntime().availableProcessors()), // max allowed threads
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());

    public PathForward(RealtimeValues rtvals, EventLoopGroup nettyGroup) {
        this.rtvals = rtvals;
        this.nettyGroup=nettyGroup;
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
        clearStores();

        this.workPath=workpath;

        // if any future is active, stop it
        customs.forEach(CustomSrc::stop);

        if (!stepsForward.isEmpty()) {// If this is a reload, reset the steps
            stepsForward.clear();
        }

        id = dig.attr("id","");
        src = dig.attr("src", "");
        delimiter = dig.attr("delimiter", delimiter);

        var importPathOpt = dig.attr("import",null,null);
        if (importPathOpt.isPresent()) { // If present overwrite the digger
            var response = doImport(importPathOpt.get());
            if (!response.isEmpty())
                return response;
        }

        if (!dig.hasChilds()) {
            error = "No child nodes found";
            return error;
        }

        // Now process all the steps
        addSteps(dig, delimiter, null);

        customs.trimToSize();
        stepsForward.trimToSize();

        // Check if there are StoreSteps or MathStep wants data
        for (var step : stepsForward) {
            if (step.wantsData()) {
                selfTarget = true;
                break;
            }
        }
        valid=true;
        error="";

        if (src.isEmpty() && customs.isEmpty()) {
            Logger.error(id() + "(pf) -> This path doesn't have a src!");
        } else if (selfTarget) {
            reloadSrc();
        }
        return "";
    }

    /**
     * Handle the path being in a seperate file instead of settings.xml
     *
     * @param importPath The path where the xml can be found
     * @return Empty string if it went ok, error message if it didn't
     */
    private String doImport(Path importPath) {

        if (!importPath.isAbsolute()) // If the path isn't absolute
            importPath = workPath.resolve(importPath); // Make it so

        var dig = XMLdigger.goIn(importPath, "dcafs", "path");
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
        return "";
    }
    public void reloadSrc() {
        if (active)
            return;
        if (customs.isEmpty()) { // If no custom sources
            if (stepsForward.isEmpty()) {
                Logger.error(id + "(pf) -> No steps to take, this often means something went wrong processing it");
                return;
            }
            Core.addToQueue(Datagram.system("stop").writable(this));
            Core.addToQueue(Datagram.system(src).writable(this)); // Request it
            active = true;
        } else { // and there are steps
            customs.forEach(CustomSrc::start);
            active=true;
        }
    }

    private void addSteps(XMLdigger steps, String delimiter, AbstractStep parent) {
        FilterStep lastIf=null;

        for (var step : steps.digOut("*")) {

            if (step.tagName("").endsWith("src")) {
                maxBufferSize = step.attr("buffer", 2500);
                customs.add(new CustomSrc(step.currentTrusted()));
                continue;
            }
            if( !step.currentTrusted().hasAttribute("delimiter"))
                step.currentTrusted().setAttribute("delimiter",delimiter);

            switch (step.tagName("")) {
                case "if" -> {
                    var filterOpt = FilterStepFab.buildFilterStep(step, delimiter, rtvals, executor);
                    if (filterOpt.isPresent()) {
                        var filter = filterOpt.get();
                        if (parent == null) {
                            parent = filter;
                            stepsForward.add(parent);
                            addSteps(step, delimiter, parent);// Add all sub steps
                            parent=parent.getLastStep(); // Point to last sub
                        } else { // Nested?
                            parent.setNext(filter);
                            if( lastIf!=null)
                                lastIf.setFailure(filter);
                            addSteps(step, delimiter, filter);
                        }
                        lastIf=filter;
                        continue;
                    }
                }
                case "filter" -> {
                    var filterOpt = FilterStepFab.buildFilterStep(step, delimiter, rtvals, executor);
                    if (filterOpt.isEmpty())
                        return;
                    parent = addToOrMakeParent(filterOpt.get(), parent, lastIf);
                }
                case "math" -> {
                    var mfOpt = StepFab.buildMathStep(step, delimiter, rtvals);
                    if (mfOpt.isEmpty())
                        return;
                    parent = addToOrMakeParent(mfOpt.get(), parent,lastIf);
                }
                case "editor" -> {
                    var editOpt = EditorStepFab.buildEditorStep(step, delimiter, rtvals);
                    if (editOpt.isEmpty())
                        return;
                    parent = addToOrMakeParent(editOpt.get(), parent,lastIf);
                }
                case "cmd" -> {
                    var cmdOpt = StepFab.buildCmdStep(step, delimiter, rtvals);
                    if (cmdOpt.isEmpty())
                        return;
                    parent = addToOrMakeParent(cmdOpt.get(), parent,lastIf);
                }
                case "store" -> {
                    parent = addStoreStep(parent, step,lastIf);
                    if (parent == null)
                        return;
                }
                case "return" -> {
                    parent=null;
                }
                default ->{}
            };
        }
    }

    private AbstractStep addToOrMakeParent(AbstractStep step, AbstractStep parent, FilterStep lastIf) {
        if( lastIf!=null){
            lastIf.setFailure(step);
            lastIf=null;
        }
        if (parent != null) {
            parent.setNext(step);
        } else {
            stepsForward.add(step);
        }
        return step;
    }

    private AbstractStep addStoreStep(AbstractStep parent, XMLdigger step, FilterStep lastIf) {

        var store = ValStore.build(step.currentTrusted(), id, rtvals).orElse(null);
        if (store == null)
            return null;
        var storeStep = new StoreStep(store);
        return addToOrMakeParent(storeStep, parent,lastIf);
    }

    public void clearStores() {
        stepsForward.forEach(step -> {
            var store = step.getStore();
            if (store != null)
                store.removeRealtimeValues(rtvals);
        });
    }

    public boolean isValid(){
        return valid;
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

        join.add("=> gives the data from " + stepsForward.get(stepsForward.size() - 1));
        return join.toString();
    }
    public ArrayList<Writable> getTargets(){
        return targets;
    }

    @Override
    public boolean writeLine(String origin, String data) {
        for (var step : stepsForward)
            executor.submit(() -> step.takeStep(data, null));
        return true;
    }

    @Override
    public boolean writeString(String data) {
        for (var target : targets)
            executor.submit(() -> target.writeLine(id, data));
        return true;
    }

    public String id() {
        return id;
    }

    public void addTarget(Writable wr) {
        if (!targets.contains(wr)) {
            targets.add(wr);
            if (!stepsForward.isEmpty())
                stepsForward.get(stepsForward.size() - 1).getFeedbackFromLastStep(this);
            reloadSrc();
        }
    }

    public void addTarget(Writable wr, String stepId) {
        Logger.info("TODO -> Add " + wr.id() + " to " + stepId);
    }

    public void removeTarget(Writable wr) {
        targets.remove(wr);
        if (targets.isEmpty() && !selfTarget)
            stop();
    }
    public void stop() {
        if (!customs.isEmpty())
            customs.forEach(CustomSrc::stop);
        Core.addToQueue(Datagram.system("stop").writable(this));
        active=false;
    }

    @Override
    public boolean isConnectionValid() {
        return true;
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
            if (targets.isEmpty() && !selfTarget)
                stop();

            var line = switch (srcType) {
                case CMD ->{
                        targets.forEach(t -> Core.addToQueue(Datagram.system(pathOrData).writable(t).toggleSilent()));
                    yield "";
                }
                case RTVALS -> ValTools.parseRTline(pathOrData, "-999", rtvals);
                case PLAIN -> pathOrData;
                case SQLITE -> writeFromSQLite();
                case FILE -> {
                    writeFromFile();
                    yield "";
                }
                default -> "";
            };
            if (!line.isEmpty()) {
                if (stepsForward.isEmpty()) {
                    targets.forEach(x -> x.writeLine(id, line));
                } else {
                    if (selfTarget || !targets.isEmpty())
                        writeLine("custom", line);
                }
            }
        }

        private String writeFromSQLite() {
            if (!buffer.isEmpty()) {
                return buffer.remove(0);
            }
            if (readOnce) {
                stop();
                return "";
            }
            var lite = SQLiteDB.createDB("custom", Path.of(path));
            var dataOpt = lite.doSelect(pathOrData);
            lite.disconnect(); //disconnect the database after retrieving the data

            if (dataOpt.isEmpty()) {
                Logger.error("Tried to read from db but failed: " + path);
                return "";
            }
            readOnce = true;
            for (var record : dataOpt.get()) {
                buffer.add(record.stream().map(Object::toString).collect(Collectors.joining(";")));
            }
            return "";
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
                    if (selfTarget)
                        writeLine("id",line);
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
