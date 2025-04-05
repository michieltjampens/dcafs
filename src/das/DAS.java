package das;

import io.Writable;
import io.collector.CollectorPool;
import io.email.Email;
import io.email.EmailWorker;
import io.forward.PathPool;
import io.hardware.gpio.InterruptPins;
import io.hardware.i2c.I2CWorker;
import io.matrix.MatrixClient;
import io.mqtt.MqttPool;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.stream.StreamManager;
import io.stream.tcp.TcpServer;
import io.stream.udp.UdpServer;
import io.telnet.TelnetCodes;
import io.telnet.TelnetServer;
import org.apache.commons.lang3.SystemUtils;
import org.tinylog.Logger;
import org.tinylog.provider.ProviderRegistry;
import util.LookAndFeel;
import util.data.RealtimeValues;
import util.database.DatabaseManager;
import util.gis.Waypoints;
import util.tasks.TaskManagerPool;
import util.tools.FileMonitor;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;
import worker.LabelWorker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class DAS implements Commandable{

    private static final String version = "3.0.2";

    private final String tinylogPath;
    private final LocalDateTime bootupTimestamp = LocalDateTime.now(); // Store timestamp at boot up to calculate uptime

    /* Workers */
    private EmailWorker emailWorker; // Worker that processes email requests
    private LabelWorker labelWorker; // Worker that processes datagrams
    private I2CWorker i2cWorker; // Worker that interacts with i2c devices

    /* */
    private StreamManager streamManager; // Pool of all the stream objects
    private TcpServer trans; // TCP server that takes requests
    private TelnetServer telnet; // Telnet server for the user CLI

    private RealtimeValues rtvals; // Pool of all the vals (realval,flagval,textval,intval) that hold realtime data
    private TaskManagerPool taskManagerPool;
    /* Managers & Pools */
    private DatabaseManager dbManager; // Manager for the database interaction
    private MqttPool mqttPool; // Pool for the mqtt connections
    private CollectorPool collectorPool; // Pool of the collector objects (mainly filecollectors)
    private CommandPool commandPool; // Pool that holds the references to all the commandables
    private boolean bootOK = false; // Flag to show if booting went ok
    String sdReason = "Unwanted shutdown."; // Reason for shutdown of das, default is unwanted

    boolean rebootOnShutDown = false; // Flag to set to know if the device should be rebooted on dcafs shutdown (linux only)
    private InterruptPins isrs; // Manager for working with IO pins
    private MatrixClient matrixClient; // Client for working with matrix connections

    private Instant lastCheck; // Timestamp of the last clock check, to know if the clock was changed after das booted
    private long maxRawAge=3600; // 1 hour default for the age of raw data writes status to turn red

    /* Status Checks */
    private int statusBadChecks = 0;
    private long statusCheckInterval =3600;
    private String statusEmail="";
    private String statusMatrixRoom="";

    /* Threading */
    private final EventLoopGroup nettyGroup = new NioEventLoopGroup(Math.min(12, Runtime.getRuntime().availableProcessors() * 2)); // Single group so telnet,trans and StreamManager can share it

    public DAS() {

        tinylogPath = figureOutTinyLogPath();   // This needs to be done before tinylog is used because it sets the paths
        if( !checkSettingsFile() ) // Check if the file exist and create if it doesn't, if it does verify it
            return;
        Logger.info("Program booting");

        var digger = Paths.digInSettings(); // Use digger to go through settings.xml

        digForSettings( digger );   // Dig for the settings node

        /* CommandPool */
        commandPool = new CommandPool( );
        addCommandable(this,"st"); // add the commands found in this file

        addRtvals();            // Add Realtimevalues

        /* Database manager */
        dbManager = new DatabaseManager(rtvals);
        addCommandable(dbManager,"dbm","myd");

        addLabelWorker();       // Add Label worker
        addStreamManager();     // Add Stream manager
        prepareForwards();      // Add forwards
        addI2CWorker();         // Add I2C

        /* TransServer */
        if( digger.hasPeek("transserver")) // Check if trans is in xml
            addTransServer(); // and if so, set it up

        /* Waypoints */
        var waypoints = new Waypoints(nettyGroup,rtvals);
        addCommandable(waypoints,"wpts");

        digForCollectors();         // Add FileCollectors
        digForGPIOs(digger);        // Add GPIO's
        digForEmail(digger);        // Add EmailWorker
        digForFileMonitor(digger);  // Add Filemonitor
        digForMatrix( digger );     // Add matrix
        addMqttPool();              // Add MQTT
        addTaskManagerPool();       // Add Taskmanagers

        /* Regular check if the system clock was changed */
        nettyGroup.schedule(this::checkClock,5,TimeUnit.MINUTES);

        /* Build the stores in the sqltables, needs to be done at the end */
        dbManager.buildStores(rtvals);
        bootOK = true;

        addTelnetServer();  // Add Telnet Server

        attachShutDownHook();

    }

    private static String figureOutTinyLogPath() {
        String tinylogPath;
        // Determine working dir based on the classpath
        if( Files.exists(Paths.settings())){
            var digger = XMLdigger.goIn(Paths.settings(),"dcafs","settings");
            tinylogPath = digger.peekAt("tinylog").value(Paths.storage().toString());
        } else {
            tinylogPath = Paths.storage().toString();
        }

        System.out.println("Workpath lib: "+Paths.storage()); // System because logger isn't initiated yet
        System.out.println("Workpath tinylog: "+tinylogPath); // System because logger isn't initiated yet
        if( System.getProperty("tinylog.directory") == null ) { // don't overwrite this
            // Re set the paths for the file writers to use the same path as the rest of the program
            System.setProperty("tinylog.directory", tinylogPath); // Set work path as system property
        }

        Logger.info("Used settingspath: "+Paths.settings());
        return tinylogPath;
    }
    private boolean checkSettingsFile(){
        if (Files.notExists(Paths.settings())) { // Check if the settings.xml exists
            Logger.warn("No Settings.xml file found, creating new one. Searched path: "
                    + Paths.settings().toAbsolutePath());
            createXML(); // doesn't exist so create it
        }

        if( !XMLtools.checkXML(Paths.settings()).isEmpty() ){// Check if the reading resulted in a proper xml
            Logger.error("Issue in current settings.xml, aborting: " + Paths.settings());
            addTelnetServer(); // Even if settings.xml is bad, start the telnet server anyway to inform user
            return false;
        }
        return true;
    }
    /**
     * Check the digger for the settings node and process if found
     * @param digger The digger for the settings file with dcafs root
     */
    private void digForSettings( XMLdigger digger ){
        if( digger.hasPeek("settings") ){
            digger.usePeek();
            var age = digger.peekAt("maxrawage").value("1h");
            maxRawAge = TimeTools.parsePeriodStringToSeconds(age);
            if( maxRawAge==0){
                Logger.error("Invalid maxrawage value: "+age+" defaulting to 1 hour.");
            }
            if( digger.hasPeek("statuscheck") ){
                digger.usePeek();
                var check = digger.peekAt("checkinterval").value("1h");
                statusCheckInterval = TimeTools.parsePeriodStringToSeconds(check);
                statusEmail = digger.peekAt("email").value(statusEmail);
                statusMatrixRoom = digger.peekAt("matrix").value(statusMatrixRoom);
                digger.goUp(); // Back from statuscheck to settings
            }
            digger.goUp(); // Back from settings to root
        }
    }
    private void addRtvals(){
        rtvals = new RealtimeValues();
        addCommandable(rtvals,"flags;fv;reals;real;rv;texts;tv;int;integer;text;flag");
        addCommandable(rtvals,"rtval","rtvals");
        addCommandable(rtvals,"stop");
    }
    private void prepareForwards(){
        var pathPool = new PathPool(rtvals, nettyGroup,dbManager);
        addCommandable(pathPool,"paths","path","pf");
        addCommandable(pathPool, ""); // empty cmd is used to stop data requests
    }
    /**
     * Check the digger for the matrix node and process if found
     * @param digger The digger for the settings file with dcafs root
     */
    private void digForMatrix( XMLdigger digger ){
        if( digger.hasPeek("matrix") ){
            Logger.info("Reading Matrix info from settings.xml");
            matrixClient = new MatrixClient( rtvals );
            addCommandable(matrixClient,"matrix");
        }else{
            statusMatrixRoom="";
            Logger.info("No matrix settings");
        }
    }
    /**
     * Check the digger for the file monitor node and process if found
     * @param digger The digger for the settings file with dcafs root
     */
    private void digForFileMonitor( XMLdigger digger ){
        if( digger.hasPeek("monitor") ) {
            // Monitor files for changes
            FileMonitor fileMonitor = new FileMonitor( Paths.storage() );
            addCommandable(fileMonitor,"fm","fms");
        }
    }
    /**
     * Check the digger for the email node and process if found
     * @param digger The digger for the settings file with dcafs root
     */
    private void digForEmail( XMLdigger digger ){
        if( digger.hasPeek("email") ) {
            addEmailWorker();
        }else{
            statusEmail="";
            Logger.info( "No email defined in xml");
        }
    }
    /**
     * Check the digger for the collector node and process if found
     */
    private void digForCollectors() {
        collectorPool = new CollectorPool(nettyGroup, rtvals);
        addCommandable(collectorPool, "fc");
        addCommandable(collectorPool, "mc");
    }
    /**
     * Check the digger for the GPIO/ISR node and process if found
     * @param digger The digger for the settings file with dcafs root
     */
    private void digForGPIOs( XMLdigger digger ) {
        if (digger.hasPeek("gpios")) {
            Logger.info("Reading interrupt gpio's from settings.xml");
            isrs = new InterruptPins(rtvals);
            addCommandable(isrs, "gpios", "isr");
        }
    }
    /**
     * Get the version number of dcafs
     * @return The version nr in format x.y.z
     */
    public String getVersion(){return version;}

    /**
     * Get the period that dcafs has been active
     * @return The active period in a readable string
     */
    public String getUptime() {
        return TimeTools.convertPeriodToString(Duration.between(bootupTimestamp, LocalDateTime.now()).getSeconds(),
                TimeUnit.SECONDS);
    }
    /**
     Compares the stored timestamp with the current one because this was used at startup to determine rollover of
     databases etc. If the system booted with the wrong time, this fixes it in dcafs once it's corrected.
     */
    private void checkClock(){
        if( Duration.between(lastCheck, Instant.now()).toSeconds() > 305) { // Checks every 5 minutes, so shouldn't be much more than that
            var error = "System time change detected, last check (max 60s ago) " + TimeTools.LONGDATE_FORMATTER.format(lastCheck) + " is now " + TimeTools.formatLongUTCNow();
            Logger.error(error);
            if( emailWorker !=null )
                emailWorker.sendEmail( Email.toAdminAbout("System clock").subject("System clock suddenly changed!").content(error));
            dbManager.recheckRollOver();// The rollover is affected by sudden changes
        }
        lastCheck = Instant.now();
    }
    /* ************************************  X M L *****************************************************/
    /**
     * Create the base settings.xml
     */
    private static void createXML() {
       XMLfab.withRoot( Paths.settings(), "dcafs")
                .addParentToRoot("settings")
                    .addChild("maxrawage").content("1h")
                .addParentToRoot("streams")
                    .comment("Defining the various streams that need to be read")
                .build();
    }
    /* **************************************  C O M M A N D P O O L ********************************************/
    /**
     * Add a commandable to the CommandPool, this is the same as adding commands to dcafs
     * @param ids The unique start command (so whatever is in front of the : )
     * @param cmd The commandable to add
     */
    public void addCommandable(Commandable cmd, String... ids) {
        if (ids.length == 1) {
            Stream.of(ids[0].split(";")).forEach(id -> commandPool.addCommandable(id, cmd));
        } else {
            Stream.of(ids).forEach(id -> commandPool.addCommandable(id, cmd));
        }
    }
    /* ***************************************  T A S K M A N A G E R ********************************************/
    /**
     * Create a TaskManager to handle tasklist scripts
     */
    private void addTaskManagerPool() {
        taskManagerPool = new TaskManagerPool(rtvals, nettyGroup);
        addCommandable(taskManagerPool, "tm");
    }
    /* ******************************************  S T R E A M P O O L ***********************************************/
    /**
     * Adds the StreamManager that will manage the various streams (tcp,udp,serial)
     */
    private void addStreamManager() {

        streamManager = new StreamManager(nettyGroup, rtvals);
        addCommandable(streamManager, "ss", "streams"); // general commands
        addCommandable(streamManager, "s_", "h_");      // sending data to a stream
        addCommandable(streamManager, "raw", "stream"); // getting data from a stream
        addCommandable(streamManager, ""); // stop sending data

        streamManager.readSettingsFromXML();
        streamManager.getStreamIDs().map(id -> streamManager.getStream(id))
                .flatMap(Optional::stream) // Only get the valid results
                .filter(bs -> !(bs instanceof UdpServer)) //Can't send data so ignore
                .forEach(bs -> addCommandable(streamManager, bs.id()));
    }
    public Optional<Writable> getStreamWritable( String id ){
        var opt = streamManager.getStream(id);
        return opt.map(baseStream -> (Writable) baseStream);
    }
    /* *************************************  L A B E L W O R K E R **********************************************/
    /**
     * Adds the LabelWorker that will process the datagrams
     */
    private void addLabelWorker() {
        if (this.labelWorker == null)
            labelWorker = new LabelWorker();
        labelWorker.setCommandReq(commandPool);
    }
    /* ***************************************** M Q T T ******************************************************** */

    /**
     * Add the pool that handles the mqtt connections
     */
    private void addMqttPool(){
        mqttPool = new MqttPool(rtvals);
        addCommandable( mqttPool,"mqtt");
    }
    /* *****************************************  T R A N S S E R V E R ***************************************** */
    /**
     * Adds the TransServer listening on the given port
     */
    private void addTransServer() {

        Logger.info("Adding TransServer");
        trans = new TcpServer(nettyGroup);

        addCommandable(trans,"ts","trans"); // Add ts/trans commands to CommandPool
    }

    /* **********************************  E M A I L W O R K E R *********************************************/
    /**
     * Adds an EmailWorker, this handles sending and receiving emails
     */
    private void addEmailWorker() {
        Logger.info("Adding EmailWorker");
        emailWorker = new EmailWorker();
        addCommandable(emailWorker,"email");
        commandPool.setEmailSender(emailWorker);
    }
    /* ***************************************  T E L N E T S E R V E R ******************************************/
    /**
     * Create the telnet server
     */
    private void addTelnetServer() {
        telnet = new TelnetServer(nettyGroup,bootOK);
        if( bootOK)
            addCommandable(telnet, "telnet", "nb");
    }

    /* ********************************   B U S ************************************************/
    /**
     * Create the I2CWorker, this handles working with I2C devices
     */
    private void addI2CWorker() {
        if( i2cWorker!=null)
            return;

        if (SystemUtils.IS_OS_WINDOWS) {
            Logger.info("No native I2C busses on windows... ignoring I2C");
            return;
        }
        Logger.info("Adding I2CWorker.");
        i2cWorker = new I2CWorker(nettyGroup,rtvals);
        addCommandable(i2cWorker,"i2c","i_c");
        addCommandable(i2cWorker,"stop");
        i2cWorker.getUartIds().forEach( id -> addCommandable(i2cWorker,id) );
    }
    /* ******************************** * S H U T D O W N S T U F F ***************************************** */
    /**
     * Attach a hook to the shutdown process, so we're sure that all queue's etc. get
     * processed before the program is closed.
     */
    private void attachShutDownHook() {
        Runtime.getRuntime().addShutdownHook( new Thread("shutdownHook") {
            @Override
            public void run() {
                Logger.info("Dcafs shutting down!");
                // Inform in all active telnet sessions that a shutdown is in progress
                telnet.replyToCommand(Datagram.system("telnet", "broadcast,error,Dcafs shutting down!"));

                // Inform in the matrix rooms that it's shutting down
                if( matrixClient!=null)
                    matrixClient.broadcast("Shutting down!");

                // Run shutdown tasks
                taskManagerPool.startTask("shutdown");

                // SQLite & SQLDB
                Logger.info("Flushing database buffers");
                dbManager.flushAll();

                // Collectors
                if( collectorPool!=null)
                    collectorPool.flushAll();

                // Try to send email...
                sendShutdownEmails();

                try {
                    Logger.info("Giving things two seconds to finish up.");
                    sleep(2000);
                } catch (InterruptedException e) {
                    Logger.error(e);
                    Thread.currentThread().interrupt();
                }

                // Disconnecting tcp ports
                if (streamManager != null)
                    streamManager.disconnectAll();

                Logger.info("All processes terminated!");
                shutdownTinylog();

                // On linux it's possible to have the device reboot if das is shutdown for some reason
                if (rebootOnShutDown)
                    rebootSystem();
            }
        });
        Logger.info("Shut Down Hook Attached.");
    }

    /**
     * Reboots the computer if running on linux
     */
    private static void rebootSystem() {
        try {
            Runtime rt = Runtime.getRuntime();
            if (SystemUtils.IS_OS_LINUX) { // if linux
                rt.exec("reboot now");
            } else if (SystemUtils.IS_OS_WINDOWS) {
                Logger.warn("Windows not supported yet for reboot");
            }
        } catch (java.io.IOException err) {
            Logger.error(err);
        }
    }
    private void sendShutdownEmails(){
        if (emailWorker != null) {
            Logger.info("Informing admin");
            String r = commandPool.getShutdownReason();
            sdReason = r.isEmpty()?sdReason:r;
            emailWorker.sendEmail( Email.toAdminAbout(telnet.getTitle() + " shutting down.").content("Reason: " + sdReason) );
        }
    }
    /**
     * Shuts down the tiny log framework, makes it flush buffers etc
     */
    private static void shutdownTinylog() {
        try {
            ProviderRegistry.getLoggingProvider().shutdown(); // Shutdown tinylog
        } catch (InterruptedException e) {
            Logger.error(e);
            Thread.currentThread().interrupt();
        }
    }
    /* ******************************* T H R E A D I N G *****************************************/
    /**
     * Start all the threads
     */
    public void startAll() {

        if (labelWorker != null) {
            Logger.info("Starting LabelWorker...");
            new Thread(labelWorker, "LabelWorker").start();// Start the thread
        }

        if (trans != null && trans.isActive()) {
            Logger.info( "Starting transserver");
            trans.run(); // Start the server
        }
        if (telnet != null) {
            Logger.info("Starting telnet server");
            telnet.run(); // Start the server
        }

        // TaskManager
        if (taskManagerPool != null) {
            Logger.info( "Parsing TaskManager scripts");
            String errors = taskManagerPool.reloadAll();
            taskManagerPool.getTasKManagerIds().forEach(id -> addCommandable(taskManagerPool, id));

            if( !errors.isEmpty())
                telnet.addMessage("Errors during TaskManager parsing:\r\n"+errors);
        }
        // Matrix
        if( matrixClient != null ){
            Logger.info("Trying to login to matrix");
            matrixClient.login();
        }
        // Start the status checks if any of the possible outputs is actually usable
        if( (!statusMatrixRoom.isEmpty()||(emailWorker != null && !statusEmail.isEmpty())) && statusCheckInterval >0) // No use checking if we can't report on it or if it's disabled
            nettyGroup.schedule(this::checkStatus,20,TimeUnit.MINUTES); // First check, twenty minutes after startup

        Logger.info("Finished startAll");
    }
    /* **************************** * S T A T U S S T U F F *********************************************************/
    /**
     * Requests the status message and checks if an error is reported in it (!!) if so, email about it or send it to
     * an attached matrix room..
     */
    private void checkStatus(){
        var status = getStatus(true);

        if( status.contains("!!")){ // This means a status is in error
            if( statusBadChecks == 0 ){ // If this is the first time a bad status is reported
                if( !statusEmail.isEmpty() ) // if the recipient of the status email is filled in
                    emailWorker.sendEmail( Email.to(statusEmail).subject("[Issue] Status report").content(status));
                if( !statusMatrixRoom.isEmpty() ){ // If the status needs to be reported in a matrix room
                    var prefix = "matrix:" + statusMatrixRoom + ",txt,";
                    Core.addToQueue(Datagram.system( prefix + "<b>Issue(s) found!</b><br>" )); // send the header

                    Arrays.stream(status.split("<br>")).filter( l -> l.startsWith("!!"))
                            .forEach( line -> Core.addToQueue(Datagram.system(prefix + line)));
                }
            }
            nettyGroup.schedule(this::checkStatus,30,TimeUnit.MINUTES); // Reschedule, but earlier
            statusBadChecks++;
            if( statusBadChecks == 13) { // Every 6 hours, send a reminder?
                statusBadChecks = 0;
            }
            return;
        }else if( statusBadChecks !=0 ){
            if( !statusEmail.isEmpty())
                emailWorker.sendEmail( Email.to(statusEmail).subject("[Resolved] Status report").content(status));
            if( !statusMatrixRoom.isEmpty() ) {
                Core.addToQueue(Datagram.system("matrix:" + statusMatrixRoom + ",txt,Issues resolved"));
            }
        }
        nettyGroup.schedule(this::checkStatus, statusCheckInterval,TimeUnit.SECONDS); // every hour by default
    }
    /**
     * Request a status message regarding the streams, databases, buffers etc
     * 
     * @param html Whether the status needs to be given in telnet or html
     * @return A status message
     */
    public String getStatus(boolean html) {

        var report = new StringBuilder();

        addDcafsStatus(report,html);  // General program status like memory etc

        if (streamManager != null) {
            report.append( LookAndFeel.formatStatusTitle("Streams",html));
            LookAndFeel.formatStatusText( streamManager.getStatus(),report,html );
        }
        if( i2cWorker != null && i2cWorker.getDeviceCount()!=0){
            report.append( LookAndFeel.formatStatusTitle("Devices",html));
            LookAndFeel.formatStatusText( i2cWorker.getStatus("\r\n"),report,html );
        }
        if( isrs != null ){
            report.append( LookAndFeel.formatStatusTitle( "GPIO Isr",html));
            LookAndFeel.formatStatusText( isrs.getStatus("\r\n"),report,html);
        }
        if (mqttPool != null && !mqttPool.getMqttWorkerIDs().isEmpty()) {
            report.append( LookAndFeel.formatStatusTitle("MQTT",html));
            LookAndFeel.formatStatusText( mqttPool.getMqttBrokersInfo(),report,html);
        }

        // Buffer status
        report.append( LookAndFeel.formatStatusTitle("Buffers",html));
        LookAndFeel.formatStatusText( getQueueSizes(),report,html);

        addDbmStatus(report, html); // Database Manager status

        // Finally color the word false in red
        return report.toString().replace("false", (html?"":TelnetCodes.TEXT_RED) + "false" + (html?"":TelnetCodes.TEXT_GREEN) );
    }

    /**
     * Add the general status info on das (memory,ip,uptime etc.) for the report
     * @param report The rest of the report data
     * @param html Whether to use html formatting or not and thus telnet
     */
    private void addDcafsStatus( StringBuilder report, boolean html ){

        final String TEXT_DEFAULT = html?"":TelnetCodes.TEXT_DEFAULT;
        final String TEXT_WARN = html?"":TelnetCodes.TEXT_ORANGE;

        report.append( LookAndFeel.formatStatusTitle( "DCAFS Status at "+TimeTools.formatNow("HH:mm:ss"),html) );
        LookAndFeel.formatSplitStatusText( "DCAFS Version: "+version+" (jvm:"+System.getProperty("java.version")+")", report, html );
        LookAndFeel.formatSplitStatusText( "Uptime: "+getUptime(), report, html );
        addMemoryInfo( report, html );
        LookAndFeel.formatSplitStatusText( "IP: "+Tools.getLocalIP(), report, html);

        if( streamManager.getStreamCount()==0 ) { // No streams so no raw data
            report.append(TEXT_DEFAULT).append("Raw Age: ").append(TEXT_WARN).append("No streams yet.").append(html ? "<br>" : "\r\n");
            return;
        }
        // Streams exist so there should be raw data?
        long age = Tools.getLastRawAge( Path.of(tinylogPath) );
        String rawAge;
        if( age == -1 ){
            rawAge = "!! Raw Age: No file yet!";
        }else{
            var convert = TimeTools.convertPeriodToString(age, TimeUnit.SECONDS);
            var max = TimeTools.convertPeriodToString(maxRawAge, TimeUnit.SECONDS);
            rawAge = (age>maxRawAge?"!! ":"") + "Raw Age: "+convert+"["+max+"]";
        }
        LookAndFeel.formatSplitStatusText( rawAge, report, html );
    }
    private void addMemoryInfo( StringBuilder report, boolean html ){
        double totalMem = (double)Runtime.getRuntime().totalMemory();
        double usedMem = totalMem-Runtime.getRuntime().freeMemory();

        totalMem = Tools.roundDouble(totalMem/(1024.0*1024.0),1);
        usedMem = Tools.roundDouble(usedMem/(1024.0*1024.0),1);

        var memStatus = (usedMem > 70 ? "!! " : "") + "Memory: " + usedMem + "/" + totalMem + "MB";
        LookAndFeel.formatSplitStatusText(memStatus, report, html);
    }
    /**
     * Prepare the status information about the Database Manager
     * @param report This holds all other info
     * @param html Whether to format in html
     */
    private void addDbmStatus(StringBuilder report, boolean html) {
        report.append( LookAndFeel.formatStatusTitle("Databases",html) );
        if ( !dbManager.hasDatabases()){
            report.append("None yet").append( html ? "<br>" : "\r\n" );
            return;
        }

        for( String line : dbManager.getStatus().split("\r\n") ){
            LookAndFeel.formatSplitStatusText( line.replace(Paths.storage()+File.separator,""),report,"->", html );
        }
    }
    /**
     * Get a status update of the various queues, mostly to verify that they are
     * empty
     * 
     * @return The status update showing the amount of items in the queues
     */
    public String getQueueSizes() {
        StringJoiner join = new StringJoiner("\r\n", "", "\r\n");
        join.add("Data buffer: " + Core.queueSize() + " in receive buffer and "+ labelWorker.getWaitingQueueSize()+" waiting...");

        if (emailWorker != null)
            join.add("Email backlog: " + emailWorker.getRetryQueueSize() );
        return join.toString();
    }
    /*  COMMANDABLE INTERFACE */
    @Override
    public String replyToCommand(Datagram d) {
        return switch (d.cmd()) {
            case "?" -> doHelpCmd();
            case "st" -> getStatus(d.asHtml());
            default -> "Unknown command";
        };
    }

    public static String doHelpCmd() {
        return "st -> Get a status overview of the whole system";
    }
    /**
     * Part of the commandable interface but not used here
     * @param wr The writable to remove
     * @return True if the writable was found and removed
     */
    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
    /* END OF COMMANDABLE */
    public static void main(String[] args) {

        DAS das = new DAS();

        if( das.telnet == null ){  // If no telnet server was created
            das.addTelnetServer(); // Do it anyway
        }
        das.startAll(); // Run all the processes that need to happen after initialization of the components

        Logger.info("Dcafs "+version+" boot finished!");
    }
}