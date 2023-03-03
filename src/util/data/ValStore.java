package util.data;

import org.tinylog.Logger;
import util.xml.XMLtools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import org.w3c.dom.Element;
import worker.Datagram;

public class ValStore {
    private ArrayList<AbstractVal> rtvals = new ArrayList<>();
    private String delimiter = ",";
    private String[] db={"",""}; // dbid,table
    private String id;
    public void delimiter( String del ){
        this.delimiter=del;
    }
    public String delimiter(){
        return delimiter;
    }
    public void db( String db, String table ){
        this.db[0]=db;
        this.db[1]=table;
    }
    public String db(){
        return db[0]+":"+db[1];
    }
    public String id(){
        return id;
    }
    public void id(String id){
        this.id=id;
    }
    public void addVals( ArrayList<AbstractVal> rtvals){
        this.rtvals.addAll(rtvals);
    }
    public static Optional<ValStore> build( Element parentNode ){
        var storeOpt = XMLtools.getFirstChildByTag(parentNode,"store");

        if( storeOpt.isEmpty())
            return Optional.empty();

        String id = XMLtools.getStringAttribute(parentNode,"id","");
        if( id.isEmpty() ) {
            Logger.error("No id in parentNode");
            return Optional.empty();
        }
        var store=storeOpt.get();

        var valStore = new ValStore();
        valStore.id(id);

        String groupID = XMLtools.getStringAttribute(store,"group",id);
        valStore.delimiter( XMLtools.getStringAttribute(store,"delimiter",valStore.delimiter())); // delimiter
        // Checking for database connection
        if ( store.hasAttribute("db")) {
            var db = store.getAttribute("db").split(":");
            if( db.length==2 ) {
                valStore.db(db[0],db[1]);
            }else{
                Logger.error( id+" -> Failed to read db tag, must contain dbids:table, multiple dbids separated with ','");
            }
        }

        var vals = XMLtools.getChildElements(storeOpt.get());

        ArrayList<AbstractVal> rtvals = new ArrayList<>();
        for( var val : vals){
            int i = XMLtools.getIntAttribute(val,"index",-1);
            if( i != -1 ){
                while( i>rtvals.size() )
                    rtvals.add(null);
            }
            switch (val.getTagName()) {
                case "real" -> rtvals.add(RealVal.build(val, groupID, Double.NaN));
                case "int" -> rtvals.add(IntegerVal.build(val, groupID, Integer.MAX_VALUE));
                case "flag","bool" -> rtvals.add(FlagVal.build(val,groupID,false));
                case "ignore" -> rtvals.add(null);
                case "text" -> rtvals.add( TextVal.build(val,groupID) );
                default -> {}
            }
        }
        valStore.addVals(rtvals);
        return Optional.of( valStore );
    }
    public void shareRealtimeValues(RealtimeValues rtv){
        for( var val : rtvals ){
            if( val instanceof RealVal ){
                rtv.addRealVal((RealVal)val,false);
            }else if( val instanceof IntegerVal ){
                rtv.addIntegerVal((IntegerVal) val,null);
            }else if( val instanceof  FlagVal ){
                rtv.addFlagVal((FlagVal)val,null);
            }else if( val instanceof TextVal ){
                rtv.addTextVal((TextVal)val,null);
            }
        }
    }
    public void removeRealtimeValues( RealtimeValues rtv){
        rtvals.forEach(rtv::removeVal);
    }

    public boolean apply( String line, BlockingQueue<Datagram> dQueue){
        var items = line.split(delimiter);
        if( items.length<rtvals.size()) {
            Logger.warn(id+" -> Can't apply store, not enough data in the line received");
            return false;
        }
        boolean parsed=true;
        for( int a=0;a<rtvals.size();a++){
            if( rtvals.get(a)!=null && parsed) {
                parsed = rtvals.get(a).parseValue(items[a]);
            }
        }
        if(parsed) {
            if (!db[0].isEmpty()) { // if a db is present
                // dbm needs to retrieve everything
                Arrays.stream(db[0].split(","))
                        .forEach(id -> dQueue.add(Datagram.system("dbm:store," + id + "," + db[1])));
            }
        }
        return true;
    }
}
