package util.tasks;

import io.Writable;
import io.netty.channel.EventLoopGroup;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SplitBlock extends AbstractBlock implements Writable {
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

    @Override
    public void buildId(String id) {
        if (!this.id.isEmpty())
            return;

        var split = id.split("\\|", 2);
        if (split.length == 2) {
            var nrs = split[1].split("F");
            if (nrs.length == 2) {
                var nr = NumberUtils.toInt(nrs[1]) + 1;
                this.id = split[0] + "|" + nrs[0] + "F" + nr;
            } else {
                var nr = NumberUtils.toInt(nrs[0]) + 1;
                this.id = split[0] + "|" + nr;
            }
        } else {
            this.id = id + "|0";
        }
        if (failure != null)
            failure.buildId(this.id + "F0");
        var lastId = this.id;
        for (var n : nexts) {
            n.buildId(lastId);
            lastId = n.getLastBlock().id();
        }
    }
    public void reset() {
        nexts.forEach(AbstractBlock::reset);
    }

    public void resetId() {
        if (id.isEmpty())
            return;
        id = "";
        for (var n : nexts)
            n.resetId();
        if (failure != null)
            failure.resetId();
    }
    public AbstractBlock addNext(AbstractBlock block) {
        block.id("Branch" + nexts.size());
        if (interval != 0)
            block.setCallbackWritable(this);
        nexts.add(block);
        return this;
    }

    @Override
    public boolean writeLine(String origin, String data) {
        Logger.info("Callback? -> " + data);
        if (data.toLowerCase().contains("failure")) {
            Logger.info("Failure occurred, not executing remainder");
            if (startNext != null && !startNext.isCancelled() && !startNext.isDone())
                startNext.cancel(true);
        }
        return true;
    }

    @Override
    public boolean isConnectionValid() {
        return true;
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
