package util.tasks.blocks;

import io.netty.channel.EventLoopGroup;

public class ControlBlock extends AbstractBlock {
    EventLoopGroup eventLoop;
    String message;

    OriginBlock start;
    OriginBlock end;

    public ControlBlock(EventLoopGroup eventLoop, OriginBlock start, OriginBlock end) {
        this.eventLoop = eventLoop;
        this.start = start;
        this.end = end;
    }

    @Override
    public boolean start() {
        if (start != null)
            eventLoop.submit(start::restart);
        if (end != null)
            eventLoop.submit(end::reset);
        doNext();
        return true;
    }
}
