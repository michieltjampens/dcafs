package das;

import worker.Datagram;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Core {
    private static final BlockingQueue<Datagram> dQueue = new LinkedBlockingQueue<>();

    public static void addToQueue(Datagram d) {
        dQueue.add(d);
    }

    public static Datagram retrieve() throws InterruptedException {
        return dQueue.take();
    }
}
