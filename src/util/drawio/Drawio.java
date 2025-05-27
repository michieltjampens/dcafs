package util.drawio;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class Drawio {

    public record Arrow(DrawioCell source, String label, DrawioCell target) {
    }

    private static final List<String> drawIoAttr = List.of("id", "label", "placeholders", "style");
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]{0,4}>");
    private static final Map<String, String> ENTITY_MAP = Map.of(
            "&gt;", ">",
            "&lt;", "<",
            "&amp;", "&",
            "&quot;", "\"",
            "&apos;", "'",
            "<div>", "#nl#"
    );

    public static HashMap<String, DrawioCell> parseFile(Path path) {
        Logger.info("Parsing " + path.getFileName() + " to DrawioCells");
        HashMap<String, DrawioCell> cells = new HashMap<>();
        var dig = XMLdigger.goIn(path, "mxfile");
        for (var diagram : dig.digOut("diagram")) {
            var tabName = diagram.attr("name", "");
            diagram.digDown("mxGraphModel").digDown("root");
            parseTab(tabName, diagram, cells);
        }
        return cells;
    }

    public static HashMap<String, DrawioCell> parseTab(String tabName, XMLdigger diagram, HashMap<String, DrawioCell> cells) {
        int cellSize = cells.size();
        for (var obj : diagram.digOut("object")) {
            var drawId = obj.attr("id", "");
            parseShapeObjects(obj).ifPresent(ob -> cells.put(drawId, ob));
        }
        diagram.goUp();
        cellSize = cells.size() - cellSize;

        // Start looking for arrows
        HashMap<String, Arrow> arrows = new HashMap<>();
        for (var obj : diagram.digOut("object")) {
            if (!obj.attr("dcafstype", "").isEmpty())
                continue;
            if (obj.attr("dcafslabel", "").isEmpty())
                continue;
            var label = obj.attr("label", "");
            if (label.isEmpty() || label.startsWith("%"))
                label = obj.attr("dcafslabel", label);
            if (obj.hasPeek("mxCell")) {
                processArrow(obj.digDown("mxCell"), label, cells, arrows);
                obj.goUp();
            }
        }
        diagram.goUp();
        var mxCells = diagram.digOut("mxCell");
        Iterator<XMLdigger> it = mxCells.iterator(); // Use iterator so we can remove used nodes
        while (it.hasNext()) {
            var mxcell = it.next();
            if (mxcell.attr("parent", -1) != 1)
                continue;
            processArrow(mxcell, "", cells, arrows);
            it.remove();
        }
        int arrowCount = arrows.size();
        // Now find the missing labels in the leftover
        for (XMLdigger mxcell : mxCells) {
            if (mxcell.attr("parent", -1) != -1)
                continue;
            var parent = mxcell.attr("parent", "");
            if (parent.isEmpty())
                continue;
            var arrow = arrows.get(parent);
            if (arrow != null) {
                var label = mxcell.attr("value", "");
                arrow.source.addArrow(label, arrow.target);
                arrows.remove(parent); // remove it from arrows because processed
            }
            if (arrows.isEmpty())
                break;
        }
        // Check the leftovers if they have a block as target and source but no label, if so change label to 'next'
        var ars = arrows.values().iterator();
        while (ars.hasNext()) {
            var arrow = ars.next();
            if (arrow.source != null && arrow.target != null) {
                if (arrow.target.type.endsWith("block")) {
                    if (!arrow.source.hasArrows()) {
                        arrow.source.addArrow("next", arrow.target);
                        Logger.info("Adding blank arrow to " + arrow.source.type + " with default label of next.");
                        ars.remove();
                    }
                }
            }
        }
        Logger.info(tabName + " -> Found " + cellSize + " valid shapes, " + arrowCount
                + " arrows of which " + arrows.size() + " are left after parsing (probably without label).");
        return cells;
    }

    private static void processArrow(XMLdigger mxcell, String objLabel, HashMap<String, DrawioCell> cells, HashMap<String, Arrow> arrows) {
        var drawId = mxcell.attr("id", "");

        if (drawId.equals("0") || drawId.equals("1"))
            return;

        var style = mxcell.attr("style", "");
        var source = mxcell.attr("source", "");
        var target = mxcell.attr("target", "");

        var tg = cells.get(target);
        var src = cells.get(source);
        var label = mxcell.attr("value", objLabel);

        if (style.startsWith("endArrow")) {
            if (src != null && target != null && !label.isEmpty()) {
                src.addArrow(label, tg);
            } else {
                arrows.put(drawId, new Arrow(src, label, tg));
            }
        } else if (src != null) {
            if (label.isEmpty()) {
                arrows.put(drawId, new Arrow(src, label, tg));
            } else {
                src.addArrow(label, tg);
            }
        }
    }

    private static Optional<DrawioCell> parseShapeObjects(XMLdigger obj) {
        var drawId = obj.attr("id", "");
        var dasType = obj.attr("dcafstype", "");

        if (!dasType.isEmpty()) { // To know it's a parent node?
            var dasId = obj.attr("dcafsid", "");
            var cell = new DrawioCell(drawId, dasId, dasType);
            for (var attr : obj.allAttr().split(",")) {
                if (!drawIoAttr.contains(attr))
                    cell.addParam(attr, obj.attr(attr, ""));
            }
            return Optional.of(cell);
        }
        return Optional.empty();
    }

    public static String clean(String input) {
        if (input == null) return null;

        if (input.isEmpty())
            return "";

        // Decode basic HTML entities
        for (Map.Entry<String, String> entry : ENTITY_MAP.entrySet()) {
            input = input.replace(entry.getKey(), entry.getValue());
        }
        // Remove HTML tags
        input = TAG_PATTERN.matcher(input).replaceAll("");

        return input.replace("#nl#", "\r\n");
    }

    public static class DrawioCell {
        String drawId;
        String type;
        String dasId;

        HashMap<String, String> params = new HashMap<>();
        HashMap<String, DrawioCell> arrows = new HashMap<>();

        public DrawioCell(String drawId, String dasId, String type) {
            this.drawId = drawId;
            this.dasId = dasId;
            this.type = type;
        }

        public void addParam(String key, String value) {
            params.put(key.toLowerCase(), clean(value));
        }

        public void addArrow(String label, DrawioCell target) {
            if (arrows.get(label) != null && !label.startsWith("derive"))
                Logger.warn("Overwriting arrow with label " + label);
            label = clean(label).toLowerCase();
            label = label.split("\\|", 2)[0];
            if (label.equals("derive")) {
                while (arrows.containsKey(label)) {
                    label += "+";
                }
            }
            arrows.put(label, target);
        }

        public String getParam(String param, String def) {
            var res = this.params.get(param);
            return res != null ? res : def;
        }

        public double getParam(String param, double def) {
            var res = this.params.get(param);
            return res != null ? NumberUtils.toDouble(res, def) : def;
        }
        public int getParam(String param, int def) {
            var res = this.params.get(param);
            return res != null ? NumberUtils.toInt(res, def) : def;
        }

        public boolean getParam(String param, boolean def) {
            var res = this.params.get(param);
            return res != null ? Tools.parseBool(res, def) : def;
        }
        public boolean hasParam(String param) {
            return params.containsKey(param) && !params.get(param).isEmpty();
        }

        public boolean hasArrow(String label) {
            if (arrows.containsKey(label.split("\\|", 2)[0])) {
                return arrows.get(label) != null;
            }
            return false;
        }

        public DrawioCell getArrowTarget(String label) {
            return arrows.get(label);
        }

        public DrawioCell getArrowTarget(String... labels) {
            for (var label : labels) {
                var arrow = arrows.get(label);
                if (arrow != null)
                    return arrow;
            }
            return null;
        }

        public String getArrowLabels() {
            return String.join(",", arrows.keySet());
        }
        public boolean hasArrows() {
            return !arrows.isEmpty();
        }
    }

}
