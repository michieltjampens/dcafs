package util.tasks.blocks;

import util.data.vals.FlagVal;

public class FlagBlock extends AbstractBlock {
    enum ACTION {RAISE, RESET, TOGGLE}

    FlagVal flag;
    ACTION action;

    public static FlagBlock raises(FlagVal flag) {
        return new FlagBlock(flag, ACTION.RAISE);
    }

    public static FlagBlock lowers(FlagVal flag) {
        return new FlagBlock(flag, ACTION.RESET);
    }

    public static FlagBlock toggles(FlagVal flag) {
        return new FlagBlock(flag, ACTION.TOGGLE);
    }

    FlagBlock(FlagVal flag, ACTION action) {
        this.action = action;
        this.flag = flag;
    }

    @Override
    public boolean start() {
        switch (action) {
            case RAISE -> flag.update(true);
            case RESET -> flag.update(false);
            case TOGGLE -> flag.toggleState();
        }
        doNext();
        return true;
    }
}
