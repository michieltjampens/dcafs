package io.forward;

import io.forward.steps.EditorStep;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.data.ValTools;
import util.data.vals.Rtvals;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class EditorStepFab {

    public static Optional<EditorStep> buildEditorStep(XMLdigger dig, String delimiter, Rtvals rtvals) {
        return digEditorNode(dig, rtvals, delimiter);
    }

    @SuppressWarnings("unchecked")
    private static Optional<EditorStep> digEditorNode(XMLdigger dig, Rtvals rtvals, String delimiter) {
        ArrayList<Function<String, String>> edits = new ArrayList<>();

        var deli = dig.attr("delimiter", delimiter);
        var id = dig.attr("id", "");
        
        var info = new StringJoiner("\r\n");
        if( dig.hasPeek("*")){
            for( var node : dig.digOut("*")){
                var proc = processNode(node, deli, rtvals, info);
                if( proc !=null )
                    edits.add(proc);
            }
            /*var set = dig.digOut("*")
                    .stream()
                    .map(node -> processNode(node, deli, rtvals, info))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            edits.addAll(set);*/
        }else{
            var edit = processNode(dig, delimiter, rtvals, info);
            if (edit != null)
                edits.add(edit);
        }
        if (edits.isEmpty())
            return Optional.empty();
        return Optional.of(new EditorStep(id, edits.toArray(Function[]::new)));
    }

    private static Function<String, String> processNode(XMLdigger dig, String delimiter, Rtvals rtvals, StringJoiner info) {
        String deli = dig.attr("delimiter",delimiter,true);
        deli = Tools.fromEscapedStringToBytes(deli);
        String content = dig.value("");
        String from = dig.attr("from",",");
        String error = dig.attr("error","NaN");
        String find = dig.attr("find","");
        if( find.isEmpty())
            find = dig.attr("regex","");
        String leftover = dig.attr("leftover","append");

        int index = dig.attr("index",-1);
        if( index == -1)
            index = dig.attr("i",-1);

        if( content == null ){
            Logger.error(" -> Missing content in an edit.");
            return null;
        }
        if( index == -1 ){
            index=0;
        }
        var type = dig.attr("type",dig.tagName(""));
        return switch (type) {
            case "charsplit" -> {
                info.add("-> Charsplit on " + deli + " on positions " + content);
                Logger.info(" -> Added charsplit with delimiter " + deli + " on positions " + content);
                yield addCharSplit(deli, content);
            }
            case "resplit" -> {
                info.add("-> Resplit edit on delimiter " + deli + " with formula " + content);
                Logger.info(" -> Added resplit edit on delimiter " + deli + " with formula " + content);
                yield addResplit(deli, content, error, leftover.equalsIgnoreCase("append"), rtvals);
            }
            case "rexsplit", "regexsplit" -> {
                info.add("-> Get items from " + content + " and join with " + deli);
                Logger.info(" -> Get items from " + content + " and join with " + deli);
                yield addRexsplit(deli, content);
            }
            case "redate", "reformatdate" -> {
                info.add("-> redate edit on delimiter " + deli + " from " + from + " to " + content + " at " + index);
                Logger.info(" -> Added redate edit on delimiter " + deli + " from " + from + " to " + content + " at " + index);
                yield addRedate(from, content, index, deli);
            }
            case "retime", "reformattime" -> {
                Logger.info(" -> Added retime edit on delimiter " + deli + " from " + from + " to " + content);
                info.add("-> Retime edit on delimiter " + deli + " from " + from + " to " + content);
                yield addRetime(from, content, index, deli);
            }
            case "replace" -> {
                if (find.isEmpty()) {
                    Logger.error(" -> Tried to add an empty replace.");
                    yield null;
                }
                info.add("-> Replacing " + find + " with " + content);
                yield addReplacement(find, content);
            }
            case "rexreplace", "regexreplace" -> {
                if (find.isEmpty()) {
                    Logger.error(" -> Tried to add an empty replace.");
                    yield null;
                }
                info.add("-> Replacing matching regex " + find + " with " + content);
                yield addRegexReplacement(find, content);
            }
            case "remove" -> {
                Logger.info(" -> Remove occurrences off " + content);
                info.add("-> Removing " + content + " from data");
                yield addReplacement(content, "");
            }
            case "trim", "trimspaces" -> {
                Logger.info(" -> Trimming spaces");
                info.add("-> Trimming spaces");
                yield addTrim();
            }
            case "rexremove", "regexremove" -> {
                Logger.info(" -> RexRemove matches off " + content);
                info.add("-> Using regex " + content + " to remove data");
                yield addRexRemove(content);
            }
            case "rexkeep", "regexkeep" -> {
                Logger.info(" -> Keep result of " + content);
                info.add("-> Only keeping result of regex " + content);
                yield addRexsplit("", content);
            }
            case "prepend", "prefix", "addprefix" -> {
                Logger.info(" -> Added prepend of " + content);
                info.add("-> Prepending " + content);
                yield addPrepend(content);
            }
            case "append", "suffix", "addsuffix" -> {
                Logger.info(" -> Added append of " + content);
                info.add("-> Appending " + content);
                yield addAppend(content);
            }
            case "insert" -> {
                Logger.info(" -> Added insert of " + content);
                info.add("-> Inserting " + content + " at index " + dig.attr("index", ""));
                yield addInsert(dig.attr("index", -1), content);
            }
            case "cutstart", "cutfromstart" -> {
                if (NumberUtils.toInt(content, 0) == 0) {
                    Logger.warn(" -> Invalid number given to cut from start " + content);
                    yield null;
                }
                Logger.info(" -> Added cut start of " + content + " chars");
                info.add("-> Cutting " + content + " characters from the start");
                yield addCutStart(NumberUtils.toInt(content, 0));
            }
            case "cutend", "cutfromend" -> {
                if (NumberUtils.toInt(content, 0) == 0) {
                    Logger.warn(" -> Invalid number given to cut from end " + content);
                    yield null;
                }
                Logger.info(" -> Added cut end of " + content + " chars");
                info.add("-> Cutting " + content + " characters from the end of the data.");
                yield addCutEnd(NumberUtils.toInt(content, 0));
            }
            case "toascii" -> {
                Logger.info(" -> Added conversion to char");
                info.add("-> Converting to ascii");
                yield converToAscii(deli);
            }
            case "millisdate" -> {
                Logger.info(" -> Added millis conversion to " + content);
                info.add("-> Formatting millis at index " + index + " (split on " + deli + ") according to " + content);
                yield addMillisToDate(content, index, deli);
            }
            case "listreplace" -> {
                int first = dig.attr("first", 0);
                Logger.info("(ef) -> Added listreplace of " + content + " of index " + index);
                info.add("-> Listreplace at index " + index + " after split with '" + deli + "' using " + content);
                yield addListReplace(content, deli, index, first);
            }
            case "indexreplace","replaceindex" -> {
                Logger.info("(ef) -> Added indexreplace with " + content + " at index " + index);
                info.add("-> IndexReplace with " + content + " at index " + index);
                yield addIndexReplace(index, deli, content, rtvals);
            }
            case "removeindex" -> {
                Logger.info("(ef) -> Added remove index " + index);
                info.add("-> Remove item at index " + index);
                yield addIndexReplace(NumberUtils.toInt(content, -1), deli, "", rtvals);
            }
            default -> {
                Logger.error(" -> Unknown type used : '" + type + "'");
                yield null;
            }
        };
    }

    private static Function<String, String> addListReplace(String content, String deli, int index, int first) {
        String[] opts = content.split(",");
        return input ->
        {
            String[] items = input.split(deli);
            if( index > items.length ){
                Logger.error("(ef) -> (ListReplace) Not enough elements after split of " + input);
                return null;
            }
            int pos = NumberUtils.toInt(items[index],Integer.MAX_VALUE);
            if( pos == Integer.MAX_VALUE){
                Logger.error(" (ef) -> (ListReplace) Parsing to int failed for " + items[index]);
                return null;
            }
            pos = pos-first;
            if( pos <0 || pos > opts.length){
                Logger.error(" (ef) -> (ListReplace) Invalid index for the list (" + pos + ")");
                return null;
            }
            items[index]=opts[pos];
            return String.join(deli,items);
        };
    }

    private static Function<String, String> addCharSplit(String deli, String positions) {

        String[] pos = Tools.splitList(positions);
        var indexes = new ArrayList<Integer>();
        if( !pos[0].equals("0"))
            indexes.add(0);
        Arrays.stream(pos).forEach( p -> indexes.add( NumberUtils.toInt(p)));

        String delimiter;
        if( pos.length >= 2){
            delimiter = String.valueOf(positions.charAt(positions.indexOf(pos[1]) - 1));
        }else{
            delimiter=deli;
        }

        return input ->
        {
            if(indexes.get(indexes.size()-1) > input.length()){
                Logger.error("(ef) Can't split " + input + " if nothing is at " + indexes.get(indexes.size() - 1));
                return null;
            }
            try {
                StringJoiner result = new StringJoiner(delimiter);
                for (int a = 0; a < indexes.size() - 1; a++) {
                    result.add(input.substring(indexes.get(a), indexes.get(a + 1)));
                }
                String leftover=input.substring(indexes.get(indexes.size() - 1));
                if( !leftover.isEmpty())
                    result.add(leftover);
                return result.toString();
            }catch( ArrayIndexOutOfBoundsException e){
                Logger.error("(ef) Failed to apply charsplit on " + input);
            }
            return null;
        };
    }

    private static Function<String, String> addMillisToDate(String to, int index, String delimiter) {
        return input ->
        {
            String[] split = input.split(delimiter);
            if( split.length > index){
                long millis = NumberUtils.toLong(split[index],-1L);
                if( millis == -1L ){
                    Logger.error("(ef) -> Couldn't convert " + split[index] + " to millis");
                    return null;
                }
                var ins = Instant.ofEpochMilli(millis);
                try {
                    if( to.equalsIgnoreCase("sql")){
                        split[index] = ins.toString();
                    }else{
                        split[index] = DateTimeFormatter.ofPattern(to).withZone(ZoneId.of("UTC")).format(ins);
                    }
                    if (split[index].isEmpty()) {
                        Logger.error("(ef) -> Failed to convert datetime " + split[index]);
                        return null;
                    }
                    return String.join(delimiter, split);
                }catch(IllegalArgumentException | DateTimeException e){
                    Logger.error("(ef) -> Invalid format in millis to date: " + to + " -> " + e.getMessage());
                    return null;
                }
            }
            Logger.error("(ef) -> To few elements after split for millistodate in " + input);
            return null;
        };
    }
    /**
     * Alter the formatting of a date field
     * @param from The original format
     * @param to The new format
     * @param index On which position of the split data
     * @param delimiter The delimiter to split the data
     */
    private static Function<String, String> addRedate(String from, String to, int index, String delimiter) {

        String deli;
        if( delimiter.equalsIgnoreCase("*")){
            deli="\\*";
        }else{
            deli=delimiter;
        }
        return input ->
        {
            String[] split = input.split(deli);
            if( split.length > index){
                split[index] = TimeTools.reformatDate(split[index], from, to);
                if( split[index].isEmpty()) {
                    Logger.error(" -> Failed to convert datetime " + input.split(deli)[index]);
                    return null;
                }
                return String.join(deli,split);
            }
            Logger.error(" -> To few elements after split for redate in " + input);
            return null;
        };
    }
    /**
     * Alter the formatting of a time field
     * @param from The original format
     * @param to The new format
     * @param index On which position of the split data
     * @param delimiter The delimiter to split the data
     */
    private static Function<String, String> addRetime(String from, String to, int index, String delimiter) {

        String deli;
        if( delimiter.equalsIgnoreCase("*")){
            deli="\\*";
        }else{
            deli=delimiter;
        }
        return input ->
        {
            String[] split = input.split(deli);
            if( split.length > index){
                split[index] = TimeTools.reformatTime(split[index],from,to);
                if( split[index].isEmpty()) {
                    Logger.error("(ef) -> Tried to retime " + input + " but no such index " + index);
                    return null;
                }
                return String.join(deli,split);
            }
            Logger.error(" -> To few elements after split for retime in " + input);
            return null;
        };
    }

    private static Function<String, String> addRexsplit(String delimiter, String regex) {
        var results = Pattern.compile(regex);
        return input ->
        {
            var items = results.matcher(input)
                    .results()
                    .map(MatchResult::group)
                    .toArray(String[]::new);
            return String.join(delimiter,items);
        };
    }
    /**
     * Split a data string according to the given delimiter, then stitch it back together based on resplit
     * @param delimiter The string to split the data with
     * @param resplit The format of the new string, using i0 etc to get original values
     */
    private static Function<String, String> addResplit(String delimiter, String resplit, String error, boolean append, Rtvals rtvals) {

        var is = Pattern.compile("i[0-9]{1,3}")
                .matcher(resplit)
                .results()
                .map(MatchResult::group)
                .toArray(String[]::new);

        var filler = resplit.split("i[0-9]{1,3}");
        for( int a=0;a<filler.length;a++)
            filler[a]=filler[a].replace("§","");

        if(is.length==0) {
            Logger.warn("(ef)-> No original data referenced in the resplit");
        }

        int[] indexes = Arrays.stream(is).mapToInt(i -> NumberUtils.toInt(i.substring(1))).toArray();
        String deli = delimiter.equals("*") ? "\\*" : delimiter;

        return input ->
        {
            String[] inputEles = input.split(deli); // Get the source data

            StringJoiner join = new StringJoiner("",filler.length==0?"": ValTools.parseRTline(filler[0],error,rtvals),"");
            for( int a=0;a<indexes.length;a++){
                try {
                    join.add(inputEles[indexes[a]]);
                    if( filler.length>a+1)
                        join.add( ValTools.parseRTline(filler[a+1],error,rtvals));
                    inputEles[indexes[a]] = null;
                }catch( IndexOutOfBoundsException e){
                    Logger.error("(ef) -> Out of bounds when processing: " + input);
                    return null;
                }
            }
            if( indexes.length!=inputEles.length && append){
                StringJoiner rest = new StringJoiner(delimiter,delimiter,"");
                for( var a : inputEles){
                    if( a!=null)
                        rest.add(a);
                }
                join.add(rest.toString());
            }
            return join.toString();
        };
    }

    private static Function<String, String> addIndexReplace(int index, String delimiter, String value, Rtvals rtvals) {
        if( index==-1) {
            Logger.error("(ef) -> Invalid index given for indexreplace/removeindex");
            return null;
        }
        if( value.isEmpty() ) {
            if( index==0 ){
                return input -> {
                    int a = input.indexOf(delimiter);
                    if( a == -1 )
                        return input;
                    return input.substring(a);
                };
            }else {
                return input -> {
                    var its = input.split(delimiter);
                    var list = new ArrayList<>(Arrays.asList(its));
                    if (index < list.size()) {
                        list.remove(index);
                    } else {
                        Logger.error("(ef) -> Tried to remove index " + index + " from " + input + " but no such thing.");
                        return null;
                    }
                    return String.join(delimiter, list);
                };
            }
        }else{
            return input -> {
                var its = input.split(delimiter);
                if (its.length > index)
                    its[index] = ValTools.parseRTline(value, its[index], rtvals);
                return String.join(delimiter, its);
            };
        }
    }
    /**
     * Add a string to the start of the data
     * @param addition The string to add at the start
     */
    private static Function<String, String> addPrepend(String addition) {
        return input -> addition + input;
    }
    /**
     * Add a string to the end of the data
     * @param addition The string to add at the end
     */
    private static Function<String, String> addAppend(String addition) {
        return input -> input + addition;
    }

    private static Function<String, String> addInsert(int position, String addition) {
        return input -> {
            if( input.length() < position ) {
                Logger.error("(ef) -> Tried to insert " + addition + " at index " + position + " but input string to short -> >" + input + "<");
                return null;
            }
            if (position == -1) {
                Logger.error("(ef) -> Tried to insert at -1 index");
                return null;
            }
            return input.substring(0,position)+addition+input.substring(position);
        };
    }

    private static Function<String, String> addReplacement(String find, String replace) {
        return input -> input.replace(Tools.fromEscapedStringToBytes(find), Tools.fromEscapedStringToBytes(replace));
    }

    private static Function<String, String> addTrim() {
        return String::trim;
    }

    private static Function<String, String> addRexRemove(String find) {
        return input -> input.replaceAll(find, "");
    }

    private static Function<String, String> addRegexReplacement(String find, String replace) {
        String r = replace.isEmpty()?" ":replace;
        return input -> input.replaceAll(find, r);
    }

    private static Function<String, String> addCutStart(int characters) {
        return input -> input.length() > characters ? input.substring(characters) : null;
    }

    private static Function<String, String> addCutEnd(int characters) {
        return input -> input.length() > characters ? input.substring(0, input.length() - characters) : null;
    }

    private static Function<String, String> converToAscii(String delimiter) {
        return input -> {
            var join = new StringJoiner("");
            Arrays.stream(input.split(delimiter)).forEach( x -> join.add(String.valueOf((char) NumberUtils.createInteger(x).intValue())));
            return join.toString();
        };
    }
    /**
     * Get an overview of all the available edit types
     * @param eol The end of line to use for the overview
     * @return A listing of all the types with examples
     */
    public static String getHelp(String eol) {
        StringJoiner join = new StringJoiner(eol);

        join.add("All examples will start from 16:25:12 as base data");
        join.add("Regular")
                .add("resplit -> Use the delimiter to split the data and combine according to the value")
                .add("    xml <resplit delimiter=':' leftover='append'>i0-i1</resplit>  --> 16-25:12")
                .add("    xml <resplit delimiter=':' leftover='remove'>i0-i1</resplit>  --> 16-25")
                .add("charsplit -> Splits the given data on the char positions and combines with first used delimiter")
                .add("    fe. <charsplit>1,4,7 </charsplit>  --> 1,6:2,5:1,2")
                .add("redate -> Get the value at index according to delimiter, then go 'from' one date(time) format to the format in the value given")
                .add("    fe. <redate from='yy:dd:MM' >dd_MMMM_yy</redate>  --> 25_december_16")
                .add("    Note: to go from epoch millis use 'epochmillis' as inputformat")
                .add("retime -> Same as redate but for only time")
                .add("    fe. <retime from='HH:mm:ss' >HH-mm</retime>  --> 16-25")
                .add("replace -> Replace 'find' with the replacement (NOTE: 'replacement' can't be a whitespace character)")
                .add("    cmd pf:pathid,adde,replace,find|replace")
                .add("    fe. <replace find='1'>4</replace>  --> 46:25:42")
                .add("prepend/addprefix -> Add the given data to the front")
                .add("    cmd pf:pathid,adde,prepend,givendata")
                .add("    fe. <prepend>time=</prepend>  --> time=16:25:12")
                .add("append/addsuffix -> Add the given data at the end")
                .add("    cmd pf:pathid,adde,append,givendata")
                .add("    fe. <append> (UTC)</append>  --> time=16:25:12 (UTC)")
                .add("insert -> Add the given data at the chosen position")
                .add("    cmd pf:pathid,adde,insert,position,givendata")
                .add("    fe. <insert index='4' >!</insert>  --> time!=16:25:12 (UTC)")
                .add("indexreplace -> Replace the item at the given index (start at 0) whitt something else")
                .add("    cmd pf:pathid,adde,indexreplace,index,replacement")
                .add("    fe. <indexreplace index='1' delimiter=':'>35</indexreplace>  -->16:35:12 (UTC)")
                .add("Remove")
                .add("trim -> Remove all whitespace characters from the start and end of the data")
                .add("    cmd pf:pathid,adde,trim")
                .add("    fe. <trim/>")
                .add("remove -> Remove all occurrences of the value given  (NOTE: 'Value given' can't be a whitespace character)")
                .add("    cmd pf:pathid,adde,remove:toremove ")
                .add("    fe. <remove>1</remove>  --> 6:25:2")
                .add("cutstart -> Cut the given amount of characters from the front")
                .add("    cmd pf:pathid,adde,cutstart:charstocut")
                .add("    fe. <cutstart>2</cutstart>  --> time=:25:12")
                .add("cutend -> Cut the given amount of characters from the end")
                .add("    cmd pf:pathid,adde,cutend:charstocut")
                .add("    fe. <cutend>2</cutend>  --> time=16:25:")
                .add("Regex")
                .add("rexsplit -> Use the value as a regex to split the data and combine again with the delimiter")
                .add("    fe. <rexsplit delimiter='-'>\\d*</rexsplit>  --> 16-25-12")
                .add("rexreplace -> Use a regex based on 'find' and replace it with the value given")
                .add("    fe. <rexreplace find='\\d*'>x</rexreplace>  --> x:x:x")
                .add("rexremove -> Remove all matches of the value as a regex ")
                .add("    fe. <rexremove>\\d*</rexremove>  --> ::")
                .add("rexkeep -> Only retain the result of the regex given as value")
                .add("    fe. <rexkeep>\\d*</rexkeep>  --> 162512")
                .add("millisdate -> Convert epoch millis to a timestamp with given format")
                .add("    cmd pf:pathid,addf,millisdate:index,format")
                .add("    fe. <millisdate>yyyy-MM-dd HH:mm:ss.SSS</millisdate> ")
                .add("listreplace -> Replace the element at a certain index with the one in that position in a list")
                .add("    fe. <listreplace index='1' first='0'>cat,dog,canary</listreplace> --> if a 0 is at index 1, that will become cat");

        return LookAndFeel.formatHelpCmd(join.toString(), false);
    }
}
