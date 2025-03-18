package io.forward;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.LookAndFeel;
import util.data.RealtimeValues;
import util.data.ValTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class EditorForward extends AbstractForward{
    private final ArrayList<Function<String,String>> edits = new ArrayList<>(); // Map of all the edits being done

    public EditorForward(String id, String source, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals ){
        super(id,source,dQueue,rtvals);
    }
    public EditorForward(Element ele, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals  ){
        super(dQueue,rtvals);
        readOk = readFromXML(ele);
    }

    @Override
    protected boolean addData(String data) {

        if( data.startsWith("corrupt")){
            String d = data;
            targets.removeIf(t-> !t.writeLine(d) );
            return true;
        }

        if( debug ) // extra info given if debug is active
            Logger.info(id()+" -> Before: "+data); // how the data looked before

        for( var edit:edits){
            data = edit.apply(data);
            if( data == null ){
                Logger.error(id+"(ef) -> Editor step failed, stopped processing.");
                return true; // Still accept new data
            }
        }

        if( debug ){ // extra info given if debug is active
            Logger.info(id()+" -> After: "+data);
        }
        String finalData = data;

        // Use multithreading so the writables don't have to wait for the whole process
        nextSteps.parallelStream().forEach( ns -> ns.writeLine(id(),finalData));
        targets.parallelStream().forEach( wr -> wr.writeLine(id(),finalData));

        if( log )
            Logger.tag("RAW").info( id() + "\t" + data);

        if( !cmds.isEmpty())
            cmds.forEach( cmd->dQueue.add(Datagram.system(cmd).writable(this)));

        applyDataToStore(data);
        // If there are no targets, no label, this no longer needs to be a target
        if( noTargets() ){
            valid=false;
            return false;
        }
        return true;
    }

    @Override
    public boolean readFromXML(Element editor) {
        parsedOk=true;
        var dig = XMLdigger.goIn(editor);
        if( !readBasicsFromXml(dig))
            return false;

        edits.clear();
        if( dig.hasPeek("*")){
            dig.digOut("*").forEach(this::processNode);
        }else{
            processNode(dig);
        }
        return true;
    }

    private void processNode(XMLdigger dig ){
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
            Logger.error(id+" -> Missing content in an edit.");
            parsedOk=false;
            return;
        }
        if( index == -1 ){
            index=0;
        }
        var type = dig.attr("type",dig.tagName(""));

        switch (type) {
            case "charsplit" -> {
                addCharSplit(deli, content);
                Logger.info(id() + " -> Added charsplit with delimiter " + deli + " on positions " + content);
            }
            case "resplit" -> {
                addResplit(deli, content, error, leftover.equalsIgnoreCase("append"));
                Logger.info(id() + " -> Added resplit edit on delimiter " + deli + " with formula " + content);
            }
            case "rexsplit" -> {
                addRexsplit(deli, content);
                Logger.info(id() + " -> Get items from " + content + " and join with " + deli);
            }
            case "redate" -> {
                addRedate(from, content, index, deli);
                Logger.info(id() + " -> Added redate edit on delimiter " + deli + " from " + from + " to " + content+ " at "+index);
            }
            case "retime" -> {
                addRetime(from, content, index, deli);
                Logger.info(id() + " -> Added retime edit on delimiter " + deli + " from " + from + " to " + content);
            }
            case "replace" -> {
                if (find.isEmpty()) {
                    Logger.error(id() + " -> Tried to add an empty replace.");
                } else {
                    addReplacement(find, content);
                }
            }
            case "rexreplace" -> {
                if (find.isEmpty()) {
                    Logger.error(id() + " -> Tried to add an empty replace.");
                } else {
                    addRegexReplacement(find, content);
                }
            }
            case "remove" -> {
                addReplacement(content, "");
                Logger.info(id() + " -> Remove occurrences off " + content);
            }
            case "trim" -> {
                addTrim();
                Logger.info(id() + " -> Trimming spaces");
            }
            case "rexremove" -> {
                addRexRemove(content);
                Logger.info(id() + " -> RexRemove matches off " + content);
            }
            case "rexkeep" -> {
                addRexsplit("", content);
                Logger.info(id() + " -> Keep result of " + content);
            }
            case "prepend", "prefix" -> {
                addPrepend(content);
                Logger.info(id() + " -> Added prepend of " + content);
            }
            case "append", "suffix" -> {
                addAppend(content);
                Logger.info(id() + " -> Added append of " + content);
            }
            case "insert" -> {
                addInsert(dig.attr("index", -1), content);
                Logger.info(id() + " -> Added insert of " + content);
            }
            case "cutstart" -> {
                if (NumberUtils.toInt(content, 0) != 0) {
                    addCutStart(NumberUtils.toInt(content, 0));
                    Logger.info(id() + " -> Added cut start of " + content + " chars");
                } else {
                    Logger.warn(id() + " -> Invalid number given to cut from start " + content);
                }
            }
            case "cutend" -> {
                if (NumberUtils.toInt(content, 0) != 0) {
                    addCutEnd(NumberUtils.toInt(content, 0));
                    Logger.info(id() + " -> Added cut end of " + content + " chars");
                } else {
                    Logger.warn(id() + " -> Invalid number given to cut from end " + content);
                }
            }
            case "toascii" -> {
                converToAscii(deli);
                Logger.info(id() + " -> Added conversion to char");
            }
            case "millisdate" -> {
                addMillisToDate(content, index, deli);
                Logger.info(id() + " -> Added millis conversion to " + content);
            }
            case "listreplace" -> {
                int first = dig.attr("first", 0);
                addListReplace(content, deli, index, first);
                Logger.info(id + "(ef) -> Added listreplace of " + content + " of index " + index);
            }
            case "indexreplace","replaceindex" -> {
                addIndexReplace(index,deli,content);
                Logger.info(id + "(ef) -> Added indexreplace with " + content + " at index " + index);
            }
            case "removeindex" -> {
                addIndexReplace( NumberUtils.toInt(content,-1),deli,"");
                Logger.info(id + "(ef) -> Added remove index " + index);
            }
            default -> {
                Logger.error(id + " -> Unknown type used : '" + type + "'");
                parsedOk = false;
            }
        }
    }
    private void addListReplace( String content, String deli, int index, int first){
        rulesString.add( new String[]{"","listreplace","At "+index+" convert to "+content} );
        String[] opts = content.split(",");
        Function<String,String> edit = input ->
        {
            String[] items = input.split(deli);
            if( index > items.length ){
                Logger.error( id +"(ef) -> (ListReplace) Not enough elements after split of "+input);
                return null;
            }
            int pos = NumberUtils.toInt(items[index],Integer.MAX_VALUE);
            if( pos == Integer.MAX_VALUE){
                Logger.error(id+" (ef) -> (ListReplace) Parsing to int failed for "+items[index]);
                return null;
            }
            pos = pos-first;
            if( pos <0 || pos > opts.length){
                Logger.error( id+" (ef) -> (ListReplace) Invalid index for the list ("+pos+")");
                return null;
            }
            items[index]=opts[pos];
            return String.join(deli,items);
        };
        edits.add(edit);
    }
    private void addCharSplit( String deli, String positions){
        rulesString.add( new String[]{"","charsplit","At "+positions+" to "+deli} );
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

        Function<String,String> edit = input ->
        {
            if(indexes.get(indexes.size()-1) > input.length()){
                Logger.error(id+ "(ef) Can't split "+input+" if nothing is at "+indexes.get(indexes.size()-1));
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
                Logger.error(id+ "(ef) Failed to apply charsplit on "+input);
            }
            return null;
        };
        edits.add(edit);
    }
    private void addMillisToDate( String to, int index, String delimiter ){
        rulesString.add( new String[]{"","millisdate","millis -> "+to} );
        Function<String,String> edit = input ->
        {
            String[] split = input.split(delimiter);
            if( split.length > index){
                long millis = NumberUtils.toLong(split[index],-1L);
                if( millis == -1L ){
                    Logger.error( id() + "(ef) -> Couldn't convert "+split[index]+" to millis");
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
                        Logger.error(id() + "(ef) -> Failed to convert datetime " + split[index]);
                        return null;
                    }
                    return String.join(delimiter, split);
                }catch(IllegalArgumentException | DateTimeException e){
                    Logger.error( id() + "(ef) -> Invalid format in millis to date: "+to+" -> "+e.getMessage());
                    return null;
                }
            }
            Logger.error(id+"(ef) -> To few elements after split for millistodate in "+input);
            return null;
        };
        edits.add(edit);
    }
    /**
     * Alter the formatting of a date field
     * @param from The original format
     * @param to The new format
     * @param index On which position of the split data
     * @param delimiter The delimiter to split the data
     */
    private void addRedate( String from, String to, int index, String delimiter ){
        rulesString.add( new String[]{"","redate",from+" -> "+to} );
        String deli;
        if( delimiter.equalsIgnoreCase("*")){
            deli="\\*";
        }else{
            deli=delimiter;
        }
        Function<String,String> edit = input ->
        {
            String[] split = input.split(deli);
            if( split.length > index){
                split[index] = TimeTools.reformatDate(split[index], from, to);
                if( split[index].isEmpty()) {
                    Logger.error( id() + " -> Failed to convert datetime "+input.split(deli)[index]);
                    return null;
                }
                return String.join(deli,split);
            }
            Logger.error(id+" -> To few elements after split for redate in "+input);
            return null;
        };
        edits.add(edit);
    }
    /**
     * Alter the formatting of a time field
     * @param from The original format
     * @param to The new format
     * @param index On which position of the split data
     * @param delimiter The delimiter to split the data
     */
    private void addRetime( String from, String to, int index, String delimiter ){
        rulesString.add( new String[]{"","retime",from+" -> "+to} );
        String deli;
        if( delimiter.equalsIgnoreCase("*")){
            deli="\\*";
        }else{
            deli=delimiter;
        }
        Function<String,String> edit = input ->
        {
            String[] split = input.split(deli);
            if( split.length > index){
                split[index] = TimeTools.reformatTime(split[index],from,to);
                if( split[index].isEmpty()) {
                    Logger.error(id+"(ef) -> Tried to retime "+input+" but no such index "+index);
                    return null;
                }
                return String.join(deli,split);
            }
            Logger.error(id+" -> To few elements after split for retime in "+input);
            return null;
        };
        edits.add(edit);
    }
    private void addRexsplit( String delimiter, String regex){
        rulesString.add( new String[]{"","rexsplit","deli:"+delimiter+" ->"+regex} );

        var results = Pattern.compile(regex);

        Function<String,String> edit = input ->
        {
            var items = results.matcher(input)
                    .results()
                    .map(MatchResult::group)
                    .toArray(String[]::new);
            return String.join(delimiter,items);
        };
        edits.add(edit);
    }
    /**
     * Split a data string according to the given delimiter, then stitch it back together based on resplit
     * @param delimiter The string to split the data with
     * @param resplit The format of the new string, using i0 etc to get original values
     */
    private void addResplit( String delimiter, String resplit, String error, boolean append){

        rulesString.add( new String[]{"","resplit","deli:"+delimiter+" ->"+resplit} );

        var is = Pattern.compile("i[0-9]{1,3}")
                .matcher(resplit)
                .results()
                .map(MatchResult::group)
                .toArray(String[]::new);

        var filler = resplit.split("i[0-9]{1,3}");
        for( int a=0;a<filler.length;a++)
            filler[a]=filler[a].replace("§","");

        if(is.length==0) {
            Logger.warn(id+"(ef)-> No original data referenced in the resplit");
        }

        int[] indexes = new int[is.length];

        for( int a=0;a<is.length;a++){
            indexes[a] = Integer.parseInt(is[a].substring(1));
        }

        String deli;
        if( delimiter.equalsIgnoreCase("*")){
            deli="\\*";
        }else{
            deli=delimiter;
        }

        Function<String,String> edit = input ->
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
                    Logger.error(id+"(ef) -> Out of bounds when processing: "+input);
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
        edits.add(edit);
    }
    private void addIndexReplace( int index, String delimiter, String value ){
        if( index==-1) {
            Logger.error(id+"(ef) -> Invalid index given for indexreplace/removeindex");
            return;
        }
        if( value.isEmpty() ) {
            rulesString.add( new String[]{"","removeindex","i"+index} );
            if( index==0 ){
                edits.add( input -> {
                    int a = input.indexOf(delimiter);
                    if( a == -1 )
                        return input;
                    return input.substring(a);
                });
            }else {
                edits.add(input -> {
                    var its = input.split(delimiter);
                    var list = new ArrayList<>(Arrays.asList(its));
                    if (index < list.size()) {
                        list.remove(index);
                    } else {
                        Logger.error(id+"(ef) -> Tried to remove index " + index + " from " + input + " but no such thing.");
                        return null;
                    }
                    return String.join(delimiter, list);
                });
            }
        }else{
            rulesString.add( new String[]{"","indexreplace","i"+index+"->"+value} );
            edits.add(input -> {
                var its = input.split(delimiter);
                if (its.length > index)
                    its[index] = ValTools.parseRTline(value, its[index], rtvals);
                return String.join(delimiter, its);
            });
        }
    }
    /**
     * Add a string to the start of the data
     * @param addition The string to add at the start
     */
    private void addPrepend( String addition ){
        rulesString.add( new String[]{"","prepend","add:"+addition} );
        edits.add( input -> addition+input );
    }
    /**
     * Add a string to the end of the data
     * @param addition The string to add at the end
     */
    private void addAppend( String addition ){
        rulesString.add( new String[]{"","append","add:"+addition} );
        edits.add(  input -> input+addition );
    }
    private void addInsert( int position, String addition ){
        rulesString.add( new String[]{"","insert","add:"+addition+" at "+position} );
        edits.add( input -> {
            if( input.length() < position ) {
                Logger.error(id + "(ef) -> Tried to insert " + addition + " at index " + position + " but input string to short -> >" + input + "<");
                return null;
            }
            if (position == -1) {
                Logger.error(id + "(ef) -> Tried to insert at -1 index");
                return null;
            }
            return input.substring(0,position)+addition+input.substring(position);
        } );
    }
    private void addReplacement( String find, String replace){
        edits.add( input -> input.replace(Tools.fromEscapedStringToBytes(find),Tools.fromEscapedStringToBytes(replace)) );
        rulesString.add( new String[]{"","replace","from "+find+" -> "+replace} );
    }
    private void addTrim( ){
        rulesString.add( new String[]{"","Trim","Trim spaces "} );
        edits.add(String::trim);
    }
    private void addRexRemove( String find ){
        rulesString.add( new String[]{"","regexremove","Remove "+find} );
        edits.add( input -> input.replaceAll(find,"" ) );
    }
    private void addRegexReplacement( String find, String replace){
        String r = replace.isEmpty()?" ":replace;
        rulesString.add( new String[]{"","regexreplace","from "+find+" -> '"+r+"'"} );
        edits.add( input -> input.replaceAll(find,r ) );
    }
    private void addCutStart(int characters ){
        rulesString.add( new String[]{"","cropstart","remove "+characters+" chars from start of data"} );
        edits.add( input -> input.length()>characters?input.substring(characters):null );
    }
    private void addCutEnd( int characters ){
        rulesString.add( new String[]{"","cutend","remove "+characters+" chars from end of data"} );
        edits.add( input -> input.length()>characters?input.substring(0,input.length()-characters):null );
    }
    private void converToAscii(String delimiter){
        rulesString.add( new String[]{"","tochar","convert delimited data to char's"} );
        edits.add( input -> {
            var join = new StringJoiner("");
            Arrays.stream(input.split(delimiter)).forEach( x -> join.add(String.valueOf((char) NumberUtils.createInteger(x).intValue())));
            return join.toString();
        } );
    }
    /**
     * Test the workings of the editor by giving a string to process
     * @param input The string to process
     * @return The resulting string
     */
    public String test( String input ){
        Logger.info(id+" -> From: "+input);
        for( var edit:edits){
            input = edit.apply(input);
        }
        Logger.info(id+" -> To:   "+input);
        return input;
    }
    @Override
    protected String getXmlChildTag() {
        return "editor";
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
                .add("prepend -> Add the given data to the front")
                .add("    cmd pf:pathid,adde,prepend,givendata")
                .add("    fe. <prepend>time=</prepend>  --> time=16:25:12")
                .add("append -> Add the given data at the end")
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

        return LookAndFeel.formatCmdHelp(join.toString(), false);
    }
}
