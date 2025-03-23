package util.tasks;

import io.email.Email;
import org.tinylog.Logger;
import worker.Datagram;

import java.util.concurrent.BlockingQueue;

public class EmailBlock extends AbstractBlock {
    Email email;

    public EmailBlock(BlockingQueue<Datagram> dQueue, String to) {
        this.dQueue = dQueue;
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
        dQueue.add(Datagram.system("email:payload").payload(email).writable(this));
        return true;
    }

    @Override
    public boolean writeLine(String origin, String data) {
        Logger.info(chainId() + " -> Reply: " + data);
        if (data.startsWith("!")) {
            doFailure();
        } else {
            doNext();
        }
        return true;
    }

    public String toString() {
        return chainId() + " -> Queue's email:'" + email + "'";
    }
}
