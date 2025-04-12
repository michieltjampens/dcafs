package util.data;

import das.Commandable;
import das.Core;
import das.Paths;
import io.Writable;
import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.LookAndFeel;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A storage class
 */
public class RealtimeValues implements Commandable {

	/* Data stores */
	private final ConcurrentHashMap<String, RealVal> realVals = new ConcurrentHashMap<>(); 		 // doubles
	private final ConcurrentHashMap<String, IntegerVal> integerVals = new ConcurrentHashMap<>(); // integers
	private final ConcurrentHashMap<String, TextVal> textVals = new ConcurrentHashMap<>(); 		 // strings
	private final ConcurrentHashMap<String, FlagVal> flagVals = new ConcurrentHashMap<>(); 		 // booleans
	private final HashMap<String,DynamicUnit> units = new HashMap<>();

	public RealtimeValues( ){
		readFromXML( XMLdigger.goIn(Paths.settings(),"dcafs","rtvals") );
	}

	/* ************************************ X M L ****************************************************************** */
	/**
	 * Read an rtvals node
	 */
	public void readFromXML( XMLdigger dig ){

		if( dig.isInvalid() ) {
			Logger.info("No rtvals in settings.xml");
			return;
		}
		Logger.info("Reading rtvals");
		dig.digOut("*").forEach( node -> {
			if( node.hasTagName("group")) {
				var groupName = node.attr("id", ""); // get the node id
				node.currentSubs().forEach(rtval -> processRtvalElement(rtval, groupName));
			}else if( node.hasTagName("unit")){
				var pair = DynamicUnit.processUnitElement(node);
				units.put(pair.getKey(), pair.getValue());
			}
		});
	}
	/**
	 * Process a Val node
	 * @param rtval The node to process
	 * @param group The group of the Val incase none is specified
	 */
	public Optional<AbstractVal> processRtvalElement(Element rtval, String group ){
		return switch (rtval.getTagName()) {
			case "double", "real" -> processReal(rtval,group);
			case "integer", "int" -> processInteger(rtval,group);
			case "flag" -> processFlag( rtval,group );
			case "text" -> processText( rtval,group );
			default -> Optional.empty();
		};
	}
	private Optional<AbstractVal> processReal( Element rtval, String group ){
		var r = RealVal.build(rtval,group);
		if( r.isPresent() ){
			var rr = r.get();
			rr.enableTriggeredCmds();
			for( var unit : units.values() ){
				if( unit.matchesRegex(rr.name) ){
					if( rr.unit().isEmpty())
						rr.unit(unit.base);
					if( rr.scale()!=-1 && unit.baseScale()!=-1)
						rr.scale(unit.baseScale());
					break;
				}
			}
			realVals.put(rr.id(),rr);
			return Optional.of(rr);
		}
		return Optional.empty();
	}
	private Optional<AbstractVal> processInteger( Element rtval, String group ){
		var i = IntegerVal.build(rtval,group);
		if( i.isPresent() ){
			var ii = i.get();
			ii.enableTriggeredCmds();
			for( var unit : units.values() ){
				if( unit.matchesRegex(ii.name) ){
					if( ii.unit().isEmpty())
						ii.unit(unit.base);
					break;
				}
			}
			integerVals.put(ii.id(),ii);
			return Optional.of(ii);
		}
		return Optional.empty();
	}
	private Optional<AbstractVal> processFlag( Element rtval, String group ) {
		var f = FlagVal.build(rtval, group);
		if (f.isPresent()) {
			var ff = f.get();
			ff.enableTriggeredCmds();
			flagVals.put(ff.id(), ff);
			return Optional.of(ff);
		}
		return Optional.empty();
	}
	private Optional<AbstractVal> processText( Element rtval, String group ){
		var t = TextVal.build(rtval, group);
		if( t.isPresent()){
			var tt = t.get();
			tt.enableTriggeredCmds();
			textVals.put(tt.id(), tt);
			return Optional.of(tt);
		}
		return Optional.empty();
	}

