package das;

import io.Writable;

public interface Commandable {
    String replyToCommand(String cmd, String args, Writable wr, boolean html);
    String payloadCommand( String cmd, String args, Object payload);
    boolean removeWritable( Writable wr);
    /** Implementation info
     * -> If the amount of args are wrong return: "! Wrong amount of arguments -> <proper cmd>"
     * -> If the args don't match a subcommand: "! No such subcommand in ... : "+args;
     */
}
