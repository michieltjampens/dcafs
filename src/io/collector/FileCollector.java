package io.collector;

import das.Core;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.math.MathUtils;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.*;

public class FileCollector extends AbstractCollector{

    ScheduledExecutorService scheduler;

    BlockingQueue<String> dataBuffer = new LinkedBlockingQueue<>();

    private int byteCount=0;
    private Path destPath;
    private String eol = System.lineSeparator();
    private final ArrayList<String> headers= new ArrayList<>();
    private final Charset charSet = StandardCharsets.UTF_8;
    private int batchSize = 10;

    /* Variables related to the rollover */
    private DateTimeFormatter format = null;
    private ScheduledFuture<?> rollOverFuture;
    private ChronoUnit rollUnit = ChronoUnit.FOREVER;
    private int rollCount = 0;
    private LocalDateTime rolloverTimestamp;
    private boolean zippedRoll=false;

    private String currentForm = "";
    //private String workPath="";

    long lastData=-1;
    long firstData=-1;

    /* Triggers */
    public enum TRIGGERS {IDLE, ROLLOVER, MAXSIZE }

    ArrayList<TriggeredCommand> trigCmds = new ArrayList<>();

    /* Size limit */
    long maxBytes=-1;
    boolean zipMaxBytes=false;
    private boolean headerChanged=false;

    private Future<?> flushFuture;

    public FileCollector(String id, String timeoutPeriod, ScheduledExecutorService scheduler) {
        super(id);
        secondsTimeout = TimeTools.parsePeriodStringToSeconds(timeoutPeriod);
        this.scheduler=scheduler;
    }
    public FileCollector(String id ){
        super(id);
    }
    @Override
    public String id(){ return "fc:"+id;}
    public String toString(){
        String size;
        if( byteCount < 10000){
            size=byteCount+"B";
        }else if( byteCount < 1000000){
            size = MathUtils.roundDouble(byteCount / 1024.0, 1) + "KB";
        }else{
            size = MathUtils.roundDouble(byteCount / (1024.0 * 1024.0), 1) + "MB";
        }
        return "Writing to "+getPath()+" buffer containing "+dataBuffer.size()+"/"+batchSize+" items for a total of "+size;
    }
    public void setScheduler( ScheduledExecutorService scheduler ){
        this.scheduler=scheduler;
    }
    /**
     * Read the elements and build the FileCollectors based on the content
     * @param fcDigs The filecollector xmldiggers
     * @param scheduler A Scheduler used for timeouts, writes etc
     * @param workpath The current workpath
     * @return A list of the found filecollectors
     */
    public static List<FileCollector> createFromXml(List<XMLdigger> fcDigs, ScheduledExecutorService scheduler, String workpath) {
        var fcs = new ArrayList<FileCollector>();
        if( scheduler==null){
            Logger.error("Need a valid scheduler to use FileCollectors");
            return fcs;
        }
        for (var dig : fcDigs) {
            String id = dig.attr("id", "");
            if( id.isEmpty() )
                continue;
            var fc = new FileCollector(id);
            fc.setScheduler(scheduler);
            fc.readFromXML(dig,workpath);
            fcs.add(fc);
        }
        return fcs;
    }
    public void readFromXML( XMLdigger dig, String workpath ){

        /* Source and destination */
        addSource( dig.attr("src",""));
        var path = dig.attr("path", null, Path.of(workpath)).orElse(null);
        if (path == null) {
            Logger.error(id+"(fc) -> No valid destination given");
            return;
        }

        setPath(path);
        Logger.info("Trying to alter permissions");
        FileTools.setAllPermissions(getPath().getParent());

        /* Flush settings */
        digForFlush(dig);

        /* Headers */
        headers.clear();
        dig.peekOut("header").forEach( ele->addHeaderLine(ele.getTextContent()));

        /* RollOver */
        digForRollover( dig );

        /* Size limit */
        trigCmds.clear();
        if( dig.hasPeek("sizelimit") ){
            dig.usePeek();
            boolean zip = dig.attr("zip",false);
            var size = dig.value("");
            if( !size.isEmpty())
                setMaxFileSize(size.toLowerCase(),zip);
            dig.goUp();
        }

        /* Changing defaults */
        setEol(Tools.fromEscapedStringToBytes(dig.attr("eol", System.lineSeparator())));

        /* Triggered */
        dig.digOut("cmd").forEach( cmd -> {
            addTriggerCommand(cmd.attr("trigger","none").toLowerCase(),cmd.value(""));
        });

        /* Headers change ?*/
        if( Files.exists(getPath()) ) {
            var curHead = FileTools.readLines(getPath(), 1, headers.size(), true);
            headerChanged = !headers.equals(curHead);
        }
    }

