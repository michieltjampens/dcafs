package util.data.vals;

import das.Commandable;
import das.Paths;
import io.Writable;
import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Rtvals implements Commandable {
    /* Data stores */
    private final ConcurrentHashMap<String, RealVal> realVals = new ConcurrentHashMap<>();         // doubles
    private final ConcurrentHashMap<String, IntegerVal> integerVals = new ConcurrentHashMap<>(); // integers
    private final ConcurrentHashMap<String, TextVal> textVals = new ConcurrentHashMap<>();         // strings
    private final ConcurrentHashMap<String, FlagVal> flagVals = new ConcurrentHashMap<>();         // booleans
    private final HashMap<String, DynamicUnit> units = new HashMap<>();

    public Rtvals() {
        readFromXML(XMLdigger.goIn(Paths.settings(), "dcafs", "rtvals"));
    }

    /**
     * Read an rtvals node
     */
    public void readFromXML(XMLdigger dig) {

        if (dig.isInvalid()) {
            Logger.info("No rtvals in settings.xml");
            return;
        }
        Logger.info("Reading rtvals");
        dig.digOut("group").forEach(d -> {
            var groupName = d.attr("id", "");
            realVals.putAll(ValFab.digRealVals(d, groupName));
            integerVals.putAll(ValFab.digIntegerVals(d, groupName));
            flagVals.putAll(ValFab.digFlagVals(d, groupName));
            textVals.putAll(ValFab.digTextVals(d, groupName));
        });

        Logger.info("Reading Dynamic Units");
        dig.digOut("unit").forEach(node -> {
            var pair = DynamicUnit.processUnitElement(node);
            units.put(pair.getKey(), pair.getValue());
        });
    }

    public void removeVal(BaseVal val) {
        if (val == null)
            return;
        if (val instanceof RealVal) {
            realVals.remove(val.id());
        } else if (val instanceof IntegerVal) {
            integerVals.remove(val.id());
        } else if (val instanceof FlagVal) {
            flagVals.remove(val.id());
        } else if (val instanceof TextVal) {
            textVals.remove(val.id());
        }
    }

    /**
     * Adds the AbstractVal to the appropriate collection if not in it yet and returns the val at the key
     *
     * @param val The val to add if new
     * @return The final val at the key
     */
    public BaseVal AddIfNewAndRetrieve(BaseVal val) {
        if (val instanceof RealVal rv) {
            realVals.putIfAbsent(val.id(), rv);
            return realVals.get(val.id());
        }
        if (val instanceof IntegerVal iv) {
            integerVals.putIfAbsent(val.id(), iv);
            return integerVals.get(val.id());
        }
        if (val instanceof FlagVal fv) {
            flagVals.putIfAbsent(val.id(), fv);
            return flagVals.get(val.id());
        }
        if (val instanceof TextVal tv) {
            textVals.putIfAbsent(val.id(), tv);
            return textVals.get(val.id());
        }
        return null;
    }
    /* ************************************ R E A L V A L ***************************************************** */

    /**
     * Add a RealVal to the collection if it doesn't exist yet
     *
     * @param rv The RealVal to add
     */
    public void addRealVal(RealVal rv) {
        if (rv == null) {
            Logger.error("Invalid RealVal received, won't try adding it");
            return;
        }
        realVals.putIfAbsent(rv.id(), rv);
    }

    public boolean hasReal(String id) {
        if (id.isEmpty()) {
            Logger.error("RealVal -> Empty id given");
            return false;
        }
        return realVals.containsKey(id);
    }

    /**
     * Retrieve a RealVal from the hashmap based on the id
     *
     * @param id The reference with which the object was stored
     * @return The requested RealVal or null if not found
     */
    public Optional<RealVal> getRealVal(String id) {
        if (hasReal(id))
            return Optional.ofNullable(realVals.get(id));
        Logger.warn("Tried to retrieve non existing RealVal " + id);
        return Optional.empty();
    }

    /**
     * Sets the value of a real (in the hashmap)
     *
     * @param id    The parameter name
     * @param value The value of the parameter
     */
    public void updateReal(String id, double value) {
        getRealVal(id).ifPresent(r -> r.update(value));
    }

    /**
     * Get the value of a real
     *
     * @param id     The id to get the value of
     * @param defVal The value to return of the id wasn't found
     * @return The value found or the bad value
     */
    public double getReal(String id, double defVal) {
        return getRealVal(id).map(RealVal::value).orElse(defVal);
    }
    /* ************************************ I N T E G E R V A L ***************************************************** */

    /**
     * Adds an integerval if it doesn't exist yet
     *
     * @param iv The IntegerVal to add
     */
    public void addIntegerVal(IntegerVal iv) {
        if (iv == null) {
            Logger.error("Invalid IntegerVal received, won't try adding it");
            return;
        }
        integerVals.putIfAbsent(iv.id(), iv);
    }

    public boolean hasInteger(String id) {
        return integerVals.containsKey(id);
    }

    /**
     * Retrieve a IntegerVal from the hashmap based on the id
     *
     * @param id The reference with which the object was stored
     * @return The requested IntegerVal or empty optional if not found
     */
    public Optional<IntegerVal> getIntegerVal(String id) {
        if (hasInteger(id))
            return Optional.ofNullable(integerVals.get(id));
        Logger.warn("Tried to retrieve non existing IntegerVal " + id);
        return Optional.empty();
    }

    /* *********************************** T E X T S  ************************************************************* */
    public void addTextVal(TextVal tv) {
        if (tv == null) {
            Logger.error("Invalid IntegerVal received, won't try adding it");
            return;
        }
        textVals.putIfAbsent(tv.id(), tv);
    }

    public boolean hasText(String id) {
        return textVals.containsKey(id);
    }

    /**
     * Retrieve a TextVal from the hashmap based on the id
     *
     * @param id The reference with which the object was stored
     * @return The requested TextVal or empty optional if not found
     */
    public Optional<TextVal> getTextVal(String id) {
        if (!hasText(id) && !id.startsWith("dcafs"))
            Logger.warn("Tried to retrieve non existing TextVal " + id);
        return Optional.ofNullable(textVals.get(id));
    }

    /**
     * Set the value of a TextVal and create it if it doesn't exist yet
     *
     * @param id    The name/id of the val
     * @param value The new content
     */
    public void setText(String id, String value) {

        if (id.isEmpty()) {
            Logger.error("Empty id given");
            return;
        }
        if (textVals.containsKey(id)) {
            textVals.get(id).parseValue(value);
        } else {
            var split = id.split("_", 2);
            textVals.put(id, new TextVal(split[0], split[1], "").value(value));
        }
    }

    /* ************************************** F L A G S ************************************************************* */
    public void addFlagVal(FlagVal fv) {
        if (fv == null) {
            Logger.error("Invalid IntegerVal received, won't try adding it");
            return;
        }
        flagVals.putIfAbsent(fv.id(), fv);
    }

    public boolean hasFlag(String flag) {
        return flagVals.containsKey(flag);
    }

    public Optional<FlagVal> getFlagVal(String id) {
        return Optional.ofNullable(flagVals.get(id));
    }

    public boolean getFlagState(String id) {
        return getFlagVal(id).map(FlagVal::isUp).orElse(false);
    }
    /* ******************************************************************************************************/

    /**
     * Look through all the vals for one that matches the id
     *
     * @param id The id to find
     * @return An optional of the val, empty if not found
     */
    public Optional<BaseVal> getBaseVal(String id) {
        return Stream.of(realVals, integerVals, flagVals, textVals)  // Stream of your maps
                .map(map -> (BaseVal) map.get(id))  // For each map, try to get the value by id
                .filter(Objects::nonNull)  // Filter out any null results
                .findFirst();  // Return the first non-null value (if any)
    }

    /**
     * Look through all the vals for one that matches the id
     *
     * @param id The id to find
     * @return An optional of the val, empty if not found
     */
    public boolean hasBaseVal(String id) {
        return Stream.of(realVals, integerVals, flagVals, textVals)
                .map(map -> (BaseVal) map.get(id))
                .anyMatch(Objects::nonNull);
    }

    /* *************************** WRITABLE *********************************************************************** */
    public int addRequest(Writable writable, String type, String req) {
        var av = switch (type) {
            case "double", "real" -> realVals.get(req);
            case "int", "integer" -> integerVals.get(req);
            case "text" -> textVals.get(req);
            case "flag" -> flagVals.get(req);
            default -> {
                Logger.warn("rtvals -> Requested unknown type: " + type);
                yield null;
            }
        };
        if (av == null)
            return 0;
        // av.addTarget(writable);
        return 1;
    }

    public boolean addRequest(Writable writable, String rtval) {
        var val = getBaseVal(rtval);
        // TODO
        return false;
    }

    /* ************************************************************************************************************ */
    public String getNameVals(String regex) {
        return Stream.of(realVals, integerVals, flagVals, textVals)
                .flatMap(map -> map.values().stream()) // Flatten all the values from the maps
                .filter(val -> val.name().matches(regex)
                        && (!val.group().equals("dcafs") || !(val instanceof TextVal))) // Filter by group
                .map(val -> val.id() + " : " + val.asString())
                .collect(Collectors.joining("\r\n"));
    }

    public ArrayList<BaseVal> getGroupVals(String group) {
        return Stream.of(realVals, integerVals, flagVals, textVals)
                .flatMap(map -> map.values().stream()) // Flatten all the values from the maps
                .filter(val -> val.group().equalsIgnoreCase(group)) // Filter by group
                .collect(Collectors.toCollection(ArrayList::new)); // Collect the results into a List
    }

    /**
     * Get a listing of all stored variables that belong to a certain group
     *
     * @param group The group they should belong to
     * @param html  Use html formatting or telnet
     * @return The listing
     */
    public String getRTValsGroupList(String group, boolean html) {

        String title;
        if (group.isEmpty()) {
            title = html ? "<b>Ungrouped</b>" : TelnetCodes.TEXT_CYAN + "Ungrouped" + TelnetCodes.TEXT_YELLOW;
        } else {
            title = html ? "<b>Group: " + group + "</b>" : TelnetCodes.TEXT_CYAN + "Group: " + group + TelnetCodes.TEXT_YELLOW;
        }

        String eol = html ? "<br>" : "\r\n";
        StringJoiner join = new StringJoiner(eol, title + eol, "");
        join.setEmptyValue("None yet");

        var list = Stream.of(realVals, integerVals, flagVals, textVals)
                .flatMap(bv -> bv.values().stream())
                .filter(bv -> bv.group().equalsIgnoreCase(group))
                .map(bv -> {
                    if (bv instanceof NumericVal nv)
                        return nv.id() + " : " + applyUnit(nv);
                    return bv.id() + " : " + bv.asString() + bv.unit();
                }).toList();

        boolean toggle = false;
        for (var line : list) {
            if (!line.contains("Group:")) {
                line = (toggle ? TelnetCodes.TEXT_DEFAULT : TelnetCodes.TEXT_YELLOW) + line;
                toggle = !toggle;
            }
            join.add(line);
        }
        return join.toString();
    }

    public String applyUnit(NumericVal nv) {
        if (units.isEmpty() || nv instanceof FlagVal)
            return nv.asString() + nv.unit();

        DynamicUnit unit = null;
        for (var set : units.entrySet()) {
            if (nv.unit().endsWith(set.getKey())) {
                unit = set.getValue();
                break;
            }
        }
        if (unit == null || unit.noSubs())
            return nv.asString();

        if (nv.getClass() == RealVal.class) {
            var rv = (RealVal) nv;
            if (Double.isNaN(rv.asDouble()))
                return "NaN";
            return unit.apply(rv.value(), rv.unit());
        }
        return unit.apply(nv.asDouble(), nv.unit());
    }

    /**
     * Get the full listing of all reals,flags and text, so both grouped and ungrouped
     *
     * @param html If true will use html newline etc
     * @return The listing
     */
    public String getRtvalsList(boolean html) {
        String eol = html ? "<br>" : "\r\n";
        StringJoiner join = new StringJoiner(eol, "Status at " + TimeTools.formatShortUTCNow() + eol + eol, "");
        join.setEmptyValue("None yet");

        // Find & add the groups
        for (var group : getGroups()) {
            var res = getRTValsGroupList(group, html);
            if (!res.isEmpty() && !res.equalsIgnoreCase("none yet"))
                join.add(res).add("");
        }
        var res = getRTValsGroupList("", html);
        if (!res.isEmpty() && !res.equalsIgnoreCase("none yet"))
            join.add(res).add("");

        if (!html)
            return join.toString();

        // Try to fix some symbols to correct html counterpart
        return join.toString().replace("°C", "&#8451") // fix the °C
                .replace("m²", "&#13217;") // Fix m²
                .replace("m³", "&#13221;"); // Fix m³
    }

    /**
     * Get a list of all the groups that exist in the rtvals
     *
     * @return The list of the groups
     */
    public List<String> getGroups() {
        return Stream.of(realVals, integerVals, flagVals, textVals)
                .flatMap(map -> map.values().stream()) // Flatten all the values from the maps
                .map(BaseVal::group) // Get the group instead
                .filter(val -> !val.isEmpty() && !val.equals("dcafs"))
                .distinct()
                .toList();
    }

    @Override
    public String replyToCommand(Datagram d) {
        return "";
    }

    @Override
    public boolean removeWritable(Writable wr) {
        // TODO
        return false;
    }
}
