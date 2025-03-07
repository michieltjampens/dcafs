package util;

import io.telnet.TelnetCodes;

import java.util.StringJoiner;

public class LookAndFeel {
    final static String ERROR_COLOR = TelnetCodes.TEXT_RED;
    final static String INFO_COLOR = TelnetCodes.TEXT_GREEN;
    final static String WARN_COLOR = TelnetCodes.TEXT_ORANGE;

    public static String formatCmdHelp(String lines, boolean html ){

        String magenta = html?"": TelnetCodes.TEXT_MAGENTA;
        String cyan = html?"":TelnetCodes.TEXT_CYAN;
        String green = html?"":TelnetCodes.TEXT_GREEN;
        String reg = html?"":TelnetCodes.TEXT_DEFAULT;

        var colored = new StringBuilder();
        boolean first=true;
        for( var line : lines.split("\r\n") ){
            if( line.contains(" -> ") ){
                var before = line.substring(0,line.indexOf(" -> "));
                var after = line.substring(line.indexOf(" -> ")+4);
                colored.append(green).append("   ").append(before).append(reg).append(" -> ").append(after);
            }else if( line.startsWith("-")) {
                colored.append("        ").append(line);
            }else{
                if( !first )
                    colored.append(html?"<br>":"\r\n");
                colored.append(first?magenta:cyan).append(line).append(reg);
                first=false;
            }
            colored.append(html?"<br>":"\r\n");
        }
        return colored.toString();
    }
    /**
     * Format a portion of text for the status report, applying color is an error is marked with !!
     * @param lines The lines to format
     * @param report This holds the rest of  the status
     * @param html Whether formatting is html or not
     */
    public static void formatStatusText(String lines , StringBuilder report, boolean html ){
        final String TEXT_DEFAULT = html?"":TelnetCodes.TEXT_DEFAULT;
        final String TEXT_ERROR = html?"":ERROR_COLOR;
        final String TEXT_WARN = html?"":WARN_COLOR;

        for (String line : lines.split("\r\n") ) {
            if (line.startsWith("!!")) {
                report.append(TEXT_ERROR);
            }else if (line.startsWith("(NC)")) {
                report.append(TEXT_WARN);
            }
            report.append(line).append(TEXT_DEFAULT);
            report.append(html ? "<br>" : "\r\n");
        }
    }
    /**
     * Format a title for the status report
     * @param title The title to format
     * @param html Whether to use html
     * @return The formatted title
     */
    public static String formatStatusTitle(String title, boolean html ){
        if (html) {
            return "<br><b>"+title+"</b><br>";
        }
        // If telnet
        return TelnetCodes.TEXT_CYAN+"\r\n"+title+"\r\n"+TelnetCodes.TEXT_DEFAULT+TelnetCodes.UNDERLINE_OFF;
    }
    /**
     * Formats a line that contains the sequence subject:value
     * @param line The line to format
     * @param report The builder to write it to
     * @param html Whether formatting is html
     */
    public static void formatSplitStatusText( String line, StringBuilder report, String delimit, boolean html ){
        final String TEXT_DEFAULT = html?"":TelnetCodes.TEXT_DEFAULT;
        final String TEXT_ERROR = html?"":ERROR_COLOR;
        final String TEXT_INFO = html?"":INFO_COLOR;
        final String TEXT_WARN = html?"":WARN_COLOR;

        int index = line.indexOf(delimit)+delimit.length();
        var before = line.substring( 0, index );
        var after = line.substring( index );

        report.append(TEXT_DEFAULT).append(before);

        if (line.startsWith("!!")) {
            report.append(TEXT_ERROR);
        }else if (line.startsWith("(NC)")) {
            report.append(TEXT_WARN);
        }else{
            report.append(TEXT_INFO);
        }
        report.append(after).append(TEXT_DEFAULT);
        report.append(html ? "<br>" : "\r\n");
    }

    /**
     * Format a split status text that uses a ':' for splitting
     * @param line The line with the data
     * @param report The rest of the report
     * @param html Whether to use html formating or not (and thus telnet)
     */
    public static void formatSplitStatusText( String line, StringBuilder report, boolean html ){
        formatSplitStatusText(line,report,":",html);
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
