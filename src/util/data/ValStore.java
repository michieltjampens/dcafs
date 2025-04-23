package util.data;

import org.tinylog.Logger;
import util.data.vals.*;
import util.database.TableInsert;
import util.math.MathUtils;

import java.util.*;
import java.util.stream.Stream;

public class ValStore {
    private final ArrayList<BaseVal> localValRefs = new ArrayList<>();
    private ArrayList<BaseVal> calVal = new ArrayList<>();
    private ArrayList<String> calOps = new ArrayList<>();
    private ArrayList<String[]> dbInsert = new ArrayList<>();
    private final ArrayList<TableInsert> tis = new ArrayList<>();

    private String delimiter = ",";
    private String id;

    /* * MAPPED * */
    private boolean map=false;
    private final HashMap<String, BaseVal> valMap = new HashMap<>();
    private boolean idleReset=false;
    private boolean valid=true;
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

    public String db(){
        if( dbInsert.isEmpty())
            return "";
        var join = new StringJoiner(";");
        for( var db : dbInsert )
            join.add(db[0]+":"+db[1]);
        return join.toString();
    }
    public void addTableInsert(TableInsert ti) {
        if( !tis.contains(ti))
            tis.add(ti);
    }

    public String id(){
        return id;
    }
    public void id(String id){
        this.id=id;
    }

    public void addVals(ArrayList<BaseVal> rtvals) {
        this.localValRefs.addAll(rtvals);
    }
    public void setIdleReset( boolean state){
        idleReset = state;
    }

    public ArrayList<String[]> dbInsertSets() {
        return dbInsert;
    }

    public void setDbInsert(ArrayList<String[]> dbInsert) {
        this.dbInsert = dbInsert;
    }

    public void setCalval(ArrayList<BaseVal> calVal, ArrayList<String> calOps) {
        this.calVal = calVal;
        this.calOps = calOps;
    }

    public boolean isInvalid(){
        return !valid;
    }

    public void invalidate() {
        valid = false;
    }

    public void shareRealtimeValues(Rtvals rtv) {
        localValRefs.replaceAll(rtv::AddIfNewAndRetrieve);
        valMap.replaceAll((k, v) -> rtv.AddIfNewAndRetrieve(v));
        calVal.replaceAll(rtv::AddIfNewAndRetrieve);
    }

    public void removeRealtimeValues(Rtvals rtv) {
        localValRefs.forEach(rtv::removeVal);
        valMap.values().forEach(rtv::removeVal);
        calVal.forEach(rtv::removeVal);
    }
    public int size(){
        return localValRefs.size();
    }

    public void addEmptyVal(){
        localValRefs.add(null);
    }

    public void addAbstractVal(BaseVal val) {
        localValRefs.add(val);
    }

    public void mapFlag(boolean state) {
        map=state;
    }

    public ArrayList<String> getCurrentValues() {
        var values = new ArrayList<String>(localValRefs.size() + 1);
        for (var val : localValRefs)
            values.add(val == null ? null : val.asString());
        return values;
    }
    /* ****************************** M A P P E D **************************************************** */
    public boolean mapped(){
        return map;
    }

    public void putAbstractVal(String key, BaseVal val) {
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
            if (items.length < localValRefs.size()) {
                Logger.warn(id + " -> Can't apply store, not enough data in the line received. -> " + line);
                return false;
            }
            dbOk = true;
            for (int a = 0; a < localValRefs.size(); a++) {
                if (localValRefs.get(a) != null && dbOk) {
                    dbOk = localValRefs.get(a).parseValue(items[a]);
                }
            }
        }
        // Now try the calvals & db insert?
        if (dbOk) {
            doCalVals();
            doDbInserts();
        }
        return dbOk;
    }

    private void doDbInserts() {
        if (tis.isEmpty())
            return;

        tis.forEach(ti -> {
            for (var db : dbInsertSets()) {
                if (ti.id().equals(db[0]))
                    ti.insertStore(db);
            }
        });
    }
    public boolean apply(ArrayList<Double> vals){
        if( map ){
            Logger.error(id+"(store) -> Map not supported in apply with integer array");
            return false;
        }else {
            if (vals.size() < localValRefs.size()) {
                Logger.warn(id + "(store) -> Can't apply store, not enough data in the line received.");
                return false;
            }
            for (int a = 0; a < localValRefs.size(); a++) {
                if (localValRefs.get(a) != null) {
                    if (localValRefs.get(a) instanceof IntegerVal iv) {
                        iv.update(vals.get(a).intValue());
                    } else if (localValRefs.get(a) instanceof RealVal rv) {
                        rv.update(vals.get(a));
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

            for (BaseVal val : Stream.of(localValRefs, valMap.values()).flatMap(Collection::stream).filter(Objects::nonNull).toList()) {
                form = form.replace(val.id(), val.asString()); // Replace the id with the value
                if (!form.contains("_"))// Means all id's were replaced, so stop looking
                    break;
            }
            for (BaseVal val : valMap.values()) {
                if (val == null)
                    continue;
                form = form.replace(val.id(), val.asString()); // Replace the id with the value
                if (!form.contains("_"))// Means all id's were replaced, so stop looking
                    break;
            }
            var result = MathUtils.noRefCalculation(form, Double.NaN, false);
            if( !Double.isNaN(result)){
                if (calVal.get(op) instanceof NumericVal nv) {
                    nv.update(result);
                } else {
                    calVal.get(op).parseValue(String.valueOf(result));
                }
            }else{
                Logger.error("Failed to calculate for "+calVal.get(op).id());
            }
        }
    }
    public void resetValues(){
        localValRefs.forEach(BaseVal::resetValue);
        valMap.values().forEach(BaseVal::resetValue);
        calVal.forEach(BaseVal::resetValue);
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
        for (var val : localValRefs) {
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
