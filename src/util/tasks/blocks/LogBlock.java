package util.tasks.blocks;

import org.tinylog.Logger;

public class LogBlock extends AbstractBlock {
    enum LEVEL {INFO, WARN, ERROR}

    LEVEL level;
    String message;

    private LogBlock(LEVEL level, String message) {
        this.level = level;
        this.message = id() + " -> " + message;
    }

    public static LogBlock info(String message) {
        return new LogBlock(LEVEL.INFO, message);
    }

    public static LogBlock warn(String message) {
        return new LogBlock(LEVEL.WARN, message);
    }

    public static LogBlock error(String message) {
        return new LogBlock(LEVEL.ERROR, message);
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
        switch (level) {
            case INFO -> Logger.info(m);
            case WARN -> Logger.warn(m);
            case ERROR -> Logger.error(m);
        }
        doNext();
        return true;
    }
}
