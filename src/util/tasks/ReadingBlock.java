package util.tasks;

import das.Core;
import io.Writable;
import io.netty.channel.EventLoopGroup;
import util.tools.TimeTools;
import worker.Datagram;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ReadingBlock extends AbstractBlock implements Writable {
    String data;
    String src;
    EventLoopGroup eventLoop;
    long timeout = 0;
    ScheduledFuture<?> future;
    ScheduledFuture<?> cleanup;
    boolean writableAsked = false;
    boolean active = false;

    public ReadingBlock(EventLoopGroup eventLoop) {
        this.eventLoop = eventLoop;
    }

    public ReadingBlock setMessage(String src, String data, String timeout) {
        this.data = data;
        src = src.replace("stream", "raw");
        this.src = src;
        this.timeout = TimeTools.parsePeriodStringToSeconds(timeout);
        return this;
    }

    @Override
    boolean start() {
        if (!writableAsked) {
            Core.addToQueue(Datagram.system(src).writable(this)); // Request data updates from src
            writableAsked = true;
        }
        if (timeout > 0) {
            future = eventLoop.schedule(this::doFailure, timeout, TimeUnit.SECONDS);
            if (cleanup != null)
                cleanup.cancel(true);
            cleanup = eventLoop.schedule(this::doCleanup, 5 * timeout, TimeUnit.SECONDS);
        }
        active = true;
        clean = false;
        return true;
    }

    private void doCleanup() {
        writableAsked = false;
    }

    public String toString() {
        return telnetId() + " -> Waiting dor '" + data + "' from " + src + " for at most " + TimeTools.convertPeriodToString(timeout, TimeUnit.SECONDS);
    }
    @Override
    public synchronized boolean writeLine(String origin, String data) {
        if (!active)
            return true;

        if (this.data.equalsIgnoreCase(data)) {
            active = false;
            doNext();
            // Task moves on, so cancel data updates
            // Notify src that updates are no longer needed
            writableAsked = false; // Reset this for when executed again
            future.cancel(true);
        }
        return true;
    }

    public void reset() {
        if (future != null && !future.isDone() && !future.isCancelled())
            future.cancel(true);
        if (cleanup != null && !cleanup.isDone() && !cleanup.isCancelled())
            cleanup.cancel(true);
        writableAsked = false;

        clean = true;

        if (next != null)
            next.reset();
        if (failure != null)
            failure.reset();
    }

    @Override
    public boolean isConnectionValid() {
        return writableAsked;
    }

    protected void doFailure() {
        active = false;
        if (failure != null)
            failure.start();
    }
}
