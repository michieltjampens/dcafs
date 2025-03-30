package util.gis;

import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

public class Waypoint implements Comparable<Waypoint>{
	
	public enum STATE{INSIDE,OUTSIDE,ENTER,LEAVE,UNKNOWN}

	static DateTimeFormatter sqlFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private final Coordinate coord=new Coordinate();
	private double range;
	private double lastDist=-1;

	private STATE state=STATE.UNKNOWN;
	private final boolean temp=false;
	private final String id;
	private double bearing=0;

	/* Stuff to determine enter and leave time if entered and left multiple times in succession */
	private boolean active = false;
	private boolean movementReady=false;
	private OffsetDateTime enterTime;
	private OffsetDateTime leaveTime;
	private double leaveDistance=0;
		
	/* Specific movements */
	private final ArrayList<Travel> travels = new ArrayList<>();

	public Waypoint( String id ){
		this.id=id;
	}

	public static Waypoint build( String id){
		return new Waypoint(id);
	}
	public Waypoint lat( double lat){
		coord.lat(lat);
		return this;
	}
	public Waypoint lon( double lon){
		coord.lon(lon);
		return this;
	}
	public Waypoint range( double range){
		this.range=range;
		return this;
	}
	public Waypoint coord( double lat, double lon){
		coord.lon(lon).lat(lat);
		return this;
	}
	public boolean hasTravelCmd(){
		for( var travel: travels )
			if( !travel.cmds.isEmpty())
				return true;
		return false;
	}
	public static Optional<Waypoint> readFromXML( XMLdigger wpDig ){
		String id = wpDig.attr("id",""); // Get the id
		double lat = GisTools.convertStringToDegrees(wpDig.attr("lat","")); // Get the latitude
		double lon = GisTools.convertStringToDegrees(wpDig.attr("lon","")); // Get the longitude
		double range = wpDig.attr("range", -999.0); // Range that determines inside or outside

		if( id.isEmpty()){
			Logger.error("Waypoint without id not allowed, check settings.xml!");
			return Optional.empty();
		}

		var wp = Waypoint.build(id).lat(lat).lon(lon).range(range);// Create it

		Logger.debug("Checking for travel...");

		for( var travelDig : wpDig.digOut("travel")){
			String travelId = travelDig.attr("id",""); // The id of the travel
			String dir = travelDig.attr("dir",""); // The direction (going in, going out)
			String bearing = travelDig.attr("bearing","0 -> 360");// Which bearing used

			wp.addTravel(travelId,dir,bearing).ifPresent( // meaning travel parsed fine
					t -> {
						for (var cmd : travelDig.digDown("cmd").currentSubs()) {
							t.addCmd(cmd.getTextContent());
						}
					}
			);
		}
		return Optional.of(wp);
	}
	public void storeInXml( XMLfab fab ){
		fab.addParentToRoot("waypoint")
				.attr("id",id)
				.attr("lat", coord.lat())
				.attr("lon", coord.lon())
				.attr("range",range);

		for( Travel tr : getTravels() ){
			fab.addChild("travel")
					.attr("id", tr.id())
					.attr("dir", tr.getDirection() )
					.attr("bearing", tr.getBearingString() )
					.down();
			tr.cmds.forEach( travel -> fab.addChild("cmd",travel));
		}
	}
	public STATE currentState( OffsetDateTime when, double lat, double lon ){
		lastDist = coord.roughDistanceTo(lat,lon)*1000;// From km to m
		bearing = coord.bearingTo(lat,lon,2);

		switch (state) {
			case INSIDE -> {
				if (lastDist > range) { // Was inside but went beyond the range
					state = STATE.LEAVE;
					leaveTime = when;
					leaveDistance = lastDist;
				}
			}
			case OUTSIDE -> {
				if (lastDist < range) { // Was outside but came within the range
					state = STATE.ENTER;
					if (!active) {
						enterTime = when;
					}
					active = true;
				}
			}
			case ENTER, LEAVE, UNKNOWN -> state = lastDist < range ? STATE.INSIDE : STATE.OUTSIDE;
		}
		if( state == STATE.OUTSIDE && lastDist > 600 && active){
			active = false;
			movementReady=true;
		}
		return state;
	}
	public double getLastDistance( ) {
		return lastDist;
	}
	public Optional<Travel> checkIt( double lat, double lon ){
		switch (currentState(OffsetDateTime.now(ZoneOffset.UTC), lat, lon)) {
			case ENTER, LEAVE -> {
				if (getLastDistance() < 800 && getLastDistance() > 1) // Ignore abnormal movements
					return checkTravel();
			}
			case OUTSIDE -> {
				String l = getLastMovement();
				if (!l.isBlank()) {
					FileTools.appendToTxtFile(Path.of("logs", "waypointsMoves.txt"), l + "\r\n");
					Logger.info("Travel: " + l);
				}
			}
			default -> {
			}
		}
		return Optional.empty();
	}
	public String getLastMovement(){		
		if( movementReady ){
			movementReady=false;
			return "Arrived at "+id+" on "+enterTime.format(sqlFormat) + " and left on " + leaveTime.format(sqlFormat);
		}		
		return "";
	}
	public boolean isTemp(){
		return temp;
	}
	public String id(){
		return id;
	}
	public String toString(){
		return toString( false, false, 0.0 );
	}
	public String toString(boolean coordinate, boolean simple, double sog){
		String m = "away";
		String nm=id;

		if(state==null)
			return "Unknown state";

		int sec=0;
		String suffix=".";
		if( sog != 0.0 ){
			sec = (int)(lastDist/(sog*0.514444444));
			if( sec > 0 ){
				suffix = " (" + TimeTools.convertPeriodToString(sec, TimeUnit.SECONDS) + ").";
			}
		}
		if( lastDist != -1)
            m = Tools.metersToKm(lastDist, 2);
		if( coordinate ) {
			nm += " ["+coord.getDegrMin(4,"°",";")+"]";
		}
		if( simple ){
			return switch (state) {
				case ENTER -> "Entered in range to " + nm;
				case INSIDE -> "Inside " + nm;
				case LEAVE -> "Left " + nm + " and " + leaveDistance + " from center.";
				case OUTSIDE -> "Outside " + nm + " and " + m + " from center" + suffix;
				default -> "Unknown state of " + nm + ".";
			};
		}else{
			String mess = switch (state) {
				case ENTER -> "Entered ";
				case INSIDE -> "Inside ";
				case LEAVE -> "Left ";
				case OUTSIDE -> "Outside ";
				default -> "Unknown state of " + id + ".";
			};
			return mess + id+" at " +TimeTools.formatLongUTCNow()+ " and "+m+" from center, bearing "+bearing+"° "+suffix;
		}
	}
	public String getInfo(String newline){
		String prefix;
		if( !newline.startsWith("<")) {
			prefix = TelnetCodes.TEXT_GREEN + id + TelnetCodes.TEXT_YELLOW;
		}else{
			prefix = id;
		}
		prefix += " ["+coord.getDegrMin(4,"°",";")+"]\tRange:"+range+"m";

		StringJoiner join = new StringJoiner(newline,
					prefix,"");
		join.add("");
		if( this.travels.isEmpty() ){
			join.add(" |-> No travel linked.");
		}else{
			for( Travel tr : travels){
				join.add(" |-> "+tr.toString());
			}
		}
		return join.toString();
	}
	public double range(){
		return range;
	}
	public void updatePosition( double lat, double lon ){
		coord.lat(lat).lon(lon);
	}

