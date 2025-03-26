package io.forward;

import das.Core;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.tasks.ConditionBlock;
import util.tools.Tools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class FilterForward extends AbstractForward {

    protected ArrayList<Predicate<String>> rules = new ArrayList<>();// Rules that define the filters
    private final ArrayList<AbstractForward> reversed = new ArrayList<>();
    private boolean negate=false; // If the filter is negated or not

    public FilterForward(String id, String source){
        super(id,source,null);
    }
    public FilterForward(Element ele){
        super(null);
        readFromXML(ele);
    }

    @Override
    protected boolean addData(String data) {

        if( doFilter(data) ){
            // Use multithreading so the writables don't have to wait for the whole process
            nextSteps.parallelStream().forEach( ns -> ns.writeLine(id(),data));
            targets.parallelStream().forEach( wr -> wr.writeLine(id(),data));

            if( log )
                Logger.tag("RAW").info( id() + "\t" + data);

            applyDataToStore(data);
        } else if (!reversed.isEmpty()) {
            reversed.parallelStream().forEach(ns -> ns.writeLine(id, data));
        }
        if( !cmds.isEmpty())
            cmds.forEach( cmd-> Core.addToQueue(Datagram.system(cmd).writable(this)));

        if( noTargets() && reversed.isEmpty() && store==null){
            valid=false;
            return false;
        }
        return true;
    }

    /**
     * Add a target for the data that doesn't make it through the filter
     * @param wr The target forward
     */
    public void addReverseTarget( AbstractForward wr ){
        if( wr==null ) {
            Logger.warn(id+"(filter) -> Null forward.");
            return;
        }
        if( reversed.stream().anyMatch( rs -> rs==wr) ){
            Logger.warn(id+"(filter) -> Duplicate request for "+wr.id);
            return;
        }
        // Add it to the list of reversed
        reversed.add( wr);
        getLastStep().addNextStep(wr); // and to the last step
        wr.setParent(this); // Finally, make this the parent
        Logger.info(id() + " -> Adding reverse target to " + wr.id());
    }
    @Override
    protected void sendDataToStep( AbstractForward step ){
        boolean reverse=false;
        var matchOpt = nextSteps.stream().filter( ns -> ns==step).findFirst();
        if( matchOpt.isEmpty()) {
            matchOpt = reversed.stream().filter(ns -> ns == step).findFirst();
            reverse=true;
        }
        if( matchOpt.isPresent()) {
            requestSource();
            if(reverse){
                getLastStep().sendDataToStep(step);
            }
        }else{
            Logger.error( id()+" -> No match found for "+step.id() );
        }
    }
    @Override
    public String toString(){
        StringJoiner join = new StringJoiner("\r\n" );
        join.add("filter:"+id+ (sources.isEmpty()?"":" getting data from "+String.join( ";",sources)));
        join.add(getRules());

        StringJoiner ts = new StringJoiner(", ","    Approved data target: ","" );
        targets.forEach( x -> ts.add(x.id()));
        if( !targets.isEmpty())
            join.add(ts.toString());

        StringJoiner ts2 = new StringJoiner(", ","    Rejected data target: ","" );
        reversed.forEach( x -> ts2.add( x.id() ));
        if( !reversed.isEmpty())
            join.add(ts2.toString());

        if( store != null )
            join.add(store.toString());
        return join.toString();
    }

    protected String getXmlChildTag(){
        return "filter";
    }
    /**
     * Read the FilterWritable setup from the xml element
     * @param filter The element containing the setup
     * @return True if all went fine
     */
    public boolean readFromXML( Element filter ){
        parsedOk=true;
        var dig = XMLdigger.goIn(filter);
        if( !readBasicsFromXml(dig) )
            return false;

        negate = dig.attr("negate",false);

        rules.clear();
        // For a filter with multiple rules
        if( dig.hasPeek("rule")){ // if rules are defined as nodes
            // Process all the types except 'start'
            var ruleDigs = dig.digOut("rule");
            dig.goUp(); // Go back up after down to rule

            ruleDigs.stream()
                    .filter( rule -> !rule.attr("type","").equalsIgnoreCase("start"))
                    .forEach( rule -> {
                        String delimiter = rule.attr("delimiter",this.delimiter);
                        addRule( rule.attr("type",""), rule.value(""),delimiter);
                    } );

            ArrayList<String> starts = new ArrayList<>();

            // Process all the 'start' filters
            ruleDigs.stream()
                    .filter( rule -> rule.attr("type","").equalsIgnoreCase("start"))
                    .forEach( rule -> starts.add( rule.value("") ) );

            if( starts.size()==1){
                addRule( "start", starts.get(0));
            }else if( starts.size()>1){
                addStartOptions( starts.toArray(new String[1]) );
            }
            return true;
        }

        // For a filter without rules or an if tag
        if( !dig.value("").isEmpty() || dig.tagName("").equals("if") || dig.hasAttr("check")){ // If only a single rule is defined
            var type = dig.attr("type","");
            // If an actual type is given
            if( !type.isEmpty() )
                return addRule(type,dig.value(""),delimiter)==1;
            // If no type is given but there's a 'check' attribute
            if(  dig.hasAttr("check") ){
                type = dig.attr("check", "");
                var tt = type.split(":");
                return addRule(tt[0],tt.length==2?tt[1]:"true",delimiter)==1;
            }
            // If no type attribute nor a check attribute
            boolean ok=true;
            for( var att: dig.allAttr().split(",") ){
                if( !(att.equals("id")||att.startsWith("delim")||att.startsWith("src")) )
                    ok &= addRule( att,dig.attr(att,""),delimiter)==1; // If any returns an error, make it false
            }
            return ok;
        }
        return true;
    }
    /**
     * Add a rule to the filter
     * @param type predefined type of the filter e.g. start,nostart,end ...
     * @param value The value for the type e.g. start:$GPGGA to start with $GPGGA
     * @return -1 -> unknown type, 1 if ok
     */
    public int addRule( String type, String value, String delimiter ){
        String[] values = value.split(",");
        rulesString.add( new String[]{"",type,value,delimiter} );

        value = Tools.fromEscapedStringToBytes(value);
        Logger.info(id+" -> Adding rule "+type+" > "+value);

        switch (StringUtils.removeEnd(type, "s")) {
            case "item" -> addItemCount(delimiter, Tools.parseInt(values[0],-1),Tools.parseInt(values.length==1?values[0]:values[1],-1));
            case "maxitem" -> addItemMaxCount(delimiter, Tools.parseInt(value,-1));
            case "minitem" -> addItemMinCount(delimiter, Tools.parseInt(value,-1));
            case "start" -> addStartsWith(value);
            case "nostart","!start" -> addStartsNotWith(value);
            case "end" -> addEndsWith(value);
            case "contain","include" -> addContains(value);
            case "!contain" -> addContainsNot(value);
            case "c_start" -> addCharAt(Tools.parseInt(values[0], -1) - 1, value.charAt(value.indexOf(",") + 1));
            case "c_end" -> addCharFromEnd(Tools.parseInt(values[0], -1) - 1, value.charAt(value.indexOf(",") + 1));
            case "minlength" -> addMinimumLength(Tools.parseInt(value, -1));
            case "maxlength" -> addMaximumLength(Tools.parseInt(value, -1));
            case "nmea" -> addNMEAcheck(Tools.parseBool(value, true));
            case "regex" -> addRegex(value);
            case "math" -> addCheckBlock(delimiter, value);
            default -> {
                if( type.startsWith("at") ){
                    var in = type.substring(2);
                    int index = NumberUtils.toInt(in,-1);
                    if( index!=-1) {
                        addItemAtIndex(index,delimiter,value);
                        return 1;
                    }
                }
                Logger.error(id + " -> Unknown type chosen " + type);
                parsedOk = false;
                return -1;
            }
        }
        return 1;
    }
    public void addRule(String type, String value){
        addRule(type, value, "");
    }

    /* Filters */
    public void addItemAtIndex( int index, String deli, String val ){
        rules.add( p -> {
            var items = p.split(deli);
            return items.length > index && items[index].equals(val);
        });
    }
    public void addItemCount( String deli, int min, int max ){
        rules.add( p -> {
            var items = p.split(deli);
            return items.length >= min && items.length <= max;
        });
    }
    public void addItemMinCount( String deli, int min ){
        rules.add( p -> p.split(deli).length >= min );
    }
    public void addItemMaxCount( String deli, int max ){
        rules.add( p -> p.split(deli).length <= max );
    }
    public void addStartsWith( String with ){
        rules.add( p -> p.startsWith(with) );
    }
    public void addRegex( String regex ){
        rules.add( p -> p.matches(regex));
    }
    public void addStartsNotWith( String with ){
        rules.add( p -> !p.startsWith(with) );
    }
    public void addStartOptions( String... withs ){
        Logger.info(id+" -> Multi start"+String.join(",",withs));
        rulesString.add( new String[]{"",String.join(" or ",withs),"start with"} );
        rules.add( p ->  Stream.of(withs).anyMatch( p::startsWith));
    }
    public void addContains( String contains ){
        rules.add( p -> p.contains(contains) );
    }
    public void addContainsNot( String contains ){
        rules.add( p -> !p.contains(contains) );
    }
    public void addEndsWith( String with ){
        rules.add( p -> p.endsWith(with) );
    }
    public void addCharAt( int index, char c ){
        rules.add( p -> index >=0 && index < p.length() && p.charAt(index)==c);
    }
    public void addCharFromEnd( int index, char c ){
        rules.add( p -> index >=0 && p.length() > index && p.charAt(p.length()-index-1)==c );
    }
    public void addMinimumLength( int length ){ rules.add( p -> p.length() >= length); }
    public void addMaximumLength( int length ){ rules.add( p -> p.length() <= length); }

    public void addNMEAcheck( boolean ok ){ rules.add( p -> (MathUtils.doNMEAChecksum(p))==ok ); }
    /* Complicated ones? */
    public void addCheckBlock(String delimiter, String value){

        var is = Pattern.compile("[i][0-9]{1,2}")// Extract all the references
                .matcher(value)
                .results()
                .distinct()
                .map(MatchResult::group)
                .map( s-> NumberUtils.toInt(s.substring(1)))
                .sorted() // so the highest one is at the bottom
                .toArray(Integer[]::new);

        var block = new ConditionBlock(rtvals).setCondition(value);
        if (!block.isInvalid())
            return;
        rules.add( p -> {
            try {
                String[] vals = p.split(delimiter);
                for( int index : is) {
                    if( !block.alterSharedMem(index, NumberUtils.toDouble(vals[index])) ){
                        Logger.error(id+" (ff) -> Tried to add a NaN to shared mem");
                        return false;
                    }
                }
                return block.start();
            } catch (ArrayIndexOutOfBoundsException e) {
                Logger.error(id + "(ff) -> Index out of bounds when trying to find the number in "+p+" for math check.");
                return false;
            }
        });
    }
    public boolean doFilter( String data ){

        for( Predicate<String> check : rules ){
            boolean result = check.test(data);
            if( !result || negate ){
                if( debug )
                    Logger.info(id+" -> "+data + " -> Failed");
                return false;
            }
        }
        if( debug )
            Logger.info(id+" -> "+data + " -> Ok");
        return true;
    }
    public static String getHelp(String eol){
        StringJoiner join = new StringJoiner(eol);
        var gr = TelnetCodes.TEXT_GREEN;
        var re = TelnetCodes.TEXT_DEFAULT;
        join.add(gr+"items"+re+" -> How many items are  there after split on delimiter" )
                .add("    fe. <filter type='items' delimiter=';'>2,4</filter> --> Item count of two up to 4 (so 2,3,4 are ok)")
                .add("    fe. <filter type='items'>2</filter> --> Item count of two using default delimiter");
        join.add(gr+"start"+re+" -> Which text the data should start with" )
                .add("    fe. <filter type='start'>$</filter> --> The data must start with $");
        join.add(gr+"nostart"+re+" -> Which text the data can't start with")
                .add("    fe. <filter type='nostart'>$</filter> --> The data can't start with $");
        join.add(gr+"end"+re+" -> Which text the data should end with")
                .add("    fe. <filter type='end'>!?</filter> --> The data must end with !?");
        join.add(gr+"contain"+re+" -> Which text the data should contain")
                .add("    fe. <filter type='contain'>zda</filter> --> The data must contain zda somewhere");
        join.add(gr+"c_start"+re+" -> Which character should be found on position c from the start (1=first)")
                .add("    fe. <filter type='c_start'>1,+</filter> --> The first character must be a +");
        join.add(gr+"c_end"+re+" -> Which character should be found on position c from the end (1=last)")
                .add("    fe. <filter type='c_end'>3,+</filter> --> The third last character must be a +");
        join.add(gr+"minlength"+re+" -> The minimum length the data should be")
                .add("    fe. <filter type='minlength'>6</filter> --> if data is shorter than 6 chars, filter out");
        join.add(gr+"maxlength"+re+" -> The maximum length the data can be")
                .add("    fe.<filter type='maxlength'>10</filter>  --> if data is longer than 10, filter out");
        join.add(gr+"nmea"+re+" -> True or false that it's a valid nmea string")
                .add("    fe. <filter type='nmea'>true</filter> --> The data must end be a valid nmea string");
        join.add(gr+"regex"+re+" -> Matches the given regex")
                .add("    fe. <filter type='regex'>\\s[a,A]</filter> --> The data must contain an empty character followed by a in any case");
        join.add(gr+"math"+re+" -> Checks a mathematical comparison")
                .add("    fe. <filter type='math' delimiter=','>i1 below 2500 and i1 above 10</filter>" );
        join.add(gr+"match"+re+" -> Compare to the item at index x")
                .add("    fe. <filter at1='test'> ");
        return join.toString();
    }
}
