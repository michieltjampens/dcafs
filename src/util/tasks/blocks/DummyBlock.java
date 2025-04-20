package util.tasks.blocks;

public class DummyBlock extends AbstractBlock {
    @Override
    public boolean start() {
        doNext();
        return true;
    }
}
