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
        switch (level) {
            case INFO -> Logger.info(message);
            case WARN -> Logger.warn(message);
            case ERROR -> Logger.error(message);
        }
        doNext();
        return true;
    }
}
