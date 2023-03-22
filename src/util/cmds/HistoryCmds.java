package util.cmds;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.FileTools;
import util.tools.TimeTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.StringJoiner;


public class HistoryCmds {
    public static String replyToCommand(String request, boolean html, Path workPath ) {
        if( request.equals("?")){
            return "";
        }
        var cmds = request.split(",");
        if( cmds.length < 2 )
            return "! Not enough arguments, need at least 2";

        switch(cmds[0]){
            case "raw":
                var path = workPath.resolve("raw")
                        .resolve(TimeTools.formatNow("yyyy-MM"));
                var regex = TimeTools.formatNow("yyyy-MM-dd")+"_RAW_\\d+.log";
                var list = FileTools.findByFileName(path,1,regex);
                if( list.isEmpty())
                    return "!No file found with regex: "+regex;
                int lines = cmds.length==3? NumberUtils.toInt(cmds[2]):25;
                if( lines==-1)
                    lines=Integer.MAX_VALUE;
                var join = new StringJoiner("\r\n");
                var data = new ArrayList<String>();
                try {
                    int finalLines = lines;
                    Files.lines(list.get(0)).filter(line -> line.contains(cmds[1]))
                            .forEach(line ->{
                                data.add(line);
                                if( data.size()> finalLines)
                                    data.remove(0);
                            });
                    data.forEach(join::add);
                }catch( IOException e){
                    Logger.error(e);
                }
                return join.toString();

        }
        return "unknown command: "+request;
    }
}
