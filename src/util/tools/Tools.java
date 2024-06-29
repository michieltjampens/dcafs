package util.tools;

import com.fazecast.jSerialComm.SerialPort;
import org.tinylog.Logger;
import util.gis.GisTools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A collection of various often used small methods that do a variety of useful
 * things
 * 
 * @author Michiel T'Jampens
 */
public class Tools {

    /* ********************************* D O U B L E ********************************************* */
    /**
     * More robust way of parsing strings to double than the standard
     * Double.parseDouble method and return a chosen value on error Removes: space
     * ',' '\n' '\r'
     * 
     * @param number The string double to parse
     * @param error  The double to return if something went wrong
     * @return The parsed double if successful or the chosen error value.
     */
    public static double parseDouble(String number, double error) {

        if (number == null)
            return error;

        number = number.trim().replace(",", ".").replace("\n", "").replace("\r", "");

        if (number.isBlank()) {
            return error;
        }
        try {
            return Double.parseDouble(number);
        } catch (NumberFormatException e) {
            return error;
        }
    }

    /**
     * Rounds a double to a certain amount of digits after the comma
     * 
     * @param r            The double to round
     * @param decimalPlace The amount of digits
     * @return The rounded double
     */
    public static double roundDouble(double r, int decimalPlace) {
        if (Double.isInfinite(r) || Double.isNaN(r) || decimalPlace < 0)
            return r;
        BigDecimal bd = BigDecimal.valueOf(r);
        return bd.setScale(decimalPlace, RoundingMode.HALF_UP).doubleValue();
    }
    /* ******************************* I N T E G E R  ************************************************************ */
    /**
     * More robust way of parsing strings to integer than the standard
     * Integer.parseInteger method and return a chosen value on error Removes: space
     * ',' '\n' '\r'
     * 
     * @param number The string integer to parse. If starts with 0x, it's considered
     *               hex
     * @param error  The integer to return if something went wrong
     * @return The parsed integer if successful or the chosen error value.
     */
    public static int parseInt(String number, int error) {
        try {
            number = number.trim().replace("\n", "").replace("\r", "").replace(";", "");

            if (number.startsWith("0x")) {
                return Integer.parseInt(number.substring(2), 16);
            }
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return error;
        }
    }

    /**
     * Check if the given string is parsable as a bool.
     * @param value The value to check.
     * @return True if it's a valid bool.
     */
    public static boolean validBool( String value ){
        var valid = new String[]{"yes", "no", "true", "false", "1", "0"};
        return Arrays.asList(valid).contains(value.toLowerCase().trim());
    }

    /**
     * Parses a value to a boolean, returning the error value if conversion fails.
     *
     * @param value The value to parse.
     * @param error The boolean value to return on error.
     * @return The parsed value or error if failed.
     */
    public static boolean parseBool( String value, boolean error){
        value=value.toLowerCase().trim();
        if( value.equals("yes")||value.equals("true")||value.equals("1")||value.equals("on")||value.equals("high"))
            return true;
        if( value.equals("no")||value.equals("false")||value.equals("0")||value.equals("off")||value.equals("low"))
            return false;
        if( !value.isEmpty())
            Logger.warn("No valid value received to convert to bool: "+value);
        return error;
    }

    /**
     * Convert a signed byte to an unsigned integer.
     * @param val The byte to convert
     * @return The unsigned equivalent of the given value.
     */
    public static int toUnsigned(byte val) {
        int a = val;
        return a < 0 ? a + 256 : a;
    }
    public static int toUnsignedWord(int val) {
        return val < 0 ? val + 65536 : val;
    }
    /**
     * Adds zeros to the front of an integer till it has the specified length
     * @param ori the string to alter
     * @param length the requested length
     * @return ori with spaces added to math the length (if ori was shorter)
     */
    public static String addTrailingSpaces(String ori, int length) {
        StringBuilder res = new StringBuilder(ori);
        while (res.length() < length)
            res.append(" ");
        return res.toString();
    }
    /* ************************************** S T R I N G ******************************************************** */

