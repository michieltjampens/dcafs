package util.database;

import java.util.List;
import java.util.Optional;

public interface QueryWriting {

    int addDirectInsert(String id, String table, Object... values);
    boolean insertStores(String id, String table);
    boolean addQuery(String id, String query);
    Optional<List<List<Object>>> doSelect(String id, String query);
    boolean hasDB( String id);
    boolean isValid(String id,int timeout);
    Optional<SQLDB> getDatabase(String id);
}
