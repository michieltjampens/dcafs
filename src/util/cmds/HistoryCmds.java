package util.cmds;

import das.Paths;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.LookAndFeel;
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
            return doHelpCmd(html);
        }
        var cmds = request.split(",");
        if( cmds.length < 2 )
            return "! Not enough arguments, need at least 2. Check history:? for options";

        return switch(cmds[0]){
            case "raw" -> {
                var path = workPath.resolve("raw")
                        .resolve(TimeTools.formatNow("yyyy-MM"));
                var regex = TimeTools.formatNow("yyyy-MM-dd") + "_RAW_\\d+.log";
                int lines = cmds.length == 3 ? NumberUtils.toInt(cmds[2]) : 50;
                yield readLogs("24h", cmds[1], path, regex,lines);
            }
            case "info" -> doInfoCmd(cmds);
            case "error", "errors" -> doErrorCmd(cmds);
            default -> "! No such cmd, check history:?";
        };
    }

    private static String doHelpCmd(boolean html) {
        var help = new StringJoiner("\r\n");
        help.add("Commands that read from the raw or log files");
        help.add("Read raw data")
                .add("history:raw,filter<,max> -> Check uncompressed raw files of today for up to max lines containing filter, default max is 50");
        help.add("Read info log")
                .add("history:info,age,period<,filter> -> Get the errors (up to 1k lines) from the past period fe. 10m or 1h etc, with optional contains filter")
                .add("history:info,today<,filter> -> Get the last 1k lines of errors of today, with optional contains filter")
                .add("history:error,day,yyMMdd<,filter> -> Get the last 1k lines of errors of requested day, with optional contains filter");
        help.add("Read error data")
                .add("history:error,age,period -> Get the errors (up to 1k lines) from the past period fe. 10m or 1h etc, with optional contains filter")
                .add("history:error,today<,filter> -> Get the last 1k lines of errors of today, with optional contains filter")
                .add("history:error,day,yyMMdd<,filter> -> Get the last 1k lines of errors of requested day,  with optional contains filter");
        return LookAndFeel.formatHelpCmd(help.toString(), html);
    }

    private static String doInfoCmd(String[] cmds) {
        var workPath = Paths.storage().resolve("logs");
        return switch (cmds[1]) {
            case "age" -> {
                if (cmds.length < 3)
                    yield "! Not enough arguments: history:" + cmds[0] + ",age,period<,filter> fe.5h";
                var filter = cmds.length == 4 ? cmds[3] : "";
                yield readLogs(cmds[2], filter, workPath, "info.log");
            }
            case "today" -> {
                var seconds = TimeTools.secondsSinceMidnight();
                var filter = cmds.length == 3 ? cmds[2] : "";
                yield readLogs(seconds + "s", filter, workPath, "info.log");
            }
            default -> "! No such subcommand: " + cmds[1] + ", options: age,today";
        };
    }

    private static String doErrorCmd(String[] cmds) {
        String day;
        String filter = "";
        var workPath = Paths.storage().resolve("logs");

        return switch (cmds[1]) {
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
                if (cmds.length < 4)
                    yield "! Not enough arguments: history:error,day,yyMMdd<,find>";
                filter = cmds.length == 4 ? cmds[3] : filter;
                yield readLogs("24h", filter, workPath, "errors_" + cmds[2] + ".log");
            }
            default -> "! No such subcommand: " + cmds[1] + ", options: age,day,today";
        };
    }
    private static String readLogs( String period, String filter, Path workPath,String filename ){
        int MAX_ERRORS = 1000;
        return readLogs(period,filter,workPath,filename, MAX_ERRORS);
    }
    private static String readLogs( String period, String filter, Path workPath,String filename, int limit ){

        var list = FileTools.findByFileName( workPath, 1, filename );
        if( list.isEmpty() )
            return "! No such file: "+filename;

        var age = TimeTools.parsePeriodStringToSeconds(period);
        var from = LocalDateTime.now().minus(age, TimeUnit.SECONDS.toChronoUnit());
        var day = DateTimeFormatter.ofPattern("yyMMdd").format(from);

        var join = new StringJoiner("\r\n");
        join.setEmptyValue("! No results");

        for (Path logFile : list) {
            var datePart = day;
            if( filename.length()>13)
                datePart = logFile.getFileName().toString().substring(7, 13); // Get the date part
            if (!NumberUtils.isCreatable(datePart) || datePart.compareTo(day) >= 0) // if the same date or newer
                processLines(logFile, filter, from, limit).forEach(join::add);
        }
        return join.toString();
    }

    private static ArrayList<String> processLines(Path logFile, String filter, LocalDateTime from, int limit) {
        boolean ok = false;
        var data = new ArrayList<String>();
        try (var stream = Files.lines(logFile).filter(l -> l.contains(filter))) {
            for (var line : stream.toList()) {
                String tsPart;
                if (line.startsWith("[")) { // new style
                    tsPart = line.substring(1, line.indexOf("]"));
                } else { // old one
                    var split = line.split(" "); // Line starts with date and time with space between
                    tsPart = split[0] + " " + split[1];
                }
                if (ok) { // No need to check timestamps if previous one was ok
                    data.add(line);
                } else {
                    var ts = TimeTools.parseDateTime(tsPart, "yyyy-MM-dd HH:mm:ss.SSS");
                    if (ts != null && ts.isAfter(from)) { // Check if it's a valid timestamp or if previous lines had one
                        ok = true;
                        data.add(line);
                    }
                }
                if (data.size() > limit)
                    data.remove(0);
            }
        } catch (IOException | SecurityException e) {
            Logger.error(e);
        }
        return data;
    }
}
