package util.tasks;

public class ConditionBlock extends AbstractBlock {

    @Override
    public boolean start() {
        return false;
    }

    @Override
    void doFailure() {

    }

    @Override
    public boolean giveObject(String info, Object object) {
        return false;
    }
}