    /**
     * Checks the digger for settings for the flush feature
     * @param dig The digger to look into
     */
    private void digForFlush( XMLdigger dig ){
        if (dig.hasPeek("flush")) {
            dig.usePeek();
            setBatchsize( dig.attr("batchsize",Integer.MAX_VALUE));
            if( scheduler != null ) {
                String timeout = dig.attr( "age", "-1");
                if (!timeout.equalsIgnoreCase("-1")) {
                    setTimeOut(timeout, scheduler);
                }
            }
            dig.goUp();
        }
    }
    private void digForRollover( XMLdigger dig ){
        if( dig.hasPeek("rollover")){
            dig.usePeek();
            String period = dig.attr("period","").toLowerCase();
            var rollCount = NumberUtils.toInt(period.replaceAll("\\D",""));
            var unit = period.replaceAll("[^a-z]","");
            String format = dig.value("");

            boolean zip = dig.attr("zip",false);

            ChronoUnit rollUnit = TimeTools.parseToChronoUnit( unit );
            if( rollUnit !=null){
                Logger.info(id+"(fc) -> Setting rollover: "+format+" "+rollCount+" "+rollUnit);
                setRollOver(format,rollCount,rollUnit,zip);
            }else{
                Logger.error(id+"(fc) -> Bad Rollover given" );
            }
        }
    }
    /**
     * Add a blank node in the position the fab is pointing to
     * @param fab XMLfab pointing to where the collectors parent should be
     * @param id The id for the filecollector
     * @param source The source of data
     * @param path The path of the file
     */
    public static void addBlankToXML(XMLfab fab, String id, String source, String path ){
        fab.selectOrAddChildAsParent("collectors")
                .addChild("file").attr("id",id).attr("src",source)
                    .down()
                    .addChild("path",path)
                    .addChild("flush").attr("batchsize","30").attr("age","1m")
                .build();
    }

    /**
     * Set the maximum size th file can become, and whether to zip it after reaching this point
     * @param size The size of the file, allows kb,mb,gb extension (fe. 15mb)
     * @param zip If true, this file gets zipped
     */
    public void setMaxFileSize( String size,boolean zip ){
        long multiplier=1;
        size=size.replace("b","");
        if( size.endsWith("k"))
            multiplier=1024;
        if( size.endsWith("m"))
            multiplier=1024*1024;
        if( size.endsWith("g"))
            multiplier=1024*1024*1024;

        maxBytes=NumberUtils.toLong(size.substring(0,size.length()-1))*multiplier;
        zipMaxBytes=zip;
        Logger.info(id+"(fc) -> Maximum size set to "+maxBytes);
    }

    /**
     * Change the max file size but keep the zip option as it was
     * @param size The size of the file, allows kb,mb,gb extension (fe. 15mb)
     */
    public void setMaxFileSize( String size ){
        setMaxFileSize(size,zipMaxBytes);
    }

