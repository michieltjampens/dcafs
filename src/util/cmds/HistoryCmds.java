package util.cmds;

import io.telnet.TelnetCodes;
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
                    .add(green+" history:raw,find<,max> "+reg+"-> Check raw file of today for up to max lines containing 'find', default is 50");
            join.add("").add(cyan+"Read error data"+reg)
                    .add(green+" history:error,age,period "+reg+"-> Get the errors (up to 1k lines) from the past period fe. 10m or 1h etc ")
                    .add(green+" history:error,today "+reg+"-> Get the last 1k lines of errors of today")
                    .add(green+" history:error,day,yyMMdd "+reg+"-> Get the last 1k lines of errors of requested day");
            return join.toString();
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
                int lines = cmds.length == 3 ? NumberUtils.toInt(cmds[2]) : 50;
                if (lines == -1)
                    lines = Integer.MAX_VALUE;

                var data = new ArrayList<String>();
                for( var file : list ){
                    try ( var stream = Files.lines(file)){
                        int finalLines = lines;
                        stream.filter(line -> line.contains(cmds[1]))
                                .forEach(line -> {
                                    data.add(line);
                                    if (data.size() > finalLines)
                                        data.remove(0);
                                });
                        data.forEach(join::add);
                    } catch (IOException e) {
                        Logger.error(e);
                    }
                }

                return join.toString();
            }
            case "error","errors" -> {

                String day="";
                switch (cmds[1]) {
                    case "age":
                        if (cmds.length < 3)
                            return "! Not enough arguments: history:error,age,period fe.5h";

                        var age = TimeTools.parsePeriodStringToSeconds(cmds[2]);
                        var from = LocalDateTime.now().minus(age, TimeUnit.SECONDS.toChronoUnit());
                        day = DateTimeFormatter.ofPattern("yyMMdd").format(from);

                        var data = new ArrayList<String>();

                        var list = FileTools.findByFileName( workPath.resolve("logs"), 1, "errors_.*" );
                        for( Path p : list){
                            var datePart = p.getFileName().toString().substring(7,13); // Get the date part
                            if( datePart.compareTo(day) >= 0){ // if the same date or newer
                                try ( var stream = Files.lines(p)){
                                    boolean ok=false;
                                    for( var line : stream.toList() ){
                                        var split = line.split(" "); // Line starts with date and time with space between
                                        if(split.length==1) {
                                            data.add(line);
                                            continue;
                                        }
                                        var ts = TimeTools.parseDateTime(split[0]+" "+split[1],"yyyy-MM-dd HH:mm:ss.SSS");
                                        if( ts==null && ok ) {
                                            data.add(line);
                                        }else{
                                            ok = ts!=null && ts.isAfter(from);
                                            if(ok)
                                                data.add(line);
                                        }
                                        if( data.size() > MAX_ERRORS )
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
                    case "today":
                        day = TimeTools.formatNow("yyMMdd");
                    case "day":
                        if( cmds.length!=3 && day.isEmpty())
                            return "! Not enough arguments: history:error,day,yyMMdd";
                        var raw = workPath.resolve("logs").resolve("errors_"+(cmds.length==3?cmds[2]:day)+".log");
                        if(Files.notExists(raw))
                            return "! No such file: "+raw.getFileName();
                        long total = FileTools.getLineCount(raw);// Get the amount of lines in the file
                        try (var coll = Files.lines(raw) ){
                            if( total > MAX_ERRORS) { // If the file has more than 1k lines
                                coll.skip(total - MAX_ERRORS).forEach(join::add);; //Skip so we only read last 1k
                            }else{
                                coll.forEach(join::add); // write to stringjoiner
                            }
                            join.setEmptyValue("No errors yet (somehow)");
                            return join.toString();
                        } catch (IOException | SecurityException e) {
                            Logger.error(e);
                        }
                    case "period":
                        return "! Todo";
                    default:
                        return "! No such subcommand: "+cmds[1];
                }
            }
        }
        return "unknown command: "+request;
    }
}
