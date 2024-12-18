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
import io.stream.StreamManager;
import util.gis.Waypoints;
import util.tools.FileMonitor;
import io.stream.tcp.TcpServer;
import io.telnet.TelnetCodes;
import io.telnet.TelnetServer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.commons.lang3.SystemUtils;
import org.tinylog.Logger;
import org.tinylog.provider.ProviderRegistry;
import util.data.RealtimeValues;
import util.database.*;
import util.task.TaskManagerPool;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

public class DAS implements Commandable{

    private static final String version = "2.13.0";

    private final Path settingsPath; // Path to the settings.xml
    private String workPath; // Path to the working dir of dcafs
    private String tinylogPath;
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

    private Waypoints waypoints; // Pool of waypoints

    /* Managers & Pools */
    private DatabaseManager dbManager; // Manager for the database interaction
    private MqttPool mqttPool; // Pool for the mqtt connections
    private TaskManagerPool taskManagerPool; // Pool that holds the tasksmanagers
    private CollectorPool collectorPool; // Pool of the collector objects (mainly filecollectors)
    private CommandPool commandPool; // Pool that holds the references to all the commandables
    private boolean bootOK = false; // Flag to show if booting went ok
    String sdReason = "Unwanted shutdown."; // Reason for shutdown of das, default is unwanted

    private final BlockingQueue<Datagram> dQueue = new LinkedBlockingQueue<>(); // Queue for datagrams for the labelworker
    boolean rebootOnShutDown = false; // Flag to set to know if the device should be rebooted on dcafs shutdown (linux only)
    private InterruptPins isrs; // Manager for working with IO pins
    private PathPool pathPool;
    private MatrixClient matrixClient; // Client for working with matrix connections
    private FileMonitor fileMonitor; // Monitor files for changes

    private Instant lastCheck; // Timestamp of the last clock check, to know if the clock was changed after das booted
    private long maxRawAge=3600; // 1 hour default for the age of raw data writes status to turn red

    /* Status Checks */
    private int statusBadChecks = 0;
    private long statusCheckInterval =3600;
    private String statusEmail="";
    private String statusMatrixRoom="";

    /* Threading */
    private final EventLoopGroup nettyGroup = new NioEventLoopGroup(); // Single group so telnet,trans and StreamManager can share it

