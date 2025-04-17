package util.database;

import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public abstract class Database{

    protected long firstSimpleStamp = 0; // The System.currentTimeInMillis of the last query
    protected long firstPrepStamp = 0; // The System.currentTimeInMillis of the last query

    protected String id = ""; // the name of the database
    protected String user = ""; // username with writing rights
    protected String pass = ""; // password for the username
    protected String irl = ""; // irl string for the connection

    protected int maxQueries = 30; // Maximum amount of queries in a buffer before a flush is triggered
    protected long maxAge = 30; // Maximum age of the oldest query in the buffer before a flush is triggered
    protected int idleCount = 0; // How many seconds this has been idle
    protected int idleTime = -1; // Amount of seconds before a database is considered idle and the disconnected
    protected long maxInsertAge=3600;   // Max age since last insert before it's considered a problem
    protected STATE state = STATE.IDLE; // current state of the database

    protected enum STATE {
        IDLE, CON_BUSY, HAS_CON, NEED_CON, FLUSH_REQ, HAS_DUMP,ACCESS_DENIED
    }

    protected boolean tablesRetrieved = false;
    protected String lastError="";

    /* ************************************************************************************************* */

    /**
     * Set the ID  of this database
     * 
     * @param id The id given
     */
    public void id(String id) {
        this.id = id;
    }

    /**
     * Get the database id
     * 
     * @return the id given to this database
     */
    public String id() {
        return id;
    }
    /**
     * Read the part of the node that contains the information on records flushing
     *
     * @param set The element that contains the info
     */
    protected void readFlushSetup(Element set){
        if( set != null ){
            var dig = XMLdigger.goIn(set);
            maxAge = TimeTools.parsePeriodStringToSeconds( dig.attr("age", "30s") ); // How much time before data is flushed (if not reached batch size)
            maxQueries = dig.attr("batchsize", 30);		 // Minimum amount of queries before a flush unless checks till flush is reached
            Logger.debug( id+" -> Flush:"+maxAge+"s maxQ:"+maxQueries);
        }else{
            Logger.debug( id+" -> No changes requested to default flush/idle values ");
        }
    }
    protected long getTimeSinceLastInsert(){
        long prep=Math.max(firstPrepStamp,firstSimpleStamp);
        if(prep==0)
            return -1;
        return (Instant.now().toEpochMilli()-prep)/1000;
    }
    /* Abstract Methods */

    /**
     * Connect to the database
     * @param force If true and already connected, disconnect first
     * @return True if connection was established
     */
    public abstract boolean connect(boolean force); // Connect to the database

    /**
     * Disconnect from the database
     * @return True if disconnected
     */
    public abstract boolean disconnect(); // Disconnect from the database

    /**
     * Check if the connection to the database is still valid
     * @param timeout How long to wait for a reply to the connection test in seconds
     * @return True is valid
     */
    public abstract boolean isValid(int timeout); // Check if the connection is still valid

    /**
     * Get the amount of records currently buffered
     * @return Get the amount of bufferd records
     */
    public abstract int getRecordsCount() ;

    /**
     * Check if there are buffered records
     * @return True if there's at least one buffered record
     */
    public abstract boolean hasRecords();

    /**
     * Write the current settings to xml
     * @param fab The fab that points to the parent to insert the database node in
     */
    public abstract void writeToXml(XMLfab fab);

    /**
     * Retrieve the current tables from the data
     * @param clear True means clearing the local tables first
     * @return True if successful
     */
    public abstract boolean getCurrentTables(boolean clear);

    /**
     * Create the local tables and views in the database
     * @param keepConnection True if the established connection should be kept
     * @return The created content
     */
    public abstract String createContent(boolean keepConnection);

    /**
     * Get all the information on the local tables
     * @param eol The eol sequence to use
     * @return The gathered information
     */
    public abstract String getTableInfo(String eol);
    /**
     * Check and update the current state of the database
     * @param secondsPassed How many seconds passed since the last check (interval so fixed)
     * @throws Exception Catch any exception so the thread doesn't get killed
     */
    public abstract void checkState( int secondsPassed ) throws Exception;
    /**
     * Write a select query and then retrieve the content of a single column from it base on the (case insensitive) name
     * @param query The query to execute
     * @return ArrayList with the data or an empty list if nothing found/something went wrong
     */
    public abstract Optional<List<List<Object>>> doSelect(String query, boolean includeNames );

    /**
     * Run a select query and return the result but don't include columnnames in top index
     * @param query The query to execute
     * @return ArrayList with the data or an empty list if nothing found/something went wrong
     */
    public Optional<List<List<Object>>> doSelect(String query ){
        return doSelect(query,false);
    }
}