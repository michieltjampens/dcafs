package io.forward;

import org.apache.commons.lang3.math.NumberUtils;
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
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PathForward {

    private String src = "";

    private final ArrayList<Writable> targets = new ArrayList<>();
    private final ArrayList<CustomSrc> customs=new ArrayList<>();

    RealtimeValues rtvals;
    BlockingQueue<Datagram> dQueue;
    EventLoopGroup nettyGroup;

    String id;
    ArrayList<AbstractForward> stepsForward;
    enum SRCTYPE {REG,PLAIN,RTVALS,CMD,FILE,SQLITE,INVALID}
    Path workPath;
    static int READ_BUFFER_SIZE=2500;
    static long SKIPLINES = 0;
    String error="";
    boolean valid=false;

    public PathForward(RealtimeValues rtvals, BlockingQueue<Datagram> dQueue, EventLoopGroup nettyGroup ){
        this.rtvals = rtvals;
        this.dQueue=dQueue;
        this.nettyGroup=nettyGroup;
    }
    public String id(){
        return id;
    }
    public PathForward src( String src){
        this.src=src;
        return this;
    }
    public void setSrc( String src ){
        this.src=src;
    }
    public String src(){
        return src;
    }
    public String readFromXML( Element pathEle, Path workpath ){

        // Reset things
        var oldTargets = new ArrayList<>(targets);
        targets.clear();
        clearStores();

        this.workPath=workpath;

        // if any future is active, stop it
        customs.forEach(CustomSrc::stop);

        if( stepsForward!=null && !stepsForward.isEmpty()) {// If this is a reload, reset the steps
            dQueue.add(Datagram.system("nothing").writable(stepsForward.get(0))); // stop asking for data
            lastStep().ifPresent(ls -> oldTargets.addAll(ls.getTargets())); // retain old targets
            stepsForward.clear();
        }

        id = XMLtools.getStringAttribute(pathEle,"id","");
        String delimiter = XMLtools.getStringAttribute(pathEle,"delimiter","");
        this.src = XMLtools.getStringAttribute(pathEle,"src","");

        var importPathOpt = XMLtools.getPathAttribute(pathEle,"import",null);
        if( importPathOpt.isPresent() ) {
            var importPath = importPathOpt.get();
            if( !importPath.isAbsolute()) // If the path isn't absolute
                importPath = workPath.resolve(importPath); // Make it so
            var p = XMLfab.getRootChildren(importPath,"dcafs","path");
            if(!p.isEmpty()) {
                pathEle = p.get(0);
                if( id.isEmpty())
                    id = XMLtools.getStringAttribute(pathEle,"id",id);
                delimiter = XMLtools.getStringAttribute(pathEle,"delimiter",delimiter);
                Logger.info("Valid path script found at "+importPath);

                // Check for rtvals
                var l = XMLfab.getRootChildren(importPath,"dcafs","rtvals");
                if( !l.isEmpty())
                    rtvals.readFromXML(l.get(0));
            }else{
                Logger.error("No valid path script found: "+importPath);
                error="No valid path script found: "+importPath;
                String error = XMLtools.checkXML(importPath);
                if( !error.isEmpty())
                    dQueue.add(Datagram.system("telnet:error,PathForward: "+error));
                return error;
            }
        }

        var steps = XMLtools.getChildElements(pathEle);

        if( steps.size() > 0) {
            stepsForward = new ArrayList<>();
        }else{
            error = "No child nodes found";
            return error;
        }

        FilterForward lastff=null;
        boolean hasLabel=false;
        int vals=1;
        for( int a=0;a<steps.size();a++  ){
            Element step = steps.get(a);
            ValStore store=null;
            if(step.getTagName().equalsIgnoreCase("customsrc")){
                addCustomSrc( step.getTextContent(),
                              XMLtools.getStringAttribute(step,"interval","1s"),
                              XMLtools.getStringAttribute(step,"type","plain"),
                              XMLtools.getStringAttribute(step,"label",""));
                continue;
            }

            // Check if the next step is a generic/store, if so change the label attribute of the current step
            if( a<steps.size()-1 ){
                var next = steps.get(a+1);
                if(next.getTagName().equalsIgnoreCase("generic")
                        ||next.getTagName().equalsIgnoreCase("store")){// Next element is a generic
                    var storeOpt = ValStore.build(next,id);
                    if( storeOpt.isPresent()) {
                        store = storeOpt.get();
                        store.shareRealtimeValues(rtvals);
                    }
                }
                if(next.getTagName().equalsIgnoreCase("valmap")){
                    if( !step.hasAttribute("label")){
                        var valid = next.getAttribute("id");
                        valid = valid.isEmpty()?id+"_vm"+vals:valid;
                        vals++;
                        step.setAttribute("label","valmap:"+valid);
                    }
                }
            }
            if( step.hasAttribute("label"))
                hasLabel=true;

            boolean lastGenMap = false; // Used to determine end of 'filter' and start of '!filter'
            if( !stepsForward.isEmpty() ) {
                var prev = steps.get(a - 1);
                lastGenMap = prev.getTagName().equalsIgnoreCase("generic")
                                ||prev.getTagName().equalsIgnoreCase("store")
                                ||prev.getTagName().equalsIgnoreCase("valmap");
            }

            // If this step doesn't have a delimiter, alter it
            if( !step.hasAttribute("delimiter")&& !delimiter.isEmpty())
                step.setAttribute("delimiter",delimiter);

            if( !step.hasAttribute("id"))
                step.setAttribute("id",id+"_"+stepsForward.size());

            var src = XMLtools.getStringAttribute(step,"src","");
            if( stepsForward.isEmpty() && src.isEmpty())
                step.setAttribute("src",this.src);

            switch (step.getTagName()) {
                case "filter" -> {
                    FilterForward ff = new FilterForward(step, dQueue);
                    if (!src.isEmpty()) {
                        addAsTarget(ff, src);
                    } else if (lastff != null && (!(lastStep().isPresent()&&lastStep().get() instanceof FilterForward) || lastGenMap)) {
                        lastff.addReverseTarget(ff);
                    }
                    if( store!=null)
                        ff.setStore(store);
                    lastff = ff;
                    stepsForward.add(ff);
                }
                case "math" -> {
                    MathForward mf = new MathForward(step, dQueue, rtvals);
                    mf.removeSources();
                    if( !mf.hasSrc() ) {
                        if (lastff != null && lastGenMap)
                            lastff.addReverseTarget(mf);
                        addAsTarget(mf, src, !(lastff != null && lastGenMap));
                    }else{
                        addAsTarget(mf, mf.getSrc(),!(lastff!=null && lastGenMap));
                    }
                    if( store!=null)
                        mf.setStore(store);
                    stepsForward.add(mf);
                }
                case "editor" -> {
                    var ef = new EditorForward(step, dQueue, rtvals);
                    if( !ef.readOk) {
                        error="Failed to read EditorForward";
                        return error;
                    }
                    if( !ef.hasSrc() ) {
                        if (lastff != null && lastGenMap) {
                            lastff.addReverseTarget(ef);
                        }
                        ef.removeSources();
                        addAsTarget(ef, src,!(lastff!=null && lastGenMap));
                    }else{
                        addAsTarget(ef, ef.getSrc(),!(lastff!=null && lastGenMap));
                    }
                    if( store!=null)
                        ef.setStore(store);
                    stepsForward.add(ef);
                }
            }

        }
        if( !oldTargets.isEmpty()&&!stepsForward.isEmpty()){ // Restore old requests
            oldTargets.forEach(this::addTarget);
        }
        if( !lastStep().map(AbstractForward::noTargets).orElse(false) || hasLabel) {
            if (customs.isEmpty() ) { // If no custom sources
                if(stepsForward.isEmpty()) {
                    Logger.error(id+" -> No steps to take, this often means something went wrong processing it");
                }else{
                    dQueue.add(Datagram.system(this.src).writable(stepsForward.get(0)));
                }
            } else {// If custom sources
                if( !stepsForward.isEmpty()) // and there are steps
                    targets.add(stepsForward.get(0));
                customs.forEach(CustomSrc::start);
            }
        }
        customs.trimToSize();
        valid=true;
        error="";
        return "";
    }
    public void clearStores(){
        if( stepsForward!=null)
            stepsForward.forEach( x -> x.clearStore(rtvals));
    }
    public boolean isValid(){
        return valid;
    }
    public String getLastError(){
        return error;
    }
    private void addAsTarget( AbstractForward f, String src ){
        addAsTarget(f,src,true);
    }
    private void addAsTarget( AbstractForward f, String src, boolean notReversed ){
        if( !src.isEmpty() ){
            var s = getStep(src);
            if( s!= null ) {
                if( s instanceof FilterForward && src.startsWith("!")){
                    ((FilterForward) s).addReverseTarget(f);
                }else {
                    s.addTarget(f);
                }
            }
        }else if( !stepsForward.isEmpty() && notReversed) {
            if( lastStep().isPresent() ){
                var ls = lastStep().get();
                ls.addTarget(f);
                f.addSource(ls.id());
            }
        }
    }
    public String debugStep( String step, Writable wr ){
        var join = new StringJoiner(", ", "Request for ", " received");
        join.setEmptyValue("No such step");
        boolean ok=false;
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
        }
        return join.toString();
    }
    public String debugStep( int step, Writable wr ){
        if( wr==null )
            return "No proper writable received";
        if( step >= stepsForward.size() )
            return "Wanted step "+step+" but only "+stepsForward.size()+" available";

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
            return "Failed to request data, bad index given";
        }
        var s = stepsForward.get(step);

        return "Request for "+s.getXmlChildTag()+":"+s.id+" received";
    }
    public Optional<AbstractForward> lastStep(){
        if( stepsForward == null ||stepsForward.isEmpty())
            return Optional.empty();
        return Optional.ofNullable(stepsForward.get(stepsForward.size()-1));
    }
    private AbstractForward getStep(String id){
        for( var step : stepsForward){
            if( id.endsWith(step.id)) // so that the ! (for reversed) is ignored
                return step;
        }
        return null;
    }
    public void addCustomSrc( String data, String interval, String type, String label){
        if( data.contains("{") && data.contains("}")) {
            type ="rtvals";
        }else if(type.equalsIgnoreCase("rtvals")){
            type="plain";
        }
        customs.add( new CustomSrc(data,type,TimeTools.parsePeriodStringToMillis(interval),label) );
    }

    public String toString(){
        var join = new StringJoiner("\r\n");
        if( customs.isEmpty() ){
            if( stepsForward==null||stepsForward.isEmpty())
                return "Nothing in the path yet";
        }

        customs.forEach(c->join.add(c.toString()));
        if(stepsForward!=null) {
            for (AbstractForward abstractForward : stepsForward) {
                join.add("|-> " + abstractForward.toString()).add("");
            }
            if( !stepsForward.isEmpty() )
                join.add( "=> gives the data from "+stepsForward.get(stepsForward.size()-1).id() );
        }
        return join.toString();
    }
    public ArrayList<Writable> getTargets(){
        return targets;
    }
    public void addTarget(Writable wr){
        if( stepsForward == null )
            return;

        if( stepsForward.isEmpty() ){ // If no steps are present
            if (!targets.contains(wr))
                targets.add(wr);
        }else{
            // Go through the steps and make the connections?
            for( int a=stepsForward.size()-1;a>0;a--){
                var step = stepsForward.get(a);
                for( var sib : stepsForward ){
                    if( sib.id().equalsIgnoreCase(step.getSrc())) {
                        sib.addTarget(step);
                        break;
                    }
                }
            }
            // The path can receive the data but this isn't given to first step unless there's a request for the data
            if (!targets.contains(stepsForward.get(0))) // Check if the first step is a target, if not
                targets.add(stepsForward.get(0)); // add it
            stepsForward.get(stepsForward.size()-1).addTarget(wr); // Asking the path data is actually asking the last step
        }
        enableSource();
    }
    private void enableSource(){
        if( targets.size()==1 ){
            if( customs.isEmpty()){
                dQueue.add( Datagram.system(src).writable(stepsForward.get(0)));
            }else{
                customs.forEach(CustomSrc::start);
            }
        }
    }

    public void removeTarget( Writable wr){
        if( stepsForward==null||stepsForward.isEmpty() ) {
            targets.remove(wr);// Stop giving data
        }else{
            for( var step : stepsForward )
                step.removeTarget(wr);

            lastStep().ifPresent( ls -> ls.removeTarget(wr));

            if( lastStep().isEmpty() )
                return;

            disableSource();
        }
    }
    private void disableSource(){
        if( lastStep().map(AbstractForward::noTargets).orElse(true) ) { // if the final step has no more targets, stop the first step
            if (customs.isEmpty()) {
                if (src.startsWith("raw:") || src.startsWith("path:")) {
                    dQueue.add(Datagram.system(src.replace(":", ":!")).writable(stepsForward.get(0)));
                }
            } else {
                customs.forEach(CustomSrc::stop);
            }
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

        ScheduledFuture<?> future;
        ArrayList<String> buffer;
        ArrayList<Path> files;

        int lineCount=1;
        long sendLines=0;
        int multiLine=1;

        String label;

        boolean readOnce=false;

        public CustomSrc( String data, String type, long intervalMillis, String label){
            pathOrData = data;
            this.label=label;
            this.intervalMillis=intervalMillis;
            var spl = type.split(":");
            srcType = switch (spl[0]) {
                case "rtvals" -> SRCTYPE.RTVALS;
                case "cmd" -> SRCTYPE.CMD;
                case "plain" -> SRCTYPE.PLAIN;
                case "file" ->  SRCTYPE.FILE;
                case "sqlite" -> SRCTYPE.SQLITE;
                default -> SRCTYPE.INVALID;
            };
            if( srcType==SRCTYPE.FILE){
                files = new ArrayList<>();
                var p = Path.of(pathOrData);
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
                if (spl.length == 2)
                    multiLine = NumberUtils.toInt(spl[1]);
            }else if( srcType == SRCTYPE.INVALID ){
                Logger.error(id + "(pf) -> no valid srctype '" + type + "'");
            }else if( srcType==SRCTYPE.SQLITE){
                path = spl[1];
                buffer = new ArrayList<>();
            }
        }
        public void start(){
            if( future==null || future.isDone())
                future = nettyGroup.scheduleAtFixedRate(this::write,intervalMillis,intervalMillis, TimeUnit.MILLISECONDS);
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
                        targets.forEach(t -> dQueue.add(Datagram.build(pathOrData).label("system").writable(t).toggleSilent()));
                case RTVALS -> {
                    var write = ValTools.parseRTline(pathOrData, "-999", rtvals);
                    targets.forEach(x -> x.writeLine(write));
                }
                case PLAIN -> targets.forEach(x -> x.writeLine(pathOrData));
                case SQLITE -> {
                    if (buffer.isEmpty()) {
                        if (readOnce) {
                            stop();
                            return;
                        }
                        var lite = SQLiteDB.createDB("custom", Path.of(path));
                        var dataOpt = lite.doSelect(pathOrData);
                        lite.disconnect(); //disconnect the database after retrieving the data
                        if (dataOpt.isPresent()) {
                            var data = dataOpt.get();
                            readOnce = true;
                            for (var d : data) {
                                StringJoiner join = new StringJoiner(";");
                                d.stream().map(Object::toString).forEach(join::add);
                                buffer.add(join.toString());
                            }
                        }
                    } else {
                        String line = buffer.remove(0);
                        targets.forEach(wr -> wr.writeLine(line));
                    }
                }
                case FILE -> {
                    try {
                        for (int a = 0; a < multiLine; a++) {
                            if (buffer.isEmpty()) {
                                if (files.isEmpty()) {
                                    future.cancel(true);
                                    dQueue.add(Datagram.system("telnet:broadcast,info," + id + " finished."));
                                    return;
                                }
                                if (SKIPLINES == 0) {
                                    buffer.addAll(FileTools.readLines(files.get(0), lineCount, READ_BUFFER_SIZE));
                                } else {
                                    if (!readOnce)
                                        buffer.addAll(FileTools.readSubsetLines(files.get(0), 10, SKIPLINES));
                                    readOnce = true;
                                }
                                lineCount += buffer.size();
                                if (buffer.size() < READ_BUFFER_SIZE) {
                                    dQueue.add(Datagram.system("telnet:broadcast,info," + id + " processed " + files.get(0)));
                                    Logger.info("Finished processing " + files.get(0));
                                    files.remove(0);
                                    lineCount = 1;
                                    if (buffer.isEmpty()) {
                                        if (!files.isEmpty()) {
                                            Logger.info("Started processing " + files.get(0));
                                            buffer.addAll(FileTools.readLines(files.get(0), lineCount, READ_BUFFER_SIZE));
                                            lineCount += buffer.size();
                                        } else {
                                            future.cancel(true);
                                            dQueue.add(Datagram.system("telnet:broadcast,info," + id + " finished."));
                                            return;
                                        }
                                    }
                                }
                            }
                            String line = buffer.remove(0);
                            targets.forEach(wr -> wr.writeLine(line));
                            if (!label.isEmpty()) {
                                dQueue.add(Datagram.build(line).label(label));
                            }
                            sendLines++;
                        }
                    } catch (Exception e) {
                        Logger.error(e);
                    }
                }
            }
        }
        public String toString(){
            return "Reads '"+ pathOrData +"' every "+TimeTools.convertPeriodtoString(intervalMillis,TimeUnit.MILLISECONDS);
        }
    }
}
