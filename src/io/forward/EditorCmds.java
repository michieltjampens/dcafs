package io.forward;

import util.xml.XMLfab;

public class EditorCmds {

    public static String addEditor(XMLfab fab, String type, String value) {

        NodeAdder node = NodeAdder.withFab(fab).value(value).type(type);

        String[] p = value.split("[|]");
        return switch (type) {
            /* Splitting */
            case "rexsplit", "resplit" -> doSplit(fab, type, value);
            case "charsplit" -> node.args("regextosplitwith")
                    .comment("Charsplit added with default delimiter").build();
            /* Timestamp stuff */
            case "redate" -> node.args("index|from|to").value(p[2]).attr("index", p[0])
                    .comment("The date on index " + p[0] + " is reformatted from " + p[1] + " to " + p[2])
                    .build();
            case "retime" -> node.args("index|from|to").value(p[2]).attr("index", p[1])
                    .comment("Time on index " + p[1] + " is reformatted from " + p[0] + " to " + p[2])
                    .build();
            /* Replacing */
            case "replace" -> node.args("find|replacement")
                    .value(p[1]).attr("find", p[0])
                    .comment("Replace " + p[0] + " with " + p[1]).build();
            case "rexreplace" -> node.args("regexwhat|replacement")
                    .attr("find", p[0])
                    .comment("Replacing " + p[0] + " with " + p[1]).build();
            case "replaceindex", "indexreplace" ->
                    node.args("index|replacement").comment("Replacing value at " + p[0] + " with " + p[1]).build();
            /* Remove stuff */
            case "remove" ->
                    node.args("find").value("").attr("find", p[0]).comment("Removing " + value + " from the data").build();
            case "rexremove" ->
                    node.args("regexfind").value("").attr("find", p[0]).comment("Removing " + value + " from data").build();
            case "trim" -> node.value("").comment("Trimming spaces from data").build();
            case "cutstart" -> node.args("charcount").comment("Cutting " + value + " chars from the start").build();
            case "cutend" -> node.args("charcount").comment("Cutting " + value + " chars from the end").build();

            case "removeindex" -> node.args("index").comment("Removing item at index " + value).build();
            /* Adding stuff */
            case "prepend", "prefix" ->
                    node.args(type + ":toprepend").comment("Prepending " + value + " to the data").build();
            case "insert" -> node.args(":position,toinsert")
                    .value(value.substring(value.indexOf(",") + 1))
                    .attr("position", value.substring(0, value.indexOf(",")))
                    .comment("Inserting " + node.value() + " at char " + node.attrValue() + " in the data").build();
            case "append", "suffix" ->
                    node.args(type + ":toappend").comment("Appending " + value + " to the data").build();
            default -> "Unknown type " + type;
        };
    }


    private static String doSplit( XMLfab fab, String type, String value ){
        String deli;
        if (value.startsWith(",")) {
            deli = ",";
            value = value.substring(2);
        } else {
            int in = value.indexOf(",");
            deli = value.substring(0, in);
            value = value.substring(in + 1);
        }
        String comment="";
        if (type.equals("rexsplit") || type.equals("regexsplit")) {
            comment="Find matches on " + value + " then concatenate with " + deli;
        }else if( type.equals("resplit")){
            comment="Split on " + deli + " then combine according to " + value;
        }
        fab.comment(comment);
        fab.addChild(type,value);
        if (type.equals("rexsplit") || type.equals("regexsplit")) {
            fab.attr("type","rexsplit");
        }else if( type.equals("resplit")){
            fab.attr("delimiter", deli)
               .attr("leftover", "append");
        }
        fab.build();
        return comment;
    }
    static class NodeAdder {
        private final XMLfab fab;
        private String type;
        private String value;
        private String args = "";
        private String comment;
        private String[] attr;

        public NodeAdder(XMLfab fab) {
            this.fab = fab;
        }

        public static NodeAdder withFab(XMLfab fab) {
            return new NodeAdder(fab);
        }

        public NodeAdder type(String type) {
            this.type = type;
            return this;
        }

        public NodeAdder value(String value) {
            this.value = value;
            return this;
        }

        public String value() {
            return value;
        }

        public NodeAdder args(String args) {
            this.args = args;
            return this;
        }

        public NodeAdder comment(String comment) {
            this.comment = comment;
            return this;
        }

        public NodeAdder attr(String att, String val) {
            attr = new String[]{att, val};
            return this;
        }

        public String attrValue() {
            return attr[1];
        }

        public String build() {
            if (value.equals("?"))
                return "pf:pathid,addedit," + type + (args.isEmpty() ? "" : ",") + args;

            if (!comment.isEmpty())
                fab.comment(comment);
            fab.addChild(type, value);
            if (attr != null)
                fab.attr(attr[0], attr[1]);
            fab.build();
            return comment;
        }
    }
}