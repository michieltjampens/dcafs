package util.database;

import das.Paths;
import org.tinylog.Logger;
import util.data.store.ValStore;
import util.data.vals.*;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

public class SqlTableFab {

    public static Optional<SqlTable> buildSqlTable(XMLdigger dig, boolean server) {

        var tableName = dig.attr("name", "").trim();
        var table = SqlTable.withName(tableName);
        var allow = dig.attr("allowinserts", "");
        if (!allow.isEmpty()) {
            var split = allow.split("_", 2);
            table.setAllowInsertsFlag(FlagVal.newVal(split[0], split[1]));
        }
        boolean ok = true;

        // Build the columns
        for (var node : dig.digOut("*")) {
            if (node == null)
                continue;
            var columnName = node.value("").trim();
            if (columnName.equals(".")) {
                Logger.error("Column still needs a name! " + tableName);
                ok = false;
                break;
            }

            var column = processColumnNode(node, tableName);
            if (column == null)
                return Optional.empty();
            column.setIsServer(server);
            table.addColumn(column);
        }
        if (ok) {
            table.ready_state = SqlTable.TABLE_STATE.READ_FROM_XML;
            return Optional.of(table);
        }
        return Optional.empty();
    }

    public static void buildTableStore(SqlTable table, Rtvals rtvals) {
        table.getStores().values().forEach(store -> store.removeRealtimeValues(rtvals));
        table.getStores().clear();

        for (var prep : table.getPreps().entrySet()) {
            var store = buildStore(rtvals, table.getTableName(), table.getColumns(), prep.getValue());
            if (store == null || store.isInvalid()) {
                Logger.error("Failed to build store for " + table.getTableName());
                return;
            }
            table.getStores().put(prep.getKey(), store);
        }
    }

    private static SqlColumn processColumnNode(XMLdigger node, String tableName) {
        var columnName = node.value("");
        var rtval = node.attr("rtval", tableName + "_" + columnName);
        var type = parseType(node.tagName(""), columnName);
        if (type == SqlColumn.COLUMN_TYPE.UNKNOWN)
            return null;
        var column = buildColumn(type, columnName, tableName, rtval);

        /* Setup of the column */
        String setup = node.attr("setup", "").toLowerCase();
        column.primary = setup.contains("primary");
        column.notnull = setup.contains("notnull");
        column.unique = setup.contains("unique");
        if (node.hasAttr("def"))
            column.setDefault(node.attr("def", ""));

        return column;
    }

    private static SqlColumn.COLUMN_TYPE parseType(String type, String columnName) {
        return switch (type.toLowerCase()) {
            case "int", "integer", "bit", "boolean" -> SqlColumn.COLUMN_TYPE.INTEGER;
            case "double", "decimal", "float", "real" -> SqlColumn.COLUMN_TYPE.REAL;
            case "text", "string", "char" -> SqlColumn.COLUMN_TYPE.TEXT;
            case "localdtnow", "localnow" -> SqlColumn.COLUMN_TYPE.LOCALDTNOW;
            case "utcdtnow", "utcnow" -> SqlColumn.COLUMN_TYPE.UTCDTNOW;
            case "datetime", "timestamp" -> SqlColumn.COLUMN_TYPE.DATETIME;
            case "long" -> {
                if (columnName.equals("timestamp"))
                    yield SqlColumn.COLUMN_TYPE.EPOCH;
                yield SqlColumn.COLUMN_TYPE.UNKNOWN;
            }
            case "epoch", "epochmillis" -> SqlColumn.COLUMN_TYPE.EPOCH;
            default -> SqlColumn.COLUMN_TYPE.UNKNOWN;
        };
    }

    public static SqlColumn addColumnToTable(SqlTable table, String type, String columnName) {
        var enumType = parseType(type, columnName);
        if (enumType == SqlColumn.COLUMN_TYPE.UNKNOWN) {
            Logger.error(" -> Found unknown column type in " + table.getTableName() + ": " + columnName + " -> " + type);
            return null;
        }
        var col = buildColumn(parseType(type, columnName), columnName, table.getTableName(), "");
        table.addColumn(col);
        return col;
    }

    private static SqlColumn buildColumn(SqlColumn.COLUMN_TYPE type, String columnName, String tableName, String rtval) {
        if (rtval.isEmpty()) {
            rtval = tableName + "_" + columnName;
            rtval = rtval.toLowerCase();
        }
        return new SqlColumn(columnName, tableName, rtval, type);
    }

    private static ValStore buildStore(Rtvals rtvals, String tableName, ArrayList<SqlColumn> columns, PrepStatement prep) {
        //SqlTableFab.PrepStatement prep = preps.get("");
        if (prep == null) {
            Logger.error(tableName + " -> No such prep: " + tableName);
            return null;
        }
        Logger.info(tableName + " -> Building store");

        var store = new ValStore("");

        for (int colPos : prep.getIndexes()) {
            SqlColumn col = columns.get(colPos);
            String ref = col.rtval;
            try {
                switch (col.type) {
                    case EPOCH -> store.addEmptyVal();
                    case REAL -> {
                        if (!doReal(rtvals, ref, store, col, tableName))
                            return null;
                    }
                    case INTEGER -> {
                        if (!doInteger(rtvals, ref, store, col, tableName))
                            return null;
                    }
                    case UTCDTNOW -> doUtcdtNow(rtvals, store);
                    case LOCALDTNOW -> doLocaldtNow(rtvals, store);
                    case TEXT, DATETIME -> {
                        var v = rtvals.getTextVal(ref);
                        if (v.isPresent()) {
                            store.addAbstractVal(v.get());
                        } else {
                            Logger.error(tableName + " -> Couldn't find " + ref);
                            store.addEmptyVal();
                        }
                    }

                }
            } catch (NullPointerException e) {
                Logger.error(tableName + " -> Null pointer when looking for " + ref + " type:" + col.type);
            }
        }
        return store;
    }

