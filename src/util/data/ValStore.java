package util.data;

import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathUtils;
import util.xml.XMLdigger;
import util.xml.XMLtools;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;

public class ValStore {
    private final ArrayList<AbstractVal> rtvals = new ArrayList<>();
    private final ArrayList<AbstractVal> calVal = new ArrayList<>();
    private final ArrayList<String> calOps = new ArrayList<>();
    private final ArrayList<String[]> dbInsert = new ArrayList<>();
    private String delimiter = ",";
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
        if( dbInsert.isEmpty()) {
            dbInsert.add(new String[]{db, table});
        }else{
            dbInsert.set(0,new String[]{db, table});
        }
    }
    public String db(){
        if( dbInsert.isEmpty())
            return "";
        var join = new StringJoiner(";");
        for( var db : dbInsert )
            join.add(db[0]+":"+db[1]);
        return join.toString();
    }
    public ArrayList<String[]> dbInsertSets(){
        return dbInsert;
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
        idleReset = state;
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

        var dig = XMLdigger.goIn(store);

        var groupID = dig.attr("group",id);
        delimiter( dig.attr("delimiter",delimiter()) );
        setIdleReset( dig.attr("idlereset",false) );

        // Checking for database connection
        if ( store.hasAttribute("db")) {
            var db = dig.attr("db","").split(";");
            for( var dbi : db ){
                var split = dbi.split(":");
                if( split[0].contains(",")&&split[1].contains(",")) {
                    Logger.error( id+"(store) -> Can't have multiple id's and tables defined.");
                }else if( split[0].contains(",")){ // multiple id's but same tables
                    for( var id : split[0].split(","))
                        dbInsert.add( new String[]{id,split[1]});
                }else if( split[1].contains(",") ){ // multiple tables but same id
                    for( var table : split[1].split(","))
                        dbInsert.add( new String[]{split[0],table});
                }else{ // one of each
                    dbInsert.add( split );
                }
            }
        }else{
            Logger.info( id + " -> No database referenced.");
            dbInsert.clear();
        }

        // Map
        mapFlag( dig.attr("map",false) );
        if( mapped() ) { // key based
            var vals = dig.currentSubs();
            for (var val : dig.digOut("*")) {
                if (checkForCalVal(val, groupID))
                    continue;

                var key = val.attr("key", "");
                switch (val.tagName("")) {
                    case "real" -> RealVal.build(val.currentTrusted(), groupID).ifPresent(v -> putAbstractVal(key, v));
                    case "int" ->
                            IntegerVal.build(val.currentTrusted(), groupID).ifPresent(v -> putAbstractVal(key, v));
                    case "flag", "bool" ->
                            FlagVal.build(val.currentTrusted(), groupID).ifPresent(v -> putAbstractVal(key, v));
                    case "text" -> TextVal.build(val.currentTrusted(), groupID).ifPresent(v -> putAbstractVal(key, v));
                    default -> {
                    }
                }
            }
            if (mapSize() + calVal.size() != (vals.size())) {
                Logger.error("Failed to create an AbstractVal for " + groupID+" while mapping.");
                return false;
            }
        }else{ // index based
            ArrayList<AbstractVal> rtvals = new ArrayList<>();
            calOps.clear();

            for (var val : dig.digOut("*")) {
                if (checkForCalVal(val, groupID))
                    continue;

                // Find the index wanted
                int i = val.attr("index",-1);
                if( i == -1 )
                    i = val.attr("i",-1);
                if (i != -1) {
                    while (i >= rtvals.size()) // Make sure the arraylist has at least the same amount
                        rtvals.add(null);
                }

                if( i==-1){
                    var grid = val.attr("group",groupID);
                    Logger.error("No valid index given for "+grid+"_"+val.value(""));
                    valid=false;
                    return false;
                }
                final int pos=i; // need a final to use in lambda's
                if( rtvals.get(i)!=null){
                    Logger.warn(id+"(store) -> Already using index "+i+" overwriting previous content!");
                }
                switch (val.tagName("")) {
                    case "real" -> RealVal.build(val.currentTrusted(), groupID).ifPresent(x->rtvals.set(pos,x));
                    case "int","integer" -> IntegerVal.build(val.currentTrusted(), groupID).ifPresent(x->rtvals.set(pos,x));
                    case "flag", "bool" -> FlagVal.build(val.currentTrusted(), groupID).ifPresent(x->rtvals.set(pos,x));
                    case "ignore" -> rtvals.add(null);
                    case "text" -> TextVal.build(val.currentTrusted(), groupID).ifPresent(x->rtvals.set(pos,x));
                    case "macro" -> {
                        Logger.warn("Val of type macro ignored");
                        rtvals.add(null);
                    }
                    default -> {
                    }
                }
                if (rtvals.get(pos)==null) {
                    Logger.error("Failed to create an AbstractVal for " + groupID+" of type "+val.tagName(""));
                    return false;
                }
            }
            addVals(rtvals);
        }
        if( rtv!=null)
            shareRealtimeValues(rtv);
        return true;
    }

    private boolean checkForCalVal(XMLdigger dig, String groupID) {
        String o = dig.attr("o", "");
        if (!o.isEmpty()) { // Skip o's
            calOps.add(o);
            switch (dig.tagName("")) {
                case "real" -> RealVal.build(dig.currentTrusted(), groupID).ifPresent(calVal::add);
                case "int", "integer" -> IntegerVal.build(dig.currentTrusted(), groupID).ifPresent(calVal::add);
                default -> {
                    Logger.error("Can't do calculation on any other than real and int for now");
                    return false;
                }
            }
            return true;
        }
        return false;
    }
    public boolean isInvalid(){
        return !valid;
    }
    public void shareRealtimeValues(RealtimeValues rtv){
        rtvals.replaceAll(rtv::AddIfNewAndRetrieve);
        valMap.replaceAll((k, v) -> rtv.AddIfNewAndRetrieve(v));
        calVal.replaceAll(rtv::AddIfNewAndRetrieve);
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
                Logger.warn(id + " -> No mapping found for " + items[0] + ", skipping db insert");
                return false;
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
        if (dbOk)
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

            for (AbstractVal val : Stream.of(rtvals, valMap.values()).flatMap(Collection::stream).filter(Objects::nonNull).toList()) {
                form = form.replace(val.id(), val.stringValue()); // Replace the id with the value
                if (!form.contains("_"))// Means all id's were replaced, so stop looking
                    break;
            }
            for (AbstractVal val : valMap.values()) {
                if (val == null)
                    continue;
                form = form.replace(val.id(), val.stringValue()); // Replace the id with the value
                if (!form.contains("_"))// Means all id's were replaced, so stop looking
                    break;
            }
            var result = MathUtils.noRefCalculation(form, Double.NaN, false);
            if( !Double.isNaN(result)){
                if (calVal.get(op) instanceof NumericVal nv) {
                    nv.updateValue(result);
                } else {
                    calVal.get(op).parseValue(String.valueOf(result));
                }
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
        rtvals.forEach(AbstractVal::resetValue);
        valMap.values().forEach(AbstractVal::resetValue);
        calVal.forEach(AbstractVal::resetValue);
    }
    public void doIdle(){
        if( idleReset )
            resetValues();
    }
    public String toString(){
        var join = new StringJoiner("\r\n");
        join.add("\r\nStore splits on '"+delimiter+"'"+(db().length()>1?"":" and in db at "+db()));
        join.add( "Rtvals:");
        int index=0;
        for( var val :rtvals ){
            if( val !=null){
                join.add( "   i"+index+" -> "+val.id() );
            }
            index++;
        }
        for( var val : valMap.entrySet()){
            join.add( "   At key "+val.getKey()+" -> "+val.getValue());
        }
        return join.toString();
    }
}
