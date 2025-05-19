package util.drawio;

import org.tinylog.Logger;
import util.xml.XMLdigger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Collectors;

public class DrawioEditor {

    public static void addIds(HashMap<String, String> idMap, Path xml) {

        var list = idMap.entrySet().stream().map(x -> new String[]{x.getValue(), x.getKey()}).collect(Collectors.toCollection(ArrayList::new));
        var dig = XMLdigger.goIn(xml, "mxfile");
        for (var diagram : dig.digOut("diagram")) {
            diagram.digDown("mxGraphModel").digDown("root");
            addIdsInTab(diagram, list);
        }
    }

    public static void addIdsInTab(XMLdigger dig, ArrayList<String[]> idMap) {
        var iterator = idMap.iterator();
        var altered = false;
        while (iterator.hasNext()) {
            var pair = iterator.next();
            if (dig.hasPeek("object", "id", pair[0])) {
                dig.usePeek();
                if (!dig.attr("dcafsid", "").equals(pair[1])) {
                    dig.useEditor().attr("dcafsid", pair[1]);
                    Logger.info("Gave " + pair[1] + " to " + pair[0]);
                    altered = true;
                }
                dig.goUp();
                iterator.remove();
            }
        }
        if (altered)
            dig.useEditor().build();
    }

    public static int addAttributeBatch(Path xml, ArrayList<String[]> prep) {
        var dig = XMLdigger.goIn(xml, "mxfile");

        var node = findObject(dig, prep.get(0)[0]);
        var oldId = prep.get(0)[0];
        int success = 0;
        for (var work : prep) {
            if (!oldId.equals(work[0])) { // Stick around if the id remains the same
                dig.goUp();
                node = findObject(dig, work[0]);
            }
            oldId = work[0];
            if (node == null) {
                Logger.error("Node not found for id: " + work[0]);
                continue;
            }
            node.useEditor().attr(work[1], work[2]);
            success++;
        }
        dig.useEditor().build();
        return success;
    }

    public static boolean addAttribute(Path xml, String id, String attribute, String value) {
        var dig = XMLdigger.goIn(xml, "mxfile");
        var node = findObject(dig, id);
        if (node == null) {
            Logger.error("Node not found for id: " + id);
            return false;
        }
        node.useEditor().attr(attribute, value).build();
        return true;
    }

    private static XMLdigger findObject(XMLdigger dig, String id) {
        for (var diagram : dig.digOut("diagram")) {
            diagram.digDown("mxGraphModel").digDown("root");
            if (diagram.hasPeek("object", "dcafsid", id)) {
                return diagram.usePeek();
            }
        }
        return null;
    }

}
