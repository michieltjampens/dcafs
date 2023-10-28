package util.gis;

import das.Commandable;
import io.telnet.TelnetCodes;
import util.data.RealVal;
import io.Writable;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.gis.Waypoint.Travel;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Waypoints implements Commandable {

    HashMap<String,Waypoint> wps = new HashMap<>();

    Path settingsPath;

    static final String XML_TAG = "waypoints";
    static final String XML_TRAVEL = "travel";
    static final String XML_CHILD_TAG = "waypoint";

    RealVal latitude;
    RealVal longitude;
    RealVal sog;

    ScheduledExecutorService scheduler;
    final static int CHECK_INTERVAL = 20;
    BlockingQueue<Datagram> dQueue;
    ScheduledFuture<?> checkTravel;
    ScheduledFuture<?> checkThread;
    OffsetDateTime lastCheck;
    Instant lastThreadCheck;
    /* *************************** C O N S T R U C T O R *********************************/
    public Waypoints(Path settingsPath, ScheduledExecutorService scheduler, RealtimeValues rtvals, BlockingQueue<Datagram> dQueue){
        this.settingsPath=settingsPath;
        this.scheduler=scheduler;
        this.dQueue=dQueue;

        readFromXML(rtvals);

        if( hasTravel() ){
            Logger.info("Waypoints with travel found, enable hourly check thread");
            checkThread = scheduler.scheduleAtFixedRate(this::checkThread,1, 1, TimeUnit.HOURS);
        }
    }
    /* ****************************** A D D I N G ****************************************/
    /**
     * Adding a waypoint to the list
     *
     * @param wp The waypoint to add
     */
    public void addWaypoint(String id, Waypoint wp ) {
        if( checkTravel!=null) {
            if( checkTravel.isDone()||checkTravel.isCancelled() )
                Logger.error("Checktravel cancelled? " + checkTravel.isCancelled() + " or done: " + checkTravel.isDone());
        }
        if(wp.hasTravelCmd() && (checkTravel==null || (checkTravel.isDone()||checkTravel.isCancelled())))
            checkTravel = scheduler.scheduleAtFixedRate(this::checkWaypoints,5, CHECK_INTERVAL, TimeUnit.SECONDS);
        Logger.info("Adding waypoint: "+id);
    	wps.put(id,wp);
    }
    public void addWaypoint( String id, double lat, double lon, double range) {
        wps.put( id, Waypoint.build(id).lat(lat).lon(lon).range(range) );
    }
    public Collection<Waypoint> items(){
        return wps.values();
    }
    public boolean readFromXML(RealtimeValues rtvals){
        return readFromXML(rtvals,true);
    }
    public boolean readFromXML( RealtimeValues rtvals, boolean clear ){
        
        if( settingsPath == null){
            Logger.warn("Reading Waypoints failed because invalid XML.");
            return false;
        }
        if( clear ){
            wps.clear();
        }
        // Get the waypoints node
        var xmlOpt = XMLtools.readXML(settingsPath);
        if( xmlOpt.isEmpty()) {
            Logger.error( "No valid settings.xml for waypoints");
            return false;
        }
        var wptsOpt = XMLtools.getFirstElementByTag( xmlOpt.get(), XML_TAG);

        if( wptsOpt.isEmpty() ) // If no node, quit
            return false;

        var wpts = wptsOpt.get();

        if( rtvals!=null) { // if RealtimeValues exist
            Logger.info("Looking for lat, lon, sog");
            var latOpt = rtvals.getRealVal( XMLtools.getStringAttribute(wpts, "latval", "") );
            var longOpt = rtvals.getRealVal( XMLtools.getStringAttribute(wpts, "lonval", "") );
            var sogOpt = rtvals.getRealVal( XMLtools.getStringAttribute(wpts, "sogval", "") );

            if( latOpt.isEmpty() || longOpt.isEmpty() || sogOpt.isEmpty() ){
                Logger.error( "No corresponding lat/lon/sog realVals found for waypoints");
                return false;
            }

            latitude = latOpt.get();
            longitude = longOpt.get();
            sog = sogOpt.get();
        }else{
            Logger.error("Couldn't process waypoints because of missing rtvals");
            return false;
        }

        Logger.info("Reading Waypoints");
        for( Element el : XMLtools.getChildElements(wpts, XML_CHILD_TAG)){ // Get the individual waypoints
        	if( el != null ){ // Check if it's valid
        		String id = XMLtools.getStringAttribute(el,"id",""); // Get the id
        	    double lat = GisTools.convertStringToDegrees(el.getAttribute("lat")); // Get the latitude
        		double lon = GisTools.convertStringToDegrees(el.getAttribute("lon")); // Get the longitude
                double range = Tools.parseDouble( el.getAttribute("range"), -999); // Range that determines inside or outside

                if( id.isEmpty()){
                    Logger.error("Waypoint without id not allowed, check settings.xml!");
                    continue;
                }

                var wp = Waypoint.build(id).lat(lat).lon(lon).range(range);// Create it

                Logger.debug("Checking for travel...");

                for( Element travelEle : XMLtools.getChildElements(el,XML_TRAVEL)){
                    if( travelEle != null ){ // Only try processing if valid
                        String idTravel = XMLtools.getStringAttribute(travelEle,"id",""); // The id of the travel
                        String dir = XMLtools.getStringAttribute(travelEle,"dir",""); // The direction (going in, going out)
                        String bearing = XMLtools.getStringAttribute(travelEle,"bearing","0 -> 360");// Which bearing used

                        wp.addTravel(idTravel,dir,bearing).ifPresent( // meaning travel parsed fin
                                    t -> {
                                        for (var cmd : XMLtools.getChildElements(travelEle, "cmd")) {
                                            t.addCmd(cmd.getTextContent());
                                        }
                                    }
                                );
                    }
                }
                addWaypoint( id, wp );// Add it
        	}else{
                Logger.error( "Invalid waypoint in the node");
            }
        }
        return true;
    }
    /**
     * Write the waypoint data to the file it was read fom originally
     * @param includeTemp Whether to also include the temp waypoints
     * @return True if successful
     */
    public boolean storeInXML( boolean includeTemp ){
        if( settingsPath==null){
            Logger.error("XML not defined yet.");
            return false;
        }
        var fab = XMLfab.withRoot(settingsPath,"dcafs","settings");
        fab.digRoot(XML_TAG);
        fab.clearChildren();

        int cnt=0;

        //Adding the clients
        for( Waypoint wp : wps.values() ){
            if( !wp.isTemp() || includeTemp){
                cnt++;
                fab.addParentToRoot(XML_CHILD_TAG)
                        .attr("lat",wp.getLat())
                        .attr("lon",wp.getLon())
                        .attr("range",wp.getRange());
                
                for( Travel tr : wp.getTravels() ){
                    fab.addChild(XML_TRAVEL)
                            .attr("dir", tr.getDirection() )
                            .attr("bearing", tr.getBearingString() )
                            .attr(XML_CHILD_TAG, wp.getName())
                            .down();
                    tr.cmds.forEach( travel -> fab.addChild("cmd",travel));
                }
            }
        }
        Logger.info("Stored "+cnt+" waypoints.");

        return fab.build();//overwrite the file
        
    }
    /* ****************************** R E M O V E ****************************************/

    /**
     * Remove the waypoint with the given name
     * @param id The name of the waypoint to remove
     * @return True if it was removed
     */
    public boolean removeWaypoint( String id ) {
    	return wps.remove(id)!=null;
    }
    /**
     * Remove all the waypoints that are temporary
     */
    public void clearTempWaypoints(){
        wps.values().removeIf( Waypoint::isTemp );
    }
    /* ******************************** I N F O ******************************************/
    /**
     * Get the amount of waypoints
     * @return The size of the list containing the waypoints
     */
    public int size() {
    	return wps.size();
    }
    /**
     * Check if a waypoint with the given name exists
     * @param id The id to look for
     * @return True if the id was found
     */
    public boolean isExisting( String id ){
        return wps.get(id) != null;
    }
    public boolean checkThread(){
        lastThreadCheck=Instant.now();
        if( checkTravel!=null) {
            if( checkTravel.isDone()||checkTravel.isCancelled() ) {
                Logger.error("Checktravel cancelled? " + checkTravel.isCancelled() + " or done: " + checkTravel.isDone());
                checkTravel = scheduler.scheduleAtFixedRate(this::checkWaypoints,5, CHECK_INTERVAL, TimeUnit.SECONDS);
                return false;
            }else{
                Logger.info("Waypoints travel checks still ok.");
            }
        }
        return true;
    }
    public boolean hasTravel(){
        return wps.values().stream().anyMatch(Waypoint::hasTravelCmd);
    }
    /**
     * Get an overview off all the available waypoints
     * @param coords Whether to add coordinates
     * @param sog Speed is used to calculate the time till the waypoint
     * @return A descriptive overview off all the current waypoints
     */
	public String getCurrentStates(boolean coords, double sog ){
        StringJoiner b = new StringJoiner("\r\n");
        b.setEmptyValue( "No waypoints yet.");
        if( !wps.isEmpty() ){
            b.add("Current Coordinates: "+latitude +" "+longitude);
        }
    	for( Waypoint w : wps.values())
    		b.add( w.toString(coords, true, sog) );
    	return b.toString();
    }
    public String getWaypointList(String newline ){
        StringJoiner b = new StringJoiner(newline);
        if( wps.isEmpty() )
            return "No waypoints yet.";
        for( Waypoint wp : wps.values() ){
            b.add( wp.getInfo(newline) );
            b.add( wp.toString(false, true, sog.value()) ).add("");
        }
        var age = TimeTools.convertPeriodtoString(Duration.between(lastCheck,OffsetDateTime.now(ZoneOffset.UTC)).getSeconds(),TimeUnit.SECONDS);

        b.add("Time since last travel check: "+age+" (check interval: "+CHECK_INTERVAL+"s)");
        if( lastThreadCheck!=null) {
            var ageThread = TimeTools.convertPeriodtoString(Duration.between(lastThreadCheck, Instant.now()).getSeconds(), TimeUnit.SECONDS);
            b.add("Time since last thread check: " + ageThread + " (check interval: 1h)");
        }else{
            b.add("No thread check done yet (check interval: 1h)");
        }
        return b.toString();
    }
    /**
     * Request the closest waypoints to the given coordinates
     * @param lat The latitude
     * @param lon The longitude
     * @return Name of the closest waypoint
     */
    public String getClosestWaypoint( double lat, double lon){
		double dist=10000000;
		String wayp="None";
		for( Waypoint wp : wps.values() ) {
			double d = wp.distanceTo( lat, lon );
			if( d < dist ) {
				dist = d;
				wayp = wp.getName();
			}
		}
		return wayp;
    }

    /**
     * Get the waypoints closest to the current coordinates
      * @return The id found
     */
    public String getNearestWaypoint( ){
        return getClosestWaypoint( latitude.value(),longitude.value());
    }
    /**
     * Check the waypoints to see if any travel occurred, if so execute the commands associated with it
     */
    private void checkWaypoints(){
        lastCheck = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            wps.values().forEach(wp -> {
                if (wp == null) {
                    Logger.error("Invalid waypoint in the list!");
                } else {
                    wp.checkIt(lastCheck, latitude.value(), longitude.value()).ifPresent(
                            travel -> travel.getCmds().forEach(cmd -> dQueue.add(Datagram.system(cmd)))
                    );
                }
            });
        }catch( Throwable trow){
            Logger.error(trow);
        }
    }

    /**
     * Get the distance to a certain waypoint in meters
     * @param id The id of the waypoint
     * @return The distance in meters
     */
    public double distanceTo(String id){
        var wp = wps.get(id);
        if( wp == null || longitude==null || latitude==null)
            return -1;
        return wp.distanceTo(latitude.value(), longitude.value());
    }
    /* ****************************************************************************************************/
    /**
     * Reply to requests made
     *
     * @param wr The writable of the origin of this request
     * @param html Determines if EOL should be <br> or crlf
     * @return Descriptive reply to the request
     */
    @Override
    public String replyToCommand(String cmd,String args, Writable wr, boolean html) {
        
        String[] cmds = args.split(",");
        String cyan = html?"": TelnetCodes.TEXT_CYAN;
        String green=html?"":TelnetCodes.TEXT_GREEN;
        String reg=html?"":TelnetCodes.TEXT_DEFAULT;

        switch (cmds[0]) {
            case "?" -> {
                StringJoiner b = new StringJoiner(html ? "<br>" : "\r\n");
                b.add(cyan + "Add/remove/alter waypoints")
                        .add(green + "wpts:add,<id,<lat>,<lon>,<range> " + reg + "-> Create a new waypoint with the name and coords lat and lon in decimal degrees")
                        .add(green + "wpts:addblank" + reg + "-> Add a blank waypoints node with a single empty waypoint node inside")
                        .add(green + "wpts:addtravel,waypoint,bearing,name" + reg + " -> Add travel to a waypoint.")
                        .add(green + "wpts:cleartemps " + reg + "-> Clear temp waypoints")
                        .add(green + "wpts:remove,<name> " + reg + "-> Remove a waypoint with a specific name")
                        .add(green + "wpts:update,id,lat,lon " + reg + "-> Update the waypoint coordinates lat and lon in decimal degrees")
                        .add(cyan + "Get waypoint info")
                        .add(green + "wpts:list " + reg + "-> Get a listing of all waypoints with travel.")
                        .add(green + "wpts:states " + reg + "-> Get a listing  of the state of each waypoint.")
                        .add(green + "wpts:exists,id " + reg + "-> Check if a waypoint with the given id exists")
                        .add(green + "wpts:nearest " + reg + "-> Get the id of the nearest waypoint")
                        .add(green + "wpts:reload " + reg + "-> Reloads the waypoints from the settings file.");
                return b.toString();
            }
            case "list" -> {
                return getWaypointList(html ? "<br>" : "\r\n");
            }
            case "exists" -> {
                return isExisting(cmds[1]) ? "Waypoint exists" : "No such waypoint";
            }
            case "cleartemps" -> {
                clearTempWaypoints();
                return "Temp waypoints cleared";
            }
            case "distanceto" -> {
                if (cmds.length == 1)
                    return "No id given, must be wpts:distanceto,id";
                var d = distanceTo(cmds[1]);
                if (d == -1)
                    return "No such waypoint";
                return "Distance to " + cmds[1] + " is " + d + "m";
            }
            case "nearest" -> {
                return "The nearest waypoint is " + getNearestWaypoint();
            }
            case "states" -> {
                if (sog == null)
                    return "Can't determine state, no sog defined";
                return getCurrentStates(false, sog.value());
            }
            case "store" -> {
                if (this.storeInXML(false)) {
                    return "Storing waypoints succesful";
                } else {
                    return "Storing waypoints failed";
                }
            }
            case "reload" -> {
                if (readFromXML(null)) {
                    return "Reloaded stored waypoints";
                } else {
                    return "Failed to reload waypoints";
                }
            }
            case "addblank" -> {
                XMLfab.withRoot(settingsPath, "dcafs", "settings")
                        .addParentToRoot(XML_TAG, "Waypoints are listed here")
                        .attr("lat", "lat_rtval")
                        .attr("lon", "lon_rtval")
                        .attr("sog", "sog_rtval")
                        .addChild(XML_CHILD_TAG)
                        .attr("lat", 1)
                        .attr("lon", 1)
                        .attr("range", 50)
                        .content("wp_id")
                        .build();
                return "Blank section added";
            }
            case "add" -> { //wpts:new,51.1253,2.2354,wrak
                if (cmds.length < 4)
                    return "Not enough parameters given";
                if (cmds.length > 5)
                    return "To many parameters given (fe. 51.1 not 51,1)";
                double lat = GisTools.convertStringToDegrees(cmds[1]);
                double lon = GisTools.convertStringToDegrees(cmds[2]);
                String id = cmds[3];
                double range = 50;
                if (cmds.length == 5) {
                    id = cmds[4];
                    range = Tools.parseDouble(cmds[3], 50);
                }
                addWaypoint(id, lat, lon, range);
                return "Added waypoint called " + cmds[3] + " lat:" + lat + "°\tlon:" + lon + "°\tRange:" + range + "m";
            }
            case "update" -> {
                if (cmds.length < 4)
                    return "Not enough parameters given wpts:update,id,lat,lon";
                var wpOpt = wps.get(cmds[1]);
                if (wpOpt != null) {
                    wpOpt.updatePosition(Tools.parseDouble(cmds[2], -999), Tools.parseDouble(cmds[3], -999));
                    return "Updated " + cmds[1];
                }
                return "No such waypoint";
            }
            case "remove" -> {
                if (removeWaypoint(cmds[1])) {
                    return "Waypoint removed";
                } else {
                    return "No waypoint found with the name.";
                }
            }
            case "addtravel" -> {
                Waypoint way = wps.get(cmds[1]);
                if (way == null) {
                    return "No such waypoint: " + cmds[1];
                }
                if (cmds.length != 6)
                    return "Incorrect amount of parameters";
                way.addTravel(cmds[5], cmds[4], cmds[2]);
                return "Added travel " + cmds[5] + " to " + cmds[1];
            }
            case "checktread" -> {
                return checkThread() ? "Thread is fine" : "Thread needed restart";
            }
            default -> {
                return "! No such subcommand in " + cmd + ": " + cmds[0];
            }
        }
    }
    public String payloadCommand( String cmd, String args, Object payload){
        return "! No such cmds in "+cmd;
    }
    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
}