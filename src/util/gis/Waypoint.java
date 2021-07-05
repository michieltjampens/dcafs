package util.gis;

import org.tinylog.Logger;
import util.math.MathUtils;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class Waypoint implements Comparable<Waypoint>{
	
	public enum STATE{INSIDE,OUTSIDE,ENTER,LEAVE,UNKNOWN}

	static DateTimeFormatter sqlFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	double lat;
	double lon;
	double range;
	double lastDist=-1;
	double depth;
	String name;
	int id;
	boolean inRange = false;
	boolean lastCheck = false;
	STATE state=STATE.UNKNOWN;
	boolean temp=false;
	
	double bearing=0;
	
	/* Stuff to determine enter and leave time if entered and left multiple times in succession */
	boolean active = false;
	boolean movementReady=false;
	OffsetDateTime enterTime;
	OffsetDateTime leaveTime;
	double leaveDistance=0;
		
	/* Specific movements */
	ArrayList<Travel> travels = new ArrayList<>();

	public Waypoint ( int id, String name, double lat, double lon, double depth, double range ){
		this.name=name.trim();
		this.lat=lat;
		this.lon=lon;
		this.range=range;
		this.id=id;
		this.depth=depth;
		Logger.info( "New waypoint added: "+this.name+"\t"+lat+" "+lon+" Req Range:"+range);
	}

	public Waypoint ( int id, String name, double lat, double lon ){
		this(id,name,lat,lon,0,50);
	}	
	public Waypoint ( String name, double lat, double lon, double range ){
		this( 0, name,lat,lon,0,range);
	}
	public Waypoint( String name, double lat, double lon, double range,boolean temp){
		this( 0, name,lat,lon,0, range);
		this.temp=temp;
	}	
	public Waypoint( String name ){
		this.name=name;
	}
	public STATE currentState( OffsetDateTime when, double lat, double lon ){
		lastDist = GisTools.roughDistanceBetween(lon, lat, this.lon, this.lat, 3)*1000;// From km to m				
		bearing = GisTools.calcBearing( lon, lat, this.lon, this.lat, 2 );
		
		boolean ok = lastDist < range;
		
		if( ok == lastCheck){			
			state = lastDist < range?STATE.INSIDE:STATE.OUTSIDE;
		}else{
			lastCheck = ok;
			state = ok?STATE.ENTER:STATE.LEAVE;
		}
		if( state == STATE.ENTER ){
			if( !active ){
				enterTime=when;
			}
			active = true;
		}
		if( state == STATE.LEAVE ){
			leaveTime=when;
			leaveDistance=lastDist;
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
	public Travel checkIt(OffsetDateTime when, double lat, double lon ){

		switch( currentState( when , lat, lon ) ){
			case ENTER:
			case LEAVE:
				return checkTravel();
			case INSIDE:
				return null;
			case OUTSIDE:
				String l = getLastMovement();
				if( !l.isBlank()){
					FileTools.appendToTxtFile(Path.of("logs","stationMoves.txt"), l+"\r\n");
					Logger.info( "Travel: "+l);
				}
				break;
			default:
				break;    		
		}
		return null;
	}
	public String getLastMovement(){		
		if( movementReady ){
			movementReady=false;
			return "Arrived at "+name+" on "+enterTime.format(sqlFormat) + " and left on " + leaveTime.format(sqlFormat);
		}		
		return "";
	}
	public OffsetDateTime getEnterTimeStamp(){
		return enterTime;
	}
	public OffsetDateTime getLeaveTimeStamp(){
		return leaveTime;
	}
	public boolean isTemp(){
		return temp;
	}
	public String getName(){
		return name;
	}
	public String toString(){
		return toString( false, false, 0.0 );
	}
	public String toString(boolean coord, boolean simple, double sog){
		String m = "away";
		String nm=name;

		if(state==null)
			return "Unknown state";
		
		int sec=0;
		String suffix=".";
		if( sog != 0.0 ){
			sec = (int)(lastDist/(sog*0.514444444));
			if( sec > 0 ){
				suffix=" ("+TimeTools.convertPeriodtoString(sec, TimeUnit.SECONDS)+").";				
			}
		}
		if( lastDist != -1)
			m= Tools.metersToKm(lastDist,2);
		if( coord ) {
			nm += " ["+GisTools.fromDegrToDegrMin(lat,4,"°")+";"+GisTools.fromDegrToDegrMin(lon,4,"°")+"]";
		}
		if( simple ){
			switch(state){
				case ENTER: return "Entered in range to "+nm;
				case INSIDE:return "Inside "+nm;			
				case LEAVE: return "Left "+nm+" and "+leaveDistance+" from center.";
				case OUTSIDE:return "Outside "+nm+" and "+m+" from center"+suffix;
				default: return "Unknown state of "+nm+".";		
			}
		}else{
			String mess="";
			switch(state){
				case ENTER:   mess = "Entered "; break;
				case INSIDE:  mess = "Inside ";  break;			
				case LEAVE:   mess = "Left ";    break;
				case OUTSIDE: mess = "Outside "; break;
				default:      mess = "Unknown state of "+name+".";break;		
			}			
			return mess + name+" at " +TimeTools.formatLongUTCNow()+ " and "+m+" from center, bearing "+bearing+"° "+suffix;
		}
	}
	public String simpleList(String newline){
		StringJoiner join = new StringJoiner(newline,
					this.name+" ["+GisTools.fromDegrToDegrMin(lat,4,"°")+";"+GisTools.fromDegrToDegrMin(lon,4,"°")+"]\tRange:"+range+"m","");
		if( this.travels.isEmpty() ){
			join.add(" |-> No travel linked.");
		}else{
			for( Travel tr : travels){
				join.add(" |-> "+tr.toString());
			}
		}
		return join.toString();
	}
	public double getLat(){
		return lat;
	}
	public double getLon(){
		return lon;
	}
	public double getRange(){
		return range;
	}
	public boolean samePosition( double lat, double lon){
		return this.lat == lat && this.lon == lon;
	}
	public boolean isNear() {
		return state == STATE.INSIDE || state == STATE.ENTER;
	}
	public void updatePosition( double lat, double lon ){
		this.lat=lat;
		this.lon=lon;
	}
	public void setName( String name ){
		this.name=name;
	}
	/* **********************************************************************************/
	/**
	 * Generate a WPL sentence based on this waypoint
	 * @return The generated WPL sentence
	 */
	public String generateWPL(){
		/*
		 * WPL Waypoint Location
				1      2   3      4   5   6
				|      |   |      |   |   |
			$--WPL,llll.ll,a,yyyyy.yy,a,c--c*hh
			1) Latitude
			2) N or S (North or South)
			3) Longitude
			4) E or W (East or West)
			5) Waypoint Name
			6) Checksum
		 */
		
		String latWPL = GisTools.parseDegreesToGGA(this.lat)+","+(this.lat>0?"N":"S")+",";		
		String lonWPL = GisTools.parseDegreesToGGA(this.lon)+","+(this.lon>0?"E":"W")+",";
		if( this.lon < 100)
			lonWPL = "0"+lonWPL;
		String result = "$GPWPL,"+latWPL+lonWPL+name+"*";
		result += MathUtils.getNMEAchecksum(result+"00");
		return result;
	}
	/* ******************************************************************************** **/
	/**
	 * Adds a possible travel to the waypoint
	 * 
	 * @param name The name of the travel
	 * @param dir The direction either in(or enter) or out( or leave)
	 * @param bearing Range of bearing in readable english, fe. from 100 to 150
	 */
	public Travel addTravel( String name, String dir, String bearing ){
		var travel = new Travel(name, dir, bearing);
		travels.add(travel);
		Logger.info("Added travel named "+name+" to waypoint "+this.name);
		return travel;
	}
	/**
	 * Check if any travel occurred, if so return the travel in question
	 * @return The travel that occurred
	 */
	public Travel checkTravel(){
		for( Travel t : travels ){
			if( state == t.direction && t.check.apply(bearing) ){
				Logger.info("Travel occurred "+t.name);
				return t;
			}
		}
		return null;
	}
	public List<Travel> getTravels(){
		return this.travels;
	}
	@Override
	public int compareTo(Waypoint arg0) {
		return Double.compare(lastDist, arg0.lastDist);
	}

	public double distanceTo( double lat, double lon){
		lastDist = GisTools.roughDistanceBetween(lon, lat, this.lon, this.lat, 3)*1000;// From km to m						
		return lastDist;
	}
	public double bearingTo( double lat, double lon ) {
		bearing = GisTools.calcBearing( lon, lat, this.lon, this.lat, 2 );
		return bearing;
	}
	static class Travel{
		String name="";
		String bearing="";
		Function<Double,Boolean> check;
		STATE direction;		

		ArrayList<String> cmds;

		public Travel( String name, String dir, String bearing ){
			this.name=name;
			if( dir.equals("in")||dir.equals("enter"))
				direction = STATE.ENTER;
			if( dir.equals("out")||dir.equals("leave"))
				direction = STATE.LEAVE;
			check=MathUtils.parseSingleCompareFunction(bearing);
			this.bearing=bearing;
		}
		public ArrayList<String> getCmds(){
			return cmds;
		}
		public String getDirection(){
			switch( direction ){
				case LEAVE: return "out";
				case ENTER:
				default: return "in";
			}
		}
		public String toString(){
			return (direction==STATE.ENTER?"Entering ":"Leaving")+" with bearing "+bearing+"° is called "+name;
		}
		public Travel addCmd( String cmd){
			if( cmd==null) {
				Logger.error(name+" -> Invalid cmd given");
				return this;
			}
			if( cmds==null)
				cmds=new ArrayList<>();
			cmds.add(cmd);
			return this;
		}
	}
}
