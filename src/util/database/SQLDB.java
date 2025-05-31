package util.database;

import das.Paths;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.vals.Rtvals;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class SQLDB extends Database implements TableInsert{

    private String tableRequest;   // The query to request table information
    private String columnRequest;  // The query to request column information
    private String address;
    private String dbName;         // The name of the database
    protected HashMap<String, SqlTable> tables = new HashMap<>(); // Map of the tables
    protected ArrayList<String> views = new ArrayList<>();        // List if views
    protected ArrayList<String> simpleQueries = new ArrayList<>();// Simple query buffer

    protected Connection con = null; // Database connection
    protected int insertErrors=0;

    DBTYPE type = DBTYPE.UNKNOWN;                                   // The type of database this object connects to

    public enum DBTYPE {UNKNOWN, MSSQL, MYSQL, MARIADB, POSTGRESQL} // Supported types

    boolean busySimple =false;   // Busy with the executing the simple queries
    boolean busyPrepared =false; // Busy with executing the prepared statement queries

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1); // Scheduler for queries
    Path workPath;
    private int mariadbTimePrecision = 0;
    protected boolean doInserts = true;
    protected int connectionAttempts = 0;
    protected String quotePrefix = "", quoteSuffix = "";

    protected SQLDB(){
        tableRequest="";
        columnRequest="";
        dbName="";
    }

    public SQLDB(String type, String address, String dbName, String user, String pass) {
        this.type = parseType(type);
        this.address = address;
        this.dbName = dbName;
        this.user = user;
        this.pass = pass;

        prepareIRL();
    }

    public static Optional<SQLDB> createFromXML(String id) {
        var db = new SQLDB();
        var dig = Paths.digInSettings("databases").digDown("server", "id", id);
        if (dig.isInvalid())
            return Optional.empty();
        db.readFromXML(dig);
        return Optional.of(db);
    }

    private void prepareIRL() {
        switch (type) {
            case MYSQL -> {
                irl = "jdbc:mysql://" + address + (address.contains(":") ? "/" : ":3306/") + dbName;
                tableRequest = "SHOW FULL TABLES;";
                columnRequest = "SHOW COLUMNS FROM ";
                quotePrefix = "`";
                quoteSuffix = "`";
            }
            case MSSQL -> {
                irl = "jdbc:sqlserver://" + address + (dbName.isBlank() ? "" : ";database=" + dbName);
                tableRequest = "SELECT * FROM information_schema.tables";
                columnRequest = "";
                quotePrefix = "[";
                quoteSuffix = "]";
            }
            case MARIADB -> {
                irl = "jdbc:mariadb://" + address + (address.contains(":") ? "/" : ":3306/") + dbName;
                tableRequest = "SHOW FULL TABLES;";
                columnRequest = "SHOW COLUMNS FROM ";
                quotePrefix = "`";
                quoteSuffix = "`";
            }
            case POSTGRESQL -> {
                irl = "jdbc:postgresql://" + address + (address.contains(":") ? "/" : ":5432/") + dbName;
                tableRequest = "SELECT table_name FROM information_schema.tables WHERE NOT table_schema='pg_catalog'AND NOT table_schema='information_schema';";
                columnRequest = "SELECT column_name,udt_name,is_nullable,is_identity FROM information_schema.columns WHERE table_name=";
                quotePrefix = "\"";
                quoteSuffix = "\"";
            }
            default -> {
                tableRequest = "";
                columnRequest = "";
                Logger.error(id + " (db) -> Unknown database type: " + type);
            }
        }
    }

    public boolean isInValidType() {
        return type == DBTYPE.UNKNOWN;
    }

    private static DBTYPE parseType(String type) {
        return switch (type.toLowerCase()) {
            case "mysql" -> DBTYPE.MYSQL;
            case "mariadb" -> DBTYPE.MARIADB;
            case "mssql" -> DBTYPE.MSSQL;
            case "postgresql" -> DBTYPE.POSTGRESQL;
            default -> DBTYPE.UNKNOWN;
        };
    }
    public void setWorkPath(Path p){
        workPath=p;
    }
    /* ************************************************************************************************* */
    public String toString(){
        var age = getTimeSinceLastInsert();
        var time = TimeTools.convertPeriodToString(age, TimeUnit.SECONDS);
        var join = new StringJoiner("");
        if( age > maxInsertAge || getRecordsCount()>maxQueries || insertErrors!=0  || state == STATE.ACCESS_DENIED )
            join.add("!! ");
        join.add(id+" : ");
        join.add(type.toString().toLowerCase()+"@"+getTitle());
        join.add(" -> ");
        join.add(getRecordsCount()+"/"+maxQueries);
        join.add(" ["+time+"]");

        if( insertErrors!=0)
            join.add( " errors:"+insertErrors);

        if( !isValid(3)) {
            if( state == STATE.ACCESS_DENIED){
                join.add(" (Access Denied)");
            }else {
                join.add(" (NC)");
            }
        }
        return  join.toString();
    }

    /**
     * Get the title of the database
     * @return The title
     */
    public String getTitle(){
        if( isMySQL() || type==DBTYPE.POSTGRESQL)
            return irl.substring(irl.lastIndexOf("/")+1);
        return irl.substring(irl.lastIndexOf("=")+1);
    }

    /**
     * Check if the database is a MySQL variant (mysql,mariadb)
     * @return True if so
     */
    public boolean isMySQL(){
        return type==DBTYPE.MARIADB || type==DBTYPE.MYSQL;
    }
    public void clearErrors(){
        insertErrors=0;
    }
    /* **************************************************************************************************/
    /**
     * Open the connection to the database
     * @param force If true the current connection will be closed (if any)
     * @return True if successful
     */
    @Override
    public boolean connect(boolean force) {
        if (state == STATE.CON_BUSY)
            return true;
        state = STATE.CON_BUSY;
        CompletableFuture.supplyAsync(() -> {
            try {
                if (con != null) {
                    if (!con.isValid(2) || force) {
                        con.close();
                    } else {
                        state = STATE.HAS_CON;
                        return true;
                    }
                }
            } catch (SQLException e) {
                Logger.error(id + " (db) -> " + e.getMessage());
            }

            if (!loadClass(type))
                return false;

            return attemptConnection();
        }, scheduler).thenAccept(result -> {
            // Callback after task is completed
            if (result) {
                Logger.info(id + " -> Connection made");
                doOnConnectionMade();
            } else {
                connectionAttempts++;
                // Dump after x attempts?
                if (connectionAttempts % 5 == 0) {
                    tables.values().forEach(t -> t.dumpData("", Paths.storage().resolve("db").resolve(id + "_" + t.getTableName() + ".csv")));

                }
            }
        });
        return true;
    }

    private boolean attemptConnection() {
        try {
            state = STATE.CON_BUSY;
            con = DriverManager.getConnection(irl, user, pass);
            Logger.info(id + "(db) -> Connection: " + irl + con);
            state = STATE.HAS_CON; // Connection established, change state
        } catch (SQLException ex) {
            String message = ex.getMessage();
            int eol = message.indexOf("\n");
            if (eol != -1)
                message = message.substring(0, eol);
            if (LookAndFeel.isNthAttempt(connectionAttempts))
                Logger.error(id + "(db) -> (" + connectionAttempts + ") Failed to make connection to database! " + message);
            if (!message.toLowerCase().contains("access denied")) {
                state = STATE.NEED_CON; // Failed to connect, set state to try again
            } else {
                state = STATE.ACCESS_DENIED;
                Logger.error(id + "(dbm) -> Access denied, no use retrying.");
            }
            return false;
        }
        return true;
    }
    protected void doOnConnectionMade() {
        connectionAttempts = 0;
        if (!tablesRetrieved) {
            if (getCurrentTables(false)) { // No use trying to create content of reading tables failed
                lastError = createContent(true);
            }
            tablesRetrieved = true;
        }
        if (busySimple)
            prepSimple();
        if (busyPrepared)
            scheduler.submit(new DoPrepared());
    }

    /**
     * Load the driver associated with the used database
     *
     * @param type The database type (mssql,mysql,mariadb and so on)
     * @return The result of loading the class
     */
    private static boolean loadClass(DBTYPE type) {
        try {
            switch (type) { // Set the class according to the database, might not be needed anymore
                case MSSQL -> Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                case MYSQL, MARIADB -> Class.forName("org.mariadb.jdbc.Driver");
                case POSTGRESQL -> Class.forName("org.postgresql.Driver");
            }
            return true;
        } catch (ClassNotFoundException ex) {
            Logger.error("(db) -> Driver issue, probably sqlite",ex);
            return false;
        }
    }
    @Override
    public boolean disconnect() {
        if (con != null) { // No use trying to disconnect a connection that doesn't exist
            try {
                if (con.isClosed()) // if no active connection, just return
                    return false;
                con.close(); // try closing it
                Logger.info(id+" -> Closed connection");
                return true;
            } catch (SQLException e) { // Failed to close it somehow
                Logger.error(id + " (db)-> " + e.getMessage());
            }
        }
        return false;
    }
    /**
     * Check whether the database is valid with a given timeout in seconds
     *
     * @param timeout The timeout in seconds
     * @return True if the connection is still valid
     */
    public boolean isValid(int timeout) {
        try {
            if (con == null) { // no con = not valid
                return false;
            }
            return con.isValid(timeout);
        } catch (SQLException e) {
            Logger.error(id+"(db) -> SQLException when checking if valid: "+e.getMessage());
            return false;
        }
    }

    /**
     * Get the amount of records currently buffered
     * @return Get the amount of bufferd records
     */
    public int getRecordsCount() {
        return tables.values().stream().mapToInt(SqlTable::getRecordCount).sum() + simpleQueries.size();
    }
    /**
     * Check if there are buffered records
     * @return True if there's at least one buffered record
     */
    public boolean hasRecords() {
        return !simpleQueries.isEmpty() || tables.values().stream().anyMatch(t -> t.getRecordCount() != 0);
    }
    /**
     * Get the SQLiteTable associated with the given id
     *
     * @param id The id of the table
     * @return The table if found or an empty optional if not
     */
    public Optional<SqlTable> getTable(String id) {
        if (tables.get(id) == null && !tablesRetrieved) { // No table info
            tablesRetrieved = true;
            getCurrentTables(false);
        }
        return Optional.ofNullable(tables.get(id));
    }

    /**
     * Get a stream of all the table object linked to this database
     * @return The stream of tables
     */
    public Stream<SqlTable> getTables() {
        return tables.values().stream();
    }
    /**
     * Get all the information on the local tables
     * @param eol The eol sequence to use
     * @return The gathered information
     */
    public String getTableInfo(String eol){
        StringJoiner j = new StringJoiner(eol,"Info about "+id+eol,"");
        j.setEmptyValue("No tables stored.");
        tables.values().forEach( table -> j.add(table.getInfo()));
        return j.toString();
    }
    /**
     * Check which tables are currently in the database and add them to this object
     * @param clear Clear the stored databases first
     * @return True if successful
     */
    @Override
    public boolean getCurrentTables(boolean clear) {

        requestTablesSetupFromDb(clear);

        // Get the column information....
        for( SqlTable table :tables.values() ){
            if( table.hasColumns() ){// Don't overwrite existing info
                Logger.debug(id + "(db) -> The table " + table.getTableName() + " has already been setup, not adding the columns");
                continue;
            }
            if( type == DBTYPE.MSSQL ){
                Logger.debug(id + "(db) -> The table " + table.getTableName() + " is inside a MSSQL DB, no column request query.");
                continue;
            }
            requestColumns(table);
        }     
        return true;
    }

    private void requestTablesSetupFromDb(boolean clear){
        if( clear ) // If clear is set, first clear existing tables
            tables.clear();

        readTable(con,tableRequest, rs -> {
            try {
                ResultSetMetaData rsmd = rs.getMetaData(); // get the metadata
                String tableName = rs.getString(1); // the tablename is in the first column

                String tableType="base table"; // only interested in the base tables, not system ones
                if( rsmd.getColumnCount()==2)
                    tableType=rs.getString(2);

                if( tableType.equalsIgnoreCase("base table") && !tableName.startsWith("sym_") ){ // ignore symmetricsDS tables and don't overwrite
                    SqlTable table = tables.get(tableName); // look for it in the stored tables
                    if (table == null || !table.hasColumns()) { // if not found, it's new
                        table = new SqlTable(tableName); // so create it
                        requestColumns(table);
                        table.flagAsReadFromDB();
                        tables.put(tableName, table); //and add it to the hashmap
                    } else { // Found so not new and probably ready? (check column matching?)
                        table.flagAsReady();
                    }
                    table.toggleServer();
                    Logger.debug(id+" (db) -> Found: "+tableName+" -> "+tableType);
                }
            } catch (SQLException e) {
                Logger.error(id+"(db) -> Error during table read: "+e.getErrorCode());
                Logger.error(e.getMessage());
            }
        });
    }
    private void requestColumns(SqlTable table){
        String tblName = table.getTableName();
        if( type == DBTYPE.POSTGRESQL )
            tblName = "'"+tblName+"'";
        final boolean[] first = {true};//so it can be uses in lambda
        table.flagAsReadFromDB();
        readTable(con, columnRequest + quotePrefix + tblName + quoteSuffix + ";", rs -> {
            try {
                String name = rs.getString(1);
                String colType = rs.getString(2).toLowerCase();

                SqlTableFab.addColumnToTable(table, colType, name);
            } catch (SQLException e) {
                Logger.error(id+"(db) -> Error during table read: "+e.getErrorCode());
            }
        });
        Logger.info(table.getInfo());
    }
    /**
     * Actually create all the tables
     * @param keepConnection True if the connection should be kept open afterward
     * @return Empty string if all ok, otherwise error message
     */
    public String createContent(boolean keepConnection){

        if( isValid(5) ){
            createTables(); // Create the tables
            createViews();  // Create the views

            try {
                if( !con.getAutoCommit())
                    con.commit();
                StringJoiner errors = new StringJoiner("\r\n");
                tables.values().stream().filter(x-> !x.lastError.isEmpty()).forEach( x -> errors.add(x.getLastError(true)));
                return errors.toString();
            } catch (SQLException e) {
                Logger.error(e);
                return e.getMessage();
            }
        }
        return "No valid connection";
    }

    private void createTables() {
        tables.values().forEach(tbl -> {
            Logger.debug(id + "(db) -> Checking to create " + tbl.getTableName() + " read from?" + tbl.isReadFromDB());
            if (!tbl.isServer() && !tbl.hasColumns()) {
                lastError = "Note: Tried to create a table without columns, not allowed in SQLite.";
            } else if (tbl.isReadFromXML()) {
                try (Statement stmt = con.createStatement()) {
                    var create = tbl.create();
                    if (type == DBTYPE.MARIADB) // Mariadb allows setting the precision of datetime
                        create = create.replace("DATETIME", "DATETIME(" + mariadbTimePrecision + ")");
                    stmt.execute(create);
                    if (tables.get(tbl.getTableName()) != null && tbl.hasIfNotExists()) {
                        Logger.warn(id + "(db) -> Already a table with the name " + tbl.getTableName() + " nothing done because 'IF NOT EXISTS'.");
                    }
                    tbl.flagAsReady(); // Created on database, so flag as ready
                } catch (SQLException e) {
                    Logger.error(id + "(db) -> Failed to create table with: " + tbl.create());
                    Logger.error(e.getMessage());
                    tbl.setLastError(e.getMessage() + " when creating " + tbl.getTableName() + " for " + id);
                }
            } else if (tbl.isReadFromDB()) {
                var fab = Paths.fabInSettings("databases");
                fab.selectOrAddChildAsParent(tbl.isServer() ? "server" : "sqlite", "id", id);
                tbl.writeToXml(fab, true);
                tbl.flagAsReady(); // Wrote to xml, so flag as ready
                Logger.debug(id + "(db) -> Not creating " + tbl.getTableName() + " because already read from database...");
            }
        });
    }

    private void createViews() {
        views.forEach(
                x -> {
                    try (Statement stmt = con.createStatement()) {
                        stmt.execute(x);
                    } catch (SQLException e) {
                        Logger.error(e.getMessage());
                    }
                });
    }
    /**
     * Write a select query and then retrieve the content of a single column from it base on the (case-insensitive) name
     * @param query The query to execute
     * @return ArrayList with the data or an empty list if nothing found/something went wrong
     */
    public Optional<List<List<Object>>> doSelect(String query, boolean includeNames ){

        if( !isValid(1) && !connect(false) ){
            Logger.error( id+"(db) -> Couldn't connect to database: "+id);
            return Optional.empty();
        }
        var data = new ArrayList<List<Object>>();
        try( Statement stmt = con.createStatement() ){
            ResultSet rs = stmt.executeQuery(query);
            int cols = rs.getMetaData().getColumnCount();
            if( includeNames ){
                var record = new ArrayList<>();
                for( int a=1;a<cols;a++ ){
                    record.add(rs.getMetaData().getColumnName(a));
                }
                data.add(record);
            }
            while( rs.next() ){
                var record = new ArrayList<>();
                for( int a=0;a<cols;a++ ){
                    record.add(rs.getObject(a+1));
                }
                data.add( record );
            }
        } catch (SQLException e) {
            Logger.error(id+"(db) -> Error running query: "+query+" -> "+e.getErrorCode());
        }
        return Optional.of(data);
    }
    public Optional<TableInsert> getTableInsert( String tableid ){
        int index = tableid.indexOf(":");
        if( index != -1)
            tableid=tableid.substring(0,index);
        if( tables.get(tableid)==null)
            return Optional.empty();
        return Optional.of( this );
    }

    public void setDoInserts(boolean doInserts) {
        this.doInserts = doInserts;
        Logger.info(id() + "(dbm) -> Allowing inserts? " + doInserts);
    }

    public void buildStores(Rtvals rtvals) {
        tables.values().stream()
                .filter(table -> table.noValidStore(""))
                .forEach(table -> {
                    SqlTableFab.buildTableStore(table, rtvals);
                    var allow = table.getAllowInsertFlag();
                    var res = rtvals.addFlagVal(allow); // Only adds if absent, returns og flagval if it was absent
                    if (res != null)
                        table.setAllowInsertsFlag(res);
                });
    }
    public synchronized boolean insertStore(String[] dbInsert ) {
        if (!doInserts)  // Global insert disable
            return true;

        if( !id.equalsIgnoreCase(dbInsert[0])) {
            Logger.warn(id + "(db) -> Mismatch between insert id and current db -> " + id + " vs " + dbInsert[0]);
            return false;
        }
        var tableOpt = getTable(dbInsert[1]);
        if (tableOpt.isEmpty()) {
            Logger.error(id+"(db) ->  No such table <"+dbInsert[1]+">");
            lastError= "No such table <"+dbInsert[1]+"> in the database.";
            insertErrors++;
            return false;
        }

        var table = tableOpt.get();
        if (table.insertsNotAllowed()) // Table level disable
            return false;

        if (!table.isReady() && isValid(2)) {
            Logger.error(id+"(db) ->  No such table <"+dbInsert[1]+"> in the database.");
            lastError= "No such table <"+dbInsert[1]+"> in the database.";
            insertErrors++;
            return false;
        }

        if (!hasRecords())
            firstPrepStamp = Instant.now().toEpochMilli();

        if (table.insertFromStore("")) {
            if (tables.values().stream().mapToInt(SqlTable::getRecordCount).sum() > maxQueries && isValid(3))
                flushPrepared();
            return true;
        }else{
            Logger.error(id+"(db) -> Build insert failed for <"+dbInsert[1]+">");
        }
        return false;
    }
    public synchronized boolean fillPrep( String table, String[] data){
        if (!hasRecords())
            firstPrepStamp = Instant.now().toEpochMilli();

        if( getTable(table).isEmpty() ){
            Logger.error(id+"(db) ->  No such table "+table);
            return false;
        }
        if( getTable(table).map( t -> !t.isReadFromDB()).orElse(false) ){
            Logger.error(id+"(db) ->  No such table <"+table+"> in the database.");
            return false;
        }
        if (getTable(table).map(t -> t.parsePrep("",data)).orElse(false)) {
            if(tables.values().stream().mapToInt(SqlTable::getRecordCount).sum() > maxQueries)
                flushPrepared();
            return true;
        }else{
            Logger.error(id+"(db) -> Build insert failed for "+table);
        }
        return false;
    }
    /**
     * Flush all the buffers to the database
     */
    public void flushAll(){
        flushSimple();
        flushPrepared();
    }

    /**
     * Flush the simple queries to the database
     */
    protected void flushSimple(){
        if (!busySimple) {
            busySimple = true;
            if (isValid(1)) {
                prepSimple();
            } else {
                if (state != STATE.ACCESS_DENIED) // No use trying
                    flagNeedcon();
            }
        }
    }

    protected void prepSimple() {
        var temp = new ArrayList<String>();
        while (!simpleQueries.isEmpty())
            temp.add(simpleQueries.remove(0));
        scheduler.submit(new DoSimple(temp));
    }
    /**
     * Flush all the PreparedStatements to the database
     */
    protected void flushPrepared(){
        if (!busyPrepared ) { // Don't ask for another flush when one is being done
            busyPrepared = true; // Set Flag so we know the buffer is being flushed

            if(isValid(1)) {
                scheduler.submit(new DoPrepared());
            }else{
                if( state != STATE.ACCESS_DENIED ) // No use trying
                    flagNeedcon();
            }
        }
    }
    /**
     * Read the setup of a database server from the settings.xml
     * @param dbDig The digger pointing at the element
     * @return The database object made with the info
     */
    public boolean readFromXML(XMLdigger dbDig) {

        if (dbDig == null)
            return false;

        var id = dbDig.attr("id","");

        if( !dbDig.hasPeek("db") || !dbDig.hasPeek("address") ) {
            Logger.error(id+"(dbm) -> No db and/or address node found");
            return false;
        }
        dbDig.digDown("db");
        dbName = dbDig.value("");                  // The name of the database
        user = dbDig.attr("user", "");            // A username with writing rights
        pass = dbDig.attr("pass", "");            // The password for the earlier defined username
        dbDig.goUp();

        address = dbDig.peekAt("address").value("");            // Set the address of the server on which the DB runs (either hostname or IP)

        type = parseType(dbDig.attr("type", ""));
        prepareIRL();

        id(id);
        maxInsertAge = TimeTools.parsePeriodStringToSeconds(dbDig.peekAt("maxinsertage").value("1h"));

        /* Setup */
        if (dbDig.hasPeek("flush"))
            readFlushSetup(dbDig.currentTrusted());

        // How many seconds before the connection is considered idle (and closed)
        idleTime = (int) TimeTools.parsePeriodStringToSeconds(dbDig.peekAt("idleclose").value("5m"));

        /* Tables */
        for (var table : dbDig.digOut("table")) {
            SqlTableFab.buildSqlTable(table, true).ifPresent(t -> {
                t.toggleServer();
                tables.put(t.getTableName(), t);
            });
        }
        // Mariadb
        if (dbDig.hasPeek("timeprecision"))
            mariadbTimePrecision = dbDig.peekAt("timeprecision").value(0);

        return true;
    }

    public void reloadDatabase() {
        disconnect(); // Start with disconnecting

        var dig = Paths.digInSettings("databases").digDown("server", "id", id);
        tables.clear();
        views.clear();
        readFromXML(dig);

        connect(false); // Reconnect
    }
    public void flagNeedcon() {
        if (state != STATE.HAS_CON)
            state = STATE.NEED_CON;
    }
    /**
     * Write the setup of this database to the settings.xml
     * @param fab A fab pointing to the databases node
     */
    public void writeToXml(XMLfab fab){

        String flush = TimeTools.convertPeriodToString(maxAge, TimeUnit.SECONDS);
        String address = irl.substring( irl.indexOf("//")+2,irl.lastIndexOf("/"));

        String idle = "-1";
        if( idleTime!=-1)
            idle = TimeTools.convertPeriodToString(maxAge, TimeUnit.SECONDS);

        fab.selectOrAddChildAsParent("server","id", id.isEmpty()?"remote":id).attr("type",type.toString().toLowerCase())
                .alterChild("db",dbName).attr("user",user).attr("pass",pass)
                .alterChild("flush").attr("age",flush).attr("batchsize",maxQueries)
                .alterChild("idleclose",idle)
                .alterChild("address",address);

        fab.build();
    }

    /**
     * Write the table information into the database node
     * @param fab The XMLfab to use
     * @param tableName The name of the table to write or * to write all
     * @return The amount of tables written
     */
    public int writeTableToXml( XMLfab fab, String tableName ){
        int cnt=0;
        for( var table : tables.values()) {
            if ((table.getTableName().equalsIgnoreCase(tableName) || tableName.equals("*"))
                    && fab.hasChild("table", "name", table.getTableName()).isEmpty()) {
                    table.writeToXml( fab, false);
                    cnt++;
            }
        }
        if( cnt!=0)
            fab.build();
        return cnt;
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
            case -1 -> Logger.error("No such prepStatement found in " + id + ":" + table);
            case -2 -> Logger.error("No such table (" + table + ") found in " + id);
        }
        return res;
    }
    /**
     * Check and update the current state of the database
     * @param secondsPassed How many seconds passed since the last check (interval so fixed)
     */
    public void checkState( int secondsPassed ) {
        switch (state) {
            case FLUSH_REQ -> doFlushReq(); // Required a flush
            case HAS_CON -> doHas_Con(secondsPassed); // If we have a connection, but not using it
            case IDLE -> doIdle(); // Database is idle
            case NEED_CON -> connect(false); // Needs a connection
            case ACCESS_DENIED -> Logger.info("Todo ACCESS DENIED");
            case CON_BUSY -> Logger.debug(id + " -> Trying to connect...");
            default -> Logger.warn(id + "(db) -> Unknown state: " + state);
        }
    }

    private void doFlushReq() {
        if (!simpleQueries.isEmpty()) {
            Logger.info(id + "(db) -> Flushing simple");
            flushSimple();
        }
        if (tables.values().stream().anyMatch(t -> t.getRecordCount() != 0)) { // If any table has records
            flushPrepared();
        }
        if (isValid(1)) { // If not valid, flush didn't work either
            state = STATE.HAS_CON; // If valid, the state is has_connection
        } else {
            state = STATE.NEED_CON; // If invalid, need a connection
        }
    }

    private void doHas_Con(int secondsPassed) {
        if (!hasRecords()) {
            idleCount += secondsPassed;
            if (idleCount > idleTime && idleTime > 0) {
                Logger.info(id() + "(id) -> Connection closed because idle: " + id + " for " + TimeTools.convertPeriodToString(idleCount, TimeUnit.SECONDS) + " > " +
                        TimeTools.convertPeriodToString(idleTime, TimeUnit.SECONDS));
                disconnect();
                state = STATE.IDLE;
            }
        } else {
            Logger.debug(id + "(id) -> Waiting for max age to pass...");
            if (!simpleQueries.isEmpty()) {
                long age = (Instant.now().toEpochMilli() - firstSimpleStamp) / 1000;
                Logger.debug(id + "(id) -> Age of simple: " + age + "s versus max: " + maxAge);
                if (age > maxAge) {
                    state = STATE.FLUSH_REQ;
                    Logger.info(id + "(id) -> Requesting simple flush because of age");
                }
            }
            if (tables.values().stream().anyMatch(t -> t.getRecordCount() != 0)) {
                long age = (Instant.now().toEpochMilli() - firstPrepStamp) / 1000;
                Logger.debug(id + "(id) -> Age of prepared: " + age + "s");
                if (age > maxAge) {
                    state = STATE.FLUSH_REQ;
                }
            }
            idleCount = 0;
        }
    }

    private void doIdle() {
        if (hasRecords()) { // If it has records
            if (isValid(2000)) { // try to connect but don't reconnect if connected
                state = STATE.HAS_CON; // connected
            } else {
                state = STATE.NEED_CON; // connection failed
            }
        }
    }

    // @SuppressWarnings("SQLInjection")
    protected void readTable(Connection con, String query, Consumer<ResultSet> action) {
        Logger.debug("Running query: " + query);
        try (Statement stmt = con.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            if (rs == null)
                return;

            while (rs.next())
                action.accept(rs);

        } catch (SQLException e) {
            Logger.error(id() + " -> Error during table read: " + e.getErrorCode());
        }
    }
    /**
     * Execute the simple queries one by one
     */
    public class DoSimple implements Runnable{

        ArrayList<String> temp = new ArrayList<>(); // Array to store the queries that are being executed
        int total; // Counter for the amount of queries to be executed

        public DoSimple( ArrayList<String> data){
            temp.addAll(data); // Add all the given queries to the temp arraylist
            total=temp.size(); // Set total to the amount of queries received
        }
        @Override
        public void run() {
            // Process the regular queries
            if( !doBatchRun(temp) ) { // if still not ok, do rest one by one
                for( int a = 0; a<temp.size();a++){
                    try (PreparedStatement pst = con.prepareStatement(temp.get(a))){
                        pst.execute();
                        if (!con.getAutoCommit())
                            con.commit();
                        temp.set(a,""); // if executed, clear the entry in the temp arraylist
                    } catch (SQLException e) {
                        insertErrors++;
                        if( e.getErrorCode()==19){
                            temp.set(a,""); // Don't care about this error, clear the entry
                        }else if( e.getMessage().contains("syntax error") || e.getMessage().contains("no such ")
                                || e.getMessage().contains("has no ") || e.getMessage().contains("PRIMARY KEY constraint")){ //sql errors
                            Logger.tag("SQL").error(id()+"\t"+temp.get(a)); // Bad query, log it
                            Logger.info(id+" (db)-> Error code: "+e.getErrorCode());
                            temp.set(a,"");
                        }else{
                            Logger.error(temp.get(a)+" -> "+e.getMessage());
                            Logger.info(id+" (db)-> Error code: "+e.getErrorCode());
                            temp.set(a,"");
                        }
                    }
                }
            }

            try {
                temp.removeIf(String::isEmpty); // Clear the empty entries
                if( !temp.isEmpty()){ // If there are entries left
                    firstSimpleStamp = Instant.now().toEpochMilli(); // alter the timestamp
                    simpleQueries.addAll(temp); // Add the leftover queries back to the buffer
                }
            }catch(ConcurrentModificationException e){
                Logger.error(id+" (db) -> Clean failed");
            }catch (Exception e){
                Logger.error(e);
            }

            Logger.debug(id+" (db) -> Queries done: "+(total-temp.size())+"/"+total);
            busySimple =false;
        }
    }

    /**
     * Execute queries in a batch
     * @param queries The queries to execute
     * @return True if all ok
     */
    private boolean doBatchRun(ArrayList<String> queries){

        boolean batchOk=false;
        int retries=2;

        while(retries>=1 &&!batchOk) {
            try (var ps = con.createStatement()){
                boolean auto = con.getAutoCommit();
                for (String q : queries) {
                    ps.addBatch(q);
                }
                if (auto)
                    con.setAutoCommit(false);
                try {
                    var res = ps.executeBatch();
                    con.commit();
                    for (int a = 0; a < res.length; a++)
                        queries.set(a, "");
                    batchOk = true;
                } catch (BatchUpdateException g) {
                    insertErrors++;
                    var result = g.getUpdateCounts();
                    boolean first = false;
                    for (int x = 0; x < result.length; x++) {
                        if (result[x] > 0 || result[x] == Statement.SUCCESS_NO_INFO) {
                            Logger.debug("Removing because " + result[x] + " -> " + queries.get(x));
                            queries.set(x, "");
                        } else if (!first) {
                            first = true;
                            Logger.error("Error: " + g.getMessage());
                            Logger.error(id() + " -> Bad query: " + queries.get(x));
                            queries.set(x, "");
                        }
                    }
                }
                con.setAutoCommit(auto);
            } catch (SQLException e) {
                Logger.error(e);
                batchOk = false;
            }
            retries--;
        }
        return batchOk;
    }

    /**
     * Execute the stored prepared statements
     */
    private class DoPrepared implements Runnable{

        @Override
        public void run() {
            // Process the prepared statements
            tables.values().stream().filter( SqlTable::hasRecords ).forEach(
                    sqlTable -> sqlTable.getPreps().keySet().forEach(
                            id ->
                            {
                                boolean ok=true;
                                int errors=0;
                                while (sqlTable.hasRecords(id) && ok) { //do again if new queries arrived or part of the batch failed
                                    int cnt;
                                    try (PreparedStatement ps = con.prepareStatement(sqlTable.getPreparedStatement(id))) {
                                        cnt = sqlTable.fillStatement(id, ps);
                                        if( cnt > 0 ){
                                            ps.executeBatch();
                                            sqlTable.clearTempRecords(id);
                                            if (!con.getAutoCommit())
                                                con.commit();
                                            if( hasRecords() ) // if there are records left, the timestamp should be reset
                                                firstSimpleStamp = Instant.now().toEpochMilli();
                                        }else{
                                            ok=false;
                                        }
                                    } catch (BatchUpdateException e) {
                                        // One or multiple queries in the batch failed
                                        Logger.error(id()+" (db)-> Batch error, clearing batched:"+e.getMessage());
                                        insertErrors++;
                                        Logger.error(e.getErrorCode());
                                        Logger.error(id() + " (db)-> Removed bad records: " + sqlTable.removeBadRecordsAndQueue(id, e.getLargeUpdateCounts())); // just drop the data or try one by one?
                                    } catch (SQLException e) {
                                        errors = doSqlException(e, sqlTable, errors);
                                        if (errors > 10)
                                            ok = false;
                                    } catch (Exception e) {
                                        insertErrors++;
                                        Logger.error(id() + "(db) -> General Error:" + e);
                                        Logger.error(e);
                                        ok = false;
                                    }
                                }
                            }
                    )
            );
            // If there are still records left, this becomes the next first
            if (tables.values().stream().anyMatch(t -> t.getRecordCount() != 0))
                firstPrepStamp = Instant.now().toEpochMilli();

            busyPrepared = false; // Finished work, so reset the flag
        }

        private int doSqlException(SQLException e, SqlTable sqlTable, int errors) {
            errors++;
            insertErrors++;
            if (e.getMessage().contains("no such table") && SQLDB.this instanceof SQLiteDB) {
                Logger.error(id() + "(db) -> Got no such sqlite table error, trying to resolve...");
                try {
                    var c = con.createStatement();
                    c.execute(sqlTable.create());
                    if (!con.getAutoCommit())
                        con.commit();
                } catch (SQLException f) {
                    Logger.error(f);
                }
            }
            if (errors > 10) {
                Logger.error(id() + " -(db)> 10x SQL Error:" + e.getMessage());
                Logger.error("Errorcode:" + e.getErrorCode());
                if (e.getErrorCode() == 8 && !sqlTable.server) {
                    connect(true);
                    Logger.warn(id() + "->Errorcode 8 detected for sqlite, trying to reconnect.");
                } else {
                    sqlTable.dumpData(id, workPath);
                }
            }
            return errors;
        }
    }
}