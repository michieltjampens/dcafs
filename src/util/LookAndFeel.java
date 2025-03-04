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

    /**
     * Limits action based on event count.
     * Returns true if only one digit is non-zero (e.g. ,1,2,3...10,20....100,200)
     * @param count The number of attempts,events, etc.
     * @return true if only one digit is non-zero
     */
    public static boolean isNthAttempt(int count) {
        if (count <= 0) {
            return false;
        }
        // Logarithm base 10 gives the number of digits minus one.
        // Example: log10(123) = 2 → 10^2 = 100
        // This calculates the closest lower power of 10 to group attempts (10, 100, 1000, ...).
        // The count is divided by this power, showing the message only on exact multiples.
        int divisor = (int)Math.pow(10, (int)Math.log10(count));
        return count % divisor == 0;
    }
}
