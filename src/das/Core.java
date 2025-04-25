package das;

import worker.Datagram;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Core {
    private static final BlockingQueue<Datagram> dQueue = new LinkedBlockingQueue<>();

    public static void addToQueue(Datagram d) {
        dQueue.add(d);
    }

    public static void queueSystemCmd(String cmd) {
        dQueue.add(Datagram.system(cmd));
    }
    public static Datagram retrieve() throws InterruptedException {
        return dQueue.take();
    }
    public static int queueSize(){
        return dQueue.size();
    }
}