    public DAS() {
        // Note: Don't use TinyLog yet!
        // Determine working dir based on the classpath
        var classPath = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        classPath = classPath.replace("%20"," ");
        System.out.println("Checking for workpath at : "+classPath);

        if( classPath.startsWith("/") && SystemUtils.IS_OS_WINDOWS ) // for some reason this gets prepended
            classPath=classPath.substring(1);

        Path p=Path.of(classPath); // Convert to path

        if (classPath.endsWith("classes/")) { //meaning from ide
            p = p.getParent(); // get parent to get out of the classes
        }

        workPath = p.getParent().toString();
        if( workPath.matches(".*lib$")) { // Meaning used as a lib
            workPath = Path.of(workPath).getParent().toString();
        }else if( workPath.contains("repository")){
            workPath = Path.of("").toAbsolutePath().toString();
        }
        settingsPath = Path.of(workPath, "settings.xml");
        tinylogPath = workPath;
        if( Files.exists(settingsPath)){
            var digger = XMLdigger.goIn(settingsPath,"dcafs","settings");
            tinylogPath = digger.peekAt("tinylog").value(workPath);
        }

        System.out.println("Workpath lib: "+workPath); // System because logger isn't initiated yet
        System.out.println("Workpath tinylog: "+tinylogPath); // System because logger isn't initiated yet
        if( System.getProperty("tinylog.directory") == null ) { // don't overwrite this
            // Re set the paths for the file writers to use the same path as the rest of the program
            System.setProperty("tinylog.directory", tinylogPath); // Set work path as system property
        }


        /* *************************** FROM HERE ON TINYLOG CAN BE USED ****************************************** */

        if (Files.notExists(settingsPath)) { // Check if the settings.xml exists
            Logger.warn("No Settings.xml file found, creating new one. Searched path: "
                    + settingsPath.toFile().getAbsolutePath());
            createXML(); // doesn't exist so create it
        }
        Logger.info("Used settingspath: "+settingsPath);

        if( !XMLtools.checkXML(settingsPath).isEmpty() ){// Check if the reading resulted in a proper xml
            Logger.error("Issue in current settings.xml, aborting: " + settingsPath);
            addTelnetServer(); // Even if settings.xml is bad, start the telnet server anyway to inform user
            return;
        }
        var digger = XMLdigger.goIn(settingsPath,"dcafs"); // Use digger to go through settings.xml

        if( digger.hasPeek("settings") ){
            digger.digDown("settings");
            var age = digger.peekAt("maxrawage").value("1h");
            maxRawAge = TimeTools.parsePeriodStringToSeconds(age);
            if( maxRawAge==0){
                Logger.error("Invalid maxrawage value: "+age+" defaulting to 1 hour.");
            }
            if( digger.hasPeek("statuscheck") ){
                digger.digDown("statuscheck");
                var check = digger.peekAt("checkinterval").value("1h");
                statusCheckInterval = TimeTools.parsePeriodStringToSeconds(check);
                statusEmail = digger.peekAt("email").value(statusEmail);
                statusMatrixRoom = digger.peekAt("matrix").value(statusMatrixRoom);
                digger.goUp(); // Back from statuscheck to settings
            }
            digger.goUp(); // Back from settings to root
        }
        Logger.info("Program booting");

        /* CommandPool */
        commandPool = new CommandPool( workPath );
        addCommandable(this,"st"); // add the commands found in this file

        /* RealtimeValues */
        rtvals = new RealtimeValues( settingsPath, dQueue );
        addCommandable(rtvals,"flags;fv;reals;real;rv;texts;tv;int;integer;text;flag");
        addCommandable(rtvals,"rtval","rtvals");
        addCommandable(rtvals,"stop");

        /* Database manager */
        dbManager = new DatabaseManager(workPath,rtvals);
        addCommandable(dbManager,"dbm","myd");

        /* Label Worker */
        addLabelWorker();

        /* StreamManager */
        addStreamManager();

        /* TransServer */
        if( digger.hasPeek("transserver")) // Check if trans is in xml
            addTransServer(); // and if so, set it up

        /* Hardware: I2C & GPIO */
        if( digger.hasPeek("gpios") ){
            Logger.info("Reading interrupt gpio's from settings.xml");
            isrs = new InterruptPins(dQueue,settingsPath,rtvals);
            addCommandable(isrs,"gpios","isr");
        }
        addI2CWorker();

        /* Forwards */
        pathPool = new PathPool(dQueue, settingsPath, rtvals, nettyGroup,dbManager);
        addCommandable(pathPool,"paths","path","pf","paths");
        addCommandable(pathPool, ""); // empty cmd is used to stop data requests

        /* Waypoints */
        waypoints = new Waypoints(settingsPath,nettyGroup,rtvals,dQueue);
        addCommandable(waypoints,"wpts");

        /* Collectors */
        if( digger.hasPeek("collectors")) {
            collectorPool = new CollectorPool(settingsPath.getParent(), dQueue, nettyGroup, rtvals);
            addCommandable(collectorPool, "fc");
            addCommandable(collectorPool, "mc");
        }else{
            Logger.info("No collectors defined in xml");
        }
        /* EmailWorker */
        if( digger.hasPeek("email") ) {
            addEmailWorker();
        }else{
            statusEmail="";
            Logger.info( "No email defined in xml");
        }
        /* File monitor */
        if( digger.hasPeek("monitor") ) {
            fileMonitor = new FileMonitor(settingsPath.getParent(), dQueue);
            addCommandable(fileMonitor,"fm","fms");
        }
        /* Matrix */
        if( digger.hasPeek("matrix") ){
            Logger.info("Reading Matrix info from settings.xml");
            matrixClient = new MatrixClient( dQueue, rtvals, settingsPath );
            addCommandable(matrixClient,"matrix");
        }else{
            statusMatrixRoom="";
            Logger.info("No matrix settings");
        }

        /* MQTT worker */
        addMqttPool();

        /* TaskManagerPool */
        addTaskManager();

        /* Check if the system clock was changed */
        nettyGroup.schedule(this::checkClock,5,TimeUnit.MINUTES);
        bootOK = true;

        /* Build the stores in the sqltables */
        dbManager.buildStores(rtvals);

        /* Telnet */
        addTelnetServer();

        attachShutDownHook();
    }

