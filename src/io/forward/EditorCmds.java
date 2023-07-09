package io.forward;

import util.xml.XMLfab;

public class EditorCmds {
    private static XMLfab addNode( XMLfab fab, String type, String value, String comment){
        if( !comment.isEmpty())
            fab.comment(comment);
        fab.addChild(type,value);
        return fab;
    }
    public static String addEditor(XMLfab fab, String type, String value ){
        String deli="";
        String[] p = value.split("[|]");
        switch (type) {
            /* Splitting */
            case "rexsplit" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,rexsplit,delimiter|regextomatch";
                if (value.startsWith(",")) {
                    deli = ",";
                    value = value.substring(2);
                } else {
                    int in = value.indexOf(",");
                    deli = value.substring(0, in);
                    value = value.substring(in + 1);
                }
                addNode( fab,type,value,"Find matches on " + value + " then concatenate with " + deli)
                        .attr("type","rexsplit");
                return "Find matches on " + value + " then concatenate with " + deli;
            }
            case "resplit" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,resplit,delimiter|format";
                if (value.startsWith(",")) {
                    deli = ",";
                    value = value.substring(2);
                } else {
                    int in = value.indexOf(",");
                    deli = value.substring(0, in);
                    value = value.substring(in + 1);
                }
                addNode( fab,type,value,"Split on " + deli + " then combine according to " + value)
                        .attr("delimiter", deli)
                        .attr("leftover", "append")
                        .build();
                return "Split on '" + deli + "', then combine according to " + value + " and append leftover data";
            }
            case "charsplit" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,rexplit,regextosplitwith";

                addNode(fab, type,value,"");
                return "Charsplit added with default delimiter";
            }
            /* Timestamp stuff */
            case "redate" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,redate,index|from|to";

                addNode(fab, "redate", p[2], "")
                    .attr("index", p[0])
                    .build();
                return "After splitting on " + deli + " the date on index " + p[0] + " is reformatted from " + p[1] + " to " + p[2];
            }
            case "retime" -> {
                if (value.equals("?"))
                    return "pf:pathid,addeditor,retime,index|from|to";

                addNode(fab, "retime", p[2], "")
                    .attr("index", p[1])
                    .build();
                return "After splitting on , the time on index " + p[1] + " is reformatted from " + p[0] + " to " + p[2];
            }
            /* Replacing */
            case "replace" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,replace,find|replacement";
                addNode( fab, type,p[1],"Replace " + p[0] + " with " + p[1])
                        .attr("find", p[0])
                        .build();
                return "Replacing " +  p[0] + " with " + p[1];
            }
            case "rexreplace" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,rexreplace,regexwhat|replacement";
                addNode( fab, type,value,"RexReplace " + p[0] + " with " + p[1])
                        .attr("find", p[0])
                        .build();
                return "Replacing " + p[0] + " with " + p[1];
            }
            case "replaceindex","indexreplace" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,"+type+",index|replacement";
                addNode( fab, type,value,type+" " + p[0] + " with " + p[1])
                        .attr("find", p[0])
                        .build();
                return "Replacing value at " + p[0] + " with " + p[1];
            }
            /* Remove stuff */
            case "remove" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,remove,find";
                addNode( fab, type,"","Removing " + value+" from data")
                        .attr("find", p[0])
                        .build();
                return "Removing " + value + " from the data";
            }
            case "rexremove" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,rexremove,regexfind";
                addNode( fab, type,"","Removing " + value+" from data")
                        .attr("find", p[0])
                        .build();
                return "Removing matches of " + value + " from the data";
            }
            case "trim" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,trim";
                addNode( fab, type,"","Trimming data")
                        .build();
                return "Trimming spaces from data";
            }
            case "cutstart" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,cutstart,charcount";
                addNode( fab, type,value,"Cutting "+value+" chars from the start")
                    .build();
                return "Cutting " + value + " char(s) from the start";
            }
            case "cutend" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,cutend:charcount";
                addNode( fab, type,value,"Cutting "+value+" chars from the end")
                        .build();
                return "Cutting " + value + " char(s) from the end";
            }
            case "removeindex" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,removeindex:index";
                addNode( fab, type,value,"Removing item at index "+value)
                        .build();
                return "Removing item at index "+value;
            }
            /* Adding stuff */
            case "prepend", "prefix" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,prepend:toprepend or pf:pathid,addedit,prefix:toprepend";
                addNode( fab, type,value,"Prepending " + value + " to the data")
                        .build();
                return "Prepending " + value + " to the data";
            }
            case "insert" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,insert:position,toinsert";
                var what = value.substring(value.indexOf(",")+1);
                var where = value.substring(0,value.indexOf(","));
                addNode( fab, type,what,"Inserting " + what + " at char " + where + " in the data")
                        .attr("position", where)
                        .build();
                return "Inserting " + value + " at char " + p[0] + " in the data";
            }
            case "append", "suffix" -> {
                if (value.equals("?"))
                    return "pf:pathid,addedit,append:toappend or pf:pathid,addedit,suffix:toappend";
                addNode( fab, type,value,"Appending " + value + "to the data")
                        .build();
                return "Appending " + value + " to the data";
            }
        }
        return "unknown type: "+type;
    }
    private XMLfab addEditNode( XMLfab fab, String type, String value, boolean build){
        fab.addChild("edit",value).attr( "type",type)
                .attr("delimiter",",");
        if( build )
            fab.build();
        return fab;
    }
}
