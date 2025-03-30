package util.gis;

public class Coordinate {
    private double latitude;
    private double longitude;

    public Coordinate(){
    }
    public static Coordinate at( double longitude,double latitude){
        var c = new Coordinate();
        c.lat(latitude).lon(longitude);
        return c;
    }
    public Coordinate lat( double latitude){
        this.latitude=latitude;
        return this;
    }
    public Coordinate lon( double longitude){
        this.longitude=longitude;
        return this;
    }
    public double lat(){
        return latitude;
    }
    public double lon(){
        return longitude;
    }
    public double roughDistanceTo( double lon, double lat ){
        return GisTools.roughDistanceBetween(longitude,latitude,lon,lat,3);
    }
    public double bearingTo( double lon, double lat, int decimals ){
        return GisTools.calcBearing(longitude,latitude,lon,lat,decimals);
    }
    public double bearingTo( Coordinate to, int decimals ){
        return GisTools.calcBearing(longitude,latitude,to.lon(),to.lat(),decimals);
    }
    public String getDegrMin( int decimals, String degreesSymbol, String delimiter ){
        return GisTools.fromDegrToDegrMin(latitude,decimals,degreesSymbol) +delimiter+ GisTools.fromDegrToDegrMin(latitude,decimals,degreesSymbol);
    }
    public String toString(){
        return latitude+ " " + longitude;
    }
}
