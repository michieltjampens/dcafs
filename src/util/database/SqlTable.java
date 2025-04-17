package util.database;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.ValStore;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.xml.XMLfab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class SqlTable{

    private final HashMap<String, SqlTableFab.PrepStatement> preps = new HashMap<>();
    private final String tableName;
    private final ArrayList<SqlColumn> columns = new ArrayList<>();
    private final HashMap<String, ValStore> stores = new HashMap<>();
    enum TABLE_STATE {
        NEW, READ_FROM_XML, XML_FAILED, FOUND_IN_DB, READ_FROM_DB, READY
    }

    TABLE_STATE ready_state = TABLE_STATE.NEW;

    boolean ifnotexists = false;
    boolean server = false;


    String lastError="";

    private long prepCount=0;

    public SqlTable(String name) {
        this.tableName = name;
        preps.put("", new SqlTableFab.PrepStatement());
    }

    /**
     * Create a SQLiteTable object for a table with the given name
     *
     * @param name The name of the table
     * @return The created object
     */
    public static SqlTable withName(String name) {
        return new SqlTable(name);
    }
    /**
     * By default, this assumes it's for sqlite, with this it's toggled to be for a server instead
     */
    public void toggleServer(){
        server=true;
    }

    public boolean isServer() {
        return server;
    }
    public void setLastError(String error ){
        this.lastError=error;
    }

    public void addColumn(SqlColumn column) {
        if (column != null) {
            columns.add(column);
            preps.get("").addColumnIndex(columns.size() - 1);
        } else {
            Logger.error(tableName + "-> Not adding a null column");
        }
    }

    public ArrayList<SqlColumn> getColumns() {
        return columns;
    }

    public String getColumnTypes() {
        var join = new StringJoiner(",");
        columns.forEach(c -> join.add(String.valueOf(c.type)));
        return join.toString();
    }

    /**
     * Check if this table has columns
     *
     * @return True if it is not empty
     */
    public boolean hasColumns() {
        return !columns.isEmpty();
    }
    public void addStore(String key, ValStore store) {
        if (store != null && !store.isInvalid())
            stores.put(key, store);
    }

    public HashMap<String, ValStore> getStores() {
        return stores;
    }

    public boolean noValidStore(String id) {
        var store = stores.get(id);
        return store == null || store.isInvalid();
    }
    /**
     * Get the last error that has occurred during sql operations
     * @param clear Clear the error after returning it
     * @return The last error message
     */
    public String getLastError( boolean clear ){
        String t = lastError;
        if( clear)
            lastError= "";
        return t;
    }

    public void flagAsReadFromXML() {
        ready_state = TABLE_STATE.READ_FROM_XML;
    }
    public boolean isReadFromXML() {
        return ready_state == TABLE_STATE.READ_FROM_XML;
    }

    /**
     * Flag that the sqltable was read from a database (and not from xml)
     */
    public void flagAsReadFromDB() {
        ready_state = TABLE_STATE.READ_FROM_DB;
    }

    /**
     * Check if the table was read from the database (instead of xml)
     *
     * @return True if read from database
     */
    public boolean isReadFromDB() {
        return ready_state == TABLE_STATE.READ_FROM_DB;
    }

    public void flagAsReady() {
        ready_state = TABLE_STATE.READY;
    }
    public boolean isReady() {
        return ready_state == TABLE_STATE.READY;
    }
    /**
     * Store the setup of a table in xml
     *
     * @param fab   The xmlfab to use, pointing to the database as parent node
     * @param build If true, the xml is build at the end
     */
    public void writeToXml(XMLfab fab, boolean build ){
        fab.addChild("table").attr("name", tableName).down();
        for( var col : columns ){
            fab.addChild(col.type.toString().toLowerCase(),col.title);
            if (!col.rtval.isEmpty() && !col.rtval.equalsIgnoreCase(tableName + "_" + col.title)) {
                fab.attr("rtval", col.rtval);
            }else{
                fab.removeAttr("rtval");
            }
            fab.removeAttr("alias");
            if( !col.defString.isEmpty())
                fab.attr("def",col.defString);
            String setup = (col.primary?"primary ":"")+(col.notnull?"notnull ":"")+(col.unique?"unique ":"");
            if( !setup.isEmpty())
                fab.attr("setup",setup.trim());
        }
        fab.up();

        if (build) {
            fab.build();
        }
    }
    /**
     * Get the name of the table
     * 
     * @return The table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Check if the build query of this table would use 'if not exists'
     * @return True ifnotexists flag is high
     */
    public boolean hasIfNotExists() {
        return ifnotexists;
    }
    /**
     * Get the CREATE statement to make this table
     * 
     * @return The CREATE statement in string format
     */
    public String create() {
        return toString();
    }

    /**
     * Get all the info about this table
     * 
     * @return Info message
     */
    public String getInfo() {
        StringJoiner join = new StringJoiner("\r\n", "Table '" + tableName + "'\r\n", "");
        for (SqlColumn column : columns) {
            join.add("> " + column.toString()
                    + (column.rtval.equals(column.title) ? "" : " (rtval=" + column.rtval + ")"));
        }
        return join + "\r\n";
    }

    /**
     * Get the CREATE statement to make this table
     * 
     * @return The CREATE statement in string format
     */
    public String toString() {
        if (columns.isEmpty() )
            return "CREATE TABLE" + (ifnotexists ? " IF NOT EXISTS " : " ") + "`" + tableName + "`;";

        StringJoiner join = new StringJoiner(", ",
                "CREATE TABLE " + (ifnotexists ? "IF NOT EXISTS" : "") + " `" + tableName + "` (", " );");
        columns.forEach(x -> join.add(x.toString()));
        return join.toString();
    }

    public int getRecordCount() {
        return preps.values().stream().mapToInt(SqlTableFab.PrepStatement::recordSize).sum();
    }
    public boolean hasRecords(){
        return preps.values().stream().anyMatch(SqlTableFab.PrepStatement::hasRecords);
    }
    public boolean hasRecords(String id){
        return getPrep(id).map(SqlTableFab.PrepStatement::hasRecords).orElse(false);
    }

    private Optional<SqlTableFab.PrepStatement> getPrep(String id) {
        return Optional.ofNullable(preps.get(id));
    }
    public String getPreparedStatement( String id ) {
        if( id.isEmpty())
            return getPreparedStatement();
        return getPrep(id).map(SqlTableFab.PrepStatement::getStatement).orElse("");
    }

    public HashMap<String, SqlTableFab.PrepStatement> getPreps() {
        return preps;
    }
    public String getPreparedStatement() {
        SqlTableFab.PrepStatement prep = preps.get("");
        if( prep.getStatement().isEmpty() )
            buildDefStatement();
        return prep.getStatement();
    }
    public int fillStatement( String id, PreparedStatement ps ) {
        SqlTableFab.PrepStatement prep = preps.get(id);
        if( prep==null || ps==null)
            return -1;

        int count=0;
        var tempRecords = prep.drainRecords();
        for (int a = 0; a < tempRecords.size(); a++) { //foreach can cause concurrency issues

            Object[] d = tempRecords.get(a);
            if( d==null ){
                Logger.error(tableName + ":" + (id.isEmpty() ? "def" : id) + " -> Asked for a record at " + a + " which is null... skipping");
                continue;
            }
            int index = 0;
            try {
                for ( int colIndex : prep.getIndexes() ) {
                    SqlColumn c = columns.get(colIndex);
                    try{
                        if( d[index] instanceof OffsetDateTime )
                            d[index] = asTimestamp((OffsetDateTime) d[index]);
                        ps.setObject( index+1,d[index] );
                        index++;
                    }catch( java.lang.ClassCastException | NullPointerException e){
                        Logger.error(tableName + ":" + id + " -> Failed to cast " + d[index] + " to " + c.type);
                        Logger.error(e);
                        break;
                    }                    
                }
                count++;                
                ps.addBatch();
            } catch ( Exception e ) {
                Logger.error(e);
                return -1;
            } 
        }
        return count;
    }
    public void dumpData(String id, Path path) {
        SqlTableFab.PrepStatement prep = preps.get(id);
        if( prep==null )
            return;

        if (!path.toString().endsWith(".csv"))
            path = path.resolve(tableName + "_dump.csv");
        if (Files.notExists(path)) {
            var join = new StringJoiner(";");
            columns.forEach( c -> join.add(c.title));
            FileTools.appendToTxtFile(path, getPreparedStatement() + System.lineSeparator() + join + System.lineSeparator());
        }
        for (var data : prep.drainRecords()) {
            var join = new StringJoiner(";");
            Arrays.stream(data).forEach(dd -> join.add(String.valueOf(dd)));
            FileTools.appendToTxtFile(path, join + System.lineSeparator());
        }
    }
    public static Timestamp asTimestamp(OffsetDateTime offsetDateTime) {
        if (offsetDateTime != null)
            return Timestamp.valueOf(offsetDateTime.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        return null;
    }

    public void clearTempRecords(String id) {
        preps.get(id).clearTempRecords();
    }

    public int removeBadRecordsAndQueue(String id, long[] updateCounts) {
        SqlTableFab.PrepStatement prep = preps.get(id);
        if( prep==null){
            Logger.error(tableName + " -> No such prep: " + id);
            return -1;
        }

        var dd = prep.getTempRecords();
        int offset=0;
        for( int index=0;index<updateCounts.length;index++){
            if( updateCounts[index]==Statement.EXECUTE_FAILED || updateCounts[index]==Statement.SUCCESS_NO_INFO){
                var bad = dd.remove(index - offset);
                Logger.error("Removed query: " + badQuery(prep, bad));
                offset++;
            }
        }
        prep.requeueTemp();
        return offset;
    }

    private String badQuery(SqlTableFab.PrepStatement prep, Object[] bad) {
        var join = new StringJoiner(", ",prep.statement+" -> Data: ","");
        for (var o : bad)
            join.add(String.valueOf(o));
        return join.toString();
    }
    public int doInsert(Object[] values){
        return getPrep("").map(p -> p.add(values) ? 1 : 0).orElse(-1);
    }


    public boolean insertFromStore(String id ){
        if( stores.isEmpty())
            return false;

        if (noValidStore(id)) {
            Logger.error(tableName + " -> No valid store, aborting insert.");
            return false;
        }

        var store = stores.get(id);
        var rt = store.getCurrentValues(); // Get the values of the rtvals

        SqlTableFab.PrepStatement prep = preps.get(id);
        if( prep==null){
            Logger.error(tableName + " -> No such prep: " + id);
            return false;
        }

        Object[] record = new Object[columns.size()];
        int index=-1;
        for( int colPos : prep.getIndexes() ){
            SqlColumn col = columns.get(colPos);
            index++;

            Object val = col.fetchData(id, rt.get(index));
            if (val != null) {
                record[index] = val;
            }else{
                Logger.error(id + " -> Couldn't find " + col.rtval + " for " + tableName + " aborted insert.");
                return false;
            }
        }
        prepCount++;
        return prep.add(record);
    }

    public long getPrepCount(){
        return prepCount;
    }


    /**
     * Gives all the data as strings to be parsed to the correct object, so it can use the prepared statement
     * @param id The id of the prepared statement to use
     * @param data The array of data
     * @return True if it works
     */
    public boolean parsePrep( String id, String[] data ){
        SqlTableFab.PrepStatement prep = preps.get(id);
        if( prep==null){
            Logger.error(tableName + " -> No such prep: " + id);
            return false;
        }
        int dataIndex=0;
        int recordIndex=0;

        Object[] record = new Object[columns.size()];
        for( int colPos : prep.getIndexes() ){
            SqlColumn col = columns.get(colPos);
            String def = col.getDefault();

            try{
                record[recordIndex++] = switch(col.type ){
                    case INTEGER -> {
                        var defI = def.isEmpty()?Integer.MAX_VALUE:NumberUtils.createInteger(def);
                        var val = NumberUtils.toInt(data[dataIndex++],defI);
                        yield val==Integer.MAX_VALUE?null:val;
                    }
                    case REAL -> {
                        var defI = def.isEmpty()?Double.MAX_VALUE:NumberUtils.createDouble(def);
                        var val = NumberUtils.toDouble(data[dataIndex++],defI);
                        yield val==Double.MAX_VALUE?null:val;
                    }
                    case TEXT,OBJECT,TIMESTAMP -> data[dataIndex++];
                    case EPOCH -> Instant.now().toEpochMilli();
                    case LOCALDTNOW -> server?OffsetDateTime.now():OffsetDateTime.now().toString();
                    case UTCDTNOW -> server?OffsetDateTime.now(ZoneOffset.UTC):OffsetDateTime.now(ZoneOffset.UTC).toString();
                    case DATETIME -> {
                        var dt = TimeTools.parseDateTime(data[dataIndex++], "yyyy-MM-dd HH:mm:ss.SSS");
                        yield server ? dt : dt.toString();
                    }
                    case UNKNOWN -> null;
                };
            }catch( NullPointerException e ){
                Logger.error(id+" -> Null pointer when looking for at "+dataIndex + " type:"+col.type);
            }
        }
        prepCount++;
        return prep.add(record);
    }

    private void buildDefStatement(){
        SqlTableFab.PrepStatement stat = preps.get("");

        StringJoiner qMarks = new StringJoiner(",", "", ");");
        StringJoiner cols = new StringJoiner(",", "INSERT INTO `" + tableName + "` (", ") VALUES (");
        stat.getIndexes().forEach(c -> {
            qMarks.add("?");
            cols.add( columns.get(c).title );
        });
        stat.setStatement( cols + qMarks.toString() );
    }

}