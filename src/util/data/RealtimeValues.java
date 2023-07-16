package util.data;

import das.Commandable;
import das.IssuePool;
import io.Writable;
import io.forward.AbstractForward;
import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * A storage class
 */
public class RealtimeValues implements Commandable {

	/* Data stores */
	private final ConcurrentHashMap<String, RealVal> realVals = new ConcurrentHashMap<>(); 		 // doubles
	private final ConcurrentHashMap<String, IntegerVal> integerVals = new ConcurrentHashMap<>(); // integers
	private final ConcurrentHashMap<String, TextVal> textVals = new ConcurrentHashMap<>(); 		 // strings
	private final ConcurrentHashMap<String, FlagVal> flagVals = new ConcurrentHashMap<>(); 		 // booleans

	private final IssuePool issuePool;

	/* General settings */
	private final Path settingsPath;

	/* Other */
	private final BlockingQueue<Datagram> dQueue; // Used to issue triggered cmd's

	public RealtimeValues( Path settingsPath,BlockingQueue<Datagram> dQueue ){
		this.settingsPath=settingsPath;
		this.dQueue=dQueue;

		XMLdigger.goIn(settingsPath,"dcafs")
					.digDown("rtvals")
					.current()
				    .ifPresentOrElse(this::readFromXML,() -> Logger.info("No rtvals in settings.xml"));

		issuePool = new IssuePool(dQueue, settingsPath,this);
	}

