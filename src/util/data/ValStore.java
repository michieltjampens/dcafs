package util.data;

import io.forward.AbstractForward;
import org.tinylog.Logger;
import util.math.MathUtils;
import util.xml.XMLtools;
import java.math.BigDecimal;
import java.util.*;
import org.w3c.dom.Element;

public class ValStore {
    private final ArrayList<AbstractVal> rtvals = new ArrayList<>();
    private final ArrayList<AbstractVal> calVal = new ArrayList<>();
    private final ArrayList<String> calOps = new ArrayList<>();
    private String delimiter = ",";
    private String dbids; // dbid,table
    private String dbtable;
    private String id;

    /* * MAPPED * */
    private boolean map=false;
    private final HashMap<String,AbstractVal> valMap = new HashMap<>();
    private boolean idleReset=false;
    private boolean valid=true;
    private String lastKey=""; // Last key trigger db store
    public ValStore(String id){
        this.id=id;
    }
    public ValStore(String id, Element ele, RealtimeValues rtvals){
        this.id=id;
        reload(ele,rtvals);
    }
    public void delimiter( String del ){
        this.delimiter=del;
    }
    public String delimiter(){
        return delimiter;
    }
    public void db( String db, String table ){
        Logger.info( id+" (Store) -> DB set to "+db+"->"+table);
        dbids=db;
        dbtable=table;
    }
    public String db(){
        return dbids+":"+dbtable;
    }
    public String dbTable(){ return dbtable; }
    public String dbIds(){ return dbids; }
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
        Element storeNode;
        if( parentNode.getTagName().equals("store")){ // If already in the node
            storeNode=parentNode;
        }else{ // If not
            var storeOpt = XMLtools.getFirstChildByTag(parentNode,"store");
            if( storeOpt.isEmpty())
                return Optional.empty();
            storeNode=storeOpt.get();
        }
        String id = storeNode.getAttribute("id");

        return ValStore.build(storeNode,id,null);
    }
    public boolean reload(Element store, RealtimeValues rtv){
        if( rtv!=null)
            removeRealtimeValues(rtv);

        rtvals.clear();
        calVal.clear();

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
            Logger.info( id + " -> No database referenced.");
            dbids="";
            dbtable="";
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
            calOps.clear();

            for (var val : vals) {
                String o = XMLtools.getStringAttribute(val, "o", "");
                if( !o.isEmpty()) { // Skip o's
                    calOps.add(o);
                    switch (val.getTagName()) {
                        case "real" -> RealVal.build(val, groupID).ifPresent(calVal::add);
                        case "int", "integer" -> IntegerVal.build(val, groupID).ifPresent(calVal::add);
                        default -> Logger.error("Can't do calculation on any other than real and int for now");
                    }
                    continue;
                }

                // Find the index wanted
                int i = XMLtools.getIntAttribute(val, "index", -1);
                if( i == -1 )
                    i = XMLtools.getIntAttribute(val, "i", -1);
                if (i != -1) {
                    while (i >= rtvals.size()) // Make sure the arraylist has at least the same amount
                        rtvals.add(null);
                }

                if( i==-1){
                    var grid = val.getAttribute("group");
                    if(grid.isEmpty())
                        grid=groupID;
                    Logger.error("No valid index given for "+grid+"_"+val.getTextContent());
                    valid=false;
                    return false;
                }
                final int pos=i; // need a final to use in lambda's
                switch (val.getTagName()) {
                    case "real" -> RealVal.build(val, groupID).ifPresent(x->rtvals.set(pos,x));
                    case "int","integer" -> IntegerVal.build(val, groupID).ifPresent(x->rtvals.set(pos,x));
                    case "flag", "bool" -> FlagVal.build(val, groupID).ifPresent(x->rtvals.set(pos,x));
                    case "ignore" -> rtvals.add(null);
                    case "text" -> TextVal.build(val, groupID).ifPresent(x->rtvals.set(pos,x));
                    case "macro" -> {
                        Logger.warn("Val of type macro ignored");
                        rtvals.add(null);
                    }
                    default -> {
                    }
                }
                if (rtvals.get(pos)==null) {
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
    public boolean isInvalid(){
        return !valid;
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
        for( int index=0;index<calVal.size();index++){
            var val = calVal.get(index);
            if( val instanceof RealVal ){
                if( rtv.addRealVal((RealVal)val) == AbstractForward.RESULT.EXISTS)
                    calVal.set(index, rtv.getRealVal(val.id()).get());
            }else if( val instanceof IntegerVal ){
                if( rtv.addIntegerVal((IntegerVal)val) == AbstractForward.RESULT.EXISTS)
                    calVal.set(index, rtv.getIntegerVal(val.id()).get());
            }
        }
    }
    public void removeRealtimeValues( RealtimeValues rtv){
        rtvals.forEach(rtv::removeVal);
        valMap.values().forEach(rtv::removeVal);
        calVal.forEach(rtv::removeVal);
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
    private void mapFlag( boolean state){
        map=state;
    }
    public ArrayList<AbstractVal> getAllVals(){
        return rtvals;
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
    public boolean apply(String line){
        var items = line.split(delimiter);
        boolean dbOk; // Ok to apply db write
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
                Logger.warn(id + " -> Can't apply store, not enough data in the line received.");
                return false;
            }
            dbOk = true;
            for (int a = 0; a < rtvals.size(); a++) {
                if (rtvals.get(a) != null && dbOk) {
                    dbOk = rtvals.get(a).parseValue(items[a]);
                }
            }
        }
        // Now try the calvals?
        doCalVals();
        return dbOk;
    }
    public boolean apply(ArrayList<Double> vals){
        if( map ){
            Logger.error(id+"(store) -> Map not supported in apply with integer array");
            return false;
        }else {
            if (vals.size() < rtvals.size()) {
                Logger.warn(id + "(store) -> Can't apply store, not enough data in the line received.");
                return false;
            }
            for (int a = 0; a < rtvals.size(); a++) {
                if (rtvals.get(a) != null ) {
                    if( rtvals.get(a) instanceof IntegerVal iv ){
                        iv.value(vals.get(a).intValue());
                    }else if( rtvals.get(a) instanceof RealVal rv ){
                        rv.value(vals.get(a));
                    }else{
                        Logger.error(id+" (store) -> Tried to apply an integer to a flag or text");
                        return false;
                    }
                }
            }
        }
        // Now try the calvals?
        doCalVals();
        return true;
    }
    public void doCalVals(){
        for( int op=0;op<calOps.size();op++ ){
            var form = calOps.get(op);
            for (AbstractVal val : rtvals) {
                if (val == null)
                    continue;
                form = form.replace(val.id(), val.stringValue()); // Replace the id with the value
                if (!form.contains("_"))// Means all id's were replaced, so stop looking
                    break;
            }
            var result = MathUtils.simpleCalculation(form,Double.NaN,false);
            if( !Double.isNaN(result)){
                calVal.get(op).parseValue(String.valueOf(result));
            }else{
                Logger.error("Failed to calculate for "+calVal.get(op).id());
            }
        }
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
