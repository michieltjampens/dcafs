package util;

import io.telnet.TelnetCodes;

import java.util.StringJoiner;

public class LookAndFeel {

    public static String formatCmdHelp(String lines, boolean html ){

        String magenta = html?"": TelnetCodes.TEXT_MAGENTA;
        String cyan = html?"":TelnetCodes.TEXT_CYAN;
        String green = html?"":TelnetCodes.TEXT_GREEN;
        String reg = html?"":TelnetCodes.TEXT_DEFAULT;

        var colored = new StringJoiner("\r\n" );
        boolean first=true;
        for( var line : lines.split("\r\n") ){
            if( line.contains(" -> ") ){
                var before = line.substring(0,line.indexOf(" -> "));
                var after = line.substring(line.indexOf(" -> ")+4);
                colored.add(green).add("   ").add(before).add(reg).add(after);
            }else{
                colored.add(first?magenta:cyan).add(line).add(reg);
                first=false;
            }
        }
        return colored.toString();
    }
}