    /**
     * Get the version number of dcafs
     * @return The version nr in format x.y.z
     */
    public String getVersion(){return version;}

    /**
     * Get the path to parent folder of the settings.xml
     * @return Path of the parent folder of the settings.xml
     */
    public Path getWorkPath(){
        return Path.of(workPath);
    }

    /**
     * Get the path of the settings.xml
     * @return Path of the settings.xml
     */
    public Path getSettingsPath(){
        return settingsPath;
    }

    /**
     * Get the period that dcafs has been active
     * @return The active period in a readable string
     */
    public String getUptime() {
        return TimeTools.convertPeriodtoString(Duration.between(bootupTimestamp, LocalDateTime.now()).getSeconds(),
                TimeUnit.SECONDS);
    }
    /**
     Compares the stored timestamp with the current one because this was used at startup to determine rollover of
     databases etc.
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
    private void createXML() {
       XMLfab.withRoot(settingsPath, "dcafs")
                .addParentToRoot("settings")
                    .addChild("maxrawage").content("1h")
                .addParentToRoot("streams")
                    .comment("Defining the various streams that need to be read")
                .build();
    }
    /* **************************************  C O M M A N D P O O L ********************************************/
    /**
     * Add a commandable to the CommandPool, this is the same as adding commands to dcafs
     * @param id The unique start command (so whatever is in front of the : )
     * @param cmd The commandable to add
     */
    public void addCommandable( Commandable cmd, String... id  ){
        commandPool.addCommandable(String.join(";",id),cmd);
    }
    /* ***************************************  T A S K M A N A G E R ********************************************/
    /**
     * Create a TaskManager to handle tasklist scripts
     */
    private void addTaskManager() {

        taskManagerPool = new TaskManagerPool(workPath, rtvals, commandPool);

        if (streamManager != null)
            taskManagerPool.setStreamPool(streamManager);
        if (emailWorker != null)
            taskManagerPool.setEmailSending(emailWorker.getSender());
        taskManagerPool.readFromXML();
        addCommandable(taskManagerPool,"tm");
    }
    /* ******************************************  S T R E A M P O O L ***********************************************/
    /**
     * Adds the StreamManager that will manage the various streams (tcp,udp,serial)
     */
    private void addStreamManager() {

       streamManager = new StreamManager(dQueue, nettyGroup,rtvals);
       addCommandable(streamManager,"ss","streams"); // general commands
       addCommandable(streamManager,"s_","h_");      // sending data to a stream
       addCommandable(streamManager,"raw","stream"); // getting data from a stream
       addCommandable(streamManager,""); // stop sending data

       streamManager.readSettingsFromXML(settingsPath);
       streamManager.getStreamIDs().forEach( id -> addCommandable(streamManager,id) );
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
            labelWorker = new LabelWorker(dQueue);
        labelWorker.setCommandReq(commandPool);
    }
    public BlockingQueue<Datagram> getDataQueue(){
        return dQueue;
    }
    /* ***************************************** M Q T T ******************************************************** */

    /**
     * Add the pool that handles the mqtt connections
     */
    private void addMqttPool(){
        mqttPool = new MqttPool(settingsPath,rtvals,dQueue);
        addCommandable( mqttPool,"mqtt");
    }
    /* *****************************************  T R A N S S E R V E R ***************************************** */
    /**
     * Adds the TransServer listening on the given port
     */
    private void addTransServer() {

        Logger.info("Adding TransServer");
        trans = new TcpServer(settingsPath, nettyGroup);
        trans.setDataQueue(dQueue); // Uses the same queue for datagrams like StreamManager etc

        addCommandable(trans,"ts","trans"); // Add ts/trans commands to CommandPool
    }

