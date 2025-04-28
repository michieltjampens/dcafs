package util.tasks.blocks;

import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.tools.Tools;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class DelayBlock extends AbstractBlock {

    enum TYPE {DELAY, INTERVAL, CLOCK}

    TYPE type = TYPE.DELAY;
    EventLoopGroup eventLoop;
    long initialDelay = 0;
    long interval = 0;
    int repeats = -1;
    int reps = -1;
    String time = "";
    boolean localTime = false;
    ScheduledFuture<?> future;
    ArrayList<DayOfWeek> taskDays;

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

    public DelayBlock useClock(String time, boolean local) {
        type = TYPE.CLOCK;
        localTime = local;
        var split = Tools.splitList(time, 2, "");
        this.time = split[0];
        taskDays = TimeTools.convertDAY(split[1]);
        return this;
    }
    @Override
    public boolean start() {
        clean = false;
        firstRun();
        return true;
    }

    private void firstRun() {
        future = switch (type) {
            case DELAY -> eventLoop.schedule(this::doNext, initialDelay, TimeUnit.SECONDS);
            case INTERVAL -> {
                // If no delay is specified, calculate how much time till clean interval fe
                // For example if interval is 20min, and it's now 16:14, initial delay will be 6min
                if (initialDelay == 0)
                    initialDelay = TimeTools.secondsDelayToCleanTime(interval * 1000) / 1000;
                yield eventLoop.scheduleWithFixedDelay(this::doNext, initialDelay, interval, TimeUnit.SECONDS);
            }
            case CLOCK -> {
                var initialDelay = TimeTools.calcSecondsTo(time, localTime, taskDays); // Calculate seconds till requested time
                yield eventLoop.schedule(this::dailyRun, initialDelay, TimeUnit.SECONDS);
            }
        };
    }
    private void dailyRun() {
        doNext();
        Logger.info(id() + " -> Running daily task!");
        var initialDelay = TimeTools.calcSecondsTo(time, localTime, taskDays);
        future = eventLoop.schedule(this::dailyRun, initialDelay, TimeUnit.SECONDS);
    }
    @Override
    public void doNext() {
        switch (reps) {
            case -1 -> super.doNext(); // -1 means endless
            case 0 -> { // Last rep done, signal failure
                doFailure(); // Do the failure step
                sendCallback(id() + " -> FAILURE"); // Let it know up the chain
                future.cancel(false); // Cancel waiting task
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
        clean = true;  // Restore clean
        if (future != null && !future.isDone() && !future.isCancelled()) { // Cancel any waiting task
            future.cancel(true);
            Logger.info("Tried to cancel the future");
        }
        // Propagate the reset to the next steps
        if (next != null)
            next.reset();
        if (failure != null)
            failure.reset();
    }

    @Override
    public DelayBlock setFailureBlock(AbstractBlock failure) {
        if (repeats == -1) {
            Logger.warn(id + " -> Trying to set a failure block in a DelayBlock");
            return this;
        }
        if (failure != null)
            this.failure = failure;
        return this;
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
            case CLOCK -> {
                var dayListing = taskDays.stream().map(dow -> dow.toString().substring(0, 2)).collect(Collectors.joining(""));
                var days = taskDays.size() == 7 ? "" : " on " + dayListing;
                yield telnetId() + " -> Runs at " + time + days + ", next one in " + nextRun;
            }
        };
    }

}
