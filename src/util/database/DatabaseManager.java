package util.database;

import das.Commandable;
import io.Writable;
import io.collector.StoreCollector;
import io.forward.*;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import util.data.RealtimeValues;
import org.tinylog.Logger;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DatabaseManager implements QueryWriting, Commandable {
    private final Map<String, SQLiteDB> lites = new HashMap<>();        // Store the SQLite databases
    private final Map<String, SQLDB> sqls = new HashMap<>();            // Store the SQL databases
    private static final int CHECK_INTERVAL=5;                          // How often to check the state
    private final ScheduledExecutorService scheduler;                   // Scheduler for the request data action
    private final String workPath;                                            // dcafs workpath
    private final Path settingsPath;                                          // Path to dcafs settings.xml
    private final RealtimeValues rtvals;                                 // Reference to the realtime data
    private static final String[] DBTYPES = {"mssql","mysql","mariadb","sqlite","postgresql"};

    /**
     * Create a manager that uses its own scheduler
     */
    public DatabaseManager( String workPath, RealtimeValues rtvals) {
        this.workPath=workPath;
        this.rtvals=rtvals;

        settingsPath = Path.of(workPath,"settings.xml");
        scheduler = Executors.newScheduledThreadPool(1); // create a scheduler with a single thread

        readFromXML();  // Read the settings from the xml
    }

    /**
     * Adds a SQLiteDB to the manager, this adds: - Check if the oldest query in the
     * buffer is older than the max age - Takes care of roll over - Adds the
     * listener
     * 
     * @param id The name to reference this database with
     * @param db The SQLiteDB
     * @return The database added
     */
    public SQLiteDB addSQLiteDB(String id, SQLiteDB db) {
        if (lites.size() == 0 && sqls.size() == 0)
            scheduler.scheduleAtFixedRate(new CheckQueryAge(), 2L*CHECK_INTERVAL, CHECK_INTERVAL, TimeUnit.SECONDS);

        SQLiteDB old = lites.get(id);
        if (old != null) // Check if we are overwriting an older version, and if so cancel any rollover
            old.cancelRollOver();

        lites.put(id, db);
        return db;
    }

    /**
     * Add a SQL database to the manager and uf the first one, enable the scheduled state checking
     * @param id The id of the database
     * @param db The database object
     * @return The added database
     */
    public SQLDB addSQLDB(String id, SQLDB db) {
        if (lites.size() == 0 && sqls.size() == 0)
            scheduler.scheduleAtFixedRate(new CheckQueryAge(), 2L*CHECK_INTERVAL, CHECK_INTERVAL, TimeUnit.SECONDS);
        sqls.put(id, db);
        return db;
    }
    /**
     * Check if the manager has a database with the given id
     * 
     * @return True if a database was found
     */
    public boolean hasDB(String id) {
        return lites.get(id) != null || sqls.get(id) != null;
    }

    /**
     * Check if a database has a valid connection
     * @param id The id of the database
     * @param timeout The timeout in seconds to allow
     * @return True if it has a valid connection
     */
    public boolean isValid(String id,int timeout) {
        return getDatabase(id).map( d -> d.isValid(timeout)).orElse(false);
    }

    /**
     * Get a database based on the id (so either implmentation of the Database parent class)
     * @param id The id to look for
     * @return An optional database or empty one if not found
     */
    public Optional<SQLDB> getDatabase(String id){
        SQLiteDB lite = lites.get(id);
        if( lite != null )
            return Optional.of(lite);
        return Optional.ofNullable(sqls.get(id));
    }
    public boolean hasDatabases() {
        return !lites.isEmpty() || !sqls.isEmpty();
    }
    /* ****************************************************************************************************************/
    /**
     * Get status update on the various managed databases
     * 
     * @return A string showing for each database: current filename, amount and max
     *         queries, if there's rollover
     */
    public String getStatus() {
        StringJoiner join = new StringJoiner("\r\n", "", "\r\n");
        lites.forEach((id, db) -> join.add( id + " : " + db.toString() ));
        sqls.forEach((id, db)  -> join.add( id + " : " + db.toString() + (db.isValid(1)?"":" (NC)")));
        return join.toString();
    }

    /**
     * Read the databases' setup from the settings.xml
     */
    private void readFromXML() {
        XMLdigger.goIn(settingsPath,"dcafs","databases")
                .peekOut("sqlite").stream()
                .filter( db -> !db.getAttribute("id").isEmpty() )
                .forEach( db -> SQLiteDB.readFromXML(db,workPath).ifPresent( d -> addSQLiteDB(db.getAttribute("id"),d)) );

        XMLdigger.goIn(settingsPath,"dcafs","databases")
                .peekOut("server").stream()
                .filter( db -> !db.getAttribute("id").isEmpty() )
                .forEach( db -> addSQLDB(db.getAttribute("id"), SQLDB.readFromXML(db)));
    }

    /**
     * Reload the settings of the requested database
     * @param id The id of the database
     * @return The database reloaded
     */
    public Optional<Database> reloadDatabase( String id ){
        var dig = XMLdigger.goIn(settingsPath,"dcafs","databases");
        if( dig.hasPeek("sqlite","id",id)){
            var d = dig.usePeek().current();
            return SQLiteDB.readFromXML( d.get(),workPath).map(sqLiteDB -> addSQLiteDB(id, sqLiteDB));
        }else if( dig.hasPeek("server","id",id)){
            var d = dig.usePeek().current();
            return Optional.ofNullable(addSQLDB(id, SQLDB.readFromXML(d.get())));
        }
        return Optional.empty();
    }
    public void buildStores(RealtimeValues rtvals){
        sqls.values().forEach( x -> x.buildStores(rtvals));
        lites.values().forEach( x -> x.buildStores(rtvals));
    }
    public void recheckRollOver(){
        lites.values().forEach( lite -> lite.updateFileName(LocalDateTime.now(ZoneId.of("UTC"))));
    }
    /* ***************************************************************************************************************/
    /**
     * Run the queries of all the managed databases, mainly run before shutdown
     */
    public void flushAll() {
        lites.values().forEach( SQLiteDB::flushAll );
        sqls.values().forEach(SQLDB::flushAll);
    }
    /* **************************************  Q U E R Y W R I T I N G************************************************/

    /**
     * Give the data to a database object to be inserted without checking the data (except for amount of elements)
     * @param id The id of the database
     * @param table The name of the table
     * @param values The data to insert
     * @return How many tables received the insert
     */
    @Override
    public int addDirectInsert(String id, String table, Object... values) {
        lites.entrySet().stream().filter(ent -> ent.getKey().equalsIgnoreCase(id)).forEach(db -> db.getValue().addDirectInsert(table,values));
        sqls.entrySet().stream().filter(ent -> ent.getKey().equalsIgnoreCase(id)).forEach(db -> db.getValue().addDirectInsert(table,values));
        for( SQLiteDB sqlite : lites.values() ){
            if( sqlite.getID().equalsIgnoreCase(id))
                return sqlite.addDirectInsert(table,values);
        }
        for( SQLDB sqldb : sqls.values() ){
            if( sqldb.getID().equalsIgnoreCase(id))
                return sqldb.addDirectInsert(table,values);
        }
        return 0;
    }

    public boolean insertStores( String ids, String table ) {
        int ok=0;
        for( var id : ids.split(",")) {
           for (SQLiteDB sqlite : lites.values()) {
               if (sqlite.getID().equalsIgnoreCase(id))
                  ok += sqlite.insertStore(table)?1:0;
           }
           for (SQLDB sqldb : sqls.values()) {
               if (sqldb.getID().equalsIgnoreCase(id))
                   ok += sqldb.insertStore(table)?1:0;
           }
       }
       return ok==ids.split(",").length;
    }
    public boolean buildPrep( String ids, String table, String[] data ){
        int ok=0;
        for( var id : ids.split(",")) {
            for (SQLiteDB sqlite : lites.values()) {
                if (sqlite.getID().equalsIgnoreCase(id))
                    ok+=sqlite.fillPrep(table,data)?1:0;
            }
            for (SQLDB sqldb : sqls.values()) {
                if (sqldb.getID().equalsIgnoreCase(id))
                    ok+=sqldb.fillPrep(table,data)?1:0;
            }
        }
        return ok==ids.split(",").length;
    }
    /**
     * Add a query to the buffer of the given database
     * @param id The database to add the query to
     * @param query the query to add
     * @return True if added
     */
    @Override
    public boolean addQuery( String id, String query){
        for( SQLiteDB sqlite : lites.values() ){
            if( sqlite.getID().equalsIgnoreCase(id)) {
                sqlite.addQuery(query);
                return true;
            }
        }
        for( SQLDB sqldb : sqls.values() ){
            if( sqldb.getID().equalsIgnoreCase(id)) {
                sqldb.addQuery(query);
                return true;
            }
        }
        return false;
    }

    /**
     * Run a select query on the given database
     * @param id The database to use
     * @param query The query to run
     * @return An optional result
     */
    public Optional<List<List<Object>>> doSelect(String id, String query){
        for( SQLiteDB sqlite : lites.values() ){
            if( sqlite.getID().equalsIgnoreCase(id)) {
                return sqlite.doSelect(query);
            }
        }
        for( SQLDB sqldb : sqls.values() ){
            if( sqldb.getID().equalsIgnoreCase(id)) {
                return sqldb.doSelect(query);
            }
        }
        return Optional.empty();
    }
    /* **************************************  R U N N A B L E S ****************************************************/
    /**
     * Checks if the oldest query present in the buffer isn't older than the maximum
     * age. If so, the queries are executed
     */
    private class CheckQueryAge implements Runnable {
        @Override
        public void run() {
            for (SQLiteDB db : lites.values()) {
                try {
                    db.checkState(CHECK_INTERVAL);
                } catch (Exception e) {
                   Logger.error(e);
                }
            }
            for (SQLDB db : sqls.values()){
                try {
                    db.checkState(CHECK_INTERVAL);
                } catch (Exception e) {
                    Logger.error(e);
                }
            }
        }
    }

    /**
     * Add a blank table node to the given database (can be both server or sqlite)
     * @param settings The path to the settings file
     * @param id The id of the database the table belongs to
     * @param table The name of the table
     * @param format The format of the table
     * @return True if build
     */
    public static boolean addBlankTableToXML( Path settings, String id, String table, String format){

        var dig = XMLdigger.goIn(settings,"dcafs","databases");
        if( dig.hasPeek("sqlite","id",id)){ // It's an sqlite
            dig.usePeek();
        }else if( dig.hasPeek("server","id",id)){ // it's a server
            dig.usePeek();
        }else{
            return false;
        }
        var fabOpt = XMLfab.alterDigger(dig);
        return fabOpt.filter(xmLfab -> SqlTable.addBlankToXML(xmLfab, table, format)).isPresent();
    }
    /* ********************************** C O M M A N D A B L E *********************************************** */

    /**
     * Not used, but needs to be implemented
     * @param wr The writable to remove
     * @return True if it was removed
     */
    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }

    /**
     * Execute a command related to databases

     * @param wr The writable the command originated from
     * @param html If the reply should be in html
     * @return The response or unknown command if no command was found
     */
    @Override
    public String replyToCommand(String cmd, String args, Writable wr, boolean html) {

        if( cmd.equalsIgnoreCase("myd"))
            return doMYsqlDump(args);

        String[] cmds = args.split(",");

        StringJoiner join = new StringJoiner(html?"<br":"\r\n");

        String id = cmds.length>=2?cmds[1]:"";

        String cyan = html?"":TelnetCodes.TEXT_CYAN;
        String green=html?"":TelnetCodes.TEXT_GREEN;
        String reg=html?"":TelnetCodes.TEXT_DEFAULT;
        var or = html?"":TelnetCodes.TEXT_ORANGE;

        if( cmds.length == 1){
            return switch( cmds[0]) {
                case "?" -> {
                    join.add(TelnetCodes.TEXT_MAGENTA + "The databasemanager connects to databases, handles queries and fetches table information.\r\n");
                    join.add(or + "Notes" + reg)
                            .add("  rtval -> the rtval of a column is the id to look for in the rtvals instead of the default tablename_column")
                            .add("  macro -> an at runtime determined value that can be used to define the rtval reference").add("");
                    join.add(cyan + "Connect to a database" + reg)
                            .add(green + "  dbm:addmssql,id,db name,ip:port,user:pass " + reg + "-> Adds a MSSQL server on given ip:port with user:pass")
                            .add(green + "  dbm:addmysql,id,db name,ip:port,user:pass " + reg + "-> Adds a MySQL server on given ip:port with user:pass")
                            .add(green + "  dbm:addmariadb,id,db name,ip:port,user:pass " + reg + "-> Adds a MariaDB server on given ip:port with user:pass")
                            .add(green + "  dbm:addpostgresql,id,db name,ip:port,user:pass " + reg + "-> Adds a PostgreSQL server on given ip:port with user:pass")
                            .add(green + "  dbm:addsqlite,id(,filename) " + reg + "-> Creates an empty sqlite database, filename and extension optional default db/id.sqlite")
                            .add("").add(cyan + "Working with tables" + reg)
                            .add(green + "  dbm:id,addtable,tablename " + reg + "-> Adds a table to the given database id")
                            .add(green + "  dbm:id,addcol,tablename,columntype:columnname<:rtval> " + reg + "-> Add a column to the given table")
                            .add("        - columntypes: r(eal),t(ime)s(tamp),i(nteger),t(ext), utc(now), ltc")
                            .add(green + "  dbm:id,tablexml,tablename " + reg + "-> Write the table in memory to the xml file, use * as tablename for all")
                            .add(green + "  dbm:id,tables " + reg + "-> Get info about the given id (tables etc)")
                            .add(green + "  dbm:id,fetch " + reg + "-> Read the tables from the database directly, not overwriting stored ones.")
                            .add(green + "  dbm:id,store,tableid " + reg + "-> Trigger a insert for the database and table given")
                            .add("").add(cyan + "Other" + reg)
                            .add(green + "  dbm:id,addrollover,count,unit,pattern " + reg + "-> Add rollover to a SQLite database")
                            .add(green + "  dbm:id,coltypes,table"+reg+" -> Get a list of the columntypes in the table, only used internally")
                            .add(green + "  dbm:id,reload " + reg + "-> (Re)loads the database with the given id fe. after changing the xml")
                            .add(green + "  dbm:status " + reg + "-> Show the status of all managed database connections")
                            .add(green + "  st " + reg + "-> Show the current status of the databases (among other things)");
                    yield join.toString();
                }
                case "list", "status" -> getStatus();
                case "prep" -> {
                    lites.values().forEach( x-> {
                        join.add("SQLite: "+x.getID());
                        x.tables.forEach((i,val) -> join.add("  "+i+" : "+val.getPrepCount()));
                        join.add("");
                    });
                    sqls.values().forEach( x-> {
                        join.add("SQL: "+x.getID());
                        x.tables.forEach((i,val) -> join.add("  "+i+" : "+val.getPrepCount()));
                        join.add("");
                    });
                    yield join.toString();
                }
                default -> "! No such command " + cmds[0];
            };
        }else if( cmds[0].startsWith("add")){ // So the addmssql, addmysql, addmariadb and addsqlite
            cmds[0] = cmds[0].substring(3);

            if( Arrays.stream(DBTYPES).noneMatch(x->x.equalsIgnoreCase(cmds[0])) )
                return "! No such supported database type "+cmds[0];

            String dbName = cmds.length>=3?cmds[2]:"";
            String address = cmds.length>=4?cmds[3]:"";
            String user = cmds.length>=5?cmds[4]:"";
            String pass="";

            if( user.contains(":")){
                pass = user.substring(user.indexOf(":")+1);
                user = user.substring(0,user.indexOf(":"));
            }

            if( cmds[0].equalsIgnoreCase("sqlite")){
                if( !dbName.contains(File.separator))
                    dbName = "db"+File.separator+(dbName.isEmpty()?id:dbName);
                if(!dbName.endsWith(".sqlite"))
                    dbName+=".sqlite";
                Path p = Path.of(dbName);
                if( !p.isAbsolute())
                    p = Path.of(workPath).resolve(p);
                var sqlite = SQLiteDB.createDB( id, p );
                if( sqlite.connect(false) ){
                    addSQLiteDB(id,sqlite);
                    sqlite.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","databases") );
                    return "Created SQLite at "+dbName+" and wrote to settings.xml";
                }
                return "! Failed to create SQLite";
            }else{
                SQLDB db = switch( cmds[0] ){
                    case "mssql" ->  SQLDB.asMSSQL(address,dbName,user,pass);
                    case "mysql" -> SQLDB.asMYSQL(address, dbName, user, pass);
                    case "mariadb" -> SQLDB.asMARIADB(address,dbName,user,pass);
                    case "postgresql" -> SQLDB.asPOSTGRESQL(address, dbName, user, pass);
                    default -> null;
                };
                if( db == null )
                    return "! Invalid db type: "+cmds[0];
                cmds[0]=cmds[0].toUpperCase();
                db.setID(id);
                if( db.connect(false) ){
                    db.getCurrentTables(false);
                    db.writeToXml( XMLfab.withRoot(settingsPath,"dcafs","databases"));
                    addSQLDB(id,db);
                    return "Connected to "+cmds[0]+" database and stored in xml as id "+id;
                }
                return "! Failed to connect to "+cmds[0]+" database.";
            }
        }else{
            var dbOpt = getDatabase(cmds[0]);
            if( dbOpt.isEmpty() ) {
                Logger.error(cmd+":"+args+" -> Failed because no such database: "+cmds[0]);
                return "! No such database: " + cmds[0];
            }
            var db = dbOpt.get();
            if( cmds.length==2 ){
                return switch (cmds[1]) {
                    case "fetch" -> {
                        if (db.getCurrentTables(false))
                            yield "Tables fetched, run dbm:" + cmds[1] + ",tables to see result.";
                        if (db.isValid(1))
                            yield "! Failed to get tables, but connection valid...";
                        yield "! Failed to get tables because connection not active.";
                    }
                    case "tables" -> db.getTableInfo(html ? "<br" : "\r\n");
                    case "reload" -> {
                        var r = reloadDatabase(cmds[0]);
                        if( r.isPresent() ){
                            ((SQLDB)r.get()).buildStores(rtvals);
                            var error = r.get().getLastError();
                            yield error.isEmpty() ? "Database reloaded" : error;
                        }
                        yield "! Reload failed";
                    }
                    default -> "! No such command (or to few arguments)";
                };
            }else{
                switch (cmds[1]) {
                    case "tablexml" -> {
                        // Select the correct server node
                        var fab = XMLfab.withRoot(settingsPath, "dcafs", "databases");
                        if (fab.selectChildAsParent("server", "id", cmds[0]).isEmpty())
                            fab.selectChildAsParent("sqlite", "id", cmds[0]);
                        if (fab.hasChild("table", "name", cmds[2]).isPresent())
                            return "! Already present in xml, not adding";

                        int rs = dbOpt.get().writeTableToXml(fab, cmds[2]);
                        return rs == 0 ? "None added" : "Added " + rs + " tables to xml";
                    }
                    case "addrollover" -> {
                        if (cmds.length < 5)
                            return "! Not enough arguments, needs to be dbm:dbid,addrollover,count,unit,pattern";
                        if (db instanceof SQLiteDB) {
                            var sql =  ((SQLiteDB) db).setRollOver(cmds[4], NumberUtils.createInteger(cmds[2]), cmds[3]);
                            if( sql==null)
                                return "! Bad arguments given, probably format?";
                            sql.writeToXml(XMLfab.withRoot(settingsPath, "dcafs", "databases"));
                            ((SQLiteDB) db).forceRollover();
                            return "Rollover added";
                        }
                        return "! " + cmds[0] + " is not an SQLite";
                    }
                    case "addtable" -> {
                        if (DatabaseManager.addBlankTableToXML(settingsPath,cmds[0], cmds[2], cmds.length == 4 ? cmds[3] : "")) {
                            if (cmds.length == 4)
                                return "Added a partially setup table to " + cmds[0] + " in the settings.xml, edit it to set column names etc";
                            return "Created tablenode for " + cmds[0] + " inside the db node";
                        }
                        return "! Failed to add table to database node";
                    }
                    case "addcolumn","addcol" -> {
                        if( cmds.length<4 )
                            return "! Not enough arguments: dbm:id,addcol,table,type:name";

                        if (!cmds[3].contains(":"))
                            return "! Needs to be columtype:columnname";

                        var dig = XMLdigger.goIn(settingsPath,"dcafs")
                                .digDown("databases"); // Go into the database node
                        dig.hasPeek("sqlite","id",cmds[0]);
                        if( dig.hasValidPeek() ){
                            dig.digDown("sqlite","id",cmds[0]);
                        }else if( dig.hasPeek("server","id",cmds[0]) ){
                            dig.digDown("server","id",cmds[0]);
                        }else{
                            return "! No such database node yet";
                        }
                        if( dig.isInvalid() )
                            return "! No such database node yet";
                        dig.digDown("table","name",cmds[2]);
                        if( dig.isInvalid() )
                            return "! No such table node yet";

                        var fabOpt = XMLfab.alterDigger(dig);
                        if( fabOpt.isEmpty())
                            return "! Failed to convert to fab?";

                        var fab = fabOpt.get();
                        for (int a = 3; a < cmds.length; a++) {
                            var spl = cmds[a].split(":");
                            switch (spl[0]) {
                                case "timestamp", "ts" -> spl[0] = "timestamp";
                                case "integer", "int", "i" -> spl[0] = "int";
                                case "real", "r", "d" -> spl[0] = "real";
                                case "text", "t" -> spl[0] = "text";
                                case "utc" -> spl[0] = "utcnow";
                                case "ltc" -> spl[0] = "localdtnow";
                                case "dt" -> spl[0] = "datetime";
                                default -> {
                                    return "! Invalid column type: " + spl[0];
                                }
                            }
                            fab.addChild(spl[0], spl[1]);
                            if (spl.length == 3)
                                fab.attr("alias", spl[2]);
                        }
                        fab.build();
                        return "Column(s) added";
                    }
                    case "store" -> {
                            if( insertStores(cmds[0],cmds[2] ) )
                                return "Wrote record";
                            Logger.error("Tried to do store on "+cmds[0]+"->"+cmds[2]+", but failed.");
                            return "! Failed to write record";
                    }
                    case "prep" -> {
                        if( buildPrep(cmds[0],cmds[2],Arrays.copyOfRange(cmds,3,cmds.length)) )
                            return "Wrote record";
                        return "! Failed to write record";
                    }
                    case "coltypes" -> {
                        var tableOpt = db.getTable(cmds[2]);
                        if( tableOpt.isEmpty() )
                            return "! No such table: "+cmds[2];
                        return tableOpt.get().getColumnTypes();
                    }
                    default -> {
                        return "! No such subcommand in " + cmd + ": " + cmds[0];
                    }
                }
            }
        }
    }
    public String payloadCommand( String cmd, String args, Object payload){
        String[] cmds = args.split(",");
        if( payload==null)
            return "! No valid payload given with "+cmd+":"+args;
        if( cmds.length>=2) {
            if (cmds[1].equals("tableinsert")) {
                if (cmds.length < 3)
                    return "! Not enough arguments, needs to be dbm:dbid,tableinsert,tableid";
                var dbOpt = getDatabase(cmds[0]);
                if( dbOpt.isEmpty() ) {
                    Logger.error(cmd+":"+args+" -> Failed because no such database: "+cmds[0]);
                    return "! No such database: " + cmds[0];
                }
                var db = dbOpt.get();
                var tiOpt = db.getTableInsert(cmds[2]);
                if( tiOpt.isEmpty())
                    return "! No such table id "+cmds[2]+" in "+cmds[0];
                if( payload.getClass() == MathForward.class ||  payload.getClass() == EditorForward.class
                        || payload.getClass() == FilterForward.class || payload.getClass() == CmdForward.class ){
                    ((AbstractForward)payload).addTableInsert(tiOpt.get());
                    return "TableInsert added";
                }else if( payload.getClass() == StoreCollector.class ){
                    ((StoreCollector)payload).addTableInsert(tiOpt.get());
                }
                return "! Payload isn't the correct object type for "+cmd+":"+args;
            }
        }
        return "! No such command " + cmd + ": " + args;
    }
    /**
     * Response to MySQLdump related commands
     * @param args The command
     * @return The response
     */
    public String doMYsqlDump(String args ){
        String[] cmds = args.split(",");
        switch (cmds[0]) {
            case "?" -> {
                return " myd:run,dbid,path -> Run the mysqldump process for the given database";
            }
            case "run" -> {
                if (cmds.length != 3)
                    return "! Not enough arguments, must be mysqldump:run,dbid,path";
                var dbOpt = getDatabase(cmds[1]);
                if (dbOpt.isEmpty())
                    return "! No such database " + cmds[1];
                var sql = dbOpt.get();
                if (sql instanceof SQLiteDB)
                    return "! Database is an sqlite, not mysql/mariadb";

                if (sql.isMySQL()) {
                    // do the dump
                    String os = System.getProperty("os.name").toLowerCase();
                    if (!os.startsWith("linux")) {
                        return "! Only Linux supported for now.";
                    }
                    try {
                        ProcessBuilder pb = new ProcessBuilder("bash", "-c", "mysqldump " + sql.getTitle() + " > " + cmds[2] + ";");
                        pb.inheritIO();
                        Process process;

                        Logger.info("Started dump attempt at " + TimeTools.formatLongUTCNow());
                        process = pb.start();
                        process.waitFor();
                        // zip it?
                        if (Files.exists(Path.of(workPath, cmds[2]))) {
                            if (FileTools.zipFile(Path.of(workPath, cmds[2])) == null) {
                                Logger.error("Dump of " + cmds[1] + " created, but zip failed");
                                return "! Dump created, failed zipping.";
                            }
                            // Delete the original file
                            Files.deleteIfExists(Path.of(workPath, cmds[2]));
                        } else {
                            Logger.error("Dump of " + cmds[1] + " failed.");
                            return "! No file created...";
                        }
                        Logger.info("Dump of " + cmds[1] + " created, zip made.");
                        return "Dump finished and zipped at " + TimeTools.formatLongUTCNow();
                    } catch (IOException | InterruptedException e) {
                        Logger.error(e);
                        Logger.error("Dump of " + cmds[1] + " failed.");
                        return "! Something went wrong";
                    }
                } else {
                    return "! Database isn't mysql/mariadb";
                }
            }
            default -> {
                return "! No such subcommand in myd: "+cmds[0];
            }
        }
    }
}