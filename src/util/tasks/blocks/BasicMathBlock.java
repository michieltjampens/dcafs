package util.tasks.blocks;

import org.tinylog.Logger;
import util.data.vals.NumericVal;

public class BasicMathBlock extends AbstractBlock {

    enum OP {COPY, INCREMENT, DECREMENT, RESET, NOOP}

    NumericVal[] targetVal;
    NumericVal sourceVal;

    OP op;

    public BasicMathBlock(NumericVal[] targetVal, NumericVal sourceVal, String op) {
        this.targetVal = targetVal;
        this.sourceVal = sourceVal;
        this.op = parseOp(op);
    }
    public static BasicMathBlock build(String id, NumericVal targetVal, String op, NumericVal sourceVal) {
        return BasicMathBlock.build(id, new NumericVal[]{targetVal}, op, sourceVal);
    }

    public static BasicMathBlock build(String id, NumericVal[] targetVal, String op, NumericVal sourceVal) {
        var b = new BasicMathBlock(targetVal, sourceVal, op);
        b.id(id);
        if (b.op == OP.NOOP) {
            Logger.error(id + " -> Unknown operation provided.(" + op + ")");
            return null;
        }
        if (b.op != OP.RESET && sourceVal == null) {
            Logger.error(id + " -> Requested " + op + " which requires a source, but none provided. (target:" + targetVal[0].id() + ")");
            return null;
        }
        return b;
    }

    private static OP parseOp(String op) {
        return switch (op.toLowerCase()) {
            case "inc", "increment" -> OP.INCREMENT;
            case "dec", "decrement" -> OP.DECREMENT;
            case "reset", "clear" -> OP.RESET;
            case "copy" -> OP.COPY;
            default -> {
                Logger.error("Unknown operation provided.");
                yield OP.NOOP;
            }
        };
    }

    @Override
    public boolean start() {
        for (var nv : targetVal) {
            switch (op) {
                case COPY -> nv.update(sourceVal.asDouble());
                case INCREMENT -> nv.update(nv.asDouble() + sourceVal.asDouble());
                case DECREMENT -> nv.update(nv.asDouble() - sourceVal.asDouble());
                case RESET -> nv.resetValue();
                case NOOP -> {
                }
            }
        }
        doNext();
        return true;
    }
}
