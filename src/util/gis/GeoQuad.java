package util.gis;

import org.tinylog.Logger;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.util.ArrayList;
import java.util.Optional;

public class GeoQuad {
    Coordinate c1,c2,c3,c4;

    double minLat,minLon;
    double maxLat,maxLon;

    double c1MinBearing,c1MaxBearing;
    double c3MinBearing,c3MaxBearing;

    String id;

    ArrayList<String> enterCmds = new ArrayList<>();
    ArrayList<String> leaveCmds = new ArrayList<>();

    private boolean inside=false;

    public GeoQuad( String id ){
        this.id=id;
    }
    public static GeoQuad build( String id ){
        return new GeoQuad(id);
    }
    public GeoQuad addCorners( Coordinate c1,Coordinate c2,Coordinate c3,Coordinate c4){
        this.c1=c1;
        this.c2=c2;
        this.c3=c3;
        this.c4=c4;

        doCalculations();
        return this;
    }

    public static Optional<GeoQuad> readFromXML(XMLdigger gqDig) {
        var id = gqDig.attr("id","");
        if( id.isEmpty()){
            Logger.error("GeoQuad without id not allowed, check settings.xml!");
            return Optional.empty();
        }
        var corners = gqDig.digOut("corner");
        if( corners.size()!=4 ){
            Logger.error("GeoQuad "+id+" needs four corners, check settings.xml!");
            return Optional.empty();
        }

        var c1 = Coordinate.at( corners.get(0).attr("lon",0.0),corners.get(0).attr("lat",0.0));
        var c2 = Coordinate.at( corners.get(1).attr("lon",0.0),corners.get(1).attr("lat",0.0));
        var c3 = Coordinate.at( corners.get(2).attr("lon",0.0),corners.get(2).attr("lat",0.0));
        var c4 = Coordinate.at( corners.get(3).attr("lon",0.0),corners.get(3).attr("lat",0.0));

        var quad = GeoQuad.build(id).addCorners(c1,c2,c3,c4);

        if( gqDig.hasPeek("onleave") ){
            for (var leaveCmd : gqDig.digOut("onleave"))
                quad.addLeaveCmd(leaveCmd.value(""));
        }
        if( gqDig.hasPeek("onenter") ) {
            for (var enterCmd : gqDig.digOut("onenter"))
                quad.addEnterCmd(enterCmd.value(""));
        }
        return Optional.of(quad);
    }
    public void storeInXml(XMLfab fab){
        fab.addParentToRoot("geoquad").attr("id",id);
        fab.addChild("corner").attr("lat",c1.lat()).attr("lon",c1.lon());
        fab.addChild("corner").attr("lat",c2.lat()).attr("lon",c2.lon());
        fab.addChild("corner").attr("lat",c3.lat()).attr("lon",c3.lon());
        fab.addChild("corner").attr("lat",c4.lat()).attr("lon",c4.lon());

        if( !enterCmds.isEmpty() ){
            fab.addChild("onenter").down();
            enterCmds.forEach( cmd -> fab.addChild("cmd",cmd));
            fab.up();
        }
        if( !leaveCmds.isEmpty() ){
            fab.addChild("onleave").down();
            leaveCmds.forEach( cmd -> fab.addChild("cmd",cmd));
            fab.up();
        }
    }
    public void id(String id){
        this.id=id;
    }
    public String id(){
        return id;
    }
    public boolean hasCmds(){
        return !enterCmds.isEmpty() || !leaveCmds.isEmpty();
    }
    public void addLeaveCmd( String cmd ){
        if( !cmd.isEmpty())
            leaveCmds.add(cmd);
    }
    public void addEnterCmd( String cmd){
        if( !cmd.isEmpty())
            enterCmds.add(cmd);
    }
    /**
     * Does all the calculations so they don't need to be done later.
     * TODO Take the equator in account?
     */
    private void doCalculations(){

        /* Minimum and maximum longitude and latitude */
        // Minimum latitude
        minLat=Math.min(c1.lat(),c2.lat());
        var ml = Math.min(c3.lat(),c4.lat());
        minLat = Math.min(minLat,ml);

        // Minimum longitude
        minLon=Math.min(c1.lon(),c2.lon());
        ml = Math.min(c3.lon(),c4.lon());
        minLon = Math.min(minLon,ml);

        // Maximum latitude
        maxLat=Math.max(c1.lat(),c2.lat());
        ml = Math.max(c3.lat(),c4.lat());
        maxLat = Math.max(maxLat,ml);

        // Maximum longitude
        maxLon=Math.max(c1.lon(),c2.lon());
        ml = Math.max(c3.lon(),c4.lon());
        maxLon = Math.max(maxLon,ml);

        /* Bearings */
        /* Calculate bearing of two opposing corners to figure out the range  the points need to be in */
        c1MinBearing = c1.bearingTo(c4,3);
        c1MaxBearing = c1.bearingTo(c2,3);

        c3MinBearing = c3.bearingTo(c4,3);
        c3MaxBearing = c3.bearingTo(c2,3);
    }

    /**
     * Determine if the given coordinate is within the geoquad or not.
     * @param p The coordinate to compare
     * @return True if inside
     */
    public boolean isInside( Coordinate p ){
        if( isOutOfBounds(p.lon(),minLon,maxLon) )
            return false;
        if( isOutOfBounds(p.lat(),minLat,maxLat) )
            return false;
        if( isOutOfBounds( p.bearingTo(c1,2),c1MinBearing,c1MaxBearing) )
            return false;
        return !isOutOfBounds( p.bearingTo(c3,2),c3MinBearing,c3MaxBearing);
    }
    private boolean isOutOfBounds( double p, double min, double max){
        return p>max || p<min;
    }
    public String toString(){
        return "GeoQuad("+id+"):"+c1 + ";" + c2 + ";" + c3 + ";" + c4;
    }

    public ArrayList<String> checkIt(double lat, double lon) {
        boolean nowInside = isInside( Coordinate.at(lon,lat));
        if( inside == nowInside ) // Stayed the same
            return new ArrayList<>();
        if( inside ) { // was inside is now outside
            inside=false;
            return leaveCmds;
        }
        // Otherwise Was outside and now inside
        inside=true;
        return enterCmds;
    }
}