    /**
     * Check if the end of the string is a null.
     * @param txt The string to check.
     * @return True if the last character is null.
     */
    public static boolean isNullEnded( String txt ){
        var bytes = txt.getBytes();
        if( bytes.length==0)
            return false;
        return bytes[bytes.length-1]==0;
    }
   /**
	 * Convert the descriptive name of the delimiter to the actual findable string
	 * @param delimiter The descriptive name
	 * @return The findable version of the descriptive name
	 */
    public static String getDelimiterString( String delimiter ){
        delimiter = delimiter.replace("cr","\r")
                             .replace("lf","\n")
                             .replace("tab","\t")
                             .replace("nextion","\\x7F\\x7F\\x7F");
        return fromEscapedStringToBytes(delimiter);
	}
    /* ************************** * H E X A D E C I M A L ********************************************************* */
    /**
     * Converts a array of bytes to a space separated string of hexadecimals (0x00
     * 0x01)
     * 
     * @param data The array to parse
     * @return The hex string
     */
    public static String fromBytesToHexString(byte[] data) {
        if (data == null)
            return "";
        return fromBytesToHexString(data, 0, data.length);
    }

    /**
     * Converts a part of an array of characters to a space separated string of
     * hexadecimals (0x00 0x01), can work MSB->LSB and LSB->MSB
     * 
     * @param data   The array to parse
     * @param offset Start index
     * @param length Amount of bytes from the start to convert, negative means LSB first
     * @return The hex string
     */
    public static String fromBytesToHexString(byte[] data, int offset, int length) {
        if (data == null)
            return "";

        StringJoiner join = new StringJoiner(" 0x", "0x", "");
        for (int x = offset; (length>0?x<offset+length:x>offset+length) && (length>0?x<data.length:x>-1); x+=(length>0?1:-1)) {
            String hex = Integer.toHexString(data[x]).toUpperCase();
            if (hex.length() > 2) {
                hex = hex.substring(hex.length() - 2);
            }
            join.add((hex.length() == 1 ? "0" : "") + hex);
        }
        return join.toString();
    }

    /**
     * Converts a delimited string of hexes to a byte array
     * 
     * @param line The delimited line (will split on space, komma and semicolon)
     * @return The resulting array
     */
    public static byte[] fromHexStringToBytes(String line) {

        line = line.toLowerCase().replace("0x", "");

        byte[] result = Tools.fromBaseToBytes(16, Tools.splitList(line));
        if (result.length == 0) {
            Logger.error("Failed to convert " + line);
        }
        return result;
    }

    /**
     * Converts a delimited string of decimals to a byte array
     * 
     * @param line The delimited line (will split on space, komma and semicolon)
     * @return The resulting array
     */
    public static byte[] fromDecStringToBytes(String line) {
        byte[] result = Tools.fromBaseToBytes(10, Tools.splitList(line));
        if (result.length == 0) {
            Logger.error("Failed to convert " + line);
        }
        return result;
    }
    /**
     * Search the given txt for regex matches and alter those with the value but with append and or prepend
     * @param txt The txt to check/alter
     * @param regex The regex to look for
     * @param prepend The string to add in front
     * @param append The string to add in the back
     * @return The altered text or the original if it failed
     */
    public static String alterMatches(String txt, String filter, String regex, String prepend, String append ){
        try {
            var pat = Pattern.compile(regex);
            var res = pat.matcher(txt)
                    .results()
                    .map(MatchResult::group)
                    .filter( s -> s.matches(filter))
                    .collect(Collectors.toCollection(ArrayList::new));

            for( var r : res ){
                txt=txt.replace(r,prepend+r+append);
            }
        }catch( Exception e){
            Logger.error(e);
        }
        return txt;
    }
    /**
     * Replaces all the occurrences of the byte size hex escape sequences (fe.\x10) with their respective value
     * @param txt The text in which to replace them
     * @return The resulting bytes
     */
    public static String fromEscapedStringToBytes( String txt ){

        // Replace the known ones like \t, \r and \n
        txt = txt.replace("\\t","\t")
                    .replace("\\r","\r")
                    .replace("\\n","\n")
                    .replace("\\0","\0")
                    .replace("\\e","\\x1B");

        // First extract all the hexes
        var hexes = Pattern.compile("[\\\\][x]([0-9]|[A-F]){1,2}")
                .matcher(txt)//apply the pattern
                .results()//gather the results
                .map(MatchResult::group)//no idea
                .toArray(String[]::new);//export to a string array

        // Then replace all those hexes in the string with a null character
        for( String hex : hexes) { // replace all the hexes with the escape
            try {
                txt = txt.replace(hex, String.valueOf((char) Integer.parseInt(hex.substring(2), 16)));
            }catch( NumberFormatException e){
                Logger.error("Failed to convert: "+txt);
            }
        }
        return txt;
    }
    public static byte[] fromStringToBytes( String txt ) {

        // Replace the known ones like \t, \r and \n
        txt = txt.replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\0", "\0");
        return txt.getBytes();
    }
    /**
     * Splits a line trying multiple delimiters, first space, then semicolon and
     * then comma and finally |
     * 
     * @param line The string to split
     * @return The resulting array
     */
    public static String[] splitList(String line) {
        String[] delims = { " ", "\t", ";", ",","\\|" };
        String[] eles = { line };
        for (String delim : delims) {
            if (line.contains(delim)) {
                return line.split(delim);
            }
        }
        return eles;
    }

