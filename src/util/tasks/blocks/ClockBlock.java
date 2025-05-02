package util.tasks.blocks;

import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.tools.Tools;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ClockBlock extends AbstractBlock {
    enum CLOCK {LOCAL, UTC}

    CLOCK clock = CLOCK.UTC;
    DayOfWeek[] days;
    String time = "";
    ScheduledFuture<?> future;
    EventLoopGroup eventLoop;

    private ClockBlock(EventLoopGroup eventLoop, String time, DayOfWeek[] days, CLOCK clock) {
        this.eventLoop = eventLoop;
        this.time = time;
        this.days = days;
        this.clock = clock;
    }

    public static ClockBlock create(EventLoopGroup eventLoop, String time, boolean local) {
        var split = Tools.splitList(time, 2, "");
        return new ClockBlock(eventLoop, split[0], TimeTools.convertDAY(split[1]), local ? CLOCK.LOCAL : CLOCK.UTC);
    }

    public static ClockBlock create(EventLoopGroup eventLoop, String time, String days, boolean local) {
        return new ClockBlock(eventLoop, time, TimeTools.convertDAY(days), local ? CLOCK.LOCAL : CLOCK.UTC);
    }

    @Override
    public boolean start() {
        scheduleRun();
        return true;
    }

    private void dailyRun() {
        if (future == null || future.isCancelled()) {
            Logger.info(id() + " -> Task canceled, exiting dailyRun...");
            return;  // Exit early if the task is canceled
        }
        doNext();
        Logger.info(id() + " -> Running daily task!");
        scheduleRun();
    }

    private void scheduleRun() {
        var initialDelay = TimeTools.calcSecondsTo(time, clock == CLOCK.LOCAL, days); // Calculate seconds till requested time
        future = eventLoop.schedule(this::dailyRun, initialDelay, TimeUnit.SECONDS);
    }

    public String toString() {
        var nextRun = TimeTools.convertPeriodToString(future.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS);
        var dayListing = Arrays.stream(days).map(dow -> dow.toString().substring(0, 2)).collect(Collectors.joining(""));
        var daysInfo = this.days.length == 7 ? "" : " on " + dayListing;
        return telnetId() + " -> Runs at " + time + daysInfo + ", next one in " + nextRun;
    }
}
