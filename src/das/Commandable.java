package das;

import io.Writable;

public interface Commandable {
    String replyToCommand(String cmd, String args, Writable wr, boolean html);
    boolean removeWritable( Writable wr);
}
