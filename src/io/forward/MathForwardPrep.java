package io.forward;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.data.RealtimeValues;
import util.gis.GisTools;
import util.math.Calculations;
import util.math.MathFab;
import util.math.MathUtils;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class MathForwardPrep {

    HashMap<String, String> defines = new HashMap<>();

    private final ArrayList<BigDecimal> temps = new ArrayList<>();
    boolean parsedOk = false;

    // Own
    private final ArrayList<MathOperation> ops = new ArrayList<>();
    MathForward mf;
    boolean valid = false;
    RealtimeValues rtvals;
    protected final ArrayList<String[]> rulesString = new ArrayList<>();
    boolean readOk = false;

    public MathForwardPrep(RealtimeValues rtvals) {
        this.rtvals = rtvals;

    }

    public MathForward createMathForward(XMLdigger dig) {
        if (!readBasicsFromXml(dig))
            return null;

        readFromXML(dig);
        if (parsedOk)
            return mf;
        return null;
    }

    protected boolean readBasicsFromXml(XMLdigger dig) {

        if (dig == null) { // Can't work if this is null
            parsedOk = false;
            return false;
        }
        /* Attributes */
        var id = dig.attr("id", "");
        if (id.isEmpty()) {// Cant work without id
            parsedOk = false;
            return false;
        }
        mf = new MathForward(id, "", rtvals);

        // Read the delimiter and taking care of escape codes
        var delimiter = dig.attr("delimiter", mf.delimiter, true);
        mf.delimiter = dig.attr("delim", delimiter, true); // Could have been shortened

        var log = dig.attr("log", false); // Logging to raw files or not
        if (log) // this counts as a target, so enable it
            mf.valid = true;

        /* Sources */
        mf.sources.clear();
        mf.addSource(dig.attr("src", ""));
        dig.peekOut("src").forEach(ele -> mf.addSource(ele.getTextContent()));

        rulesString.clear();
        Logger.info(id + " -> Reading from xml");
        return true;
    }

    public boolean readFromXML(XMLdigger dig) {

        // Reset everything that will be altered
        parsedOk = true;
        mf.getReferencedNums().clear();
        temps.clear();
        ops.clear();

        mf.setSuffix(dig.attr("suffix", ""));
        String content = dig.value("");

        // Check if it Has content but no child elements, so a single op
        if (content != null && dig.peekOut("*").isEmpty()) {
            return procSingleOp(dig, content);
        }

        // Check for other subnodes besides 'op' those will be considered def's to reference in the op
        digForFixedOperands(dig, defines);

        boolean oldValid = valid; // Store the current state of valid

        // First go through all the ops and to find all references to real/int/flag and determine highest i(ndex) used
        var maxIndex = digForMaxTempIndex(mf.id(), dig);
        if (maxIndex == -1) {
            parsedOk = false;
            return false;
        }
        while (maxIndex >= temps.size())
            temps.add(BigDecimal.ZERO);

        // Find all the references to realtime values
        for (var ops : dig.peekOut("op")) {
            if (!findRtvals(ops.getTextContent())) {
                parsedOk = false; //Parsing failed, so set the flag and return
                return false;
            }
        }
        // Go through the op's again to actually process the expression
        digForOpsProcessing(dig);

        //   if( !oldValid && valid )// If math specific things made it valid
        //        sources.forEach( source -> Core.addToQueue( Datagram.system( source ).writable(mf) ) );
        mf.getReferencedNums().trimToSize(); // Won't be changed after this, so trim excess space
        return true;
    }

    private boolean procSingleOp(XMLdigger dig, String content) {
        if (findRtvals(content)) { // Figure out the used references to vals and determine the highest used index/i
            var op = addStdOperation(
                    content,
                    dig.attr("scale", -1),
                    dig.attr("cmd", "")
            );
            if (op.isEmpty()) { // If no op could be parsed from the expression
                parsedOk = false;
                Logger.error(mf.id() + "(mf) -> No valid operation found in: " + content);
                return false;
            }
            // Processing ok, can stop here
            return true;
        }
        // Failed to find or parse references to real/int/flagvals
        Logger.error(mf.id() + "(mf) -> Failed to process references in " + content);
        parsedOk = false;
        return false;
    }

    /**
     * Check the expression for references to:
     * - reals -> {r:id} or {real:id}
     * - flags -> {f:id} or {flag:id}
     * If found, check if those exist and if so, add them to the corresponding list
     *
     * @param exp The expression to check
     * @return True if everything went ok and all references were found
     */
    private boolean findRtvals(String exp) {
        var referencedNums = mf.getReferencedNums();
        // Find all the double/int/flag pairs
        var pairs = Tools.parseKeyValue(exp, true);
        if (referencedNums == null)
            referencedNums = new ArrayList<>();
        int originalSize = referencedNums.size();

        for (var p : pairs) {
            if (p.length == 2 || p.length == 1) {
                int nums = referencedNums.size(); // Store current size to later check if it increased
                var find = p[p.length - 1];
                if (referencedNums.stream().noneMatch(val -> val.id().equalsIgnoreCase(find))) {
                    if (rtvals.hasReal(find))
                        rtvals.getRealVal(p[p.length - 1]).ifPresent(referencedNums::add);
                    if (rtvals.hasInteger(find))
                        rtvals.getIntegerVal(p[p.length - 1]).ifPresent(referencedNums::add);
                    if (rtvals.hasFlag(find))
                        rtvals.getFlagVal(p[p.length - 1]).ifPresent(referencedNums::add);
                    if (referencedNums.size() == nums) // No increase, so not found
                        Logger.error(mf.id() + "(mf) -> Couldn't find val with id " + find);
                }
            } else {
                Logger.error(mf.id() + " (mf)-> Pair containing odd amount of elements: " + String.join(":", p));
            }
        }
        if (originalSize == referencedNums.size()) {
            Logger.debug(mf.id() + "(mf) -> No vals found in " + exp);
        }
        // Find the highest used 'i' index
        var is = Pattern.compile("i[0-9]{1,2}")
                .matcher(exp)
                .results()
                .map(MatchResult::group)
                .sorted()
                .toArray(String[]::new);
        if (is.length != 0) {
            var highestI = Math.max(-1, Integer.parseInt(is[is.length - 1].substring(1)));
            Logger.debug(mf.id() + "(mf) -> Highest I needed is " + highestI);
            mf.setHighestI(highestI);
        }
        return true;
    }

    /**
     * Check the node for references to static values and odd those to the collection
     *
     * @param dig A digger pointing to the MathForward
     */
    private static void digForFixedOperands(XMLdigger dig, HashMap<String, String> defines) {
        dig.peekOut("*")
                .stream().filter(ele -> !ele.getTagName().equalsIgnoreCase("op"))
                .forEach(def -> {
                    var val = def.getTextContent().replace(",", "."); // unify decimal separator
                    if (def.getTagName().equalsIgnoreCase("def")) {
                        defines.put(def.getAttribute("ref"), val); // <def ref="A1">12.5</def>
                    } else {
                        defines.put(def.getTagName(), val);             // <A1>12.5</A1>
                    }
                });
    }

    /**
     * Go through the op's and find any reference to temporary values designated with tx then prepare the temps
     * collection to hold that many elements
     *
     * @param dig A digger pointing to the MathForward
     */
    private static int digForMaxTempIndex(String id, XMLdigger dig) {
        var pattern = Pattern.compile("t[0-9]{1,2}");
        int max = 0;
        try {
            for (var opNode : dig.peekOut("op")) {
                // Find all the temp indexes
                var maxIndex = pattern
                        .matcher(opNode.getTextContent())// Apply the pattern to the text content
                        .results() // Get the results of the pattern applied
                        .map(m -> m.group().substring(1)) // Remove the t at the start (i.e., extract the number)
                        .filter(t -> {
                            boolean isValid = NumberUtils.isCreatable(t); // Check if it's a valid number
                            if (!isValid)
                                throw new IllegalArgumentException("Invalid temp index found: " + t);
                            return true; // Only allow valid indexes through
                        })
                        .mapToInt(NumberUtils::toInt) // Convert valid strings to integers
                        .max() // Find the maximum index
                        .orElse(0); // Default to 0 if no valid indexes are found

                // Increase the temp collection so all will fit
                max = Math.max(maxIndex, max);
            }
            return max;
        } catch (IllegalArgumentException e) {
            Logger.error(id + "(mf) -> " + e.getMessage());
            return -1;
        }
    }

    private void digForOpsProcessing(XMLdigger dig) {
        dig.digOut("op").forEach(ops -> {
            try {
                var type = fromStringToOPTYPE(mf.id(), ops.attr("type", "complex"));
                switch (Objects.requireNonNull(type)) {
                    case COMPLEX -> addStdOperation(
                            ops.value(""),
                            ops.attr("scale", -1),
                            ops.attr("cmd", "")
                    );
                    case LN, SALINITY, SVC, TRUEWINDSPEED, TRUEWINDDIR, UTM, GDC -> addOperation(
                            ops.attr("index", "-1"),
                            ops.attr("scale", -1),
                            type,
                            ops.attr("cmd", ""),
                            ops.value(""));
                    default -> Logger.error("Bad type " + type);
                }

            } catch (NumberFormatException e) {
                Logger.error(mf.id() + " (mf)-> Number format Exception " + e.getMessage());
            }
        });
    }

    /**
     * Convert a string version of OP_TYPE to the enum
     *
     * @return The resulting enum value
     */
    private static MathForward.OP_TYPE fromStringToOPTYPE(String id, String optype) {
        return switch (optype.toLowerCase()) {
            case "scale" -> MathForward.OP_TYPE.SCALE;
            case "ln" -> MathForward.OP_TYPE.LN;
            case "salinity" -> MathForward.OP_TYPE.SALINITY;
            case "svc" -> MathForward.OP_TYPE.SVC;
            case "truewinddir" -> MathForward.OP_TYPE.TRUEWINDDIR;
            case "truewindspeed" -> MathForward.OP_TYPE.TRUEWINDSPEED;
            case "utm" -> MathForward.OP_TYPE.UTM;
            case "gdc" -> MathForward.OP_TYPE.GDC;
            default -> {
                Logger.error(id + "(mf) -> Invalid op type given, using default complex");
                yield MathForward.OP_TYPE.COMPLEX;
            }
        };
    }
    /* ************************************** ADDING OPERATIONS **************************************************** */

    /**
     * Add an operation to this object
     *
     * @param cmd        Send the result as part of a command
     * @param expression The expression to use
     * @return True if it was added
     */
    public Optional<MathOperation> addStdOperation(String expression, int scale, String cmd) {

        // Remove spaces and replace ++ with +=1 etc
        expression = normalizeExpression(expression);

        if (!expression.contains("=")) // If this doesn't contain a '=' it's no good
            return handleSingleIndexOp(expression, cmd, scale);


        // split into result and operation (i0 = i1+2 -> [i0][i1+2]
        var split = handleCompoundAssignment(expression);

        // Process destination references (like i0, i1, t0, etc.)
        var dest = split[0].split(",");
        var map = getDestinationIndex(split, dest, expression);
        int index = map.getKey();
        expression = map.getValue();

        // Check if the destination is a realval or integerval and not an i
        dest[0] = dest[0].toLowerCase();
        if ((dest[0].startsWith("{d") || dest[0].startsWith("{r")) && dest.length == 1) {
            if (split[1].matches("i[0-9]{1,3}")) { // the expression is only reference to an i
                return Optional.of(addOp(NumberUtils.toInt(split[1].substring(1), -1), expression, cmd, scale));
            } else {
                index = -2;
            }
        }

        var exp = split[1];

        if (index == -1) {
            Logger.warn(mf.id() + " -> Bad/No index given in " + expression);
        }

        for (var entry : defines.entrySet()) // Check for the defaults and replace
            exp = exp.replace(entry.getKey(), entry.getValue());

        exp = replaceTemps(exp); // replace the tx's with ix's
        exp = replaceReferences(exp); // Replace the earlier found references with i's

        if (exp.isEmpty()) {
            Logger.error(mf.id() + " (mf)-> Expression is empty after replacing refs?");
            return Optional.empty();
        }
        MathOperation op;
        if (NumberUtils.isCreatable(exp.replace(",", "."))) {
            op = new MathOperation(expression, exp.replace(",", "."), index, rtvals);
        } else {
            var fab = MathFab.newFormula(exp.replace(",", "."));
            if (fab.isValid()) { // If the formula could be parsed
                op = new MathOperation(expression, fab, index, rtvals); // create an operation
            } else {
                Logger.error(mf.id() + " (mf)-> Failed to build mathfab");
                parsedOk = false;
                readOk = false;
                return Optional.empty(); // If not, return empty
            }
        }
        op.scale(scale).cmd(cmd, rtvals);

        ops.add(op);
        rulesString.add(new String[]{"complex", String.valueOf(index), expression});
        return Optional.of(op); // return the one that was added last
    }

    private static String normalizeExpression(String expression) {
        // Support ++ and --
        return expression.replace("++", "+=1")
                .replace("--", "-=1")
                .replace(" ", ""); //remove spaces
    }

    private Optional<MathOperation> handleSingleIndexOp(String expression, String cmd, int scale) {
        if (expression.matches("i[0-9]{1,3}")) { // unless the expression is a reference?
            return Optional.of(addOp(NumberUtils.toInt(expression.substring(1), -1), expression, cmd, scale));
        } else {
            Logger.error(mf.id() + "(mf) -> Not a valid expression: " + expression);
            return Optional.empty();
        }
    }

    private static String[] handleCompoundAssignment(String exp) {
        // The expression might be a simple i0 *= 2, so replace such with i0=i0*2 because of the way it's processed
        // A way to know this is the case, is that normally the summed length of the split items is one lower than
        // the length of the original expression (because the = ), if not that means an operand was in front of '='
        var split = exp.split("[+-/*^]?=");
        int lengthAfterSplit = split[0].length() + split[1].length();
        if (lengthAfterSplit + 1 != exp.length()) { // Support += -= *= and /= fe. i0+=1
            String[] spl = exp.split("="); //[0]:i0+ [1]:1
            split[1] = spl[0] + split[1]; // split[1]=i0+1
        }
        return split;
    }

    private Map.Entry<Integer, String> getDestinationIndex(String[] split, String[] dest, String expression) {
        // It's allowed that the result is written to more than one destination
        int index = Tools.parseInt(dest[0].substring(1), -1); // Check if it's in the first or only position
        if (dest.length == 2) {
            if (index == -1) { // if not and there's a second one
                index = Tools.parseInt(dest[1].substring(1), -1); //check if it's in the second one
            } else {
                expression = expression.replace(split[0], dest[1] + "," + dest[0]); //swap the {d to front
            }
        }

        // Fix index if targeting a temp?
        if (dest[0].startsWith("t")) {
            index += mf.getHighestI() + 1;
        }
        return new AbstractMap.SimpleEntry<>(index, expression);
    }

    public void addOperation(String index, int scale, MathForward.OP_TYPE type, String cmd, String expression) {

        expression = expression.replace(" ", ""); //remove spaces

        String exp = expression;

        if (index.equalsIgnoreCase("-1")) {
            Logger.warn(mf.id() + " -> Bad/No index given in '" + cmd + "'|" + expression + " for " + type);
        }
        mf.setHighestI(NumberUtils.toInt(index, -1));

        exp = replaceTemps(exp); // replace the tx's with ix's
        exp = replaceReferences(exp); // replacec real/int/flag vals with i's

        if (exp.isEmpty())
            return;

        MathOperation op;
        String[] indexes = exp.split(",");

        switch (type) {
            case LN ->
                    op = new MathOperation(expression, MathUtils.decodeBigDecimalsOp("i" + index, exp, "ln", 0), NumberUtils.toInt(index), rtvals);
            case SALINITY -> {
                if (indexes.length != 3) {
                    Logger.error(mf.id() + " (mf)-> Not enough args for salinity calculation");
                    return;
                }
                op = new MathOperation(expression, Calculations.procSalinity(indexes[0], indexes[1], indexes[2]), NumberUtils.toInt(index), rtvals);
            }
            case SVC -> {
                if (indexes.length != 3) {
                    Logger.error(mf.id() + " (mf)-> Not enough args for soundvelocity calculation");
                    return;
                }
                op = new MathOperation(expression, Calculations.procSoundVelocity(indexes[0], indexes[1], indexes[2]), NumberUtils.toInt(index), rtvals);
            }
            case TRUEWINDSPEED -> {
                if (indexes.length != 5) {
                    Logger.error(mf.id() + " (mf)-> Not enough args for True wind speed calculation");
                    return;
                }
                op = new MathOperation(expression, Calculations.procTrueWindSpeed(indexes[0], indexes[1], indexes[2], indexes[3], indexes[4]), NumberUtils.toInt(index), rtvals);
            }
            case TRUEWINDDIR -> {
                if (indexes.length != 5) {
                    Logger.error(mf.id() + " (mf)-> Not enough args for True wind direction calculation");
                    return;
                }
                op = new MathOperation(expression, Calculations.procTrueWindDirection(indexes[0], indexes[1], indexes[2], indexes[3], indexes[4]), NumberUtils.toInt(index), rtvals);
            }
            case UTM -> op = new MathOperation(expression, GisTools.procToUTM(indexes[0], indexes[1],
                    Arrays.stream(index.split(",")).map(NumberUtils::toInt).toArray(Integer[]::new)), -1, rtvals);
            case GDC -> op = new MathOperation(expression, GisTools.procToGDC(indexes[0], indexes[1],
                    Arrays.stream(index.split(",")).map(NumberUtils::toInt).toArray(Integer[]::new)), -1, rtvals);
            default -> {
                return;
            }
        }
        addOp(op);

        if (scale != -1) { // Check if there's a scale op needed
            Function<BigDecimal[], BigDecimal> proc = x -> x[NumberUtils.toInt(index)].setScale(scale, RoundingMode.HALF_UP);
            var p = new MathOperation(expression, proc, NumberUtils.toInt(index), rtvals).cmd(cmd, rtvals);
            if (addOp(p))
                rulesString.add(new String[]{type.toString().toLowerCase(), index, "scale(" + expression + ", " + scale + ")"});
        } else {
            op.cmd(cmd, rtvals);
            rulesString.add(new String[]{type.toString().toLowerCase(), index, expression});
        }
    }

    private String replaceTemps(String exp) {
        int index = mf.getHighestI() + 1;
        var ts = Pattern.compile("\bt[0-9]+")
                .matcher(exp)
                .results()
                .map(MatchResult::group)
                .toArray(String[]::new);
        for (var t : ts) {
            int in = Integer.parseInt(t.substring(1));
            try {
                exp = exp.replace(t, "i" + (index + in));
            } catch (NumberFormatException e) {
                Logger.error(e);
            }
        }
        return exp;
    }

    /**
     * Use the earlier found references and replace them with the corresponding index.
     * The indexes will be altered so that they match if the correct index of an array containing
     * - The received data split according to the delimiter up to the highest used index
     * - The realVals found
     * - The intVals found
     * - The flagVals found
     * <p>
     * So if highest is 5 then the first double will be 6 and first flag will be 5 + size of double list + 1
     *
     * @param exp The expression to replace the references in
     * @return The altered expression or an empty string if something failed
     */
    private String replaceReferences(String exp) {
        // Find the pairs in the expression
        for (var p : Tools.parseKeyValue(exp, true)) {
            if (p.length == 2 || p.length == 1) { // The pair should be an actual pair
                boolean ok = false; // will be used at the end to check if ok
                for (int pos = 0; pos < mf.getReferencedNums().size(); pos++) { // go through the known Vals
                    var d = mf.getReferencedNums().get(pos);
                    if (d.id().equalsIgnoreCase(p[p.length - 1])) { // If a match is found
                        var repl = "{" + p[0] + "}";
                        if (p.length == 2)
                            repl = "{" + p[0] + ":" + p[1] + "}";
                        var i = "i" + (mf.getHighestI() + pos + temps.size() + 1);
                        exp = exp.replace(repl, i);
                        Logger.debug(mf.id() + "(mf) -> Replacing " + repl + " with " + i);
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    Logger.error(mf.id() + " (mf)-> Didn't find a match when looking for " + String.join(":", p));
                    return "";
                }
            } else {
                Logger.error(mf.id() + " (mf)-> Pair containing to many elements: " + String.join(":", p));
                return "";
            }
        }
        return exp;
    }

    private MathOperation addOp(int index, String expression, String cmd, int scale) {
        var op = new MathOperation(expression, index, rtvals);
        op.cmd(cmd, rtvals).scale(scale);
        if (addOp(op))
            rulesString.add(new String[]{"complex", String.valueOf(index), expression});
        return op;
    }

    private boolean addOp(MathOperation op) {
        if (op == null) {
            valid = false;
            Logger.error(mf.id() + "(mf) -> Tried to add a null operation, MathFormward is invalid");
            return false;
        }
        if (op.isValid()) {
            valid = false;
            Logger.error(mf.id() + "(mf) -> Tried to add an invalid op, MathFormward is invalid");
        }
        ops.add(op);
        return true;
    }
}
