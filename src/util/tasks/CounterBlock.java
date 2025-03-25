package util.tasks;

public class CounterBlock extends AbstractBlock {
    int count = -1;

    public CounterBlock(int cnt) {
        this.count = cnt;
    }

    @Override
    boolean start() {
        if (count == 0) {
            doFailure();
        } else {
            count--;
            doNext();
        }
        return true;
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
