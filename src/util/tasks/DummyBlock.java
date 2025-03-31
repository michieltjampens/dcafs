package util.tasks;

public class DummyBlock extends AbstractBlock {
    @Override
    boolean start() {
        doNext();
        return true;
    }
}
