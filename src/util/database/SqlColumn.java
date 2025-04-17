package util.database;

import org.tinylog.Logger;
import util.tools.TimeTools;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Inner class that holds all the info regarding a single column
 */
public class SqlColumn {
    public enum COLUMN_TYPE {
        INTEGER, REAL, TEXT, TIMESTAMP, EPOCH, OBJECT, LOCALDTNOW, UTCDTNOW, UNKNOWN, DATETIME
    }

    COLUMN_TYPE type;
    String title;
    String rtval;
    boolean unique = false;
    boolean notnull = false;
    boolean primary = false;
    boolean server = false;
    boolean hasDefault = false;
    String defString = "";

    public SqlColumn(String title, String tableName, String rtval, COLUMN_TYPE type) {
        this.title = title;
        if (rtval.isEmpty()) // if no rtval is given, we assume it's the same as the title
            rtval = tableName + "_" + title;
        this.rtval = rtval;
        this.type = type;

        switch (type) {
            case TIMESTAMP, EPOCH -> notnull = true;
            // these aren't allowed to be null by default
            default -> {
            }
        }
    }

    public void setIsServer(boolean server) {
        this.server = server;
    }

    public void setDefault(String def) {
        this.defString = def;
        hasDefault = true;
    }

    public String getDefault() {
        if (type == COLUMN_TYPE.TEXT) {
            return "'" + defString + "'";
        }
        return defString;
    }

    public Object fetchData(String id, String val) {
        try {
            if (server) {
                var res = switch (type) {
                    case LOCALDTNOW -> LocalDateTime.now();
                    case UTCDTNOW -> OffsetDateTime.now(ZoneOffset.UTC);
                    case DATETIME -> {
                        var ts = TimeTools.parseDateTime(val, "yyyy-MM-dd HH:mm:ss.SSS");
                        yield (Object) ts;
                    }
                    default -> null;
                };
                if (res != null)
                    return res;
            }

            if (type == SqlColumn.COLUMN_TYPE.EPOCH)
                return Instant.now().toEpochMilli();

            if (val == null) {
                if (hasDefault)
                    return defString;
                return null;
            }
            if (type == SqlColumn.COLUMN_TYPE.DATETIME)
                return TimeTools.parseDateTime(val, "yyyy-MM-dd HH:mm:ss.SSS").toString();
            return val;
        } catch (NullPointerException e) {
            Logger.error(id + " -> Null pointer when looking for " + rtval + " type:" + type);
        }
        return null;
    }

    /**
     * Get the string that will be used in the CREATE statement for this column
     */
    public String toString() {

        if ((type == COLUMN_TYPE.TIMESTAMP || type == COLUMN_TYPE.LOCALDTNOW || type == COLUMN_TYPE.UTCDTNOW) && !server) // Timestamp should be timestamp on a server
            return title + " TEXT" + (unique ? " UNIQUE" : "") + (notnull ? " NOT NULL" : "") + (primary ? " PRIMARY KEY" : "");
        if (type == COLUMN_TYPE.EPOCH)
            return title + " REAL" + (unique ? " UNIQUE" : "") + (notnull ? " NOT NULL" : "") + (primary ? " PRIMARY KEY" : "");
        if ((type == COLUMN_TYPE.LOCALDTNOW || type == COLUMN_TYPE.UTCDTNOW))
            return title + " DATETIME" + (unique ? " UNIQUE" : "") + (notnull ? " NOT NULL" : "") + (primary ? " PRIMARY KEY" : "");
        return title + " " + type + (unique ? " UNIQUE" : "") + (notnull ? " NOT NULL" : "") + (primary ? " PRIMARY KEY" : "");
    }
}