	public void removeVal( AbstractVal val ){
		if( val == null)
			return;
		if( val instanceof RealVal ){
			realVals.remove(val.id());
		}else if( val instanceof IntegerVal ){
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
	public AbstractVal AddIfNewAndRetrieve(AbstractVal val) {
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
		if( rv==null) {
			Logger.error("Invalid RealVal received, won't try adding it");
			return;
		}
		if( realVals.containsKey(rv.id()))
			return;

		realVals.put(rv.id(), rv);
	}
	public boolean hasReal(String id){
		if( id.isEmpty()) {
			Logger.error("RealVal -> Empty id given");
			return false;
		}
		return realVals.containsKey(id);
	}
	/**
	 * Retrieve a RealVal from the hashmap based on the id
	 * @param id The reference with which the object was stored
	 * @return The requested RealVal or null if not found
	 */
	public Optional<RealVal> getRealVal( String id ){
		if( id.isEmpty()) {
			Logger.error("Realval -> Empty id given");
			return Optional.empty();
		}
		var opt = Optional.ofNullable(realVals.get(id));
		if( opt.isEmpty())
			Logger.warn("Tried to retrieve non existing RealVal " + id);
		return opt;
	}
	/**
	 * Sets the value of a real (in the hashmap)
	 *
	 * @param id    The parameter name
	 * @param value The value of the parameter
	 */
	public void updateReal(String id, double value) {
		getRealVal(id).ifPresent( r -> r.value(value));
	}
	/**
	 * Get the value of a real
	 *
	 * @param id The id to get the value of
	 * @param defVal The value to return of the id wasn't found
	 * @return The value found or the bad value
	 */
	public double getReal(String id, double defVal) {
		var star = id.indexOf("*");
		var dOpt = getRealVal(star==-1?id:id.substring(0,star));

		return dOpt.map(realVal -> realVal.value(star == -1 ? "" : id.substring(star + 1))).orElse(defVal);
	}
	/* ************************************ I N T E G E R V A L ***************************************************** */
	/**
	 * Adds an integerval if it doesn't exist yet
	 *
	 * @param iv The IntegerVal to add
	 */
	public void addIntegerVal(IntegerVal iv) {
		if( iv==null) {
			Logger.error("Invalid IntegerVal received, won't try adding it");
			return;
		}
		if( integerVals.containsKey(iv.id()))
			return;

		integerVals.put(iv.id(), iv);
	}
	public boolean hasInteger( String id ){
		return integerVals.containsKey(id);
	}
	/**
	 * Retrieve a IntegerVal from the hashmap based on the id
	 * @param id The reference with which the object was stored
	 * @return The requested IntegerVal or empty optional if not found
	 */
	public Optional<IntegerVal> getIntegerVal( String id ){
		if( integerVals.get(id)==null)
			Logger.warn( "Tried to retrieve non existing IntegerVal "+id);
		return Optional.ofNullable(integerVals.get(id));
	}
	/* *********************************** T E X T S  ************************************************************* */
	public void addTextVal(TextVal tv) {
		if( tv==null) {
			Logger.error("Invalid IntegerVal received, won't try adding it");
			return;
		}
		if( textVals.containsKey(tv.id()))
			return;

		textVals.put(tv.id(), tv);
	}
	public boolean hasText(String id){
		return textVals.containsKey(id);
	}
	/**
	 * Retrieve a TextVal from the hashmap based on the id
	 * @param id The reference with which the object was stored
	 * @return The requested TextVal or empty optional if not found
	 */
	public Optional<TextVal> getTextVal( String id ){
		if( textVals.get(id)==null && !id.startsWith("dcafs"))
			Logger.warn( "Tried to retrieve non existing TextVal "+id);
		return Optional.ofNullable(textVals.get(id));
	}
	/**
	 * Set the value of a TextVal and create it if it doesn't exist yet
	 *
	 * @param id    The name/id of the val
	 * @param value The new content
	 */
	public void setText(String id, String value) {

		if( id.isEmpty()) {
			Logger.error("Empty id given");
			return;
		}
		if( textVals.containsKey(id)) {
			textVals.get(id).parseValue(value);
		}else{
			textVals.put(id, TextVal.newVal(id, value));
		}
	}
	/* ************************************** F L A G S ************************************************************* */
	public void addFlagVal(FlagVal fv) {
		if( fv==null) {
			Logger.error("Invalid IntegerVal received, won't try adding it");
			return;
		}
		if( flagVals.containsKey(fv.id()))
			return;
		flagVals.put(fv.id(), fv);
	}
	public boolean hasFlag( String flag ){
		return getFlagVal(flag).isPresent();
	}
	public Optional<FlagVal> getFlagVal( String flag ){
		return Optional.ofNullable( flagVals.get(flag));
	}
	public boolean getFlagState(String flag ){
		var f = getFlagVal(flag);
		if( f.isEmpty()) {
			Logger.warn("No such flag: " + flag);
			return false;
		}
		return f.get().isUp();
	}
	/* ******************************************************************************************************/

	/**
	 * Look through all the vals for one that matches the id
	 * @param id The id to find
	 * @return An optional of the val, empty if not found
	 */
	public Optional<AbstractVal> getAbstractVal( String id ){
		// Check real
		var val = getRealVal(id);
		if( val.isPresent())
			return Optional.of(val.get());
		// Check integer
		var iv = getIntegerVal(id);
		if( iv.isPresent())
			return Optional.of(iv.get());
		// Check flags
		var fv = getFlagVal(id);
		if( fv.isPresent())
			return Optional.of(fv.get());
		// Check Texts
		var tv = getTextVal(id);
		if( tv.isPresent())
			return Optional.of(tv.get());
		return Optional.empty();
	}
	/**
	 * Look through all the vals for one that matches the id
	 * @param id The id to find
	 * @return An optional of the val, empty if not found
	 */
	public boolean hasAbstractVal( String id ){
		// Check real
		var ok = hasReal(id);
		if( ok)
			return true;
		// Check integer
		ok = hasInteger(id);
		if( ok)
			return true;
		// Check flags
		ok = hasFlag(id);
		if( ok )
			return true;
		// Check Texts
		return hasText(id);
	}
	public int addRequest(Writable writable, String type, String req) {
		var av = switch (type) {
			case "double", "real" -> realVals.get(req);
			case "int", "integer" -> integerVals.get(req);
			case "text" -> textVals.get(req);
			case "flag" -> flagVals.get(req);
			default -> {Logger.warn("rtvals -> Requested unknown type: " + type); yield null;}
		};
		if( av == null)
			return 0;
		av.addTarget(writable);
		return 1;
	}
	public boolean addRequest(Writable writable, String rtval) {
		var rv = realVals.get(rtval);
		if( rv!=null){
			rv.addTarget(writable);
			return true;
		}
		var iv = integerVals.get(rtval);
		if( iv!=null){
			iv.addTarget(writable);
			return true;
		}
		var fv = flagVals.get(rtval);
		if( fv!=null){
			fv.addTarget(writable);
			return true;
		}
		var tv = textVals.get(rtval);
		if( tv!=null){
			tv.addTarget(writable);
			return true;
		}
		return false;
	}
	public boolean removeWritable( Writable writable ) {
		realVals.values().forEach(rv -> rv.removeTarget(writable));
		integerVals.values().forEach( iv -> iv.removeTarget(writable));
		textVals.forEach( (key, list) -> list.removeTarget(writable));
		flagVals.forEach( (key, list) -> list.removeTarget(writable));
		return false;
	}
	/* ************************** C O M M A N D A B L E ***************************************** */
	@Override
	public String replyToCommand(Datagram d) {
		var html = d.asHtml();
		var args = d.args();

		// Switch between either empty string or the telnetcode because of htm not understanding telnet
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String reg=html?"":TelnetCodes.TEXT_DEFAULT;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;

		var result = switch (d.cmd()) {
			case "rv", "reals", "iv", "integers" -> replyToNumericalCmd(d.cmd(), args);
			case "texts", "tv" ->  replyToTextsCmd(args);
			case "flags", "fv" ->  replyToFlagsCmd(args, html);
			case "rtvals", "rvs" -> replyToRtvalsCmd(args, html);
			case "rtval", "real", "int", "integer","text","flag" -> {
				int s = addRequest(d.getWritable(), d.cmd(), args);
				yield s != 0 ? "Request added to " + args : "Request failed";
			}
			case "" -> {
				removeWritable(d.getWritable());
				yield "";
			}
			default ->  "! No such subcommand in rtvals: "+args;
		};
		if( result.startsWith("!"))
			return ora+result+reg;
		return green+result+reg;
	}
	public String replyToNumericalCmd( String cmd, String args ){

		var cmds = args.split(",");

		if (cmds.length == 1) {
			if (args.equalsIgnoreCase("?")) {
				if (args.startsWith("i"))
					return "iv:update,id,value -> Update an existing int, do nothing if not found";
				return "rv:update,id,value -> Update an existing real, do nothing if not found";
			}
			return "! Not enough arguments";
		}

		return switch(cmds[1]){
			case "update","def" -> doUpdateNumCmd( cmd, cmds );
			case "new" -> doNewNumCmd(cmd,cmds);
			default -> "! No such subcommand in "+cmd+": "+cmds[0];
		};
	}
	private String doUpdateNumCmd( String cmd, String[] args ){
		if (args.length < 3)
			return "! Not enough arguments, "+cmd+":id,"+args[1]+",expression";
		NumericVal val;

		if (cmd.startsWith("r")) { // so real, rv
			var rOpt = getRealVal(args[0]);
			if( rOpt.isEmpty() )
				return "! No such real yet";
			val = rOpt.get();
		}else{ // so int,iv
			var iOpt = getIntegerVal(args[0]);
			if( iOpt.isEmpty() )
				return "! No such int yet";
			val = iOpt.get();
		}
		var result = ValTools.processExpression(args[2], this);
		if (Double.isNaN(result))
			return "! Unknown id(s) in the expression " + args[2];
		val.updateValue(result);
		return val.id()+" updated to " + result;
	}
	private String doNewNumCmd( String cmd, String[] cmds ){

		// Split in group & name
		String group,name;
		if( cmds.length==3){
			group=cmds[2];
			name=cmds[0];
		}else if(cmds.length==2){
			if( !cmds[0].contains("_") )
				return "! No underscore in the id, can't split between group and name";
			group = cmds[0].substring(0, cmds[0].indexOf("_"));
			name = cmds[0].substring(group.length()+1); //skip the group and underscore
		}else{
			return "! Not enough arguments, "+cmd+":id,new or "+cmd+":name,new,group";
		}

		if( hasAbstractVal(group+"_"+name) ){
			return "! Already an rtval with that id";
		}

		// Build the node
		var fab = Paths.fabInSettings("rtvals")
						.selectOrAddChildAsParent("group","id",group);
		if(cmd.startsWith("r")){ // So real
			fab.addChild("real").attr("name",name);
			addRealVal( RealVal.newVal(group,name) );
		}else if(cmd.startsWith("i")){
			fab.addChild("int").attr("name",name);
			addIntegerVal(IntegerVal.newVal(group,name) );
		}
		fab.build();
		return "Val added.";
	}
	public String replyToFlagsCmd( String cmd, boolean html ){

		if( cmd.equals("?"))
			return doFlagHelpCmd(html);

		FlagVal flag;
		var args = cmd.split(",");
		if( args.length<2 )
			return "! Not enough arguments, at least: fv:id,cmd";

		var flagOpt = getFlagVal(args[0]);
		if( flagOpt.isEmpty() ) {
			Logger.error("No such flag: "+args[0]);
			return "! No such flag yet";
		}

		flag=flagOpt.get();
		if( args.length == 2){
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
		}else if( args.length==3 ){
			switch(args[1]) {
                case "update" -> {
                    return flag.parseValue(args[2]) ? "Flag updated" : "! Failed to parse state: " + args[2];
                }
                case "match" -> {
					if (!hasFlag(args[2]))
						return "! No such flag: " + args[2];
					getFlagVal(args[2]).ifPresent(to -> flag.value(to.state));
					return "Flag matched accordingly";
				}
				case "negated" -> {
					if (!hasFlag(args[2]))
						return "! No such flag: " + args[2];
					getFlagVal(args[2]).ifPresent(to -> flag.value(!to.state));
					return "Flag negated accordingly";
				}
			}
		}
		return "! No such subcommand in fv: "+args[1]+" or incorrect number of arguments.";
	}

	private static String doFlagHelpCmd(boolean html) {

		var join = new StringJoiner("\r\n");
		join.add("Commands that interact with the collection of flags.");
		join.add( "Note: both fv and flags are valid starters")
				.add("Update")
				.add("fv:id,raise/set -> Raises the flag/Sets the bit, created if new")
				.add("fv:id,lower/clear -> Lowers the flag/Clears the bit, created if new")
				.add("fv:id,toggle -> Toggles the flag/bit, not created if new")
				.add("fv:id,update,state -> Update  the state of the flag")
				.add("fv:id,match,refid -> The state of the flag becomes the same as the ref flag")
				.add("fv:id,negated,refid  -> The state of the flag becomes the opposite of the ref flag");
		return LookAndFeel.formatHelpCmd(join.toString(), html);
	}
	public String replyToTextsCmd( String args ){

		var cmds = args.split(",");

		// Get the TextVal if it exists
		TextVal txt;
		if( cmds.length<2 )
			return "! Not enough arguments, at least: tv:id,cmd";

		var txtOpt = getTextVal(cmds[0]);
		if( txtOpt.isEmpty() )
			return "! No such text yet";

		txt=txtOpt.get();

		// Execute the commands
		if (cmds[1].equals("update")) {
			if (cmds.length < 3)
				return "! Not enough arguments: tv:id,update,value";
			int start = args.indexOf(",update") + 8; // value can contain , so get position of start
			txt.value(args.substring(start));
			return "TextVal updated";
		}
		return "! No such subcommand in tv: "+cmds[1];
	}
	public String replyToRtvalsCmd( String args, boolean html ){

		if( args.isEmpty())
			return getRtvalsList(html,true,true,true, true);

		String[] cmds = args.split(",");

		if( cmds.length==1 ){
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
					Core.addToQueue( Datagram.system("pf","reload"));
					Core.addToQueue( Datagram.system("dbm","reload"));
					return "Reloaded rtvals and paths, databases (because might be affected).";
				}
				case "groups" -> {
					String groups = String.join(html ? "<br>" : "\r\n", getGroups());
					return groups.isEmpty() ? "! No groups yet" : groups;
				}

			}
		}else if(cmds.length==2){
			return switch( cmds[0] ){
				case "group" -> getRTValsGroupList(cmds[1], true, true, true, true, html);
				case "resetgroup" -> {
					var vals = getGroupVals(cmds[1]);
					if( vals.isEmpty()) {
						Logger.error( "No vals found in group "+cmds[1]);
						yield "! No vals with that group";
					}
					getGroupVals(cmds[1]).forEach(AbstractVal::resetValue);
					yield "Values reset";
				}
				case "name" -> getNameVals(cmds[1]);
				default -> "! No such subcommand in rtvals: "+args;
			};
		}
		return "! No such subcommand in rtvals: "+args;
	}
	public String getNameVals( String regex ){
		return Stream.of(realVals, integerVals, flagVals, textVals)
							.flatMap(map -> map.values().stream()) // Flatten all the values from the maps
							.filter(val -> val.name.matches(regex)
										&& (!val.group.equals("dcafs") || !(val instanceof TextVal))) // Filter by group
							.map( val -> val.group+"_"+val.name+" : "+val.stringValue())
							.collect(Collectors.joining("\r\n"));
	}
	public ArrayList<AbstractVal> getGroupVals( String group ){
		return Stream.of(realVals, integerVals, flagVals, textVals)
				.flatMap(map -> map.values().stream()) // Flatten all the values from the maps
				.filter(val -> val.group().equalsIgnoreCase(group)) // Filter by group
				.collect(Collectors.toCollection(ArrayList::new)); // Collect the results into a List
	}
	/**
	 * Get a listing of all stored variables that belong to a certain group
	 * @param group The group they should belong to
	 * @param html Use html formatting or telnet
	 * @return The listing
	 */
	public String getRTValsGroupList(String group, boolean showReals, boolean showFlags, boolean showTexts, boolean showInts, boolean html) {

		String title;
		if( group.isEmpty()){
			title = html ? "<b>Ungrouped</b>" : TelnetCodes.TEXT_CYAN + "Ungrouped" + TelnetCodes.TEXT_YELLOW;
		}else{
			title = html ? "<b>Group: " + group + "</b>" : TelnetCodes.TEXT_CYAN + "Group: " + group + TelnetCodes.TEXT_YELLOW;
		}

		ArrayList<NumericVal> nums = new ArrayList<>();
		if (showReals)
			nums.addAll(realVals.values().stream().filter(rv -> rv.group().equalsIgnoreCase(group)).toList());
		if( showInts )
			nums.addAll(integerVals.values().stream().filter(rv -> rv.group().equalsIgnoreCase(group)).toList());

		var list = new ArrayList<String>();
		if( !nums.isEmpty()){
			nums.stream()
					.sorted((nv1, nv2) -> {
						if (nv1.order() != nv2.order())
							return Integer.compare(nv1.order(), nv2.order());
						return nv1.name().compareTo(nv2.name());
					})
					.map(nv -> "  " + nv.name() + " : " + applyUnit(nv) + " " + nv.getExtras()) // change it to strings
					.forEach(list::add);
		}
		if( showTexts )
			textVals.values().stream().filter( v -> v.group().equalsIgnoreCase(group))
					.map(v -> "  " + v.name() + " : " + (v.value().isEmpty()?"<empty>":v.value()) )
					.sorted().forEach(list::add);

		if( showFlags )
			flagVals.values().stream().filter(fv -> fv.group().equalsIgnoreCase(group))
					.map(v -> "  " + v.name() + " : " + v) //Change it to strings
					.sorted().forEach(list::add); // Then add the sorted the strings


		String eol = html ? "<br>" : "\r\n";
		StringJoiner join = new StringJoiner(eol, title + eol, "");
		join.setEmptyValue("None yet");
		boolean toggle=false;
		for( var line : list ){
			if( !line.contains("Group:") ){
				line = (toggle?TelnetCodes.TEXT_DEFAULT:TelnetCodes.TEXT_YELLOW)+line;
				toggle=!toggle;
			}
			join.add(line);
		}
		return join.toString();
	}
	public String applyUnit( NumericVal nv ){
		if( units.isEmpty())
			return nv.asValueString();

		DynamicUnit unit=null;
		for( var set : units.entrySet() ){
			if( nv.unit().endsWith(set.getKey())) {
				unit = set.getValue();
				break;
			}
		}
		if( unit==null||unit.noSubs())
			return nv.asValueString();
		if( nv.getClass() == RealVal.class) {
			var rv = (RealVal)nv;
			if( Double.isNaN(rv.asDoubleValue()))
				return "NaN";
			return unit.apply(rv.raw(), rv.unit, rv.scale() );
		}
		return unit.apply(nv.asDoubleValue(), nv.unit(),0);
	}
	/**
	 * Get the full listing of all reals,flags and text, so both grouped and ungrouped
	 * @param html If true will use html newline etc
	 * @return The listing
	 */
	public String getRtvalsList(boolean html, boolean showReals, boolean showFlags, boolean showTexts, boolean showInts){
		String eol = html?"<br>":"\r\n";
		StringJoiner join = new StringJoiner(eol,"Status at "+ TimeTools.formatShortUTCNow()+eol+eol,"");
		join.setEmptyValue("None yet");

		// Find & add the groups
		for( var group : getGroups() ){
			var res = getRTValsGroupList(group,showReals,showFlags,showTexts,showInts,html);
			if( !res.isEmpty() && !res.equalsIgnoreCase("none yet"))
				join.add(res).add("");
		}
		var res = getRTValsGroupList("",showReals,showFlags,showTexts,showInts,html);
		if( !res.isEmpty() && !res.equalsIgnoreCase("none yet"))
			join.add(res).add("");

		if( !html)
			return join.toString();

		// Try to fix some symbols to correct html counterpart
		return join.toString().replace("°C","&#8451") // fix the °C
								.replace("m²","&#13217;") // Fix m²
								.replace("m³","&#13221;"); // Fix m³
	}
	/**
	 * Get a list of all the groups that exist in the rtvals
	 * @return The list of the groups
	 */
	public List<String> getGroups(){
		return  Stream.of(realVals, integerVals, flagVals, textVals)
				.flatMap(map -> map.values().stream()) // Flatten all the values from the maps
				.map(AbstractVal::group) // Get the group instead
				.filter( val -> !val.isEmpty() && !val.equals("dcafs"))
				.distinct()
				.toList();
	}
	/* ******************************** D Y N A M I C  U N I T **************************************************** */
	public static class DynamicUnit{
		String base;
		int baseScale=-1;
		String valRegex="";
		TYPE type = TYPE.STEP;
		ArrayList<SubUnit> subs = new ArrayList<>();
		enum TYPE {STEP,LEVEL}

