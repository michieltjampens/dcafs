package util.tasks.blocks;

import org.tinylog.Logger;

public class CounterBlock extends AbstractBlock {
    int count;
    int tempCount;
    ONZERO onZero = ONZERO.ALT;

    enum ONZERO {ALT, STOP, FAIL, ALT_RESET}

    boolean altInfinite = false;

    public CounterBlock(int cnt) {
        if (cnt == 0)
            Logger.warn("Counter made with 0 counts, so straight to alternative block");
        tempCount = cnt;
        count = cnt;
    }

    @Override
    public boolean start() {
        clean = false;
        if (tempCount == 0) {
            Logger.info(id + " -> Count ran out, executing alternative (if any).");
            switch (onZero) {
                case ALT:
                case FAIL:
                    doAltRoute(onZero == ONZERO.FAIL);
                    if (!altInfinite)
                        tempCount--;
                    break;
                case STOP:
                    tempCount = -1;
                    break;
                case ALT_RESET:
                    doAltRoute(false);
                    tempCount = count;
                    break;
            }
        } else if (tempCount > 0) {
            tempCount--;
            doNext();
        }
        return true;
    }

    public void reset() {
        tempCount = count;
        clean = true;

        if (next != null)
            next.reset();
        if (altRoute != null)
            altRoute.reset();
    }

    public void setOnZero(String action, boolean altInfinite) {
        onZero = switch (action) {
            case "alt_pass" -> ONZERO.ALT;
            case "stop" -> ONZERO.STOP;
            case "alt_fail" -> ONZERO.FAIL;
            case "alt_reset" -> ONZERO.ALT_RESET;
            default -> ONZERO.FAIL;
        };
        this.altInfinite = altInfinite;
    }

    public String toString() {
        var returning = id().startsWith(next.id());
        if (count == -1) {
            if (returning)
                return telnetId() + " -> Return to " + next.telnetId();
            return telnetId() + " -> Go to " + next.telnetId();
        }

        var otherwise = altRoute == null ? "stop." : "go to " + altRoute.telnetId();
        var result = telnetId() + " -> Count down from " + count + ". If not zero, go to " + next.telnetId() + ". Otherwise, " + otherwise;
        if (returning)
            return result.replace(" go ", " return ");
        return result;
    }
}
