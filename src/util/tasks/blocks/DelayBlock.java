package util.tasks.blocks;

import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.tools.TimeTools;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class DelayBlock extends AbstractBlock {

    enum TYPE {DELAY, INTERVAL}

    enum RETRIGGER {IGNORE, CANCEL, RESTART}

    TYPE type = TYPE.DELAY;
    RETRIGGER retrigger;

    EventLoopGroup eventLoop;
    long initialDelay = 0;
    long interval = 0;
    int repeats = 0;
    int reps = -1;

    ScheduledFuture<?> future;
    boolean waiting = false;

    private DelayBlock(EventLoopGroup eventLoop, String retrigger) {
        this.retrigger = parseRetrigger(retrigger);
        this.eventLoop = eventLoop;
    }

    public static DelayBlock useInterval(EventLoopGroup eventLoop, String initialDelay, String interval, int repeats, String retrigger) {
        var db = new DelayBlock(eventLoop, retrigger);
        return db.useInterval(initialDelay, interval, repeats);
    }

    public DelayBlock useInterval(String initialDelay, String interval, int repeats) {
        this.interval = TimeTools.parsePeriodStringToSeconds(interval);
        this.initialDelay = TimeTools.parsePeriodStringToSeconds(initialDelay);
        this.repeats = repeats - 1;
        type = TYPE.INTERVAL;
        return this;
    }

    public static DelayBlock useDelay(String delay, String retriggerVal, EventLoopGroup eventLoop) {
        var db = new DelayBlock(eventLoop, retriggerVal);
        return db.alterDelay(delay);
    }

    private static RETRIGGER parseRetrigger(String retriggerVal) {
        return switch (retriggerVal.toLowerCase()) {
            case "ignore" -> RETRIGGER.IGNORE;
            case "restart" -> RETRIGGER.RESTART;
            case "cancel" -> RETRIGGER.CANCEL;
            default -> {
                Logger.error("Unknown retrigger property value used '" + retriggerVal + "', defaulting to 'restart'.");
                yield RETRIGGER.RESTART;
            }
        };
    }
    public DelayBlock alterDelay(String delay) {
        this.initialDelay = TimeTools.parsePeriodStringToSeconds(delay);
        type = TYPE.DELAY;
        return this;
    }

    @Override
    public boolean start() {
        if (clean) {
            firstRun();
        } else if (retrigger != RETRIGGER.IGNORE) {
            cancelIfRunning(retrigger == RETRIGGER.RESTART);
        }
        clean = false;
        return true;
    }

    /**
     * Cancels the current delay if active, and restarts it if requested.
     *
     * @param shouldRestart Whether a new delay should start or not
     */
    private void cancelIfRunning(boolean shouldRestart) {
        Logger.info("Done:" + future.isDone() + " cancel:" + future.isCancelled());
        if (waiting && future != null) {
            Logger.info(telnetId() + " -> Got stopped with " + TimeTools.convertPeriodToString(future.getDelay(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS) + " left.");
            future.cancel(false);
            waiting = false;
            doAltRoute(true);
        }
        if (shouldRestart)
            firstRun();
    }
    private void firstRun() {
        waiting = true;
        reps = repeats;
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
    public boolean doNext() {
        if (future == null || future.isCancelled()) {
            Logger.info(id() + " -> Task is canceled or future is null, exiting...");
            return false;  // Exit early if the task is canceled or future is null
        }
        switch (reps) {
            case -1 -> super.doNext(); // -1 means endless
            case 0 -> { // Last rep done, take the detour
                if (type == TYPE.INTERVAL)
                    cancelIfRunning(false);
                waiting = false;
                super.doNext(); // Do the main route
            }
            default -> { // Reps not yet 0
                super.doNext();
                reps--;
            }
        }
        return true;
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
