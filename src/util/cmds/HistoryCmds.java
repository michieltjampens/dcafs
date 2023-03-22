package util.cmds;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.FileTools;
import util.tools.TimeTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;


public class HistoryCmds {
    public static String replyToCommand(String request, boolean html, Path workPath ) {
        if( request.equals("?")){
            return "";
        }
        var cmds = request.split(",");
        if( cmds.length < 2 )
            return "! Not enough arguments, need at least 2";

        switch(cmds[0]){
            case "raw" -> {
                var path = workPath.resolve("raw")
                        .resolve(TimeTools.formatNow("yyyy-MM"));
                var regex = TimeTools.formatNow("yyyy-MM-dd") + "_RAW_\\d+.log";
                var list = FileTools.findByFileName(path, 1, regex);
                if (list.isEmpty())
                    return "!No file found with regex: " + regex;
                int lines = cmds.length == 3 ? NumberUtils.toInt(cmds[2]) : 25;
                if (lines == -1)
                    lines = Integer.MAX_VALUE;
                var join = new StringJoiner("\r\n");
                var data = new ArrayList<String>();
                try {
                    int finalLines = lines;
                    Files.lines(list.get(0)).filter(line -> line.contains(cmds[1]))
                            .forEach(line -> {
                                data.add(line);
                                if (data.size() > finalLines)
                                    data.remove(0);
                            });
                    data.forEach(join::add);
                } catch (IOException e) {
                    Logger.error(e);
                }
                return join.toString();
            }
            case "error" -> {
                switch (cmds[1]) {
                    case "age":
                        if (cmds.length < 3)
                            return "! Not enough arguments: history:error,age,period fe.5h";
                        var now = LocalDateTime.now();
                        var age = TimeTools.parsePeriodStringToSeconds(cmds[2]);
                        var from = now.minus(age, TimeUnit.SECONDS.toChronoUnit());
                        var day = DateTimeFormatter.ofPattern("yyMMdd").format(from);
                        var data = new ArrayList<String>();


                            var path = workPath.resolve("logs");
                            var list = FileTools.findByFileName(path, 1, "errors_.*" );
                            for( Path p : list){
                                var name = p.getFileName().toString().substring(7,13); // Get the date part
                                var dt = TimeTools.parseDateTime(name,"yyMMdd");
                                if( dt.isAfter(from)|| name.equals(day)){ // if the same date or newer
                                    try {
                                        boolean ok=false;
                                        for( var line : Files.lines(p).toList() ){
                                            var stamp = line.split(" ERROR")[0];
                                            var ts = TimeTools.parseDateTime(stamp,"yyyy-MM-dd HH:mm:ss.SSS");
                                            if( ts==null && ok ) {
                                                data.add(line);
                                            }else if( ts!=null && ts.isAfter(from)){
                                                ok=true;
                                                data.add(line);
                                            }else{
                                                ok=false;
                                            }
                                            if( data.size() > 200 )
                                                data.remove(0);
                                        }
                                    } catch (IOException | SecurityException e) {
                                        Logger.error(e);
                                    }
                                }
                            }
                            var join = new StringJoiner("\r\n");
                            join.setEmptyValue("! Something went wrong");
                            data.forEach(join::add);
                            return join.toString();
                    case "period":
                        return "";
                    default:
                        return "! No such subcommand: "+cmds[1];
                }
            }
        }
        return "unknown command: "+request;
    }
}
