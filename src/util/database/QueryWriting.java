package util.database;

import java.util.Optional;

public interface QueryWriting {

    boolean insertStores(String id, String table);
    boolean isValid(String id,int timeout);
    Optional<SQLDB> getDatabase(String id);
}
