package das;

import io.Writable;

public interface Commandable {
    /**
     * Respond to a cmd
     * @param cmd The cmd to execute
     * @param args The arguments for the cmd
     * @param wr Thr writable from where the cmd comes from
     * @param html True means the answer should be with html
     * @return The answer
     */
    String replyToCommand(String cmd, String args, Writable wr, boolean html);

    /**
     * For cmd that require and object to be given along with it
     * @param cmd The cmd to execute
     * @param args The arguments for the cmd
     * @param payload The object that is relevant for the cmd
     * @return The answer
     */
    String payloadCommand( String cmd, String args, Object payload);

    /**
     * Remove the given writable from the list of writable's in this object.
     * @param wr The writable to remove.
     * @return The result of trying to remove, true means ok.
     */
    boolean removeWritable( Writable wr);
    /** Implementation info
     * -> If the amount of args are wrong return: "! Wrong amount of arguments -> <proper cmd>"
     * -> If the args don't match a subcommand: "! No such subcommand in ... : "+args;
     */
}
