package das;

import io.Writable;
import worker.Datagram;

public interface Commandable {
    /**
     * Respond to a cmd
     * @param d The datagram containing all relevant info
     * @return The answer
     */
    String replyToCommand(Datagram d);
    /**
     * Remove the given writable from the list of writable's in this object.
     * @param wr The writable to remove.
     * @return The result of trying to remove, true means ok.
     */
    boolean removeWritable(Writable wr);
    /*
     * Implementation info
     * -> If the amount of args are wrong return: "! Wrong amount of arguments -> <proper cmd>"
     * -> If the args don't match a subcommand: "! No such subcommand in ... : "+args;
     */
}