    /**
     * Add a triggered command to the collector
     * @param trigger The trigger, current options: rollover, idle, maxsize
     * @param cmd The command to execute if triggered
     * @return True if added successfully
     */
    public boolean addTriggerCommand( String trigger, String cmd ){
        if(cmd==null)
            return false;

        switch (trigger) {
            case "rollover" -> addTriggerCommand(TRIGGERS.ROLLOVER, cmd);
            case "idle" -> addTriggerCommand(TRIGGERS.IDLE, cmd);
            case "maxsize" -> addTriggerCommand(TRIGGERS.MAXSIZE, cmd);
            default -> {
                return false;
            }
        }
        return true;
    }
    /**
     * Add a triggered command to the collector
     * @param trigger The trigger, using the enum
     * @param cmd The command to execute if triggered
     */
    public void addTriggerCommand( TRIGGERS trigger, String cmd ){
        trigCmds.add(new TriggeredCommand(trigger, cmd));
    }
    /**
     * Set the full path (relative of absolute) to the file
     * @param path the path to the file
     */
    public void setPath( Path path ){
        this.destPath=path;
        Logger.info(id+"(fc) -> Path set to "+destPath);
    }
    public void setPath( Path path, String workPath ){
        if( !path.isAbsolute()) {
            destPath = Path.of(workPath).resolve(path);
        }else{
            destPath = path;
        }
        Logger.info(id+"(fc) -> Path set to "+destPath);
    }
    /**
     * Get the current path this file can be found at
     * @return The path to the file as a string
     */
    public Path getPath(){

        String path = destPath.toString();

        //without rollover
        if( currentForm.isEmpty() )
            return Path.of(path);

        //with rollover and on a specific position
        if( path.contains("{rollover}"))
            return Path.of(path.replace("{rollover}", currentForm));

        // with rollover but on default position
        return Path.of(path.replace(".", currentForm+'.'));
    }
    /**
     * Set the amount of messages in the batch before it's flushed to disk
     * @param batch The amount
     */
    public void setBatchsize( int batch ){
        batchSize=batch;
    }

    /**
     * Set a maximum age of data before a flush is initiated
     * @param timeoutPeriod The period (fe. 5m or 63s etc)
     * @param scheduler A scheduler to use for this
     */
    public void setTimeOut( String timeoutPeriod, ScheduledExecutorService scheduler ){
        secondsTimeout = TimeTools.parsePeriodStringToSeconds(timeoutPeriod);
        this.scheduler = scheduler;
        Logger.info(id+"(fc) -> Setting flush period to "+secondsTimeout+"s");
    }

    /**
     * Add a line that will we added first to a new file
     * @param header A line to add at the top, the standard line separator will be appended
     */
    public void addHeaderLine(String header){
        headers.add(header);
        headerChanged=true;
    }

    /**
     * Change the line separator, by default this is the system one
     * @param eol Alter the line separater/eol characters
     */
    public void setEol(String eol) {
        this.eol = eol;
    }

    @Override
    protected synchronized boolean addData(String data) {
        if( dataBuffer.isEmpty())
            firstData=Instant.now().getEpochSecond();

        dataBuffer.add(data);
        byteCount += data.length();
        lastData = Instant.now().getEpochSecond();

        if (timeoutFuture == null || timeoutFuture.isDone() || timeoutFuture.isCancelled())
            timeoutFuture = scheduler.schedule(this::timedOut, secondsTimeout, TimeUnit.SECONDS);

        if( dataBuffer.size() > batchSize && batchSize !=-1){
            Logger.debug(id+ "(fc) -> Buffer matches batchsize");
            flushNow();
        }
        return true;
    }

