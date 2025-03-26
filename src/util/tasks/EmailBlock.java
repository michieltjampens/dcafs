package util.tasks;

import das.Core;
import io.Writable;
import io.email.Email;
import org.tinylog.Logger;
import worker.Datagram;

public class EmailBlock extends AbstractBlock implements Writable {
    Email email;

    public EmailBlock(Email email) {
        this.email = email;
    }
    @Override
    boolean start() {

        if (email.content().length() < 30) { // Commands are short
            // Start by checking if the content is a command or just content
            Core.addToQueue(Datagram.system(email.content()).writable(this));
        } else { // No writable for now TODO
            Core.addToQueue(Datagram.system("email:deliver").payload(email));//.writable(this));
        }
        return true;
    }

    @Override
    public boolean writeLine(String origin, String data) {
        Logger.info(id() + " -> Reply: " + data);
        email.content(data.startsWith("!") ? email.content() : data);
        Core.addToQueue(Datagram.system("email:deliver").payload(email).writable(this));
        doNext();
        return true;
    }

    @Override
    public boolean isConnectionValid() {
        return true;
    }
    public String toString() {
        return telnetId() + " -> " + email + ".";
    }
}
