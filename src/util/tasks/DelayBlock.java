package util.tasks;

import io.netty.channel.EventLoopGroup;
import util.tools.TimeTools;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class DelayBlock extends AbstractBlock {

    EventLoopGroup eventLoop;
    long initialDelay = 0;
    long interval = 0;
    int repeats = -1;
    int reps = -1;
    ScheduledFuture<?> future;

    public DelayBlock(EventLoopGroup eventLoop) {
        this.eventLoop = eventLoop;
    }
    public DelayBlock useInterval(String initialDelay, String interval, int repeats) {
        this.interval = TimeTools.parsePeriodStringToSeconds(interval);
        this.initialDelay = TimeTools.parsePeriodStringToSeconds(initialDelay);
        this.repeats = repeats;
        reps = repeats;
        return this;
    }

    public DelayBlock useDelay(String delay) {
        this.initialDelay = TimeTools.parsePeriodStringToSeconds(delay);
        return this;
    }

    @Override
    boolean start() {
        clean = false;
        if (interval == 0) {
            future = eventLoop.schedule(this::doNext, initialDelay, TimeUnit.SECONDS);
        } else {
            future = eventLoop.scheduleAtFixedRate(this::doNext, initialDelay, interval, TimeUnit.SECONDS);
        }
        return true;
    }

    @Override
    public void doNext() {
        if (reps == -1) {
            super.doNext();
        } else {
            if (reps == 0) {
                doFailure();
                sendCallback(id() + " -> FAILURE");
                future.cancel(false);
            } else {
                super.doNext();
                reps--;
            }
        }
    }

    @Override
    public void reset() {
        reps = repeats;
        clean = true;
        if (future != null && !future.isDone() && !future.isCancelled())
            future.cancel(true);

        if (next != null)
            next.reset();
        if (failure != null)
            failure.reset();
    }

    @Override
    public void setFailureBlock(AbstractBlock failure) {
    }
    public String toString() {
        if (interval == 0) {
            return telnetId() + " -> Wait for " + TimeTools.convertPeriodToString(initialDelay, TimeUnit.SECONDS) + ", then go to " + next.telnetId();
        }
        return telnetId() + " -> After " + TimeTools.convertPeriodToString(initialDelay, TimeUnit.SECONDS)
                + " execute next, then repeat every " + TimeTools.convertPeriodToString(interval, TimeUnit.SECONDS)
                + (repeats == -1 ? " indefinitely" : " for at most " + repeats + " times");
    }

}
