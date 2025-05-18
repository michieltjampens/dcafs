package util.tasks.blocks;

import io.netty.channel.EventLoopGroup;

public class ControlBlock extends AbstractBlock {
    EventLoopGroup eventLoop;
    AbstractBlock start;
    AbstractBlock stop;

    public ControlBlock(EventLoopGroup eventLoop, AbstractBlock start, AbstractBlock stop) {
        this.eventLoop = eventLoop;
        this.start = start;
        this.stop = stop;
    }

    public void setBlocks(AbstractBlock start, AbstractBlock stop) {
        if (start != null)
            this.start = start;
        if (stop != null)
            this.stop = stop;
    }
    @Override
    public boolean start() {
        if (start != null) {
            if (start instanceof OriginBlock ori) {
                eventLoop.submit(ori::restart);
            } else {
                eventLoop.submit(start::start);
            }
        }
        if (stop != null)
            eventLoop.submit(stop::reset);
        doNext();
        return true;
    }
}
