package util.data.vals;

import das.Commandable;
import das.Core;
import das.Paths;
import io.Writable;
import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.ValTools;
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
            ValFab.digRealVals(d, groupName, realVals, this);
            ValFab.digIntegerVals(d, groupName, integerVals, this);

            flagVals.putAll(ValFab.digFlagVals(d, groupName, this));
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

    public String getRTValsGroupList(String group, boolean html) {

        var tempList = Stream.of(realVals, integerVals, flagVals, textVals)
                .flatMap(bv -> bv.values().stream())
                .filter(bv -> bv.group().equalsIgnoreCase(group))
                .sorted(Comparator.comparing(BaseVal::name))
                .collect(Collectors.toCollection(ArrayList::new));

        var syms = Stream.of(realVals)
                .flatMap(bv -> bv.values().stream())
                .filter(bv -> bv.group().equalsIgnoreCase(group))
                .filter(bv -> bv instanceof RealValSymbiote)
                .map(rv -> (RealValSymbiote) rv)
                .toList();

        for (var sym : syms) {
            for (var derived : sym.getDerived())
                tempList.remove(derived);
        }

        String title;
        if (group.isEmpty()) {
            title = html ? "<b>Ungrouped</b>" : TelnetCodes.TEXT_CYAN + "Ungrouped" + TelnetCodes.TEXT_DEFAULT;
        } else {
            title = html ? "<b>Group: " + group + "</b>" : TelnetCodes.TEXT_CYAN + "Group: " + group + TelnetCodes.TEXT_DEFAULT;
        }

        String eol = html ? "<br>" : "\r\n";
        StringJoiner join = new StringJoiner(eol, title + eol, "");
        join.setEmptyValue("None yet");

        int maxLength = tempList.stream().mapToInt(bv -> bv.name().length()).max().orElse(0);  // Get the max value

        for (var val : tempList) {
            if (val instanceof RealValSymbiote sym) {
                join.add(LookAndFeel.prettyPrintSymbiote(sym, "", ""));
            } else {
                join.add(String.format("%-" + maxLength + "s : %s", val.name(), applyUnit(val)));
            }
        }
        var total = join.toString();
        return total.replace("NaN", TelnetCodes.TEXT_ORANGE + "NaN" + TelnetCodes.TEXT_DEFAULT);
    }

    public String applyUnit(BaseVal bv) {

        if (!(bv instanceof NumericVal nv)) {
            return bv.name() + " : " + bv.asString() + bv.unit();
        }
        if (units.isEmpty() || nv instanceof FlagVal)
            return nv.asString() + nv.unit() + nv.getExtraInfo();

        DynamicUnit unit = null;
        for (var set : units.entrySet()) {
            if (nv.unit().endsWith(set.getKey())) {
                unit = set.getValue();
                break;
            }
        }
        if (unit == null || unit.noSubs())
            return nv.asString() + nv.unit() + nv.getExtraInfo();

        if (nv instanceof RealVal rv) {
            if (Double.isNaN(rv.asDouble()))
                return "NaN";
            return unit.apply(rv.value(), rv.unit()) + rv.getExtraInfo();
        }
        return unit.apply(nv.asInteger(), nv.unit()) + nv.getExtraInfo();
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

    /* ************************** C O M M A N D A B L E ***************************************** */
    @Override
    public String replyToCommand(Datagram d) {
        var html = d.asHtml();
        var args = d.args();

        // Switch between either empty string or the telnetcode because of htm not understanding telnet
        String green = html ? "" : TelnetCodes.TEXT_GREEN;
        String reg = html ? "" : TelnetCodes.TEXT_DEFAULT;
        String ora = html ? "" : TelnetCodes.TEXT_ORANGE;

        var result = switch (d.cmd()) {
            case "rv", "reals", "iv", "integers" -> replyToNumericalCmd(d.cmd(), args);
            case "texts", "tv" -> replyToTextsCmd(args);
            case "flags", "fv" -> replyToFlagsCmd(args, html);
            case "rtvals", "rvs" -> replyToRtvalsCmd(args, html);
            case "rtval", "real", "int", "integer", "text", "flag" -> {
                int s = addRequest(d.getWritable(), d.cmd(), args);
                yield s != 0 ? "Request added to " + args : "Request failed";
            }
            case "" -> {
                removeWritable(d.getWritable());
                yield "";
            }
            default -> "! No such subcommand in rtvals: " + args;
        };
        if (result.startsWith("!"))
            return ora + result + reg;
        return green + result + reg;
    }

    public String replyToNumericalCmd(String cmd, String args) {

        var cmds = args.split(",");

        if (cmds.length == 1) {
            if (args.equalsIgnoreCase("?")) {
                if (args.startsWith("i"))
                    return "iv:update,id,value -> Update an existing int, do nothing if not found";
                return "rv:update,id,value -> Update an existing real, do nothing if not found";
            }
            return "! Not enough arguments";
        }

        return switch (cmds[1]) {
            case "update", "def" -> doUpdateNumCmd(cmd, cmds);
            case "new" -> doNewNumCmd(cmd, cmds);
            default -> "! No such subcommand in " + cmd + ": " + cmds[0];
        };
    }

    private String doUpdateNumCmd(String cmd, String[] args) {
        if (args.length < 3)
            return "! Not enough arguments, " + cmd + ":id," + args[1] + ",expression";
        NumericVal val;

        if (cmd.startsWith("r")) { // so real, rv
            var rOpt = getRealVal(args[0]);
            if (rOpt.isEmpty())
                return "! No such real yet";
            val = rOpt.get();
        } else { // so int,iv
            var iOpt = getIntegerVal(args[0]);
            if (iOpt.isEmpty())
                return "! No such int yet";
            val = iOpt.get();
        }
        var result = ValTools.processExpression(args[2], this);
        if (Double.isNaN(result))
            return "! Unknown id(s) in the expression " + args[2];
        val.update(result);
        return val.id() + " updated to " + result;
    }

    private String doNewNumCmd(String cmd, String[] cmds) {

        // Split in group & name
        String group, name;
        if (cmds.length == 3) {
            group = cmds[2];
            name = cmds[0];
        } else if (cmds.length == 2) {
            if (!cmds[0].contains("_"))
                return "! No underscore in the id, can't split between group and name";
            group = cmds[0].substring(0, cmds[0].indexOf("_"));
            name = cmds[0].substring(group.length() + 1); //skip the group and underscore
        } else {
            return "! Not enough arguments, " + cmd + ":id,new or " + cmd + ":name,new,group";
        }

        if (hasBaseVal(group + "_" + name)) {
            return "! Already an rtval with that id";
        }

        // Build the node
        var fab = Paths.fabInSettings("rtvals")
                .selectOrAddChildAsParent("group", "id", group);
        if (cmd.startsWith("r")) { // So real
            fab.addChild("real").attr("name", name);
            addRealVal(RealVal.newVal(group, name));
        } else if (cmd.startsWith("i")) {
            fab.addChild("int").attr("name", name);
            addIntegerVal(IntegerVal.newVal(group, name));
        }
        fab.build();
        return "Val added.";
    }

    public String replyToFlagsCmd(String cmd, boolean html) {

        if (cmd.equals("?"))
            return doFlagHelpCmd(html);

        FlagVal flag;
        var args = cmd.split(",");
        if (args.length < 2)
            return "! Not enough arguments, at least: fv:id,cmd";

        var flagOpt = getFlagVal(args[0]);
        if (flagOpt.isEmpty()) {
            Logger.error("No such flag: " + args[0]);
            return "! No such flag yet";
        }

        flag = flagOpt.get();
        if (args.length == 2) {
            switch (args[1]) {
                case "raise", "set" -> {
                    flag.value(true);
                    return "Flag raised";
                }
                case "lower", "clear" -> {
                    flag.value(false);
                    return "Flag lowered";
                }
                case "toggle" -> {
                    flag.toggleState();
                    return "Flag toggled";
                }
            }
        } else if (args.length == 3) {
            switch (args[1]) {
                case "update" -> {
                    return flag.parseValue(args[2]) ? "Flag updated" : "! Failed to parse state: " + args[2];
                }
                case "match" -> {
                    if (!hasFlag(args[2]))
                        return "! No such flag: " + args[2];
                    getFlagVal(args[2]).ifPresent(to -> flag.value(to.isUp()));
                    return "Flag matched accordingly";
                }
                case "negated" -> {
                    if (!hasFlag(args[2]))
                        return "! No such flag: " + args[2];
                    getFlagVal(args[2]).ifPresent(to -> flag.value(!to.isUp()));
                    return "Flag negated accordingly";
                }
            }
        }
        return "! No such subcommand in fv: " + args[1] + " or incorrect number of arguments.";
    }

    private static String doFlagHelpCmd(boolean html) {

        var join = new StringJoiner("\r\n");
        join.add("Commands that interact with the collection of flags.");
        join.add("Note: both fv and flags are valid starters")
                .add("Update")
                .add("fv:id,raise/set -> Raises the flag/Sets the bit, created if new")
                .add("fv:id,lower/clear -> Lowers the flag/Clears the bit, created if new")
                .add("fv:id,toggle -> Toggles the flag/bit, not created if new")
                .add("fv:id,update,state -> Update  the state of the flag")
                .add("fv:id,match,refid -> The state of the flag becomes the same as the ref flag")
                .add("fv:id,negated,refid  -> The state of the flag becomes the opposite of the ref flag");
        return LookAndFeel.formatHelpCmd(join.toString(), html);
    }

    public String replyToTextsCmd(String args) {

        var cmds = args.split(",");

        // Get the TextVal if it exists
        TextVal txt;
        if (cmds.length < 2)
            return "! Not enough arguments, at least: tv:id,cmd";

        var txtOpt = getTextVal(cmds[0]);
        if (txtOpt.isEmpty())
            return "! No such text yet";

        txt = txtOpt.get();

        // Execute the commands
        if (cmds[1].equals("update")) {
            if (cmds.length < 3)
                return "! Not enough arguments: tv:id,update,value";
            int start = args.indexOf(",update") + 8; // value can contain , so get position of start
            txt.value(args.substring(start));
            return "TextVal updated";
        }
        return "! No such subcommand in tv: " + cmds[1];
    }

    public String replyToRtvalsCmd(String args, boolean html) {

        if (args.isEmpty())
            return getRtvalsList(html);

        String[] cmds = args.split(",");

        if (cmds.length == 1) {
            switch (cmds[0]) {
                case "?" -> {
                    var join = new StringJoiner("\r\n");
                    join.add("Interact with XML")
                            .add("rtvals:reload -> Reload all rtvals from XML")
                            .add("Get info")
                            .add("rtvals -> Get a listing of all rtvals")
                            .add("rtvals:groups -> Get a listing of all the available groups")
                            .add("rtvals:group,groupid -> Get a listing of all rtvals belonging to the group")
                            .add("rtvals:resetgroup,groupid -> Reset the values in the group to the defaults");
                    return LookAndFeel.formatHelpCmd(join.toString(), html);
                }
                case "reload" -> {
                    readFromXML(XMLdigger.goIn(Paths.settings(), "dcafs", "rtvals"));
                    Core.addToQueue(Datagram.system("pf", "reload"));
                    Core.addToQueue(Datagram.system("dbm", "reload"));
                    return "Reloaded rtvals and paths, databases (because might be affected).";
                }
                case "groups" -> {
                    String groups = String.join(html ? "<br>" : "\r\n", getGroups());
                    return groups.isEmpty() ? "! No groups yet" : groups;
                }

            }
        } else if (cmds.length == 2) {
            return switch (cmds[0]) {
                case "group" -> getRTValsGroupList(cmds[1], html);
                case "resetgroup" -> {
                    var vals = getGroupVals(cmds[1]);
                    if (vals.isEmpty()) {
                        Logger.error("No vals found in group " + cmds[1]);
                        yield "! No vals with that group";
                    }
                    getGroupVals(cmds[1]).forEach(BaseVal::resetValue);
                    yield "Values reset";
                }
                case "name" -> getNameVals(cmds[1]);
                default -> "! No such subcommand in rtvals: " + args;
            };
        }
        return "! No such subcommand in rtvals: " + args;
    }

    @Override
    public boolean removeWritable(Writable wr) {
        // TODO
        return false;
    }
}
