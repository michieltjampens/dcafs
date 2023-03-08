package util.data;

import io.forward.AbstractForward;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import util.xml.XMLtools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;

import org.w3c.dom.Element;
import worker.Datagram;

public class ValStore {
    private final ArrayList<AbstractVal> rtvals = new ArrayList<>();
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
            var b = rtvals.size();
            switch (val.getTagName()) {
                case "real" -> RealVal.build(val, groupID, Double.NaN).ifPresent(rtvals::add);
                case "int" -> IntegerVal.build(val, groupID, Integer.MAX_VALUE).ifPresent(rtvals::add);
                case "flag","bool" -> FlagVal.build(val,groupID,false).ifPresent(rtvals::add);
                case "ignore" -> rtvals.add(null);
                case "text" -> TextVal.build(val,groupID,"").ifPresent(rtvals::add);
                default -> {}
            }
            if( rtvals.size()==b) {
                Logger.error("Failed to create an AbstractVal for " + groupID);
                return Optional.empty();
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
                if( rtv.addRealVal((RealVal)val) == AbstractForward.RESULT.EXISTS)
                    rtvals.set(index, rtv.getRealVal(val.id()).get());
            }else if( val instanceof IntegerVal ){
                if( rtv.addIntegerVal((IntegerVal)val) == AbstractForward.RESULT.EXISTS)
                    rtvals.set(index, rtv.getIntegerVal(val.id()).get());
            }else if( val instanceof  FlagVal ){
                if( rtv.addFlagVal((FlagVal)val) == AbstractForward.RESULT.EXISTS)
                    rtvals.set(index, rtv.getFlagVal(val.id()).get());
            }else if( val instanceof TextVal ){
                if( rtv.addTextVal((TextVal)val) == AbstractForward.RESULT.EXISTS)
                    rtvals.set(index, rtv.getTextVal(val.id()).get());
            }
        }
    }
    public void removeRealtimeValues( RealtimeValues rtv){
        rtvals.forEach(rtv::removeVal);
    }
    public int size(){
        return rtvals.size();
    }
    public void addEmptyVal(){
        rtvals.add(null);
    }
    public void addAbstractVal( AbstractVal val){
        rtvals.add(val);
    }
    public void setAbstractVal( int index, AbstractVal val ){
        if( val==null)
            return;
        if( index > rtvals.size()) {
            Logger.warn("Tried to set a val index "+index+" but rtvals to small -> "+val.id());
            return;
        }
        rtvals.set(index,val);
    }
    public String getValueAt(int index ){
        if( index > rtvals.size() || rtvals.get(index)==null)
            return "";
        return rtvals.get(index).stringValue();
    }
    public boolean isEmptyAt(int index ){
        if( rtvals.size()<=index)
            return true;
        return rtvals.get(index)==null;
    }
    public Integer getIntValueAt(int index ){
        if( index > rtvals.size() || rtvals.get(index)==null)
            return null;
        var v = rtvals.get(index);
        if( v instanceof IntegerVal ){
            return ((IntegerVal) v).intValue();
        }
        return null;
    }
    public Double getRealValueAt(int index ){
        if( index > rtvals.size() || rtvals.get(index)==null)
            return null;
        var v = rtvals.get(index);
        if( v instanceof RealVal){
            return ((RealVal) v).value();
        }
        return null;
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
                join.add("At index "+index+" -> "+val);
            }
            index++;
        }
        return join.toString();
    }
}
