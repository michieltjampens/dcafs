package util.tasks.blocks;

import org.tinylog.Logger;
import util.data.vals.NumericVal;

public class LogBlock extends AbstractBlock {
    enum LEVEL {INFO, WARN, ERROR}

    LEVEL level;
    String message;
    NumericVal[] refs=null;

    private LogBlock(LEVEL level, String message, NumericVal[] refs) {
        this.level = level;
        this.message = id() + " -> " + message;
        this.refs=refs;
    }

    public static LogBlock info(String message, NumericVal[] refs) { return new LogBlock(LEVEL.INFO, message,refs); }
    public static LogBlock warn(String message, NumericVal[] refs) {
        return new LogBlock(LEVEL.WARN, message,refs);
    }
    public static LogBlock error(String message, NumericVal[] refs) {
        return new LogBlock(LEVEL.ERROR, message,refs);
    }

    @Override
    public boolean start() {
        return start(Double.NaN);
    }

    public boolean start(double... input) {
        var m = message;
        if (!Double.isNaN(input[0]) && input.length == 3) {
            m = m.replace("{new}", String.valueOf(input[0]));
            m = m.replace("{old}", String.valueOf(input[1]));
            m = m.replace("{math}", String.valueOf(input[2]));
        }
        if( refs!=null ){
            for( var nf : refs )
                m = m.replace("{"+nf.id()+"}",nf.asString()+nf.unit());
        }
        switch (level) {
            case INFO -> Logger.tag("TASK").info(m);
            case WARN -> Logger.tag("TASK").warn(m);
            case ERROR -> Logger.tag("TASK").error(m);
        }
        doNext();
        return true;
    }
}