    private static boolean doReal(Rtvals rtvals, String ref, ValStore store, SqlColumn col, String tableName) {
        var v = rtvals.getRealVal(ref);
        if (v.isPresent()) {
            store.addAbstractVal(v.get());
            return true;
        }
        if (col.hasDefault) {
            Logger.warn(tableName + " -> Couldn't find " + ref + " using column default");
            var rv = RealVal.newVal(tableName, col.title);
            if (rv.parseValue(col.defString)) {
                store.addAbstractVal(rv);
                return true;
            }
            Logger.error(tableName + " -> Failed to parse the default of " + col.title + " to a real.");
            return false;
        }
        Logger.error(tableName + " -> Couldn't find " + ref + " AND no column default, abort store building");
        return true;
    }

    private static boolean doInteger(Rtvals rtvals, String ref, ValStore store, SqlColumn col, String tableName) {
        var v = rtvals.getIntegerVal(ref);
        if (v.isPresent()) {
            store.addAbstractVal(v.get());
            return true;
        }

        if (col.hasDefault) {
            Logger.warn(tableName + " -> Couldn't find the IntVal " + ref + " using column default");
            var iv = IntegerVal.newVal(tableName, col.title);
            if (iv.parseValue(col.defString)) {
                store.addAbstractVal(iv);
                return true;
            }
            Logger.error(tableName + " -> Failed to parse the default of " + col.title + " to an integer.");
            return false;
        }
        Logger.error(tableName + " -> Couldn't find the IntVal " + ref + " using AND no column default, abort store building");
        return true;
    }

    private static void doLocaldtNow(Rtvals rtvals, ValStore store) {
        var v = rtvals.getTextVal("dcafs_localdt");
        if (v.isPresent()) {
            store.addAbstractVal(v.get());
        } else {
            var tv = TextVal.newLocalTimeVal("dcafs", "localdt");
            rtvals.addTextVal(tv);
            store.addAbstractVal(tv);
        }
    }

    private static void doUtcdtNow(Rtvals rtvals, ValStore store) {
        var v = rtvals.getTextVal("dcafs_utcdt");
        if (v.isPresent()) {
            store.addAbstractVal(v.get());
        } else {
            var tv = TextVal.newUTCTimeVal("dcafs", "utcdt");
            rtvals.addTextVal(tv);
            store.addAbstractVal(tv);
        }
    }

    /**
     * Add a blank table node to the given database (can be both server or sqlite)
     *
     * @param id     The id of the database the table belongs to
     * @param table  The name of the table
     * @param format The format of the table
     * @return True if build
     */
    public static boolean addBlankTableToXML(String id, String table, String format) {

        var dig = XMLdigger.goIn(Paths.settings(), "dcafs", "databases");
        if (dig.hasPeek("sqlite", "id", id) || dig.hasPeek("server", "id", id)) { // it's a server
            dig.usePeek();
        } else {
            return false;
        }
        return XMLfab.alterDigger(dig)
                .filter(xmLfab -> addBlankToXML(xmLfab, table, format))
                .isPresent();
    }

    /**
     * Adds a blank table node according to the format to the fab with current parent the database node;
     *
     * @param fab       The fab with the database node as current parent
     * @param tableName The name of the table
     * @param format    The format of the table, t=timestamp,r=real,i=int,c=text,m=epochmillis
     * @return True if written
     */
    public static boolean addBlankToXML(XMLfab fab, String tableName, String format) {

        fab.addChild("table").attr("name", tableName).down();

        for (char c : format.toCharArray()) {
            switch (c) {
                case 't' -> fab.addChild("timestamp", "columnname");
                case 'u' -> fab.addChild("utcnow", "columnname");
                case 'r' -> fab.addChild("real", "columnname");
                case 'i' -> fab.addChild("int", "columnname");
                case 'c' -> fab.addChild("text", "columnname");
                case 'm' -> fab.addChild("epochmillis", "columnname");
            }
        }
        return fab.build();
    }

    public static class PrepStatement {
        LinkedBlockingQueue<Object[]> data = new LinkedBlockingQueue<>();
        ArrayList<Integer> indexes = new ArrayList<>(); // which columns
        ArrayList<Object[]> tempRecords = new ArrayList<>();
        String statement = "";

        public int recordSize() {
            return data.size();
        }

        public boolean hasRecords() {
            return !data.isEmpty();
        }

        public ArrayList<Object[]> drainRecords() {
            tempRecords.clear();
            data.drainTo(tempRecords);
            return tempRecords;
        }

        public void clearTempRecords() {
            tempRecords.clear();
        }

        public ArrayList<Object[]> getTempRecords() {
            return tempRecords;
        }

        public void requeueTemp() {
            if (data.addAll(tempRecords)) {
                tempRecords.clear();
            } else {
                Logger.error("Failed to restore temps.");
            }
        }

        public void addColumnIndex(int index) {
            indexes.add(index);
        }

        public List<Integer> getIndexes() {
            return indexes;
        }

        public boolean add(Object[] d) {
            if (d.length != indexes.size())
                return false;
            return data.add(d);
        }

        public void addAll(ArrayList<Object[]> records) {
            data.addAll(records);
        }

        public void setStatement(String stat) {
            statement = stat;
        }

        public String getStatement() {
            return statement;
        }
    }
}