        public DynamicUnit( String base ){
			this.base=base;
		}

		public static Map.Entry<String, DynamicUnit> processUnitElement(XMLdigger dig) {

			var base = dig.attr("base", ""); // Starting point
			var unit = new DynamicUnit(base);
			unit.setValRegex(dig.attr("nameregex", ""));
			var defDiv = dig.attr("div", 1);
			var defScale = dig.attr("scale", dig.attr("digits", -1));

			if (dig.hasPeek("level")) {
				for (var lvl : dig.digOut("level")) { // Go through the levels
					var val = lvl.value(""); // the unit
					var div = lvl.attr("div", defDiv); // the multiplier to go to next step
					var max = lvl.attr("till", lvl.attr("max", 0.0)); // From which value the nex unit should be used
					var scale = lvl.attr("scale", -1);
					scale = lvl.attr("digits", scale == -1 ? defScale : scale);

					unit.addLevel(val, div, max, scale);
				}
			} else if (dig.hasPeek("step")) {
				for (var step : dig.digOut("step")) { // Go through the steps
					var val = step.value(""); // the unit
					var cnt = step.attr("cnt", 1); // the multiplier to go to next step
					unit.addStep(val, cnt);
				}
			} else {
				Logger.warn("No valid subnodes in the unit node for " + base);
			}
			return new AbstractMap.SimpleEntry<>(base, unit);
		}

