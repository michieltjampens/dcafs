package util.data;

import io.forward.AbstractForward;
import org.tinylog.Logger;
import util.xml.XMLtools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.StringJoiner;
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
    public static Optional<ValStore> build( Element store, String id){

        if( id.isEmpty() && !store.hasAttribute("group"))  {
            Logger.error("No id/group found");
            return Optional.empty();
        }

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

        var vals = XMLtools.getChildElements(store);

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
    public static Optional<ValStore> build( Element parentNode ){
        var storeOpt = XMLtools.getFirstChildByTag(parentNode,"store");

        if( storeOpt.isEmpty())
            return Optional.empty();
        String id = parentNode.getAttribute("id");

        return ValStore.build(storeOpt.get(),id);
    }
    public void shareRealtimeValues(RealtimeValues rtv){
        for( int index=0;index<rtvals.size();index++){
            var val = rtvals.get(index);
            if( val instanceof RealVal ){
                if( rtv.addRealVal((RealVal)val,false) == AbstractForward.RESULT.EXISTS)
                    rtvals.set(index, rtv.getRealVal(val.id()).get());
            }else if( val instanceof IntegerVal ){
                if( rtv.addIntegerVal((IntegerVal)val,null) == AbstractForward.RESULT.EXISTS)
                    rtvals.set(index, rtv.getIntegerVal(val.id()).get());
            }else if( val instanceof  FlagVal ){
                if( rtv.addFlagVal((FlagVal)val,null) == AbstractForward.RESULT.EXISTS)
                    rtvals.set(index, rtv.getFlagVal(val.id()).get());
            }else if( val instanceof TextVal ){
                if( rtv.addTextVal((TextVal)val,null) == AbstractForward.RESULT.EXISTS)
                    rtvals.set(index, rtv.getTextVal(val.id()).get());
            }
        }
    }
    public void removeRealtimeValues( RealtimeValues rtv){
        rtvals.forEach(rtv::removeVal);
    }

    public boolean apply( String line, BlockingQueue<Datagram> dQueue) {
        var items = line.split(delimiter);
        if (items.length < rtvals.size()) {
            Logger.warn(id + " -> Can't apply store, not enough data in the line received");
            return false;
        }
        boolean parsed = true;
        for (int a = 0; a < rtvals.size(); a++) {
            if (rtvals.get(a) != null && parsed) {
                parsed = rtvals.get(a).parseValue(items[a]);
            }
        }
        if (parsed) {
            if (!db[0].isEmpty()) { // if a db is present
                // dbm needs to retrieve everything
                Arrays.stream(db[0].split(","))
                        .forEach(id -> dQueue.add(Datagram.system("dbm:store," + id + "," + db[1])));
            }
        }
        return true;
    }
    public String toString(){
        var join = new StringJoiner("\r\n");
        join.add("Store splits on '"+delimiter+"'").add( "Targets:");
        int index=0;
        for( var val :rtvals ){
            if( val !=null){
                join.add("At index "+index+" -> "+val.toString());
            }
            index++;
        }
        return join.toString();
    }
}
