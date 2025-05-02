package util.tasks.blocks;

import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.tools.TimeTools;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class DelayBlock extends AbstractBlock {

    enum TYPE {DELAY, INTERVAL}

    TYPE type = TYPE.DELAY;
    EventLoopGroup eventLoop;
    long initialDelay = 0;
    long interval = 0;
    int repeats = -1;
    int reps = -1;

    ScheduledFuture<?> future;
    boolean retrigger = false;

    public DelayBlock(EventLoopGroup eventLoop) {
        this.eventLoop = eventLoop;
    }

    public static DelayBlock useInterval(EventLoopGroup eventLoop, String initialDelay, String interval, int repeats) {
        var db = new DelayBlock(eventLoop);
        return db.useInterval(initialDelay, interval, repeats);
    }

    public DelayBlock useInterval(String initialDelay, String interval, int repeats) {
        this.interval = TimeTools.parsePeriodStringToSeconds(interval);
        this.initialDelay = TimeTools.parsePeriodStringToSeconds(initialDelay);
        this.repeats = repeats;
        reps = repeats;
        type = TYPE.INTERVAL;
        return this;
    }

    public static DelayBlock useDelay(String delay, EventLoopGroup eventLoop) {
        var db = new DelayBlock(eventLoop);
        return db.useDelay(delay);
    }
    public DelayBlock useDelay(String delay) {
        this.initialDelay = TimeTools.parsePeriodStringToSeconds(delay);
        type = TYPE.DELAY;
        return this;
    }


    @Override
    public boolean start() {
        clean = false;
        if (future == null || future.isCancelled() || future.isDone()) {
            firstRun();
        } else if (retrigger) {
            future.cancel(true);
            firstRun();
        }
        return true;
    }

    private void firstRun() {
        if (future != null && !future.isDone()) {
            Logger.error(id() + " -> Old future still alive? Cancelling...");
            future.cancel(true);  // Cancel the old future
            future = null;        // Clear the reference to avoid future interference
        }
        future = switch (type) {
            case DELAY -> eventLoop.schedule(this::doNext, initialDelay, TimeUnit.SECONDS);
            case INTERVAL -> {
                // If no delay is specified, calculate how much time till clean interval fe
                // For example if interval is 20min, and it's now 16:14, initial delay will be 6min
                if (initialDelay == 0)
                    initialDelay = TimeTools.secondsDelayToCleanTime(interval * 1000) / 1000;
                yield eventLoop.scheduleWithFixedDelay(this::doNext, initialDelay, interval, TimeUnit.SECONDS);
            }
        };
    }

    @Override
    public void doNext() {
        if (future == null || future.isCancelled()) {
            Logger.info(id() + " -> Task is canceled or future is null, exiting...");
            return;  // Exit early if the task is canceled or future is null
        }
        switch (reps) {
            case -1 -> super.doNext(); // -1 means endless
            case 0 -> { // Last rep done, take the detour
                doAltRoute(false); // Do the alternative route
            }
            default -> { // Reps not yet 0
                super.doNext();
                reps--;
            }
        }
    }

    @Override
    public void reset() {
        reps = repeats; // Reset reps
        if (future != null && !future.isDone() && !future.isCancelled()) {
            Logger.info(id() + " -> Cancelling future in reset...");
            future.cancel(true);  // Cancel the ongoing future
            future = null;        // Clear the reference
        }
        super.reset(); // Resets clean
    }

    @Override
    public void setAltRouteBlock(AbstractBlock altRoute) {
        if (repeats == -1) {
            Logger.warn(id + " -> Trying to set a failure block in a DelayBlock");
            return;
        }
        if (altRoute != null)
            this.altRoute = altRoute;
    }
    public String toString() {
        var nextRun = "?";
        if (type != TYPE.DELAY)
            nextRun = TimeTools.convertPeriodToString(future.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS);
        return switch (type) {
            case DELAY ->
                    telnetId() + " -> Wait for " + TimeTools.convertPeriodToString(initialDelay, TimeUnit.SECONDS) + ", then go to " + next.telnetId();
            case INTERVAL -> telnetId() + " -> After " + TimeTools.convertPeriodToString(initialDelay, TimeUnit.SECONDS)
                    + " execute next, then repeat every " + TimeTools.convertPeriodToString(interval, TimeUnit.SECONDS)
                    + (repeats == -1 ? " indefinitely" : " for at most " + repeats + " times") + " next one in " + nextRun;
        };
    }

}