	/* ************************************ X M L ****************************************************************** */
	/**
	 * Read an rtvals node
	 */
	public void readFromXML( Element rtvalsEle ){

		var dig = XMLdigger.goIn(rtvalsEle).digDown("*"); // inside the rtvals node

		if( !dig.isValid())
			return;

		Logger.info("Reading rtvals element");

		while ( dig.iterate() ) { // If any found, iterate through vals
			var groupName = dig.attr("id", ""); // get the node id
			dig.currentSubs().forEach( rtval -> processRtvalElement(rtval,groupName));
		}
	}
	public void readFromXML( XMLfab fab ){
		readFromXML(fab.getCurrentElement());
	}
	/**
	 * Process a Val node
	 * @param rtval The node to process
	 * @param group The group of the Val
	 */
	private void processRtvalElement(Element rtval, String group ){

		switch (rtval.getTagName()) {
			case "double", "real" -> {
				RealVal.build(rtval,group).ifPresent( r->{
					r.enableTriggeredCmds(dQueue);
					realVals.put(r.id(),r);
				});
			}
			case "integer", "int" -> {
				IntegerVal.build(rtval,group).ifPresent( r->{
					r.enableTriggeredCmds(dQueue);
					integerVals.put(r.id(),r);
				});
			}
			case "flag" -> {
				FlagVal.build(rtval, group).ifPresent(r -> {
					r.enableTriggeredCmds(dQueue);
					flagVals.put(r.id(), r);
				});
			}
			case "text" -> {
				TextVal.build(rtval, group).ifPresent(r -> {
					r.enableTriggeredCmds(dQueue);
					textVals.put(r.id(), r);
				});
			}
		}
	}
	public void removeVal( AbstractVal val ){
		if( val == null)
			return;
		if( val instanceof RealVal ){
			realVals.remove(val.id());
		}else if( val instanceof IntegerVal ){
			integerVals.remove(val.id());
		}else if( val instanceof  FlagVal ){
			flagVals.remove( val.id());
		}else if( val instanceof  TextVal ){
			textVals.remove( val.id());
		}
	}
	/* ************************************ R E A L V A L ***************************************************** */
	/**
	 * Add a RealVal to the collection if it doesn't exist yet, optionally writing it to xml
	 * @param rv The RealVal to add
	 * @return RESULT.OK if ok, RESULT.EXISTS if already existing, RESULT.ERROR if something went wrong
	 */
	public AbstractForward.RESULT addRealVal(RealVal rv) {
		if( rv==null) {
			Logger.error("Invalid RealVal received, won't try adding it");
			return AbstractForward.RESULT.ERROR;
		}
		if( realVals.containsKey(rv.id()))
			return AbstractForward.RESULT.EXISTS;

		return realVals.put(rv.id(),rv)==null? AbstractForward.RESULT.ERROR: AbstractForward.RESULT.OK;
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
			Logger.error( "Tried to retrieve non existing realval "+id);
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
	 * @param iv The IntegerVal to add
	 * @return RESULT.OK if ok, RESULT.EXISTS if already existing, RESULT.ERROR if something went wrong
	 */
	public AbstractForward.RESULT addIntegerVal( IntegerVal iv ){
		if( iv==null) {
			Logger.error("Invalid IntegerVal received, won't try adding it");
			return AbstractForward.RESULT.ERROR;
		}
		if( integerVals.containsKey(iv.id()))
			return AbstractForward.RESULT.EXISTS;

		return integerVals.put(iv.id(),iv)==null? AbstractForward.RESULT.ERROR: AbstractForward.RESULT.OK;
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
			Logger.error( "Tried to retrieve non existing IntegerVal "+id);
		return Optional.ofNullable(integerVals.get(id));
	}
	/* *********************************** T E X T S  ************************************************************* */
	public AbstractForward.RESULT addTextVal( TextVal tv){
		if( tv==null) {
			Logger.error("Invalid IntegerVal received, won't try adding it");
			return AbstractForward.RESULT.ERROR;
		}
		if( textVals.containsKey(tv.id()))
			return AbstractForward.RESULT.EXISTS;

		return textVals.put(tv.id(),tv)==null? AbstractForward.RESULT.ERROR: AbstractForward.RESULT.OK;
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
		if( textVals.get(id)==null)
			Logger.error( "Tried to retrieve non existing TextVal "+id);
		return Optional.ofNullable(textVals.get(id));
	}
	/**
	 * Set the value of a textval and create it if it doesn't exist yet
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
	public AbstractForward.RESULT addFlagVal( FlagVal fv ){
		if( fv==null) {
			Logger.error("Invalid IntegerVal received, won't try adding it");
			return AbstractForward.RESULT.ERROR;
		}
		if( flagVals.containsKey(fv.id()))
			return AbstractForward.RESULT.EXISTS;

		return flagVals.put(fv.id(),fv)==null? AbstractForward.RESULT.ERROR: AbstractForward.RESULT.OK;
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
	public int addRequest(Writable writable, String type, String req) {
		switch (type) {
			case "double", "real" -> {
				var rv = realVals.get(req);
				if (rv == null)
					return 0;
				rv.addTarget(writable);
				return 1;
			}
			case "int", "integer" -> {
				var iv = integerVals.get(req);
				if (iv == null)
					return 0;
				iv.addTarget(writable);
				return 1;
			}
			case "text" -> {
				var tv = textVals.get(req);
				if (tv == null)
					return 0;
				tv.addTarget(writable);
				return 1;
			}
			default -> Logger.warn("Requested unknown type: " + type);
		}
		return 0;
	}
	public boolean removeWritable(Writable writable ) {
		realVals.values().forEach(rv -> rv.removeTarget(writable));
		integerVals.values().forEach( iv -> iv.removeTarget(writable));
		textVals.forEach( (key, list) -> list.removeTarget(writable));
		return false;
	}
	/* ************************** C O M M A N D A B L E ***************************************** */
	@Override
	public String replyToCommand(String cmd, String args, Writable wr, boolean html) {

		// Switch between either empty string or the telnetcode because of htm not understanding telnet
		String green=html?"":TelnetCodes.TEXT_GREEN;
		String reg=html?"":TelnetCodes.TEXT_DEFAULT;
		String ora = html?"":TelnetCodes.TEXT_ORANGE;

		String result;
		switch (cmd) {
			case "rv", "reals" -> {
				if( args.equals("?"))
					return green + "  rv:update,id,value" + reg + " -> Update an existing real, do nothing if not found";
				result = replyToNumericalCmd(cmd,args);
			}
			case "iv", "integers" -> {
				if( args.equals("?"))
					return green + "  iv:update,id,value" + reg + " -> Update an existing int, do nothing if not found";
				result = replyToNumericalCmd(cmd,args);
			}
			case "texts", "tv" -> {
				result = replyToTextsCmd(args);
			}
			case "flags", "fv" -> {
				result = replyToFlagsCmd(args, html);
			}
			case "rtvals", "rvs" -> {
				result = replyToRtvalsCmd(args, html);
			}
			case "rtval", "real", "int", "integer","text" -> {
				int s = addRequest(wr, cmd, args);
				result = s != 0 ? "Request added to " + s : "Request failed";
			}
			case "" -> {
				removeWritable(wr);
				return "";
			}
			default -> {
				return "! No such subcommand in rtvals: "+args;
			}
		}
		if( result.startsWith("!"))
			return ora+result+reg;
		return green+result+reg;
	}
	public String replyToNumericalCmd( String cmd, String args ){

		var cmds = args.split(",");

		NumericVal val;
		if( cmd.startsWith("r")){ // so real, rv
			var rOpt = getRealVal(cmds[0]);
			if( rOpt.isEmpty() )
				return "! No such real yet";
			val = rOpt.get();
		}else{ // so int,iv
			var iOpt = getIntegerVal(cmds[0]);
			if( iOpt.isEmpty() )
				return "! No such int yet";
			val = iOpt.get();
		}

		if (cmds[0].equals("update")) {
			if (cmds.length < 3)
				return "! Not enough arguments, "+cmd+":id,update,expression";
			var result = ValTools.processExpression(cmds[2], this);
			if (Double.isNaN(result))
				return "! Unknown id(s) in the expression " + cmds[2];
			val.updateValue(result);
			return val.id()+" updated to " + result;
		}
		return "! No such subcommand in "+cmd+": "+cmds[0];
	}
	public String replyToFlagsCmd( String args, boolean html ){

		var cmds = args.split(",");

		if( cmds[0].equals("?")){
			String cyan = html?"":TelnetCodes.TEXT_CYAN;
			String green=html?"":TelnetCodes.TEXT_GREEN;
			String ora = html?"":TelnetCodes.TEXT_ORANGE;
			String reg=html?"":TelnetCodes.TEXT_DEFAULT;

			var join = new StringJoiner(html?"<br>":"\r\n");
			join.setEmptyValue("None yet");
			join.add(ora + "Note: both fv and flags are valid starters" + reg)
					.add(cyan + " Update" + reg)
					.add(green + "  fv:id,raise/set" + reg + " or " + green + "flags:set,id" + reg + " -> Raises the flag/Sets the bit, created if new")
					.add(green + "  fv:id,lower/clear" + reg + " or " + green + "flags:clear,id" + reg + " -> Lowers the flag/Clears the bit, created if new")
					.add(green + "  fv:id,toggle" + reg + " -> Toggles the flag/bit, not created if new")
					.add(green + "  fv:id,update,state" + reg + " -> Update  the state of the flag")
					.add(green + "  fv:id,match,refid" + reg + " -> The state of the flag becomes the same as the ref flag")
					.add(green + "  fv:id,negated,refid" + reg + " -> The state of the flag becomes the opposite of the ref flag");
			return join.toString();
		}

		FlagVal flag;
		if( cmds.length<2 )
			return "! Not enough arguments, at least: fv:id,cmd";

		var flagOpt = getFlagVal(cmds[1]);
		if( flagOpt.isEmpty() )
			return "! No such flag yet";

		flag=flagOpt.get();

		switch (cmds[1]) {
			case "raise", "set" -> {
				flag.setState(true);
				return "Flag raised";
			}
			case "lower", "clear" -> {
				flag.setState(false);
				return  "Flag lowered";
			}
			case "toggle" -> {
				flag.toggleState();
				return "Flag toggled";
			}
			case "update" -> {
				if (cmds.length < 3)
					return "! Not enough arguments, fv:id,update,state";
				if( flag.parseValue(cmds[2]) )
					return "Flag updated";
				return "! Failed to parse state: "+cmds[2];
			}
			case "match" -> {
				if (cmds.length < 3)
					return "! Not enough arguments, fv:id,match,targetflag";
				if (!hasFlag(cmds[2]))
					return "! No such flag: " + cmds[2];
				getFlagVal(cmds[2]).ifPresent(to -> flag.setState(to.state));
				return "Flag matched accordingly";
			}
			case "negated" -> {
				if (cmds.length < 3)
					return "Not enough arguments, fv:id,negated,targetflag";
				if (!hasFlag(cmds[2]))
					return "! No such flag: " + cmds[2];
				getFlagVal(cmds[2]).ifPresent(to -> flag.setState(!to.state));
				return "Flag negated accordingly";
			}
		}
		return "! No such subcommand in fv: "+cmds[0];
	}
	public String replyToTextsCmd( String args ){

		var cmds = args.split(",");

		// Get the TextVal if it exists
		TextVal txt;
		if( cmds.length<2 )
			return "! Not enough arguments, at least: tv:id,cmd";

		var txtOpt = getTextVal(cmds[1]);
		if( txtOpt.isEmpty() )
			return "! No such text yet";

		txt=txtOpt.get();

		// Execute the commands
		if (cmds[0].equals("update")) {
			if (cmds.length < 3)
				return "! Not enough arguments: tv:id,update,value";
			int start = args.indexOf(",update") + 8; // value can contain , so get position of start
			txt.value(args.substring(start));
			return "TextVal updated";
		}
		return "! No such subcommand in tv: "+cmds[0];
	}
	public String replyToRtvalsCmd( String args, boolean html ){

		if( args.isEmpty())
			return getRtvalsList(html,true,true,true, true);

		String[] cmds = args.split(",");

		if( cmds.length==1 ){
			switch (cmds[0]) {
				case "?" -> {
					String cyan = html?"":TelnetCodes.TEXT_CYAN;
					String green=html?"":TelnetCodes.TEXT_GREEN;
					String reg=html?"":TelnetCodes.TEXT_DEFAULT;

					var join = new StringJoiner(html?"<br>":"\r\n");
					join.add(cyan + " Interact with XML" + reg)
						.add(green + "  rtvals:reload" + reg + " -> Reload all rtvals from XML")
						.add("")
						.add(cyan + " Get info" + reg)
						.add(green + "  rtvals" + reg + " -> Get a listing of all rtvals")
						.add(green + "  rtvals:groups" + reg + " -> Get a listing of all the available groups")
						.add(green + "  rtvals:group,groupid" + reg + " -> Get a listing of all rtvals belonging to the group")
						.add(green + "  rtvals:resetgroup,groupid" + reg + " -> Reset the values in the group to the defaults");
					return join.toString();
				}
				case "reload" -> {
					readFromXML(XMLfab.withRoot(settingsPath, "dcafs", "settings", "rtvals"));
					return "Reloaded rtvals";
				}
				case "groups" -> {
					String groups = String.join(html ? "<br>" : "\r\n", getGroups());
					return groups.isEmpty() ? "No groups yet" : groups;
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
	public String getNameVals( String regex){
		var join = new StringJoiner("\r\n");
		for( var val : realVals.values() ) {
			if (val.name.matches(regex))
				join.add(val.group+"_"+val.name+" : "+val.stringValue());
		}
		for( var val : integerVals.values() ) {
			if (val.name.matches(regex))
				join.add(val.group+"_"+val.name+" : "+val.stringValue());
		}
		for( var val : flagVals.values() ) {
			if (val.name.matches(regex))
				join.add(val.group+"_"+val.name+" : "+val.stringValue());
		}
		for( var val : textVals.values() ) {
			if (val.name.matches(regex))
				join.add(val.group+"_"+val.name+" : "+val.stringValue());
		}
		return join.toString();
	}
	public ArrayList<AbstractVal> getGroupVals( String group ){
		var vals = new ArrayList<AbstractVal>();
		for( var val : realVals.values() ) {
			if (val.group().equalsIgnoreCase(group))
				vals.add(val);
		}
		for( var val : integerVals.values() ) {
			if (val.group().equalsIgnoreCase(group))
				vals.add(val);
		}
		for( var val : flagVals.values() ) {
			if (val.group().equalsIgnoreCase(group))
				vals.add(val);
		}
		for( var val : textVals.values() ) {
			if (val.group().equalsIgnoreCase(group))
				vals.add(val);
		}
		return vals;
	}
	/**
	 * Get a listing of all stored variables that belong to a certain group
	 * @param group The group they should belong to
	 * @param html Use html formatting or telnet
	 * @return The listing
	 */
	public String getRTValsGroupList(String group, boolean showReals, boolean showFlags, boolean showTexts, boolean showInts, boolean html) {
		String eol = html ? "<br>" : "\r\n";
		String title;
		if( group.isEmpty()){
			title = html ? "<b>Ungrouped</b>" : TelnetCodes.TEXT_CYAN + "Ungrouped" + TelnetCodes.TEXT_YELLOW;
		}else{
			title = html ? "<b>Group: " + group + "</b>" : TelnetCodes.TEXT_CYAN + "Group: " + group + TelnetCodes.TEXT_YELLOW;
		}

		StringJoiner join = new StringJoiner(eol, title + eol, "");
		join.setEmptyValue("None yet");
		ArrayList<NumericVal> nums = new ArrayList<>();
		if (showReals){
			nums.addAll(realVals.values().stream().filter(rv -> rv.group().equalsIgnoreCase(group)).toList());
		}
		if( showInts ){
			nums.addAll(integerVals.values().stream().filter(rv -> rv.group().equalsIgnoreCase(group)).toList());
		}
		if( !nums.isEmpty()){
			nums.stream()
					.sorted((nv1, nv2) -> {
						if (nv1.order() != nv2.order()) {
							return Integer.compare(nv1.order(), nv2.order());
						} else {
							return nv1.name().compareTo(nv2.name());
						}
					})
					.map(nv -> {
						if( nv instanceof RealVal)
							return "  " + nv.name() + " : "+ nv.asValueString();
						return "  " + nv.name() + " : "+ nv.asValueString();
					} ) // change it to strings
					.forEach(join::add);
		}
		if( showTexts ) {
			textVals.values().stream().filter( v -> v.group().equalsIgnoreCase(group))
					.map(v -> "  " + v.name() + " : " + (v.value().isEmpty()?"<empty>":v.value()) )
					.sorted().forEach(join::add);
		}
		if( showFlags ) {
			flagVals.values().stream().filter(fv -> fv.group().equalsIgnoreCase(group))
					.map(v -> "  " + v.name() + " : " + v) //Change it to strings
					.sorted().forEach(join::add); // Then add the sorted the strings
		}
		return join.toString();
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

		var groups = realVals.values().stream()
				.map(RealVal::group)
				.distinct()
				.filter(group -> !group.isEmpty())
				.collect(Collectors.toList());

		integerVals.values().stream()
				.map(IntegerVal::group)
				.filter(group -> !group.isEmpty() )
				.distinct()
				.forEach(groups::add);

		textVals.values().stream()
				.map( TextVal::group)
				.filter(group -> !group.isEmpty() )
				.distinct()
				.forEach(groups::add);

		flagVals.values().stream()
				.map(FlagVal::group)
				.filter(group -> !group.isEmpty() )
				.distinct()
				.forEach(groups::add);

		return groups.stream().distinct().sorted().toList();
	}
	/* ******************************** I S S U E P O O L ********************************************************** */
	/**
	 * Get a list of the id's of the active issues
	 * @return A list of the active issues id's
	 */
	public ArrayList<String> getActiveIssues(){
		return issuePool.getActives();
	}

	/**
	 * Get the IssuePool object
	 * @return The IssuePool object
	 */
	public IssuePool getIssuePool(){
		return issuePool;
	}
}