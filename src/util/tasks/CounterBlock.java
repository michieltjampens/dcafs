package util.tasks;

import org.tinylog.Logger;

public class CounterBlock extends AbstractBlock {
    int count = -1;
    int tempCount = -1;
    public CounterBlock(int cnt) {
        tempCount = cnt;
        count = cnt;
    }

    @Override
    boolean start() {
        clean = false;
        if (tempCount == 0) {
            Logger.info(id + " -> Count ran out, executing failure (if any).");
            doFailure();
        } else {
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
        if (failure != null)
            failure.reset();
    }
    public String toString() {
        var returning = id().startsWith(next.id());
        if (count == -1) {
            if (returning)
                return telnetId() + " -> Return to " + next.telnetId();
            return telnetId() + " -> Go to " + next.telnetId();
        }

        var otherwise = failure == null ? "stop." : "go to " + failure.telnetId();
        var result = telnetId() + " -> Count down from " + count + ". If not zero, go to " + next.telnetId() + ". Otherwise, " + otherwise;
        if (returning)
            return result.replace(" go ", " return ");
        return result;
    }
}
