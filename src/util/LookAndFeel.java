package util;

import io.telnet.TelnetCodes;
import util.data.vals.DynamicUnit;
import util.data.vals.RealValSymbiote;

import java.util.StringJoiner;

public class LookAndFeel {
    final static String ERROR_COLOR = TelnetCodes.TEXT_RED;
    final static String INFO_COLOR = TelnetCodes.TEXT_GREEN;
    final static String WARN_COLOR = TelnetCodes.TEXT_ORANGE;

    public static String formatHelpCmd(String lines, boolean html) {
        String magenta = html?"": TelnetCodes.TEXT_MAGENTA;
        String reg = html?"":TelnetCodes.TEXT_DEFAULT;

        var colored = new StringBuilder();
        boolean first=true;
        for( var line : lines.split("\r\n") ){
            if (first) {
                first = false;
                colored.append(magenta).append(line).append(html ? "<br>" : "\r\n").append(reg);
            }else{
                formatHelpLine(line, html, colored);
            }
        }
        return colored.toString();
    }

    public static void formatHelpLine(String line, boolean html, StringBuilder colored) {
        String cyan = html ? "" : TelnetCodes.TEXT_CYAN;
        String green = html ? "" : TelnetCodes.TEXT_GREEN;
        String reg = html ? "" : TelnetCodes.TEXT_DEFAULT;

        if (line.contains(" -> ")) {
            var before = line.substring(0, line.indexOf(" -> "));
            var after = line.substring(line.indexOf(" -> ") + 4);
            colored.append(green).append("   ").append(before).append(reg).append(" -> ").append(after);
        } else if (line.startsWith("-")) {
            colored.append("        ").append(line);
        } else if (line.startsWith(" ")) {
            colored.append(line);
        } else {
            colored.append(html ? "<br>" : "\r\n");
            colored.append(cyan).append(line).append(reg);
        }
        colored.append(html ? "<br>" : "\r\n");
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

    public static String prettyPrintSymbiote(RealValSymbiote symbiote, String prefix, String cut, boolean crop, DynamicUnit du) {
        var join = new StringJoiner("\r\n");
        var underlings = symbiote.getUnderlings();

        if (prefix.isEmpty()) {
            var host = underlings[0];
            String right;
            if (du == null) {
                right = host.asString() + host.unit() + host.getExtraInfo();
            } else {
                right = du.apply(host.value(), host.unit()) + host.getExtraInfo();
            }
            join.add(underlings[0].name() + " : " + right);
            cut = underlings[0].name() + "_";
        }
        // First pass for the width
        var temp = new String[underlings.length - 1];
        int maxLeftLen = 1;
        if (crop) {
            for (int a = 1; a < underlings.length; a++) {
                // Clean name?
                var trimmed = underlings[a].name();
                for (var c : cut.split("_")) {
                    trimmed = trimmed.replace(c + "_", "");
                }
                temp[a - 1] = trimmed;
                maxLeftLen = Math.max(trimmed.length(), maxLeftLen);
            }
        }
        for (int a = 1; a < underlings.length; a++) {
            var ling = underlings[a];
            // spaced name?
            String right = "";
            if (du != null) {
                right = du.apply(ling.value(), ling.unit());
            } else {
                right = ling.asString() + ling.unit();
            }
            right += ling.getExtraInfo();
            String print;
            if (crop) {
                print = String.format("%-" + maxLeftLen + "s : %s", temp[a - 1], right);
            } else {
                print = underlings[a].name() + " : " + right;
            }
            join.add(prefix + (a == underlings.length - 1 ? "└── " : "├── ") + print);
            if (underlings[a] instanceof RealValSymbiote sym) {
                join.add(prettyPrintSymbiote(sym, a == underlings.length - 1 ? "    " : "│   ", sym.name() + "_", crop, du));
            }
        }
        return join.toString();
    }
}