    /**
     * Parses an array with number in ascii format to a byte array
     * 
     * @param base    The base of these number (fe 2, 10 or 16)
     * @param numbers The array to parse
     * @return The resulting byte array
     */
    public static byte[] fromBaseToBytes(int base, String[] numbers) {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (int a = 0; a < numbers.length; a++) {
            try {
                if( numbers[a].isEmpty()) // skip empty strings
                    continue;
                if (base == 16) {
                    numbers[a] = numbers[a].replace("0x", "");
                } else if (base == 2) {
                    numbers[a] = numbers[a].replace("0b", "");
                }
                int result = Integer.parseInt(numbers[a], base);
                if (result <= 0xFF) {
                    out.write((byte) result);
                } else {
                    out.write((byte) (result >> 8));
                    out.write((byte) (result % 256));
                }
            } catch (java.lang.NumberFormatException e) {
                Logger.error("Bad number format: " + numbers[a]);
                return new byte[0];
            }
        }
        return out.toByteArray();
    }
    /**
     * Parses a string with number in ascii format to a byte.
     *
     * @param base    The base of these number (fe 2, 10 or 16)
     * @param number The value to parse
     * @return The resulting byte if parsed or empty optional if something went wrong.
     */
    public static Optional<Byte> fromBaseToByte( int base, String number ){
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if( number.isEmpty()) // skip empty strings
                return Optional.empty();
            if (base == 16) {
                number = number.replace("0x", "");
            } else if (base == 2) {
                number = number.replace("0b", "");
            }
            int result = Integer.parseInt(number, base);
            if (result <= 0xFF) {
                out.write((byte) result);
            } else {
                out.write((byte) (result >> 8));
                out.write((byte) (result % 256));
            }
        } catch (java.lang.NumberFormatException e) {
            Logger.error("Bad number format: " + number);
            return Optional.empty();
        }
        return Optional.of(out.toByteArray()[0]);
    }
    /**
     * Converts meters to kilometers with the given amount of decimals
     * @param m The amount of meters
     * @param decimals The amount of decimals
     * @return The formatted result
     */
    public static String metersToKm(double m, int decimals) {
        if (m > 5000)
            return roundDouble(m / 1000, 1) + "km";
        return roundDouble(m, decimals) + "m";
    }
    /* ***************************************** * O T H E R *************************************************** */
    /**
     * Retrieve the MAC address of an network interface based on the displayname
     * 
     * @param displayname The name of the interface fe. wlan0
     * @return The found MAC or empty string if not found
     */
    public static String getMAC(String displayname) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // filters out 127.0.0.1 and inactive interfaces
                if (iface.isLoopback() || !iface.isUp())
                    continue;
                String mac = Tools.fromBytesToHexString(iface.getHardwareAddress()).replace(" ", ":");
                mac = mac.replace("0x", "");
                if (iface.getDisplayName().equalsIgnoreCase(displayname))
                    return mac;
            }
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        return "";
    }

    /**
     * Retrieve the IP address of an network interface based on the displayName. If
     * displayName is "" then all info from all interfaces will be returned
     * including mac address
     * 
     * @param displayName The name of the interface fe. wlan0
     * @param ipv4        True if the IPv4 is wanted or false for IPv6
     * @return The found IP or empty string if not found
     */
    public static String getIP(String displayName, boolean ipv4) {
        StringJoiner join = new StringJoiner("\r\n");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // filters out 127.0.0.1 and inactive interfaces
                if (iface.isLoopback() || !iface.isUp())
                    continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    String p = addr.getHostAddress();
                    if (displayName.isEmpty()) {
                        String mac = Tools.fromBytesToHexString(iface.getHardwareAddress()).replace(" ", ":");
                        mac = mac.replace("0x", "");
                        if ((p.contains(":") && !ipv4) || (p.contains(".") && ipv4))
                            join.add(iface.getDisplayName() + " -> " + p + " [" + mac + "]");
                    } else {
                        if (iface.getDisplayName().equalsIgnoreCase(displayName)) {
                            if ((p.contains(":") && !ipv4) || (p.contains(".") && ipv4))
                                return p;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        return join.toString();
    }
    public static List<String[]> parseKeyValue(String data, boolean distinct){
        var pairs = new ArrayList<String>();
        int b =0;
        while( b!=-1) {
            int a = data.indexOf("{");
            b = data.indexOf("}");
            if (a != -1 && b != -1) {
                pairs.add(data.substring(a+1, b));
            }else if( b<a){
                Logger.error("Error trying to find the : pairs, closing bracket earlier than opening one");
                break;
            }
            data=data.substring(b+1);
        }
        var splits = new ArrayList<String[]>();
        if( distinct ) {
            pairs.stream().distinct().forEach(p -> splits.add(p.split(":")));
        }else{
            pairs.forEach(p -> splits.add(p.split(":")));
        }
        return splits;
    }
    public static String parseExpression( String op ){
        op=op.replace("->"," through");

        op = op.replace(" and "," && ");
        op = op.replace(" exor "," !| ");
        op = op.replace(" or "," || ");

        if( op.startsWith("between") ){
            op=op.replace("between ",">");
            op=op.replace(" and ", ";<");
        }
        if( op.startsWith("not between") ){
            op=op.replace("not between ","<=");
            op=op.replace(" and ", ";>=");
        }
        if( op.startsWith("from ") ){
            op=op.replace("from ",">");
            op=op.replace(" to ", ";<");
            op=op.replace(" till ", ";<");
        }
        if( op.contains(" through ")){
            op=op.replace(" through ", "<=var<=");
        }
        // 15 < x <= 25   or x <= 25
        op = op.replace(" not below ",">=");   // retain support for below
        op = op.replace(" not above ","<=");   // retain support for above
        op = op.replace(" below ","<");   // retain support for below
        op = op.replace(" above ",">");   // retain support for above
        op = op.replace(" not equals ","!="); // retain support for equals
        op = op.replace(" equals ","=="); // retain support for not equals

        // diff?
        op =op.replace(" diff ","~");

        return op.replace(" ","");
    }

    /**
     * Get the IP of this system.
     * @return IP of this system.
     */
    public static String getLocalIP() {
        try (final DatagramSocket socket = new DatagramSocket()) {
            var sock = InetAddress.getByName("8.8.8.8");
            socket.connect(sock, 10002);
            return socket.getLocalAddress().getHostAddress();
        } catch (UnknownHostException | java.net.SocketException | java.io.UncheckedIOException e) {
            Logger.error(e.getMessage());
            return "None";
        }
    }
    /**
     * Execute commands associated with serial ports on the system
     *
     * @param html Whether to use html for newline etc
     * @return Descriptive result of the command, "Unknown command if not recognised
     */
    public static String getSerialPorts( boolean html ){
        StringJoiner response = new StringJoiner(html ? "<br>" : "\r\n","Ports found: ","");
        response.setEmptyValue("No ports found");

        Arrays.stream(SerialPort.getCommPorts()).forEach( sp -> response.add( sp.getSystemPortName()));
        return response.toString();
    }

    /**
     * List all the currently active threads
     * @param html Whether it should be html or standard eol
     * @return List of currently active threads
     */
    public static String listThreads( boolean html ){
        StringJoiner join = new StringJoiner(html ? "<br>" : "\r\n");
        ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
        Thread[] lstThreads = new Thread[currentGroup.activeCount()];
        currentGroup.enumerate(lstThreads);

        Arrays.stream(lstThreads).forEach( lt -> join.add("Thread ID:"+lt.getId()+" = "+lt.getName()) );
        return join.toString();
    }

    /**
     * Converts coordinates to the deg min.min format
     * @param coordinates The coordinates to convert
     * @return The result
     */
    public static String convertCoordinates(String[] coordinates){

        BigDecimal bd60 = BigDecimal.valueOf(60);
        StringBuilder b = new StringBuilder();
        ArrayList<Double> degrees = new ArrayList<>();

        for( String item : coordinates ){
            String[] nrs = item.split(" ");
            if( nrs.length == 1){//meaning degrees!
                degrees.add(Tools.parseDouble(nrs[0], 0));
            }else if( nrs.length == 3){//meaning degrees minutes seconds!
                double degs = Tools.parseDouble(nrs[0], 0);
                double mins = Tools.parseDouble(nrs[1], 0);
                double secs = Tools.parseDouble(nrs[2], 0);

                BigDecimal deg = BigDecimal.valueOf(degs);
                BigDecimal sec = BigDecimal.valueOf(secs);
                BigDecimal min = sec.divide(bd60, 7, RoundingMode.HALF_UP).add(BigDecimal.valueOf(mins));
                deg = deg.add(min.divide(bd60,7, RoundingMode.HALF_UP));
                degrees.add(deg.doubleValue());
            }
        }
        if( degrees.size()%2 == 0 ){ //meaning an even number of values
            for( int a=0;a<degrees.size();a+=2){
                double la = degrees.get(a);
                double lo = degrees.get(a+1);

                b.append("Result:").append(la).append(" and ").append(lo).append(" => ").append(GisTools.fromDegrToDegrMin(la, -1, "°")).append(" and ").append(GisTools.fromDegrToDegrMin(lo, -1, "°"));
                b.append("\r\n");
            }
        }else{
            for( double d : degrees ){
                b.append("Result: ").append(degrees).append(" --> ").append(GisTools.fromDegrToDegrMin(d, -1, "°")).append("\r\n");
            }
        }
        return b.toString();
    }
    /**
     * Get the age in seconds of the latest file in the raw folder
     * @return Age of last write to the daily raw file or -1 if no or empty file
     */
    public static long getLastRawAge(Path tinypath){
        if( tinypath==null)
            return -1;
        var raw = tinypath.resolve("raw").resolve(TimeTools.formatNow("yyyy-MM"));
        try (Stream<Path> stream = Files.list(raw)) {
            var list = stream
                    .filter(file -> !Files.isDirectory(file))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter( fn-> (fn.endsWith(".log")) && fn.contains("RAW"))// Because it can contain zip files and sql backup
                    .sorted() // sort alphabetically to get the last one
                    .toList();
            if(list.isEmpty()) // No files found
                return -1;

            var file = raw.resolve(list.get(list.size()-1));
            var fileTime = Files.getLastModifiedTime(file);
            Duration difference = Duration.between( fileTime.toInstant(), Instant.now());
            return difference.getSeconds();
        }catch( IOException e){
            return -1;
        }
    }
}
