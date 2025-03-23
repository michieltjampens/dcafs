package util.tasks;

import io.netty.channel.EventLoopGroup;
import org.tinylog.Logger;
import util.tools.TimeTools;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SplitBlock extends AbstractBlock {
    ArrayList<AbstractBlock> nexts = new ArrayList<>();
    EventLoopGroup eventLoop;
    long interval = 0;
    int pos = 0;
    ScheduledFuture<?> startNext;

    public SplitBlock(EventLoopGroup eventLoop) {
        this.eventLoop = eventLoop;
    }

    public SplitBlock setInterval(String interval) {
        if (interval.isEmpty())
            return this;
        this.interval = TimeTools.parsePeriodStringToMillis(interval);
        return this;
    }

    @Override
    boolean start() {
        if (interval == 0) {
            nexts.forEach(n -> eventLoop.submit(n::start));
        } else {
            startNext = eventLoop.schedule(this::startNext, interval, TimeUnit.MILLISECONDS);
            nexts.get(0).start();
            pos = 1;
        }
        return false;
    }

    private void startNext() {
        if (nexts.size() >= pos) {
            startNext = eventLoop.schedule(this::startNext, interval, TimeUnit.MILLISECONDS);
            nexts.get(pos).start();
            pos++;
        }
    }

    public void reset() {
        nexts.forEach(AbstractBlock::reset);
    }

    public AbstractBlock addNext(AbstractBlock block) {
        block.id("Branch" + nexts.size());
        block.setCallbackWritable(this);
        nexts.add(block);
        return this;
    }

    @Override
    public boolean writeLine(String data) {
        Logger.info("Callback? -> " + data);
        if (data.toLowerCase().contains("failure")) {
            Logger.info("Failure occurred, not executing remainder");
            if (startNext != null && !startNext.isCancelled() && !startNext.isDone())
                startNext.cancel(true);
        }
        return true;
    }

    @Override
    public boolean giveObject(String info, Object object) {
        return false;
    }

    public String getInfo(StringJoiner info, String offset) {
        if (order == 0)
            info.add("Start of chain: " + id);
        info.add(offset + this);

        for (var block : nexts) {
            var join = new StringJoiner("\r\n");
            info.add(block.getInfo(join, offset + "  "));
        }
        info.add("-End of the chain-");
        return info.toString();
    }

    public String toString() {
        return "Splitting in " + nexts.size() + " tasks" + (interval != 0 ? ", with a delay between branches of " + TimeTools.convertPeriodToString(interval, TimeUnit.MILLISECONDS) : "");
    }
}
