package util.data;

import io.forward.AbstractForward;
import org.tinylog.Logger;
import util.xml.XMLtools;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.BlockingQueue;

import org.w3c.dom.Element;
import worker.Datagram;

public class ValStore {
    private final ArrayList<AbstractVal> rtvals = new ArrayList<>();
    private String delimiter = ",";
    private final String[] db={"",""}; // dbid,table
    private String id;

    /* * MAPPED * */
    private boolean map=false;
    private final HashMap<String,AbstractVal> valMap = new HashMap<>();
    private boolean idleReset=false;

    private String lastKey=""; // Last key trigger db store
    public ValStore(String id){
        this.id=id;
    }
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
    public void setIdleReset( boolean state){
        this.idleReset=state;
    }
    public static Optional<ValStore> build( Element store, String id, RealtimeValues rtvals){

        if( id.isEmpty() && !store.hasAttribute("group"))  {
            Logger.error("No id/group found");
            return Optional.empty();
        }

        var valStore = new ValStore(id);
        if( valStore.reload(store,rtvals) ){
            return Optional.of( valStore );
        }
        return Optional.empty();
    }
    public static Optional<ValStore> build( Element parentNode ){
        var storeOpt = XMLtools.getFirstChildByTag(parentNode,"store");

        if( storeOpt.isEmpty())
            return Optional.empty();
        String id = parentNode.getAttribute("id");

        return ValStore.build(storeOpt.get(),id,null);
    }
    public boolean reload(Element store, RealtimeValues rtv){
        if( rtv!=null)
            removeRealtimeValues(rtv);

        rtvals.clear();

        String groupID = XMLtools.getStringAttribute(store,"group",id);
        delimiter( XMLtools.getStringAttribute(store,"delimiter",delimiter())); // delimiter
        setIdleReset( XMLtools.getBooleanAttribute(store,"idlereset",false));

        // Checking for database connection
        if ( store.hasAttribute("db")) {
            var db = store.getAttribute("db").split(":");
            if( db.length==2 ) {
                db(db[0],db[1]);
            }else{
                Logger.error( id+" -> Failed to read db tag, must contain dbids:table, multiple dbids separated with ','");
            }
        }else{
            db[0]="";
            db[1]="";
        }

        var vals = XMLtools.getChildElements(store);

        // Map
        mapFlag(XMLtools.getBooleanAttribute(store,"map",false));
        if(mapped()) { // key based
            for (var val : vals) {
                var key = XMLtools.getStringAttribute(val, "key","");
                switch (val.getTagName()) {
                    case "real" -> RealVal.build(val, groupID).ifPresent( v -> putAbstractVal(key,v));
                    case "int" -> IntegerVal.build(val, groupID).ifPresent(v -> putAbstractVal(key,v));
                    case "flag", "bool" -> FlagVal.build(val, groupID).ifPresent(v -> putAbstractVal(key,v));
                    case "text" -> TextVal.build(val, groupID).ifPresent(v -> putAbstractVal(key,v));
                    default -> {
                    }
                }
            }
            if(mapSize()!=vals.size()){
                Logger.error("Failed to create an AbstractVal for " + groupID+" while mapping.");
                return false;
            }
        }else{ // index based
            ArrayList<AbstractVal> rtvals = new ArrayList<>();
            for (var val : vals) {
                int i = XMLtools.getIntAttribute(val, "index", -1);
                if( i == -1 )
                    i = XMLtools.getIntAttribute(val, "i", -1);
                if (i != -1) {
                    while (i > rtvals.size())
                        rtvals.add(null);
                }
                var b = rtvals.size();
                switch (val.getTagName()) {
                    case "real" -> RealVal.build(val, groupID).ifPresent(rtvals::add);
                    case "int","integer" -> IntegerVal.build(val, groupID).ifPresent(rtvals::add);
                    case "flag", "bool" -> FlagVal.build(val, groupID).ifPresent(rtvals::add);
                    case "ignore" -> rtvals.add(null);
                    case "text" -> TextVal.build(val, groupID).ifPresent(rtvals::add);
                    case "macro" -> {
                        Logger.warn("Val of type macro ignored");
                        rtvals.add(null);
                    }
                    default -> {
                    }
                }
                if (rtvals.size() == b) {
                    Logger.error("Failed to create an AbstractVal for " + groupID+" of type "+val.getTagName());
                    return false;
                }
            }
            addVals(rtvals);
        }
        if( rtv!=null)
            shareRealtimeValues(rtv);
        return true;
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
        for( var set : valMap.entrySet() ){
            var val = set.getValue();
            if( val instanceof RealVal ){
                if( rtv.addRealVal((RealVal)val) == AbstractForward.RESULT.EXISTS)
                    valMap.put(set.getKey(),rtv.getRealVal(val.id()).get());
            }else if( val instanceof IntegerVal ){
                if( rtv.addIntegerVal((IntegerVal)val) == AbstractForward.RESULT.EXISTS)
                    valMap.put(set.getKey(), rtv.getIntegerVal(val.id()).get());
            }else if( val instanceof  FlagVal ){
                if( rtv.addFlagVal((FlagVal)val) == AbstractForward.RESULT.EXISTS)
                    valMap.put(set.getKey(),rtv.getFlagVal(val.id()).get());
            }else if( val instanceof TextVal ){
                if( rtv.addTextVal((TextVal)val) == AbstractForward.RESULT.EXISTS)
                    valMap.put(set.getKey(),rtv.getTextVal(val.id()).get());
            }
        }
    }
    public void removeRealtimeValues( RealtimeValues rtv){
        rtvals.forEach(rtv::removeVal);
        valMap.values().forEach(rtv::removeVal);
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
    private void mapFlag( boolean state){
        map=state;
    }
    /* ****************************** M A P P E D **************************************************** */
    public boolean mapped(){
        return map;
    }
    public void putAbstractVal(String key, AbstractVal val){
        lastKey=key;
        valMap.put(key, val);
    }
    public int mapSize(){
        return valMap.size();
    }
    /* ************************************************************************************************ */
    public boolean apply( String line, BlockingQueue<Datagram> dQueue) {
        var items = line.split(delimiter);
        boolean dbOk;
        if( map ){
            if( items.length<2) {
                Logger.error( id+" -> Not enough arguments after splitting: "+line);
                return false;
            }
            var val = valMap.get(items[0]);
            if( val != null ){
                String value = line.substring(items[0].length()+1);
                val.parseValue(value);
            }else{
                Logger.warn(id+" -> No mapping found for "+items[0]);
            }
            dbOk = items[0].equalsIgnoreCase(lastKey);
        }else {
            if (items.length < rtvals.size()) {
                Logger.warn(id + " -> Can't apply store, not enough data in the line received");
                return false;
            }
            dbOk = true;
            for (int a = 0; a < rtvals.size(); a++) {
                if (rtvals.get(a) != null && dbOk) {
                    dbOk = rtvals.get(a).parseValue(items[a]);
                }
            }
        }
        if (dbOk) {
            if (!db[0].isEmpty()) { // if a db is present
                // dbm needs to retrieve everything
                Arrays.stream(db[0].split(",")) //iterate over the databases
                        .forEach(id -> dQueue.add(Datagram.system("dbm:"+id+",store," + db[1])));
            }
        }
        return true;
    }
    public void setValueAt(int index, BigDecimal d){
        if( map)
            return;
        if( index>=rtvals.size() ) {
            Logger.error(id + " -> Tried to set index "+index+" but only "+rtvals.size()+" items");
            return;
        }
        var val = rtvals.get(index);
        if( val == null )
            return;
        if( val instanceof RealVal) {
            ((RealVal) val).value(d.doubleValue());
        }else if( val instanceof IntegerVal ){
            ((IntegerVal) val).value(d.intValue());
        }else{
            val.parseValue(d.toPlainString());
        }
    }
    public String dbTrigger(){
        if (!db[0].isEmpty())  // if a db is present
            return "dbm:"+db[0]+",store," + db[1];
        return "";
    }
    public void setValueAt(int index, String d){
        if( map)
            return;
        if( index>=rtvals.size() ) {
            Logger.error(id + " -> Tried to set index "+index+" but only "+rtvals.size()+" items");
            return;
        }
        var val = rtvals.get(index);
        if( val == null)
            return;
        val.parseValue(d);
    }
    public void resetValues(){
        for( var val : rtvals ){
            val.resetValue();
        }
        for( var val : valMap.values() ){
            val.resetValue();
        }
    }
    public void doIdle(){
        if( idleReset )
            resetValues();
    }
    public String toString(){
        var join = new StringJoiner("\r\n");
        join.add("\r\nStore splits on '"+delimiter+"'").add( "Targets:");
        int index=0;
        for( var val :rtvals ){
            if( val !=null){
                join.add("   At index "+index+" -> "+val.id());
            }
            index++;
        }
        for( var val : valMap.entrySet()){
            join.add( "   At key "+val.getKey()+" -> "+val.getValue());
        }
        return join.toString();
    }
}
