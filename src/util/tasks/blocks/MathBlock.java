package util.tasks.blocks;

import util.data.procs.MathEvalForVal;

public class MathBlock extends AbstractBlock {
    MathEvalForVal eval;

    public MathBlock(MathEvalForVal eval) {
        this.eval = eval;
    }

    public static MathBlock build(MathEvalForVal eval) {
        return new MathBlock(eval);
    }

    @Override
    public boolean start() {
        eval.eval(0, 0, 0);
        doNext();
        return true;
    }
}
