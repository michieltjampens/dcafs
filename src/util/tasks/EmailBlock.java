package util.tasks;

import das.Core;
import io.email.Email;
import org.tinylog.Logger;
import worker.Datagram;

public class EmailBlock extends AbstractBlock {
    Email email;

    public EmailBlock( String to ) {
        email = Email.to(to);
    }

    public EmailBlock subject(String subject) {
        email.subject(subject);
        return this;
    }

    public EmailBlock content(String content) {
        email.content(content);
        return this;
    }

    @Override
    boolean start() {
        Core.addToQueue(Datagram.system(email.content()).writable(this));
        return true;
    }

    @Override
    public boolean writeLine(String origin, String data) {
        Logger.info(id() + " -> Reply: " + data);
        var cmd = "email:send," + email.to() + "," + email.subject() + "," + email.content();
        cmd += data.startsWith("!") ? email.content() : data;
        Core.addToQueue(Datagram.system(cmd).payload(email).writable(this));
        doNext();
        return true;
    }

    public String toString() {
        return telnetId() + " -> " + email + ".";
    }
}