    /* **********************************  E M A I L W O R K E R *********************************************/
    /**
     * Adds an EmailWorker, this handles sending and receiving emails
     */
    private void addEmailWorker() {
        Logger.info("Adding EmailWorker");
        emailWorker = new EmailWorker(settingsPath, dQueue);
        addCommandable(emailWorker,"email");
        commandPool.setEmailSender(emailWorker);
    }
    /* ***************************************  T E L N E T S E R V E R ******************************************/
    /**
     * Create the telnet server
     */
    private void addTelnetServer() {
        if( bootOK) {
            telnet = new TelnetServer(dQueue, settingsPath, nettyGroup);
            addCommandable(telnet, "telnet", "nb");
        }else{
            telnet = new TelnetServer(null, settingsPath, nettyGroup);
        }
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
        i2cWorker = new I2CWorker(settingsPath,nettyGroup,rtvals,dQueue);
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
        Runtime.getRuntime().addShutdownHook(new Thread("shutdownHook") {
            @Override
            public void run() {
                Logger.info("Dcafs shutting down!");
                telnet.replyToCommand( "telnet","broadcast,error,Dcafs shutting down!",null,false);

                if( matrixClient!=null)
                    matrixClient.broadcast("Shutting down!");

                // Run shutdown tasks
                taskManagerPool.startTaskset("shutdown");

                // SQLite & SQLDB
                Logger.info("Flushing database buffers");
                dbManager.flushAll();

                // Collectors
                if( collectorPool!=null)
                    collectorPool.flushAll();

                // Try to send email...
                if (emailWorker != null) {
                    Logger.info("Informing admin");
                    String r = commandPool.getShutdownReason();
                    sdReason = r.isEmpty()?sdReason:r;
                    emailWorker.sendEmail( Email.toAdminAbout(telnet.getTitle() + " shutting down.").content("Reason: " + sdReason) );
                }
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
                try {
                    ProviderRegistry.getLoggingProvider().shutdown(); // Shutdown tinylog
                } catch (InterruptedException e) {
                    Logger.error(e);
                    Thread.currentThread().interrupt();
                }
                // On linux it's possible to have the device reboot if das is shutdown for some reason
                if (rebootOnShutDown) {
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
            }
        });
        Logger.info("Shut Down Hook Attached.");
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
            if( !errors.isEmpty()){
                telnet.addMessage("Errors during TaskManager parsing:\r\n"+errors);
            }
        }
        // Matrix
        if( matrixClient != null ){
            Logger.info("Trying to login to matrix");
            matrixClient.login();
        }
        // Start the checks?
        if( (!statusMatrixRoom.isEmpty()||(emailWorker != null && !statusEmail.isEmpty())) && statusCheckInterval >0) // No use checking if we can't report on it or if it's disabled
            nettyGroup.schedule(this::checkStatus,20,TimeUnit.MINUTES); // First check, half hour after startup

        Logger.info("Finished startAll");
    }
    /* **************************** * S T A T U S S T U F F *********************************************************/
    private void checkStatus(){
        var status = getStatus(true);

        if( status.contains("!!")){
            if( statusBadChecks ==0){
                if( !statusEmail.isEmpty() )
                    emailWorker.sendEmail( Email.to(statusEmail).subject("[Issue] Status report").content(status));
                if( !statusMatrixRoom.isEmpty() ){
                    var header = "<b>Issue found!</b><br>";
                    for( var line : status.split("<br>")){
                        if( line.startsWith( "!!") ) {
                            dQueue.add(Datagram.system("matrix:" + statusMatrixRoom + ",txt," + header + line));
                            header=""; // Only add header once
                        }
                    }

                }
            }
            nettyGroup.schedule(this::checkStatus,30,TimeUnit.MINUTES); // Reschedule, but earlier
            statusBadChecks++;
            if( statusBadChecks == 13) { // Every 6 hours, send a reminder?
                statusBadChecks =0;
            }
            return;
        }else if( statusBadChecks !=0 ){
            if( !statusEmail.isEmpty())
                emailWorker.sendEmail( Email.to(statusEmail).subject("[Resolved] Status report").content(status));
            if( !statusMatrixRoom.isEmpty() ) {
                dQueue.add(Datagram.system("matrix:" + statusMatrixRoom + ",txt,Issues resolved"));
            }
        }
        nettyGroup.schedule(this::checkStatus, statusCheckInterval,TimeUnit.SECONDS);
    }
    /**
     * Request a status message regarding the streams, databases, buffers etc
     * 
     * @param html Whether the status needs to be given in telnet or html
     * @return A status message
     */
    public String getStatus(boolean html) {
        final String TEXT_GREEN = html?"":TelnetCodes.TEXT_GREEN;
        final String TEXT_CYAN = html?"":TelnetCodes.TEXT_CYAN;
        final String UNDERLINE_OFF = html?"":TelnetCodes.UNDERLINE_OFF;
        final String TEXT_DEFAULT = html?"":TelnetCodes.TEXT_DEFAULT;
        final String TEXT_RED = html?"":TelnetCodes.TEXT_RED;
        final String TEXT_ORANGE = html?"":TelnetCodes.TEXT_ORANGE;

        var b = new StringJoiner("");

        double totalMem = (double)Runtime.getRuntime().totalMemory();
        double usedMem = totalMem-Runtime.getRuntime().freeMemory();

        totalMem = Tools.roundDouble(totalMem/(1024.0*1024.0),1);
        usedMem = Tools.roundDouble(usedMem/(1024.0*1024.0),1);

        if (html) {
            b.add("<b><u>DCAFS Status at ").add(TimeTools.formatNow("HH:mm:ss")).add(".</u></b><br><br>");
        } else {
            b.add(TEXT_GREEN).add("DCAFS Status at ").add(TimeTools.formatNow("HH:mm:ss")).add("\r\n\r\n")
                    .add(UNDERLINE_OFF);
        }
        b.add(TEXT_DEFAULT).add("DCAFS Version: ").add(TEXT_GREEN).add(version).add(" (jvm:").add(System.getProperty("java.version")).add(")\r\n");
        b.add(TEXT_DEFAULT).add("Uptime: ").add(TEXT_GREEN).add(getUptime()).add("\r\n");
        b.add(TEXT_DEFAULT).add(usedMem>70?"!! ":"").add("Memory: ")
                .add(usedMem>70?TEXT_RED:TEXT_GREEN).add(String.valueOf(usedMem)).add("/").add(String.valueOf(totalMem)).add("MB\r\n");
        b.add(TEXT_DEFAULT).add("IP: ").add(TEXT_GREEN).add(Tools.getLocalIP()).add("\r\n");

        long age = Tools.getLastRawAge(Path.of(tinylogPath));
        if( age == -1 ){
            b.add(TEXT_DEFAULT).add("!! Raw Age: ").add(TEXT_RED).add("No file yet!");
        }else{
            var convert = TimeTools.convertPeriodtoString(age,TimeUnit.SECONDS);
            var max = TimeTools.convertPeriodtoString(maxRawAge,TimeUnit.SECONDS);

            if( age > maxRawAge && streamManager.getStreamCount()==0){
                b.add(TEXT_DEFAULT).add("Raw Age: ").add(TEXT_ORANGE).add("No streams yet.");
            }else{
                b.add(TEXT_DEFAULT).add( (age>maxRawAge?"!! ":"")).add("Raw Age: ");
                b.add((age > maxRawAge ? TEXT_RED : TEXT_GREEN)).add(convert).add(" [").add(max).add("]");
            }
        }
        b.add(UNDERLINE_OFF).add("\r\n");

        if (html) {
            b.add("<br><b>Streams</b><br>");
        } else {
            b.add(TEXT_DEFAULT).add(TEXT_CYAN).add("\r\n").add("Streams").add("\r\n").add(UNDERLINE_OFF).add(TEXT_DEFAULT);
        }
        if (streamManager != null) {
            if (streamManager.getStreamCount() == 0) {
                b.add("No streams defined (yet)").add("\r\n");
            } else {
                for (String s : streamManager.getStatus().split("\r\n")) {
                    if (s.startsWith("!!")) {
                        b.add(TEXT_RED).add(s).add(TEXT_DEFAULT).add(UNDERLINE_OFF);
                    } else {
                        b.add(s);
                    }
                    b.add("\r\n");
                }
            }
        }
        if( i2cWorker != null && i2cWorker.getDeviceCount()!=0){
            if (html) {
                b.add("<br><b>Devices</b><br>");
            } else {
                b.add(TEXT_CYAN).add("\r\n").add("Devices").add("\r\n").add(UNDERLINE_OFF).add(TEXT_DEFAULT);
            }
            for( String s : i2cWorker.getStatus("\r\n").split("\r\n") ){
                if (s.startsWith("!!") || s.endsWith("false")) {
                    b.add(TEXT_RED).add(s).add(TEXT_DEFAULT).add(UNDERLINE_OFF);
                } else {
                    b.add(s);
                }
                b.add("\r\n");
            }
        }
        if( isrs != null ){
            if (html) {
                b.add("<br><b>GPIO Isr</b><br>");
            } else {
                b.add(TEXT_CYAN).add("\r\n").add("GPIO Isr").add("\r\n").add(UNDERLINE_OFF).add(TEXT_DEFAULT);
            }
            b.add(isrs.getStatus("\r\n"));
            b.add("\r\n");
        }
        if (mqttPool != null && !mqttPool.getMqttWorkerIDs().isEmpty()) {
            if (html) {
                b.add("<br><b>MQTT</b><br>");
            } else {
                b.add(TEXT_DEFAULT).add(TEXT_CYAN).add("\r\n").add("MQTT").add("\r\n").add(UNDERLINE_OFF).add(TEXT_DEFAULT);
            }
            b.add(mqttPool.getMqttBrokersInfo()).add("\r\n");
        }

        try {
            if (html) {
                b.add("<br><b>Buffers</b><br>");
            } else {
                b.add(TEXT_CYAN).add("\r\nBuffers\r\n").add(TEXT_DEFAULT)
                        .add(UNDERLINE_OFF);
            }
            b.add(getQueueSizes());
        } catch (java.lang.NullPointerException e) {
            Logger.error("Error reading buffers " + e.getMessage());
        }
        /* DATABASES */
        if (html) {
            b.add("<br><b>Databases</b><br>");
        } else {
            b.add(TelnetCodes.TEXT_CYAN)
                    .add("\r\nDatabases\r\n")
                    .add(TEXT_DEFAULT).add(UNDERLINE_OFF);
        }
        if (dbManager.hasDatabases()) {
            for( String l : dbManager.getStatus().split("\r\n") ){
                if( l.startsWith( "!! ")){
                    var before = l.substring(2, l.indexOf("->")+2);
                    var after = l.substring(l.indexOf("->")+2);
                    l = TEXT_RED+"!!"+TEXT_DEFAULT
                            +before+TEXT_RED+after+TEXT_DEFAULT;
                }else if( l.contains("(NC)")){
                    var before = l.substring(0, l.indexOf("->")+2);
                    var after = l.substring(l.indexOf("->")+2);
                    l = before+TEXT_ORANGE+after+TEXT_DEFAULT;
                }
                b.add(l.replace(workPath+File.separator,"")).add("\r\n");
            }
        }else{
            b.add("None yet\r\n");
        }
        if( html ){
            return b.toString().replace("\r\n","<br>");
        }
        return b.toString().replace("false", TEXT_RED + "false" + TEXT_GREEN);
    }
    /**
     * Get a status update of the various queues, mostly to verify that they are
     * empty
     * 
     * @return The status update showing the amount of items in the queues
     */
    public String getQueueSizes() {
        StringJoiner join = new StringJoiner("\r\n", "", "\r\n");
        join.add("Data buffer: " + dQueue.size() + " in receive buffer and "+ labelWorker.getWaitingQueueSize()+" waiting...");

        if (emailWorker != null)
            join.add("Email backlog: " + emailWorker.getRetryQueueSize() );
        return join.toString();
    }
    /*  COMMANDABLE INTERFACE */
    @Override
    public String replyToCommand( String cmd, String args, Writable wr, boolean html) {
        if( cmd.equalsIgnoreCase("st"))
            return getStatus(html);

        return "Unknown command";
    }
    public String payloadCommand( String cmd, String args, Object payload){
        return "! No such cmds in "+cmd;
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