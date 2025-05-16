package util.tasks.blocks;

public class NoOpBlock extends ConditionBlock {

    public static final NoOpBlock INSTANCE = new NoOpBlock();

    private NoOpBlock() {
    }

    @Override
    public boolean start() {
        // intentionally empty
        return true;
    }

    @Override
    public boolean start(double... input) {
        // intentionally empty
        return true;
    }
}