		public void setValRegex( String regex ){
			this.valRegex=regex;
		}
		public boolean matchesRegex(String name){
			return !valRegex.isEmpty() && name.matches(valRegex);
		}
		public int baseScale(){
			return baseScale;
		}
		public boolean noSubs(){
			return subs.isEmpty();
		}
		public void addStep( String unit, int cnt ){
			type=TYPE.STEP;
			subs.add( new SubUnit(unit,cnt,0,0,0));
		}
		public void addLevel( String unit, int mul, double max, int scale ){
			type=TYPE.LEVEL;
			double min = Double.NEGATIVE_INFINITY;
			if( !subs.isEmpty() ){
				var prev = subs.get(subs.size()-1);
				if( prev.unit.equals(unit)){ // If the same unit, don't divide
					min = prev.max;
				}else{// If different one, do
					min = prev.max/mul;
				}
			}

			subs.add( new SubUnit(unit,mul,min,max,scale));
		}
		public String apply( double total, String curUnit, int scale ){
			return switch(type){
				case STEP -> processStep(total);
				case LEVEL -> processLevel(total,scale,curUnit);
			};
		}
		private String processStep( double total ){
			StringBuilder result= new StringBuilder();
			var amount = (int)Math.rint(total);
			String unit = base;
			for( int a=0;a<subs.size();a++ ){
				var sub = subs.get(a);
				if( amount > sub.div){ // So first sub applies...
					result.insert(0, (amount % sub.div) + unit); // Add the first part
					amount = amount/sub.div; // Change to next unit
				}else{ // Sub doesn't apply
					result.insert(0, amount + unit); // Add it
					return result.toString(); // Finished
				}
				unit = sub.unit;
				if( a==subs.size()-1){ // If this is the last step
					result.insert(0, amount + unit); // Add it
					return result.toString(); // Finished
				}
			}
			return total+base;
		}
		private String processLevel(double total, int scale, String curUnit){
			if (curUnit.isEmpty())
				return String.valueOf(total);

			int index;
			for( index=0;index<subs.size();index++){
				if( subs.get(index).unit.equalsIgnoreCase(curUnit))
					break;
			}
			if( index == subs.size() ) {
				Logger.warn("Couldn't find corresponding unit in list: "+curUnit);
				return total + curUnit;
			}
			// Check lower limit
			while( subs.get(index).min!=0 && total <= subs.get(index).min && index!=0){
				if( !subs.get(index).unit.equals(subs.get(index-1).unit))
					total*=subs.get(index).div;
				index--;
			}
			while( subs.get(index).max!=0 && total > subs.get(index).max && index!=subs.size()-1){
				if( !subs.get(index).unit.equals(subs.get(index+1).unit))
					total/=subs.get(index).div;
				index++;
			}
			if( subs.get(index).scale!=-1) // -1 scale is ignored by round double, but cleaner this way?
				total = MathUtils.roundDouble(total, subs.get(index).scale);
			if( scale>0) // Apply scaling if any
				total = MathUtils.roundDouble(total, scale);
			return total + subs.get(index).unit;
		}
		public static class SubUnit{
			String unit;
			int div;
			double min,max;
			int scale;

			public SubUnit( String unit, int mul, double min, double max, int scale ){
				this.unit=unit;
				this.div =mul;
				this.min=min;
				this.max=max;
				this.scale=scale;
			}
		}
	}
}