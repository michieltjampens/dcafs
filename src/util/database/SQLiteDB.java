package util.database;

import das.Paths;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.StringJoiner;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SQLiteDB extends SQLDB{

    static final String GET_SQLITE_TABLES = "SELECT name FROM sqlite_master WHERE type ='table' AND name NOT LIKE 'sqlite_%';";
   
    private final Path dbPath;

    /* Variables related to the rollover */
    private DateTimeFormatter format = null;
    private String oriFormat="";
    private ScheduledFuture<?> rollOverFuture;
    private ChronoUnit rollUnit = ChronoUnit.FOREVER;
    private int rollCount = 0;

    private LocalDateTime rolloverTimestamp;

    private String currentForm = "";
    /**
     * Create an instance of a database with rollover
     * @param dbPath Path to the database
     */
    public SQLiteDB( String id, Path dbPath ) {

        this.id=id;
        var p = dbPath.toString();
        this.dbPath = Path.of( p.endsWith(".sqlite")?p:p+".sqlite");
        workPath=dbPath.getParent();

        try {
            Files.createDirectories(Path.of(getPath()).getParent());
        } catch (IOException e) {
            Logger.error( id() + " -> Issue trying to create "+dbPath.getParent().toString()+" -> "+e.getMessage());
        } catch (NullPointerException e ){
            Logger.error( id() + " -> Issue trying to create db, path is null");
        }
    }

    /* ************************************************************************************************************** */
    public static SQLiteDB createDB( String id, Path db ){
        return new SQLiteDB( id, db );
    }

    public void reloadDatabase() {
        disconnect(); // Start with disconnecting
        tables.clear();
        views.clear();

        var dig = Paths.digInSettings("databases").digDown("sqlite", "id", id);
        readFromXML(dig);

        connect(false); // Reconnect
    }

    public boolean readFromXML(XMLdigger dig) {

        String id = dig.attr("id","");
        var path = dig.attr("path",null,workPath);

        if( path.isEmpty() )
            return false;

        maxInsertAge = TimeTools.parsePeriodStringToSeconds(dig.peekAt("maxinsertage").value("1h"));

        /* RollOver */
        if( dig.peekAt("rollover").hasValidPeek() ){
            String period = dig.attr("period","").toLowerCase();
            var secs = 0;
            if (!period.isEmpty())
                secs = (int) TimeTools.parsePeriodStringToSeconds(period);
            String format = dig.value("");

            if (secs != 0) {
                Logger.info("Setting rollover: " + format + " " + period);
                setRollOver(format, secs, ChronoUnit.SECONDS);
            }else{
                Logger.error(id+" -> Bad Rollover given" );
                return false;
            }
        }
        /* Setup */
        dig.peekAndUse("flush").ifPresent(this::readFlushSetup);

        // How many seconds before the connection is considered idle (and closed)
        idleTime = (int) TimeTools.parsePeriodStringToSeconds(dig.attr("idleclose", "5m"));

        /* Views */
        dig.peekOut("view").forEach( view -> {
            String name = view.getAttribute("name");
            String query = view.getTextContent();
            views.add("CREATE VIEW  IF NOT EXISTS " + name + " AS " + query);
        });

        /* Tables */
        dig.digOut("table").forEach(table -> SqlTableFab.buildSqlTable(table, false).ifPresent(t -> tables.put(t.getTableName(), t)));

        /* Create the content */
        getCurrentTables(false);
        lastError = createContent(false);
        return true;
    }
    /* ************************************************************************************************************** */
    @Override
    public String toString(){
        var time = getTimeSinceLastInsert();
        var join = new StringJoiner("");
        if( time > maxInsertAge || getRecordsCount()>maxQueries || insertErrors!=0 )
            join.add("!! ");
        join.add( id +" : ");
        join.add( getPath() +" -> " +getRecordsCount()+"/"+maxQueries);
        if( rollUnit != ChronoUnit.FOREVER){
            if( rollOverFuture==null ){
                join.add( " -> No proper rollover determined..." );
            }else {
                join.add(" ->  rollover in " + TimeTools.convertPeriodToString(rollOverFuture.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS));
            }
        }
        join.add(" [" + TimeTools.convertPeriodToString(time, TimeUnit.SECONDS) + "]");
        if( insertErrors!=0)
            join.add( " errors:"+insertErrors);
        join.add(isValid(1)?"":" (NC)");
        return join.toString();
    }
    /**
     * Get the current path this database can be found at
     * @return The path to the database as a string
     */
    public String getPath(){

        //without rollover
        if( currentForm.isEmpty() )
            return dbPath.toString();

        String path = dbPath.toString();

        //with rollover and on a specific position
        if( path.contains("{rollover}"))
            return path.replace("{rollover}", currentForm);

        // with rollover but on default position
        return path.replace(".sqlite", currentForm+".sqlite");
    }
    /**
     * Open the connection to the database
     * @param force If true the current connection will be closed (if any)
     * @return True if successful
     */
    @Override
    public boolean connect(boolean force){  
        if( state==STATE.CON_BUSY)
            return false;      
        try {
            if( con != null ){ // if a connection has been made earlier
                if( !con.isValid(2) || force){ // but no longer valid or a reconnect is wanted
                    con.close(); // close the connection
                }else{   
                    return true; // connection still valid and no reconnect is needed, so return true
                }
            }
        } catch (SQLException e) {
            Logger.error(e.getMessage());
        }
        
        try {
            Class.forName("org.sqlite.JDBC"); 
		} catch (ClassNotFoundException ex) {
            Logger.error( id() + " -> Driver issue with SQLite!" );
        	return false;
        }

        // Make sure to use the proper path
        String irl = "jdbc:sqlite:"+getPath();

        try{
            state = STATE.CON_BUSY;
            con = DriverManager.getConnection(irl, user, pass);
            con.setAutoCommit(false); //Changed
            Logger.info( id() + " -> Connection: "+con+ " irl:"+irl);
            state=STATE.HAS_CON;
    	} catch ( SQLException ex) {              
            String message = ex.getMessage();
            int eol = message.indexOf("\n");
            if( eol != -1 )
                message = message.substring(0,eol);          
            Logger.error( id() + " -> Failed to make connection to SQLite database! "+message );
            state=STATE.NEED_CON;
            return false;
        }    
    	return true;
    }

    /**
     * Check which tables currently exist in the database and add them to this object
     */
    @Override
    public boolean getCurrentTables(boolean clear){
        if( !connect(false))
            return false;

        if( clear )
            tables.clear();

        readTable(con, GET_SQLITE_TABLES, rs -> {
            try {
                String tableName = rs.getString(1);
                if( tables.get(tableName)==null) {//don't overwrite
                    var t = new SqlTable(tableName);
                    t.flagAsReadFromDB();
                    readTableInfo(t);
                    tables.put(tableName, t);
                } else {
                    tables.get(tableName).flagAsReady();
                }
            } catch (SQLException e) {
                Logger.error( id() + " -> Error during table read: "+e.getErrorCode());
            }
        });
        for( SqlTable table : tables.values() ){
            if (table.hasColumns()) {// Don't overwrite existing info
                Logger.debug(id() + " -> The table " + table.getTableName() + " has already been setup, not adding the columns");
                continue;
            }
            readTableInfo(table);
        }
        return true;
    }
    private void readTableInfo(SqlTable table){
        readTable(con, "PRAGMA table_info(\"" + table.getTableName() + "\");", rs -> {
            try {
                var columnName = rs.getString(rs.findColumn("name"));
                var type = rs.getString(rs.findColumn("type"));

                var col = SqlTableFab.addColumnToTable(table, type.toLowerCase(), columnName);
                if (col != null) {
                    col.notnull = rs.getBoolean(rs.findColumn("notnull"));
                    col.primary = rs.getBoolean(rs.findColumn("pk"));
                }
            } catch (SQLException e) {
                Logger.error("Error during read of table info for:" + table.getTableName(), e);
            }
        });
    }
    /**
     * Write the settings from the database to xml
     * @param fab A Xmlfab pointing to the databases node as root
     */
    public void writeToXml( XMLfab fab ){
        String flush = TimeTools.convertPeriodToString(maxAge, TimeUnit.SECONDS);
        String idle = "-1";
        if( idleTime!=-1)
            idle = TimeTools.convertPeriodToString(maxAge, TimeUnit.SECONDS);

        fab.selectOrAddChildAsParent("sqlite","id", id).attr("path",dbPath.toString());
        if( hasRollOver() )
            fab.alterChild("rollover", oriFormat).attr("period", TimeTools.convertPeriodToString(rollCount, TimeUnit.SECONDS));
        fab.alterChild("flush").attr("age",flush).attr("batchsize",maxQueries)
           .alterChild("idleclose",idle)
           .build();
    }

    /* **************************************************************************************************/
    /**
     * Set the rollover for this sqlite
     * @param dateFormat The format part of the filename
     * @param rollCount The amount of unit
     * @param unit The unit for the rollover, options: MIN,HOUR,DAY,WEEK,MONTH,YEAR
     * @return This database
     */
    public SQLiteDB setRollOver( String dateFormat, int rollCount, ChronoUnit unit ){

        if( unit == null) {
            Logger.warn(id+" -> Bad rollover given");
            return null;
        }
        if (unit == ChronoUnit.SECONDS && rollCount % 60 == 0) {
            unit = ChronoUnit.MINUTES;
            rollCount /= 60;
        }

        try {
            format = DateTimeFormatter.ofPattern(dateFormat);
        }catch(IllegalArgumentException e){
            Logger.error(id+" -> "+e.getMessage());
            return null;
        }
        this.rollCount=rollCount;
        rollUnit=unit;
        oriFormat=dateFormat;

        rolloverTimestamp = LocalDateTime.now(ZoneOffset.UTC).withNano(0);

        rolloverTimestamp = TimeTools.applyTimestampRollover(true,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
        Logger.info(id+" -> Current rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
        updateFileName(rolloverTimestamp);

        rolloverTimestamp = TimeTools.applyTimestampRollover(false,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
        Logger.info(id+" -> Next rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));

        long next = Duration.between(LocalDateTime.now(ZoneOffset.UTC),rolloverTimestamp).toMillis();
        if( next > 1000) {
            rollOverFuture = scheduler.schedule(new DoRollOver(true), next, TimeUnit.MILLISECONDS);
            Logger.info(id + " -> Next rollover in " + TimeTools.convertPeriodToString(rollOverFuture.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS));
        }else{
            Logger.error(id+" -> Bad rollover for "+rollCount+" counts and unit "+unit);
        }
        return this;
    }

    /**
     * Cancel the next rollover events
     */
    public void cancelRollOver(){
        if( rollOverFuture!=null)
            rollOverFuture.cancel(true);
    }
    public void forceRollover(){
        scheduler.submit(new DoRollOver(false));
    }
    @Override
    public boolean disconnect(){
        if (con != null) {
            try {
                if (con.isClosed())
                    return false;

                if( hasRecords() ){
                    Logger.info(id()+" has queries, flushing those first");
                    state = STATE.FLUSH_REQ;
                    try {
                        checkState(0);
                    } catch (Exception e) {
                        Logger.error(e);
                    }
                    int max=50;
                    while(hasRecords()&& max>=0){
                        try {
                            Thread.sleep(200);
                            max--;
                        } catch (InterruptedException e) {
                            Logger.error(e);
                        }
                    }
                }

                con.close();
                Logger.info(id+" -> Closed connection");
                return true;
            } catch (SQLException e) {
                Logger.error(e);
                return false;
            }
        }
        return false;
    }
    /**
     * Update the filename of the database currently used
     */
    public void updateFileName(LocalDateTime ldt){
        if( format==null)
            return;
        try{
            if( ldt!=null ){
                currentForm = ldt.format(format);
            }
        }catch( java.time.temporal.UnsupportedTemporalTypeException f ){
            Logger.error( id() + " -> Format given is unsupported! Database creation cancelled.");
            return;
        }
        //with rollover and on a specific position
        if( dbPath.toString().contains("{rollover}")) {
            Logger.info(id+" -> Updated filename: " + dbPath.toString().replace("{rollover}", currentForm));
        }else{
            Logger.info(id+" -> Updated filename: " + dbPath.toString().replace(".sqlite", currentForm+".sqlite"));
        }
    }
    /**
     * Check if this SQLite uses rollover
     * @return True if it has rollover
     */
    public boolean hasRollOver(){
        return rollUnit != ChronoUnit.FOREVER;
    }
    /**
     * After executing any queries still in the buffer: - closes the current
     * connection to the database - creates a new file and connects to it. - creates
     * the tables - if the rollover is every x months, schedule the next one
     */
    private class DoRollOver implements Runnable {
        boolean renew;

        public DoRollOver( boolean renew ){
            this.renew=renew;
        }
        @Override
        public void run() {
            Logger.info(id+" -> Doing rollover");
            if (!isValid(1)) {
                connect(false);
            }
            if(renew)
                updateFileName(rolloverTimestamp); // first update the filename

            getTables().forEach(SqlTable::flagAsReadFromXML); // Otherwise they won't get generated

            disconnect();// then disconnect, this also flushes the queries first
            Logger.info("Disconnected to connect to new one...");
            connect(false);
            if( !createContent(true).isEmpty() ){
                Logger.error(id+" -> Failed to create the database");
            }
            if( renew ) {
                var d = rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER);
                rolloverTimestamp = TimeTools.applyTimestampRollover(false,rolloverTimestamp,rollCount,rollUnit);// figure out the next rollover moment
                Logger.info(id+" -> Current " +d +" and next rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
                // Schedule the next rollover
                long next = Duration.between(LocalDateTime.now(ZoneOffset.UTC), rolloverTimestamp).toMillis();
                rollOverFuture = scheduler.schedule(new DoRollOver(true), next, TimeUnit.MILLISECONDS);
            }
        }
    }
    /**
     *
     * @param table  The table to insert into
     * @param values The values to insert
     * @return -2=No such table, -1=No such statement,0=bad amount of values,1=ok
     */
    public synchronized int addDirectInsert(String table, Object... values) {
        if( values == null){
            Logger.error(id+" -> Tried to insert a null in "+table);
            return -3;
        }
        if (!hasRecords())
            firstPrepStamp = Instant.now().toEpochMilli();

        int res = getTable(table).map(t -> t.doInsert(values)).orElse(-2);
        switch (res) {
            case 1 -> {
                if (tables.values().stream().mapToInt(SqlTable::getRecordCount).sum() > maxQueries)
                    flushPrepared();
            }
            case 0 -> Logger.error("Bad amount of values for insert into " + id + ":" + table);
            case -1 -> Logger.error("No such prepstatement found in " + id + ":" + table);
            case -2 -> Logger.error("No such table (" + table + ") found in " + id);
        }
        return res;
    }
}