    /**
     * Force the collector to flush the data, used in case of urgent flushing (fe. before shutdown)
     */
    public void flushNow(){
        if (flushFuture == null || flushFuture.isCancelled() || flushFuture.isDone())
            flushFuture = scheduler.submit(() -> appendData(getPath()));
    }
    @Override
    protected void timedOut() {
        Logger.debug(id+ "(fc) -> TimeOut expired");

        if( dataBuffer.isEmpty() ){
            // Timed out with empty buffer
            trigCmds.stream().filter( tc -> tc.trigger==TRIGGERS.IDLE)
                             .forEach( tc-> Core.addToQueue( Datagram.system(tc.cmd.replace("{path}",getPath().toString())).writable(this)) );
        }else{
            long dif = Instant.now().getEpochSecond() - batchSize == -1 ? firstData : lastData; // if there's a batchsize use first, that is primary
            if( dif >= secondsTimeout-1 ) {
                flushNow();
                timeoutFuture = scheduler.schedule(this::timedOut, secondsTimeout, TimeUnit.SECONDS);
            }else{
                long next = secondsTimeout - dif;
                timeoutFuture = scheduler.schedule(this::timedOut, next, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Write data to the chosen file
     * @param dest The path to write to
     */
    private void appendData( Path dest ){

        if( dest ==null) {
            Logger.error(id+"(fc) -> No valid destination path");
            return;
        }
        boolean isNewFile = false;
        if( Files.notExists(dest) ){
            isNewFile=true;
            var parent = dest.toAbsolutePath().getParent();
            try { // Create the dir structure if it doesn't exist yet
                Files.createDirectories(parent);
            } catch (IOException e) {
                Logger.error(id + "(fc) -> " + e.getMessage());
                return;
            }
        }else if( headerChanged ){ // File already exists and the header changed, rename the old and start a new file
            if (renameOldFile(id, dest).isEmpty())
                return;
            isNewFile=true;
        }

        StringJoiner join = new StringJoiner(eol, "", eol);
        if( !headers.isEmpty() && isNewFile ) // the file doesn't exist yet and headers are defined
            headers.forEach( hdr -> join.add(hdr.replace("{file}",dest.getFileName().toString()))); // Add the headers

        String line;
        int cnt=dataBuffer.size()*4; // At maximum write 4 times the buffer
        while((line=dataBuffer.poll()) != null && cnt !=0) {
            if( !line.isBlank() )
                join.add(line);
            cnt--;
        }
        // Reset counter
        byteCount=0;

        writeData(join.toString(), dest);
    }

    private void writeData(String data, Path dest) {
        try {
            if (data.isBlank())// Don't write empty lines
                return;
            var isNewFile = Files.notExists(dest);
            Files.writeString(dest, data, charSet, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Logger.debug(id + "(fc) Written " + data.length() + " bytes to " + dest.getFileName().toString());

            if (isNewFile)
                FileTools.setAllPermissions(dest);

            renameIfToBig(dest);

        } catch (IOException e) {
            Logger.error(id + "(fc) -> Failed to write to " + dest + " because " + e);
        }
    }

    private void renameIfToBig(Path dest) throws IOException {
        // If max size isn't used or isn't reached return
        if (maxBytes == -1 || Files.size(dest) < maxBytes)
            return;

        var renamed = renameOldFile(id, dest).orElseGet(null);// rename the file
        if (renamed == null)
            return;

        String path;
        if (zipMaxBytes) { // if needed, zip it
            if (FileTools.zipFile(renamed, true).isEmpty())
                return;
            path = renamed + ".zip";
        } else {
            path = renamed.toString();
        }

        // run the triggered commands
        trigCmds.stream().filter(tc -> tc.trigger == TRIGGERS.MAXSIZE)
                .forEach(tc -> Core.addToQueue(Datagram.system(tc.cmd.replace("{path}", path)).writable(this)));
    }

    /**
     * Add a numbeer to the end of this file making sure it doesn't make a file originally there
     *
     * @param id       The id of the source of the request
     * @param original The path of the file to rename
     * @return The path or empty if something went wrong
     */
    private static Optional<Path> renameOldFile(String id, Path original) {
        Path renamed = null;
        for (int a = 1; a < 1000; a++) { // Find a name that isn't used yet
            renamed = Path.of(original.toString().replaceFirst("\\.(?=[^.]+$)", "." + a + "."));
            // Check if the desired name or zipped version already is available
            if (Files.notExists(renamed) && Files.notExists(Path.of(renamed + ".zip")))
                break;
        }
        try {
            Files.move(original, renamed);
            Logger.debug(id + "(fc) -> Renamed to " + renamed);
        } catch (IOException e) {
            Logger.error(id + "(fc) -> Failed to write to " + original + " because " + e);
            return Optional.empty();
        }
        return Optional.of(renamed);
    }
    /* ***************************** Overrides  ******************************************************************* */
    @Override
    public void addSource(String src) {
        if (!source.isEmpty()) { // Stop the current one before using the new
            Core.addToQueue(Datagram.system("stop:" + source).writable(this));
        }
        source = src;
        Core.addToQueue(Datagram.system(src).writable(this)); // request the data
    }
    /* ***************************** RollOver stuff *************************************************************** */
    public boolean setRollOver(String dateFormat, int rollCount, ChronoUnit unit, boolean zip ) {

        if(  unit == ChronoUnit.FOREVER || unit == null) {
            Logger.warn(id+"(fc) -> Bad rollover given");
            return false;
        }
        this.rollCount=rollCount;
        rollUnit=unit;
        zippedRoll=zip;

        format = DateTimeFormatter.ofPattern(dateFormat);
        rolloverTimestamp = LocalDateTime.now(ZoneOffset.UTC).withNano(0);

        Logger.info(id+"(fc) -> Init rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
        rolloverTimestamp = TimeTools.applyTimestampRollover(true,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
        updateFileName(rolloverTimestamp);
        rolloverTimestamp = TimeTools.applyTimestampRollover(false,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
        Logger.info(id+"(fc) -> Next rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));

        long next = Duration.between(LocalDateTime.now(ZoneOffset.UTC),rolloverTimestamp).toMillis();
        if( next > 100) {
            rollOverFuture = scheduler.schedule(new DoRollOver(true), next, TimeUnit.MILLISECONDS);
            Logger.info(id + "(fc) -> Next rollover in " + TimeTools.convertPeriodToString(rollOverFuture.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS));
        }else{
            Logger.error(id+"(fc) -> Bad rollover for "+rollCount+" counts and unit "+unit+" because next is "+next);
        }
        return true;
    }
    /**
     * Update the filename of the file currently used
     */
    public void updateFileName(LocalDateTime ldt){
        if( format==null)
            return;
        try{
            if (ldt != null)
                currentForm = ldt.format(format);
        }catch( java.time.temporal.UnsupportedTemporalTypeException f) {
            Logger.error(id() + "(fc) -> Format given is unsupported! Creation cancelled. -> "+format);
        }
    }
    private class DoRollOver implements Runnable {
        boolean renew;

        public DoRollOver( boolean renew ){
            this.renew=renew;
        }
        @Override
        public void run() {
            Logger.info(id + "(fc) -> Doing rollover.");

            Path old = getPath();
            flushNow();

            if(renew)
                updateFileName(rolloverTimestamp); // first update the filename

            if( renew ) {
                Logger.info(id+"(fc) -> Current rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
                rolloverTimestamp = TimeTools.applyTimestampRollover(false,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
                Logger.info(id+"(fc) -> Next rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
                long next = Duration.between(LocalDateTime.now(ZoneOffset.UTC), rolloverTimestamp).toMillis();
                rollOverFuture = scheduler.schedule(new DoRollOver(true), next, TimeUnit.MILLISECONDS);
            }

            try {
                String path;
                if( zippedRoll ){
                    var res = flushFuture.get(5,TimeUnit.SECONDS); // Writing should be done in 5 seconds...
                    if( res==null) { // if zipping and append is finished
                        var zipOpt = FileTools.zipFile(old, true);
                        path = zipOpt.map(zip -> {
                            Logger.info(id + "(fc) -> Zipped " + old.toAbsolutePath());
                            return zip.toString();
                        }).orElseGet(() -> {
                            Logger.error(id + "(fc) -> Failed to zip " + old.toAbsolutePath());
                            return old.toString();
                        });
                    }else{
                        path=old.toString();
                    }
                } else {
                    Logger.info(id + "(fc) -> Not zipping");
                    path=old.toString();
                }
                // Triggered commands
                trigCmds.stream().filter( tc -> tc.trigger==TRIGGERS.ROLLOVER)
                        .forEach(tc->Core.addToQueue(Datagram.system(tc.cmd.replace("{path}",path)).writable(FileCollector.this)));

            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                Logger.error(e);
            }
        }
    }
    public static class TriggeredCommand {
        TRIGGERS trigger;
        String cmd;

        public TriggeredCommand(TRIGGERS trigger, String cmd){
            this.trigger=trigger;
            this.cmd=cmd;
        }
    }
}
