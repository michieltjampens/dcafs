package util.cmds;

import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.FileTools;
import util.tools.TimeTools;

import java.awt.image.TileObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;


public class HistoryCmds {
    private static Integer MAX_ERRORS = 1000;

    public static String replyToCommand(String request, boolean html, Path workPath ) {
        var join = new StringJoiner("\r\n");

        if( request.equals("?")){
            String cyan = html?"": TelnetCodes.TEXT_CYAN;
            String green=html?"":TelnetCodes.TEXT_GREEN;
            String ora = html?"":TelnetCodes.TEXT_ORANGE;
            String reg=html?"":TelnetCodes.TEXT_YELLOW+TelnetCodes.UNDERLINE_OFF;


            join.add(ora+"Commands that read from the raw or log files");
            join.add("").add(cyan+"Read raw data"+reg)
                    .add(green+" history:raw,filter<,max> "+reg+"-> Check uncompressed raw files of today for up to max lines containing filter, default max is 50");
            join.add("").add(cyan+"Read info log"+reg)
                    .add(green+" history:info,age,period<,filter> "+reg+"-> Get the errors (up to 1k lines) from the past period fe. 10m or 1h etc, with optional contains filter")
                    .add(green+" history:info,today<,filter> "+reg+"-> Get the last 1k lines of errors of today, with optional contains filter")
                    .add(green+" history:error,day,yyMMdd<,filter> "+reg+"-> Get the last 1k lines of errors of requested day, with optional contains filter");
            join.add("").add(cyan+"Read error data"+reg)
                    .add(green+" history:error,age,period "+reg+"-> Get the errors (up to 1k lines) from the past period fe. 10m or 1h etc, with optional contains filter")
                    .add(green+" history:error,today<,filter> "+reg+"-> Get the last 1k lines of errors of today, with optional contains filter")
                    .add(green+" history:error,day,yyMMdd<,filter> "+reg+"-> Get the last 1k lines of errors of requested day,  with optional contains filter");
            return join.toString();
        }
        var cmds = request.split(",");
        if( cmds.length < 2 )
            return "! Not enough arguments, need at least 2";

        return switch(cmds[0]){
            case "raw" -> {
                var path = workPath.resolve("raw")
                        .resolve(TimeTools.formatNow("yyyy-MM"));
                var regex = TimeTools.formatNow("yyyy-MM-dd") + "_RAW_\\d+.log";
                int lines = cmds.length == 3 ? NumberUtils.toInt(cmds[2]) : 50;
                yield readLogs("24h", cmds[1], path, regex,lines);
            }
            case "info" -> {
                workPath = workPath.resolve("logs");
                yield switch (cmds[1]) {
                    case "age" -> {
                        if (cmds.length < 3)
                            yield "! Not enough arguments: history:" + cmds[0] + ",age,period<,filter> fe.5h";
                        var filter = cmds.length==4?cmds[3]:"";
                        yield readLogs(cmds[2], filter, workPath, "info.log");
                    }
                    case "today"->{
                        var seconds = TimeTools.secondsSinceMidnight();
                        var filter = cmds.length==3?cmds[2]:"";
                        yield readLogs(seconds + "s",filter, workPath, "info.log");
                    }
                    default -> "! No such subcommand: "+cmds[1]+", options: age,today";
                };
            }
            case "error","errors" -> {
                String day="";
                String filter="";
                workPath = workPath.resolve("logs");

                yield switch (cmds[1]) {
                    case "age" -> {
                        if (cmds.length < 3)
                            yield "! Not enough arguments: history:" + cmds[0] + ",age,period fe.5h";
                        filter = cmds.length == 4 ? cmds[3] : "";
                        yield readLogs(cmds[2], filter, workPath, "errors_.*");
                    }
                    case "today" -> {
                        day = TimeTools.formatNow("yyMMdd");
                        if (cmds.length == 3) {
                            filter = cmds[2];
                            cmds[2] = day;
                        }
                        yield readLogs("24h", filter, workPath, "errors.log");
                    }
                    case "day" -> {
                        if (cmds.length < 4 )
                            yield "! Not enough arguments: history:error,day,yyMMdd<,find>";
                        filter = cmds.length == 4 ? cmds[3] : filter;
                        yield readLogs("24h", filter, workPath, "errors_"+cmds[2]+".log");
                    }
                    default -> "! No such subcommand: "+cmds[1]+", options: age,day,today";
                };
            }
        };
    }
    private static String readLogs( String period, String filter, Path workPath,String filename ){
        return readLogs(period,filter,workPath,filename,MAX_ERRORS);
    }
    private static String readLogs( String period, String filter, Path workPath,String filename, int limit ){
        var join = new StringJoiner("\r\n");

        var age = TimeTools.parsePeriodStringToSeconds(period);
        var from = LocalDateTime.now().minus(age, TimeUnit.SECONDS.toChronoUnit());
        var day = DateTimeFormatter.ofPattern("yyMMdd").format(from);

        var data = new ArrayList<String>();

        var list = FileTools.findByFileName( workPath, 1, filename );
        if( list.isEmpty() )
            return "! No such file: "+filename;

        for( Path p : list){
            var datePart = day;
            if( filename.length()>13)
                datePart = p.getFileName().toString().substring(7,13); // Get the date part
            if( datePart.compareTo(day) >= 0){ // if the same date or newer
                try ( var stream = Files.lines(p).filter(l->l.contains(filter))){
                    boolean ok=false;
                    for( var line : stream.toList() ){
                        String tsPart = "";
                        if( line.startsWith("[")){ // new style
                            tsPart = line.substring(1,line.indexOf("]"));
                        }else{ // old one
                            var split = line.split(" "); // Line starts with date and time with space between
                            tsPart = split[0]+" "+split[1];
                        }

                        if( ok ){ // No need to check timestamps if previous one was ok
                            data.add(line);
                        }else{
                            var ts = TimeTools.parseDateTime(tsPart,"yyyy-MM-dd HH:mm:ss.SSS");
                            if( ts!=null && ts.isAfter(from)){ // Check if it's a valid timestamp or if previous lines had one
                                ok=true;
                                data.add(line);
                            }
                        }
                        if( data.size() > limit )
                            data.remove(0);
                    }
                } catch (IOException | SecurityException e) {
                    Logger.error(e);
                }
            }
        }

        join.setEmptyValue("! Something went wrong");
        data.forEach(join::add);
        return join.toString();
    }
}
