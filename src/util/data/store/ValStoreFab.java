package util.data.store;

import das.Core;
import org.tinylog.Logger;
import util.data.vals.BaseVal;
import util.data.vals.Rtvals;
import util.data.vals.ValFab;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.ArrayList;

public class ValStoreFab {

    public static ValStore buildValStore(XMLdigger dig, String id, Rtvals rtvals) {

        var failStore = new ValStore("");
        failStore.invalidate();

        if (!dig.tagName("").equals("store"))
            dig.goUp();

        if (!dig.tagName("").equals("store")) {
            Logger.error("Couldn't find the store node");
            return failStore;
        }

        var idn = dig.attr("id", id);
        if (idn.isEmpty() && !dig.hasAttr("group")) {
            Logger.error("No id/group found");
            return failStore;
        }

        var valStore = new ValStore(idn);
        if (reload(valStore, dig, idn, rtvals))
            return valStore;

        return failStore;
    }

    private static boolean reload(ValStore store, XMLdigger dig, String id, Rtvals rtvals) {

        var groupID = dig.attr("group", id);

        store.delimiter(dig.attr("delimiter", store.delimiter()));
        store.setIdleReset(dig.attr("idlereset", false));

        // Checking for database connection
        digForDatabaseUse(dig, store);

        // Map
        store.mapFlag(dig.attr("map", false));
        if (store.mapped()) { // key based
            if (!digMapBased(dig, groupID, store))
                return false;
        } else { // index based
            if (!digIndexBased(dig, groupID, store))
                return false;
        }
        if (rtvals != null)
            store.shareRealtimeValues(rtvals);
        return true;
    }

    private static void digForDatabaseUse(XMLdigger dig, ValStore store) {
        if (!dig.hasAttr("db")) {
            Logger.info(store.id() + " -> No database referenced.");
            return;
        }
        ArrayList<String[]> dbInsert = new ArrayList<>();

        var dbAttr = dig.attr("db", "");
        Logger.info("Got DB req: " + dbAttr);
        var db = dbAttr.split(";");

        for (var dbi : db) {
            var split = dbi.split(":");
            if (split.length == 1) {
                Logger.error("DB attribute must contain a ':', got " + dig.attr("db", ""));
            }
            if (split[0].contains(",") && split[1].contains(",")) {
                Logger.error(store.id() + "(store) -> Can't have multiple id's and tables defined.");
            } else if (split[0].contains(",")) { // multiple id's but same tables
                for (var id : split[0].split(","))
                    dbInsert.add(new String[]{id, split[1]});
            } else if (split[1].contains(",")) { // multiple tables but same id
                for (var table : split[1].split(","))
                    dbInsert.add(new String[]{split[0], table});
            } else { // one of each
                dbInsert.add(split);
            }
        }
        // Request the table inserts
        for (var last : dbInsert)
            Core.addToQueue(Datagram.system("dbm:" + last[0] + ",tableinsert," + last[1]).payload(store));

        store.setDbInsert(dbInsert);
    }

    private static boolean digMapBased(XMLdigger dig, String groupID, ValStore store) {

        ArrayList<BaseVal> calVal = new ArrayList<>();

        var vals = dig.currentSubs();
        for (var val : dig.digOut("*")) {
            if (checkForCalVal(val, calVal, groupID, store) == null)
                continue;

            var key = val.attr("key", "");
            switch (val.tagName("")) {
                case "real" -> ValFab.buildRealVal(val, groupID, null)
                        .ifPresent(v -> store.putAbstractVal(key, v));
                case "int" -> ValFab.buildIntegerVal(val, groupID, null)
                        .ifPresent(v -> store.putAbstractVal(key, v));
                case "flag", "bool" -> ValFab.buildFlagVal(val, groupID, null)
                        .ifPresent(v -> store.putAbstractVal(key, v));
                case "text" -> ValFab.buildTextVal(val, groupID)
                        .ifPresent(v -> store.putAbstractVal(key, v));
                default -> {
                }
            }
        }
        if (store.mapSize() + calVal.size() != (vals.size())) {
            Logger.error(store.id() + "-> Failed to create an AbstractVal for " + groupID + " while mapping.");
            return false;
        }
        return true;
    }

    private static ArrayList<BaseVal> checkForCalVal(XMLdigger dig, ArrayList<BaseVal> calVal, String groupID, ValStore store) {

        ArrayList<String> calOps = new ArrayList<>();

        String o = dig.attr("o", "");
        if (!o.isEmpty()) { // Skip o's
            calOps.add(o);
            switch (dig.tagName("")) {
                case "real" -> ValFab.buildRealVal(dig, groupID, null).ifPresent(calVal::add);
                case "int", "integer" -> ValFab.buildIntegerVal(dig, groupID, null).ifPresent(calVal::add);
                default -> {
                    Logger.error("Can't do calculation on any other than real and int for now");
                    return null;
                }
            }
            store.setCalval(calVal, calOps);
            return calVal;
        }
        return null;
    }

    private static boolean digIndexBased(XMLdigger dig, String groupID, ValStore store) {
        ArrayList<BaseVal> rtvals = new ArrayList<>();
        for (var val : dig.digOut("*")) {
            if (checkForCalVal(val, new ArrayList<>(), groupID, store) != null)
                continue;

            // Find the index wanted
            int i = val.attr("index", -1);
            if (i == -1)
                i = val.attr("i", -1);
            if (i == -1)
                i = rtvals.size();

            while (i >= rtvals.size()) // Make sure the arraylist has at least the same amount
                rtvals.add(null);

            final int pos = i; // need a final to use in lambda's
            if (rtvals.get(i) != null) {
                Logger.warn(store.id() + "(store) -> Already using index " + i + " overwriting previous content!");
            }
            switch (val.tagName("")) {
                case "real" -> ValFab.buildRealVal(val, groupID, null).ifPresent(x -> rtvals.set(pos, x));
                case "int", "integer" -> ValFab.buildIntegerVal(val, groupID, null).ifPresent(x -> rtvals.set(pos, x));
                case "flag", "bool" -> ValFab.buildFlagVal(val, groupID, null).ifPresent(x -> rtvals.set(pos, x));
                case "ignore" -> rtvals.add(null);
                case "text" -> ValFab.buildTextVal(val, groupID).ifPresent(x -> rtvals.set(pos, x));
                case "macro" -> {
                    Logger.warn("Val of type macro ignored");
                    rtvals.add(null);
                }
                default -> {
                }
            }
            if (rtvals.get(pos) == null) {
                Logger.error("Failed to create an AbstractVal for " + groupID + " of type " + val.tagName(""));
                return false;
            }
        }
        store.addVals(rtvals);
        return true;
    }
}
