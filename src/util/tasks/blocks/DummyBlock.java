package util.tasks.blocks;

public class DummyBlock extends AbstractBlock {
    @Override
    boolean start() {
        doNext();
        return true;
    }
}
