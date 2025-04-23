package io.forward.steps;

import das.Core;
import org.tinylog.Logger;
import util.data.vals.BaseVal;
import util.evalcore.ParseTools;
import worker.Datagram;

import java.math.BigDecimal;
import java.util.ArrayList;

public class CmdStep extends AbstractStep {
    private final ArrayList<BaseVal> vals = new ArrayList<>();
    private final ArrayList<Cmd> cmds = new ArrayList<>();
    private int highestI = -1;
    private final String delimiter;

    public CmdStep(String id, String delimiter) {
        this.delimiter = delimiter;
        this.id = id;
    }

    @Override
    public String takeStep(String data, BigDecimal[] bds) {
        String[] split = data.split(delimiter); // Split the data according to the delimiter

        if (split.length < highestI) {
            Logger.error("Not enough items in received data: " + data);
            return "error";
        }
        for (var cmd : cmds) {
            String alter = cmd.cmd;
            if (!vals.isEmpty()) {
                for (int a = 0; a < vals.size(); a++)
                    alter = alter.replace("{" + a + "}", vals.get(a).asString());
            }
            for (Integer i : ParseTools.extractIreferences(alter)) {
                alter = alter.replace("i" + i, split[i]);
            }
            Core.addToQueue(Datagram.system(alter));
        }
        return doNext(data, bds);
    }

    public void addCmd(String ori, String cmd) {
        cmds.add(new Cmd(ori, cmd));
    }

    public void addRtval(BaseVal val) {
        vals.add(val);
    }

    public void setHighestI(int highI) {
        highestI = Math.max(highI, highestI);
    }

    private static class Cmd {
        String cmd;
        String ori;

        public Cmd(String ori, String cmd) {
            this.cmd = cmd;
            this.ori = ori;
        }
    }
}
