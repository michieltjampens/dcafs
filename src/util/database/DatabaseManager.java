package util.database;

import das.Commandable;
import das.Paths;
import io.Writable;
import io.collector.StoreCollector;
import io.forward.*;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.RealtimeValues;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class DatabaseManager implements QueryWriting, Commandable {
    private final Map<String, SQLiteDB> lites = new HashMap<>();        // Store the SQLite databases
    private final Map<String, SQLDB> sqls = new HashMap<>();            // Store the SQL databases
    private static final int CHECK_INTERVAL=5;                          // How often to check the state
    private final ScheduledExecutorService scheduler;                   // Scheduler for the request data action
    private final RealtimeValues rtvals;                                 // Reference to the realtime data
    private static final String[] DBTYPES = {"mssql","mysql","mariadb","sqlite","postgresql"};

    /**
     * Create a manager that uses its own scheduler
     */
    public DatabaseManager( RealtimeValues rtvals) {

        this.rtvals=rtvals;
        scheduler = Executors.newScheduledThreadPool(1); // create a scheduler with a single thread

        readFromXML(true);  // Read the settings from the xml
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
        if (lites.isEmpty() && sqls.isEmpty())
            scheduler.scheduleAtFixedRate(new CheckQueryAge(), 2L*CHECK_INTERVAL, CHECK_INTERVAL, TimeUnit.SECONDS);

        SQLiteDB old = lites.get(id);
        if (old != null) // Check if we are overwriting an older version, and if so cancel any rollover
            old.cancelRollOver();

        lites.put(id.toLowerCase(), db);
        return db;
    }

    /**
     * Add a SQL database to the manager and uf the first one, enable the scheduled state checking
     * @param id The id of the database
     * @param db The database object
     * @return The added database
     */
    public SQLDB addSQLDB(String id, SQLDB db) {
        if( db == null ){
            Logger.error(id+"(dbm) -> No valid db object, aborting add.");
            return null;
        }
        db.setWorkPath(Paths.settings().getParent());
        if (lites.isEmpty() && sqls.isEmpty())
            scheduler.scheduleAtFixedRate(new CheckQueryAge(), 2L*CHECK_INTERVAL, CHECK_INTERVAL, TimeUnit.SECONDS);
        sqls.put(id.toLowerCase(), db);
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
        id=id.toLowerCase();
        var lite = lites.get(id);
        if( lite!=null )
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
        lites.forEach((id, db) -> join.add( db.toString() ));
        sqls.forEach((id, db)  -> join.add( db.toString() ));
        return join.toString();
    }

    /**
     * Read the databases' setup from the settings.xml
     */
    private void readFromXML(boolean clear) {
        if (clear) {
            lites.clear();
            sqls.clear();
        }
        XMLdigger.goIn( Paths.settings(),"dcafs","databases")
                .peekOut("sqlite").stream()
                .filter( db -> !db.getAttribute("id").isEmpty() )
                .forEach(db -> {
                    var dbid = db.getAttribute("id");
                    var path = Path.of(db.getAttribute("path"));
                    var lite = lites.get(dbid);
                    if (lite == null) { // If it's new, create it
                        lite = SQLiteDB.createDB(dbid, path);
                        addSQLiteDB(dbid, lite);
                    }
                    lite.reloadDatabase();
                });

        XMLdigger.goIn(Paths.settings(),"dcafs","databases")
                .peekOut("server").stream()
                .filter( db -> !db.getAttribute("id").isEmpty() )
                .forEach(db -> {
                    var id = db.getAttribute("id");
                    var sql = sqls.get(id);
                    if (sql != null) {
                        sql.reloadDatabase();
                    } else {
                        var opt = SQLDB.createFromXML(id);
                        opt.ifPresent(sqldb -> addSQLDB(id, sqldb));
                    }
                });
    }

    /**
     * Reload the settings of the requested database
     * @param id The id of the database
     * @return The database reloaded
     */
    public boolean reloadDatabase(String id) {
        var sqlOpt = Stream.of(lites, sqls)
                .flatMap(map -> map.values().stream())
                .filter(db -> db.id().equalsIgnoreCase(id))
                .findFirst();
        if (sqlOpt.isPresent()) {
            var sql = sqlOpt.get();
            sql.reloadDatabase();
            sql.buildStores(rtvals);
            return true;
        }
        return false;
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
        return getDatabase(id).map(sqldb -> sqldb.addDirectInsert(table, values)).orElse(0);
    }

    public boolean insertStores( String ids, String table ) {
        long ok = Arrays.stream(ids.split(","))
                .map(id -> Map.entry(id, getDatabase(id)))  // Keep both id and Optional together
                .filter(entry -> entry.getValue().isPresent())
                .filter(entry -> entry.getValue().get().insertStore(new String[]{entry.getKey(), table}))
                .count();

        return ok == ids.split(",").length;
    }
    public boolean buildPrep( String ids, String table, String[] data ){
        long ok = Arrays.stream(ids.split(","))
                .map(this::getDatabase)
                .filter(Optional::isPresent)
                .filter(db -> db.get().fillPrep(table, data))
                .count();
        return ok==ids.split(",").length;
    }
    /**
     * Add a query to the buffer of the given database
     * @param id The database to add the query to
     * @param query the query to add
     * @return True if added
     */
    @Override
    public boolean addQuery( String id, String query) {
        return getDatabase(id).map(db -> {
            db.doSelect(query);
            return true;
        }).orElse(false);
    }

    /**
     * Run a select query on the given database
     * @param id The database to use
     * @param query The query to run
     * @return An optional result
     */
    public Optional<List<List<Object>>> doSelect(String id, String query){
        return getDatabase(id).flatMap(db -> db.doSelect(query));
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
     * @param id The id of the database the table belongs to
     * @param table The name of the table
     * @param format The format of the table
     * @return True if build
     */
    public static boolean addBlankTableToXML( String id, String table, String format){

        var dig = XMLdigger.goIn(Paths.settings(),"dcafs","databases");
        if( dig.hasPeek("sqlite","id",id) || dig.hasPeek("server","id",id)){ // it's a server
            dig.usePeek();
        }else{
            return false;
        }
        return XMLfab.alterDigger(dig)
                        .filter(xmLfab -> SqlTable.addBlankToXML(xmLfab, table, format))
                        .isPresent();
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

        if( cmds.length == 1){
            return doOneArgCmd( cmds,html );
        }else if( cmds[0].startsWith("add")){ // So the addmssql, addmysql, addmariadb and addsqlite
            return doAddCmd(cmds,cmds[1]);
        }else{
            var dbOpt = getDatabase(cmds[0]);
            if( dbOpt.isEmpty() ) {
                Logger.error(cmd+":"+args+" -> Failed because no such database: "+cmds[0]);
                return "! No such database: " + cmds[0];
            }
            var db = dbOpt.get();
            if( cmds.length==2 ){
                return doTwoArgCmd(cmds,db,html);
            }else{
                return doMultiArgCmd(cmds,cmd,db);
            }
        }
    }
    private String doOneArgCmd( String[] cmds ,boolean html){
        StringJoiner join = new StringJoiner(html?"<br":"\r\n");
        return switch( cmds[0]) {
            case "?" -> doCmdHelp(html);
            case "list", "status" -> getStatus();
            case "prep" -> {
                lites.values().forEach( lite -> {
                    join.add("SQLite: "+lite.id() + "( tablename : total queries executed )");
                    lite.tables.forEach((i,val) -> join.add("  "+i+" : "+val.getPrepCount()));
                    join.add("");
                });
                sqls.values().forEach( sqldb-> {
                    join.add("SQL: "+sqldb.id() + "( tablename : total queries executed )");
                    sqldb.tables.forEach((i,val) -> join.add("  "+i+" : "+val.getPrepCount()));
                    join.add("");
                });
                yield join.toString();
            }
            case "reloadstores" -> {
                buildStores(rtvals);
                yield "Stores rebuild";
            }
            case "reload","reloadall" -> {
                readFromXML(false);
                buildStores(rtvals);
                yield "Reloading finished";
            }
            case "clearerrors" -> {
                sqls.values().forEach(SQLDB::clearErrors);
                yield "Cleared errors";
            }
            default -> "! No such command " + cmds[0];
        };
    }
    private String doCmdHelp( boolean html ){
        var join = new StringJoiner(html?"<br>":"\r\n");
        join.add("The databasemanager connects to databases, handles queries and fetches table information.");
        join.add("Connect to a database")
                .add("dbm:addmssql,id,db name,ip:port,user:pass -> Adds a MSSQL server on given ip:port with user:pass")
                .add("dbm:addmysql,id,db name,ip:port,user:pass -> Adds a MySQL server on given ip:port with user:pass")
                .add("dbm:addmariadb,id,db name,ip:port,user:pass -> Adds a MariaDB server on given ip:port with user:pass")
                .add("dbm:addpostgresql,id,db name,ip:port,user:pass -> Adds a PostgreSQL server on given ip:port with user:pass")
                .add("dbm:addsqlite,id(,filename) -> Creates an empty sqlite database, filename and extension optional default db/id.sqlite")
                .add("Working with tables")
                .add("dbm:id,addtable,tablename -> Adds a table to the given database id")
                .add("dbm:id,addcol,tablename,columntype:columnname<:rtval> -> Add a column to the given table, repeat last part for more")
                .add("- columntypes: r(eal),t(ime)s(tamp),i(nteger),t(ext), utc(now), ltc")
                .add("dbm:id,tablexml,tablename -> Write the table in memory to the xml file, use * as tablename for all")
                .add("dbm:id,tables -> Get info about the given id (tables etc)")
                .add("dbm:id,fetch -> Read the tables from the database directly, not overwriting stored ones.")
                .add("dbm:id,store,tableid -> Trigger a insert for the database and table given")
                .add("dbm:id,doinserts,true/false -> Disable or enable inserts from stores.")
                .add("Other")
                .add("dbm:id,addrollover,period,pattern -> Add rollover with the given period (like 1h) to a SQLite database")
                .add("dbm:id,coltypes,table -> Get a list of the columntypes in the table, only used internally")
                .add("dbm:id,reload -> (Re)loads the database with the given id fe. after changing the xml")
                .add("dbm:reloadall -> Reloads all databases")
                .add("dbm:status -> Show the status of all managed database connections")
                .add("dbm:prep -> Get total amount of queries executed with prepared statements")
                .add("dbm:clearerrors -> Reset the error count.")
                .add("st -> Show the current status of the databases (among other things)");
        return LookAndFeel.formatCmdHelp(join.toString(),html);
    }
    private String doAddCmd( String[] cmds, String id){
        cmds[0] = cmds[0].substring(3);

        if( Arrays.stream(DBTYPES).noneMatch(x->x.equalsIgnoreCase(cmds[0])) )
            return "! No such supported database type "+cmds[0];
        if( getDatabase(id.toLowerCase()).isPresent() )
            return "! ID already used, pick something else?";

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
                p = Paths.storage().resolve(p);
            var sqlite = SQLiteDB.createDB( id, p );
            if( sqlite.connect(false) ){
                addSQLiteDB(id,sqlite);
                sqlite.writeToXml( XMLfab.withRoot(Paths.settings(),"dcafs","databases") );
                return "Created SQLite at "+dbName+" and wrote to settings.xml";
            }
            return "! Failed to create SQLite";
        }else{
            SQLDB db = new SQLDB(cmds[0], address, dbName, user, pass);
            if (db.isInValidType())
                return "! Invalid db type: "+cmds[0];
            db.id(id);
            db.writeToXml(Paths.fabInSettings("databases"));
            addSQLDB(id, db);
            db.connect(false);
            return "Connecting to " + cmds[0] + " database...";
        }
    }
    private String doAddColumnCmd( String[] cmds ){
        if( cmds.length<4 )
            return "! Not enough arguments: dbm:id,addcol,table,type:name";

        if (!cmds[3].contains(":"))
            return "! Needs to be columtype:columnname";

        var dig = XMLdigger.goIn(Paths.settings(),"dcafs")
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
                case "utc", "utcnow" -> spl[0] = "utcnow";
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
    private String doTwoArgCmd( String[] cmds,SQLDB db, boolean html ){
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
                var ok = reloadDatabase(cmds[0]);
                yield ok ? "Database Reloaded" : "! Reload failed";
            }
            case "checkstore" -> {
                db.buildStores(rtvals);
                yield "Stores rebuild";
            }
            case "reconnect" -> {
                if( db instanceof SQLiteDB sd ){
                    db.connect(true);
                    yield "Trying to reconnect to the sqlite database";
                }else{
                    db.flagNeedcon();
                    yield "Trying to reconnect to the sql database";
                }
            }
            default -> "! No such command (or to few arguments)";
        };
    }
    private String doMultiArgCmd( String[] cmds, String cmd, SQLDB db ){
        return switch (cmds[1]) {
            case "tablexml" -> {
                // Select the correct server node
                var fab = XMLfab.withRoot(Paths.settings(), "dcafs", "databases");
                if (fab.selectChildAsParent("server", "id", cmds[0]).isEmpty())
                    fab.selectChildAsParent("sqlite", "id", cmds[0]);
                if (fab.hasChild("table", "name", cmds[2]).isPresent())
                    yield "! Already present in xml, not adding";

                int rs = db.writeTableToXml(fab, cmds[2]);
                yield rs == 0 ? "None added" : "Added " + rs + " tables to xml";
            }
            case "addrollover" -> {
                if (cmds.length < 4)
                    yield "! Not enough arguments, needs to be dbm:dbid,addrollover,period,pattern";
                if (db instanceof SQLiteDB) {
                    var secs = (int) TimeTools.parsePeriodStringToSeconds(cmds[2]);
                    var sql = ((SQLiteDB) db).setRollOver(cmds[3], secs, ChronoUnit.SECONDS);
                    if( sql==null)
                        yield "! Bad arguments given, probably format?";
                    sql.writeToXml(XMLfab.withRoot(Paths.settings(), "dcafs", "databases"));
                    ((SQLiteDB) db).forceRollover();
                    yield "Rollover added";
                }
                yield "! " + cmds[0] + " is not an SQLite";
            }
            case "addtable" -> {
                if (DatabaseManager.addBlankTableToXML(cmds[0], cmds[2], cmds.length == 4 ? cmds[3] : "")) {
                    if (cmds.length == 4)
                        yield "Added a partially setup table to " + cmds[0] + " in the settings.xml, edit it to set column names etc";
                    yield "Created tablenode for " + cmds[0] + " inside the db node";
                }
                yield "! Failed to add table to database node";
            }
            case "addcolumn","addcol" -> doAddColumnCmd(cmds);
            case "store" -> {
                if( insertStores(cmds[0],cmds[2] ) )
                    yield "Wrote record";
                Logger.error("Tried to do store on "+cmds[0]+"->"+cmds[2]+", but failed.");
                yield "! Failed to write record";
            }
            case "prep" -> buildPrep(cmds[0],cmds[2],Arrays.copyOfRange(cmds,3,cmds.length))?"Wrote record":"! Failed to write record";
            case "coltypes" ->  db.getTable(cmds[2]).map(SqlTable::getColumnTypes).orElse("! No such table: "+cmds[2]);
            case "doinserts" -> {
                var alter = Tools.parseBool(cmds[2], true);
                db.setDoInserts(alter);
                yield "Set do inserts for " + db.id() + " to " + alter;
            }
            default -> "! No such subcommand in " + cmd + ": " + cmds[0];
        };
    }
    public String payloadCommand( String cmd, String args, Object payload){
        String[] cmds = args.split(",");
        if( payload==null)
            return "! No valid payload given with "+cmd+":"+args;
        if (cmds.length >= 2 && cmds[1].equals("tableinsert")) {
            if (cmds.length < 3)
                return "! Not enough arguments, needs to be dbm:dbid,tableinsert,tableid";
            var dbOpt = getDatabase(cmds[0]);
            if (dbOpt.isEmpty()) {
                Logger.error(cmd + ":" + args + " -> Failed because no such database: " + cmds[0]);
                return "! No such database: " + cmds[0];
            }
            var db = dbOpt.get();
            var tiOpt = db.getTableInsert(cmds[2]);
            if (tiOpt.isEmpty()) {
                var id = "";
                if (payload instanceof AbstractForward af) {
                    id = af.id();
                }
                Logger.error("dbm payload -> No table '" + cmds[2] + "' in " + cmds[0] + " requested by " + id);
                return "! No such table id " + cmds[2] + " in " + cmds[0];
            }
            if (payload.getClass() == MathForward.class || payload.getClass() == EditorForward.class
                    || payload.getClass() == FilterForward.class || payload.getClass() == CmdForward.class) {
                ((AbstractForward) payload).addTableInsert(tiOpt.get());
                return "TableInsert added";
            } else if (payload.getClass() == StoreCollector.class) {
                ((StoreCollector) payload).addTableInsert(tiOpt.get());
            }
            return "! Payload isn't the correct object type for " + cmd + ":" + args;
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
        return switch (cmds[0]) {
            case "?" -> " myd:run,dbid,path -> Run the mysqldump process for the given database";
            case "run" -> {
                if (cmds.length != 3)
                    yield "! Not enough arguments, must be mysqldump:run,dbid,path";
                var dbOpt = getDatabase(cmds[1]);
                if (dbOpt.isEmpty())
                    yield "! No such database " + cmds[1];
                var sql = dbOpt.get();
                if (!sql.isMySQL())
                    yield "! Database isn't MySQL/MariaDB";

                if (!System.getProperty("os.name").toLowerCase().startsWith("linux"))
                    yield "! Only Linux supported for now.";

                // do the dump
                try {
                    ProcessBuilder pb = new ProcessBuilder("bash", "-c", "mysqldump " + sql.getTitle() + " > " + cmds[2] + ";");
                    pb.inheritIO();
                    Process process;

                    Logger.info("Started dump attempt at " + TimeTools.formatLongUTCNow());
                    process = pb.start();
                    process.waitFor();
                    // zip it?
                    var dump = Paths.storage().resolve(cmds[2]);
                    if (Files.exists( dump )) {
                        if (FileTools.zipFile(dump) == null) {
                            Logger.error("Dump of " + cmds[1] + " created, but zip failed");
                            yield "! Dump created, failed zipping.";
                        }
                        // Delete the original file
                        Files.deleteIfExists(dump);
                    } else {
                        Logger.error("Dump of " + cmds[1] + " failed.");
                        yield "! No file created...";
                    }
                    Logger.info("Dump of " + cmds[1] + " created, zip made.");
                    yield "Dump finished and zipped at " + TimeTools.formatLongUTCNow();
                } catch (IOException | InterruptedException e) {
                    Logger.error(e);
                    Logger.error("Dump of " + cmds[1] + " failed.");
                    yield "! Something went wrong";
                }
            }
            default -> "! No such subcommand in myd: "+cmds[0];
        };
    }
}