	/* ******************************************************************************** **/
	/**
	 * Adds a travel to the waypoint
	 * 
	 * @param id The name of the travel
	 * @param dir The direction either in(or enter) or out( or leave)
	 * @param bearing Range of bearing in readable english, fe. from 100 to 150
	 * @return An optional Travel, which is empty if the bearing parsing failed
	 */
	public Optional<Travel> addTravel( String id, String dir, String bearing ){
		var travel = new Travel(id, dir, bearing);
		if( travel.isValid()) {
			travels.add(travel);
			Logger.info(this.id+" (wp)-> Added travel with id "+id);
			return Optional.of(travel);
		}else{
			Logger.error( this.id+" (wp)-> Failed to add travel("+id+"), parsing bearing failed: "+bearing);
			return Optional.empty();
		}
	}
	/**
	 * Check if any travel occurred, if so return the travel in question
	 * @return The travel that occurred
	 */
	public Optional<Travel> checkTravel(){
		for( Travel t : travels ){
			if( t.check(state,bearing) ){
				Logger.info(id+" (wp)-> Travel occurred "+t.id);
				return Optional.of(t);
			}
		}
		return Optional.empty();
	}
	public List<Travel> getTravels(){
		return this.travels;
	}
	@Override
	public int compareTo(Waypoint wp) {
		return Double.compare(lastDist, wp.lastDist);
	}

	public double distanceTo( double lat, double lon){
		lastDist = coord.roughDistanceTo( lon,lat )*1000;// From km to m
		return lastDist;
	}
	public class Travel{
		String id;
		double maxBearing=360.0,minBearing=0.0;
		STATE direction;

		ArrayList<String> cmds=new ArrayList<>();
		boolean valid = true;

		public Travel( String id, String dir, String bearing ){
			this.id=id;
			direction = switch( dir ){
				case "in","enter" -> STATE.ENTER;
				case "out","leave" -> STATE.LEAVE;
				default -> STATE.UNKNOWN;
			};
			if( !bearing.contains("->")){
				Logger.error(id+" (wp)-> Incorrect bearing for "+id+" must be of format 0->360 or 0 -> 360");
				valid=false;
			}else{
				var br = bearing.replace(" ","").split("->");
				if( br.length!=2){
					Logger.error(id+" (wp)-> Incorrect bearing for "+id+" must be of format 0->360 or 0 -> 360");
					valid=false;
				}else {
					minBearing = NumberUtils.createDouble(br[0]);
					maxBearing = NumberUtils.createDouble(br[1]);
				}
			}
		}
		public String id(){ return id; }
		public boolean isValid(){
			return valid;
		}
		public boolean check(STATE state, double curBearing){
			if( !valid )
				return false;
			return state == direction && Double.compare(curBearing,minBearing) >=0 && Double.compare(curBearing,maxBearing)<=0;
		}
		public ArrayList<String> getCmds(){
			return cmds;
		}
		public String getDirection(){
			return direction==STATE.LEAVE?"out":"in";
		}
		public String toString(){
			String info = id +" = "+(direction==STATE.ENTER?" coming closer than "+range+"m":" going further away than "+range+"m");
			return info+" with a bearing from "+minBearing+ " to "+maxBearing+"°";
		}
		public String getBearingString(){
			return (minBearing+" -> "+maxBearing).replace(".0","");
		}
		public void addCmd(String cmd ){
			if( cmd==null) {
				Logger.error(id+" -> Invalid cmd given");
				return;
			}
			if( cmds==null)
				cmds=new ArrayList<>();
			cmds.add(cmd);
		}
	}
}
