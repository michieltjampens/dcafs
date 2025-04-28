package util.drawio;

import io.netty.channel.EventLoopGroup;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.xml.XMLdigger;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class Drawio {

    public record Arrow(DrawioCell source, String label, DrawioCell target) {
    }

    private static List<String> drawIoAttr = List.of("id", "label", "placeholders", "style");
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Map<String, String> ENTITY_MAP = Map.of(
            "&gt;", ">",
            "&lt;", "<",
            "&amp;", "&",
            "&quot;", "\"",
            "&apos;", "'",
            "<div>", "#nl#"
    );


    Path path;

    public Drawio(Path path, EventLoopGroup eventLoop) {

        this.path = path;
        var cells = parseFile(path);


        Logger.info("Result: " + cells.size());
    }

    public static HashMap<String, DrawioCell> parseFile(Path path) {
        HashMap<String, DrawioCell> cells = new HashMap<>();
        var dig = XMLdigger.goIn(path, "mxfile");
        for (var diagram : dig.digOut("diagram")) {
            diagram.digDown("mxGraphModel").digDown("root");
            parseTab(diagram, cells);
        }
        return cells;
    }

    public static HashMap<String, DrawioCell> parseTab(XMLdigger diagram, HashMap<String, DrawioCell> cells) {

        for (var obj : diagram.digOut("object")) {
            var drawId = obj.attr("id", "");
            parseObject(obj).ifPresent(ob -> cells.put(drawId, ob));
        }
        Logger.info("Found " + cells.size() + " valid shapes.");
        diagram.goUp();

        // Start looking for arrows
        HashMap<String, Arrow> arrows = new HashMap<>();
        var mxCells = diagram.digOut("mxCell");
        Iterator<XMLdigger> it = mxCells.iterator();
        while (it.hasNext()) {
            var mxcell = it.next();
            if (mxcell.attr("parent", -1) != 1)
                continue;
            var drawId = mxcell.attr("id", "");

            if (drawId.equals("0") || drawId.equals("1"))
                continue;

            var style = mxcell.attr("style", "");
            var source = mxcell.attr("source", "");
            var target = mxcell.attr("target", "");

            var tg = cells.get(target);
            var src = cells.get(source);
            var label = mxcell.attr("value", "");

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
            it.remove();
        }
        Logger.info("Found " + arrows.size() + " without label.");
        // Now find the missing labels in the leftover
        for (XMLdigger mxcell : mxCells) {
            var drawId = mxcell.attr("id", "");
            if (mxcell.attr("parent", -1) != -1)
                continue;
            var parent = mxcell.attr("parent", "");
            if (parent.isEmpty())
                continue;
            var arrow = arrows.get(parent);
            if (arrow != null) {
                var label = mxcell.attr("value", "");
                arrow.source.addArrow(label, arrow.target);
                arrows.remove(parent);
            }
            if (arrows.isEmpty())
                break;
        }

        Logger.info("Still " + arrows.size() + " left?");
        return cells;
    }

    private static Optional<DrawioCell> parseObject(XMLdigger obj) {
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

        return input.replace("#nl#", "\r\n").toLowerCase();
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
            value = clean(value);
            params.put(key.toLowerCase(), value);
        }

        public void addArrow(String label, DrawioCell target) {
            if (arrows.get(label) != null)
                Logger.warn("Overwriting arrow with label " + label);
            label = clean(label);
            arrows.put(label.split("\\|", 2)[0], target);
        }

        public String getParam(String param, String def) {
            var res = this.params.get(param);
            return res != null ? res : def;
        }

        public int getParam(String param, int def) {
            var res = this.params.get(param);
            return res != null ? NumberUtils.toInt(res, def) : def;
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

        public boolean hasArrows() {
            return !arrows.isEmpty();
        }
    }

}
