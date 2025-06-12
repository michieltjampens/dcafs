package util.tasks.blocks;

import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.tools.TimeTools;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TriggerGateBlock extends AbstractBlock {

    enum RETRIGGER {IGNORE, COUNT, RESTART}

    EventLoopGroup eventLoop;
    boolean armed = false;
    long delay;
    ScheduledFuture<?> future;

    RETRIGGER retrigger;

    private TriggerGateBlock(EventLoopGroup eventLoop, String delay, String retrigger) {
        this.eventLoop = eventLoop;
        this.delay = TimeTools.parsePeriodStringToMillis(delay);
        this.retrigger = parseRetrigger(retrigger);
    }

    public static TriggerGateBlock build(EventLoopGroup eventLoop, String delay, String retrigger) {
        return new TriggerGateBlock(eventLoop, delay, retrigger);
    }

    private static RETRIGGER parseRetrigger(String retriggerVal) {
        return switch (retriggerVal.toLowerCase()) {
            case "ignore" -> RETRIGGER.IGNORE;   // Total delay is at most 100ms
            case "count" -> RETRIGGER.COUNT;
            case "restart" -> RETRIGGER.RESTART; // Delay is 100ms+time between first and last 'bounce'

            default -> {
                Logger.error("Unknown retrigger property value used '" + retriggerVal + "', defaulting to 'ignore'.");
                yield RETRIGGER.IGNORE;
            }
        };
    }

    @Override
    public boolean start() {
        if (armed) {
            if (retrigger == RETRIGGER.RESTART) {
                future.cancel(true);
                future = eventLoop.schedule(this::disarm, delay, TimeUnit.MILLISECONDS);
            } else if (retrigger == RETRIGGER.COUNT) {
                doAltRoute(false);
            }
            return true;
        }
        armed = true;
        future = eventLoop.schedule(this::disarm, delay, TimeUnit.MILLISECONDS);
        return doNext();
    }

    public void disarm() {
        armed = false;
        if (retrigger != RETRIGGER.COUNT)
            doAltRoute(false);
    }
}
