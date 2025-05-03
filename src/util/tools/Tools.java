package util.tools;

import com.fazecast.jSerialComm.SerialPort;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.math.MathUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A collection of various often used small methods that do a variety of useful
 * things
 * 
 * @author Michiel T'Jampens
 */
public class Tools {

    private static final Pattern DELIMITER_PATTERN = Pattern.compile("[ \t,;|]+");
    private static final Pattern HEX_PATTERN = Pattern.compile("\\\\x([0-9A-Fa-f]{1,2})");

    /* ********************************* D O U B L E ********************************************* */
    /**
     * Parses a string to a double, returning a default error value if parsing fails.
     * <p>
     * Input is first normalized to account for common formatting issues such as commas
     * as decimal separators and stray newline characters. Null or unparseable values
     * return the provided error fallback.
     *
     * @param number the string to parse
     * @param error  the value to return if parsing fails
     * @return the parsed double, or {@code error} if parsing fails
     */
    public static double parseDouble(String number, double error) {
        return NumberUtils.toDouble(normalizeNumberInput(number), error);
    }

    /**
     * Normalizes a string representing a numeric value by:
     * <ul>
     *   <li>Trimming whitespace</li>
     *   <li>Replacing commas with periods (e.g., for European decimal formats)</li>
     *   <li>Removing newline and carriage return characters</li>
     *   <li>Returning an empty string if input is {@code null}</li>
     * </ul>
     *
     * @param number the input string to normalize
     * @return a cleaned-up string safe for numeric parsing
     */
    public static String normalizeNumberInput(String number) {
        if (number == null) {
            return "";
        }
        return number.trim()
                .replace(",", ".")  // Make sure the correct one is used
                .replace(";", "")
                .replace("\n", "")
                .replace("\r", "");
    }

    /* ******************************* I N T E G E R  ************************************************************ */
    /**
     * Parses a string to an integer, returning a default error value if parsing fails.
     * <p>
     * This method handles hexadecimal strings (prefixed with '0x') and normal integers.
     * The input string is normalized before parsing, which removes common formatting issues
     * such as extra whitespace and line breaks.
     *
     * @param number the string to parse
     * @param error  the value to return if parsing fails
     * @return the parsed integer, or {@code error} if parsing fails
     */
    public static int parseInt(String number, int error) {
        number = normalizeNumberInput(number);

        if (number.startsWith("0x")) {
            try {
                return Integer.parseInt(number.substring(2), 16);
            } catch (NumberFormatException e) {
                return error;
            }
        }
        return NumberUtils.toInt(number, error);
    }
    /**
     * Checks if the provided string can be interpreted as a boolean-like value.
     * <p>
     * This method returns true for values commonly recognized as boolean equivalents
     * such as "yes", "no", "true", "false", "1", "0", "on", "off", "high", and "low".
     * Additionally, any non-zero numeric string (e.g., "2", "100") is considered true,
     * while "0" is considered false.
     * <p>
     * If the string cannot be interpreted as a valid boolean-like value or a numeric
     * value (other than "0"), the method returns false.
     *
     * @param value The string to check.
     * @return {@code true} if the value is a valid boolean-like string or a non-zero
     *         number; {@code false} otherwise.
     */
    public static boolean isValidBoolean(String value) {
        if (value == null) return false;

        var valid = new String[]{"yes", "no", "true", "false", "1", "0", "on", "off", "high", "low"};
        if (Arrays.asList(valid).contains(value.toLowerCase().trim()))
            return true;
        return NumberUtils.isParsable(value);
    }

    /**
     * Parses a string value and returns a boolean representation based on common boolean-like values.
     * <p>
     * The method checks for values like "yes", "true", "1", "on", and "high" as {@code true},
     * and "no", "false", "0", "off", and "low" as {@code false}. It also parses numeric strings,
     * where any non-zero number (e.g., "2", "100") is considered {@code true}, and "0" is considered {@code false}.
     * If the string cannot be parsed to a boolean-like value, the specified {@code error} value is returned.
     * A warning is logged if the string cannot be converted to a boolean or parsed as a number.
     *
     * @param value The string value to parse.
     * @param error The default boolean value to return in case of an invalid or unrecognized input.
     * @return {@code true} or {@code false} based on the string input, or the specified {@code error} value if invalid.
     */
    public static boolean parseBool(String value, boolean error) {
        return parseBool(value).orElse(error);
    }

    public static Optional<Boolean> parseBool(String value) {
        if (value.isEmpty())
            return Optional.empty();

        return switch (value.toLowerCase().trim()) {
            case "yes", "true", "1", "on", "high" -> Optional.of(true);
            case "no", "false", "0", "0.0", "off", "low" -> Optional.of(false);
            default -> {
                var ok = NumberUtils.isParsable(value);
                yield ok ? Optional.of(ok) : Optional.empty();
            }
        };
    }
    /**
     * Converts a signed byte to an unsigned integer.
     * This method takes a signed byte value (ranging from -128 to 127) and converts it to an unsigned integer
     * representation (ranging from 0 to 255). The conversion ensures that negative byte values are correctly
     * treated as their unsigned equivalents.
     *
     * @param val The signed byte value to be converted.
     * @return The unsigned integer value corresponding to the byte.
     */
    public static int toUnsigned(byte val) {
        return val & 0xFF; // This is equivalent to converting to unsigned byte
    }

    /**
     * Converts a signed integer to an unsigned 16-bit integer.
     * This method uses bitwise masking to convert the signed integer value into
     * an unsigned 16-bit integer. It ensures that the resulting value is between
     * 0 and 65535, regardless of the input's sign.
     *
     * @param val The signed integer to be converted to an unsigned 16-bit integer.
     * @return The unsigned 16-bit integer representation of the input value.
     */
    public static int toUnsignedWord(int val) {
        return val & 0xFFFF;
    }

    /* ************************************** S T R I N G ******************************************************** */
    /**
     * Adds spaces to the end of the string until it reaches the specified length.
     * If the original string is already longer than the requested length, it remains unchanged.
     *
     * @param ori the string to alter
     * @param length the requested length
     * @return ori with spaces added to the end to match the length (if ori was shorter)
     */
    public static String addTrailingSpaces(String ori, int length) {
        if (ori == null)
            throw new IllegalArgumentException("Input string cannot be null");

        StringBuilder res = new StringBuilder(ori);
        while (res.length() < length)
            res.append(" ");
        return res.toString();
    }
    /**
     * Checks if the last byte of the string is a null byte (0x00).
     *
     * @param txt The string to check.
     * @return True if the last byte is a null byte (0x00); false otherwise.
     * @throws IllegalArgumentException If the input string is null.
     */
    public static boolean isNullEnded(String txt) {
        if (txt == null)
            throw new IllegalArgumentException("Input string cannot be null");

        var bytes = txt.getBytes();
        if (bytes.length == 0) {
            return false; // Empty string doesn't have a null byte at the end
        }
        return bytes[bytes.length - 1] == 0;
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
     * Converts a part of an array of characters to a space-separated string of
     * hexadecimals (0x00 0x01), can work MSB->LSB and LSB->MSB.
     *
     * @param data   The array to parse
     * @param offset Start index
     * @param length Amount of bytes from the start to convert, negative means LSB first
     * @return The hex string
     */
    public static String fromBytesToHexString(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || offset >= data.length)
            return "";

        StringJoiner join = new StringJoiner(" 0x", "0x", "");

        // Check if the direction is MSB->LSB or LSB->MSB
        if (length > 0) {
            // MSB -> LSB
            for (int x = offset; x < offset + length && x < data.length; x++) {
                join.add(formatByte(data[x]));
            }
        } else {
            // LSB -> MSB
            for (int x = offset; x > offset + length && x >= 0; x--) {
                join.add(formatByte(data[x]));
            }
        }

        return join.toString();
    }

    /**
     * Helper method to format a byte as a two-digit hexadecimal string.
     *
     * @param b The byte to format
     * @return A two-digit hexadecimal string
     */
    private static String formatByte(byte b) {
        String hex = Integer.toHexString(b & 0xFF).toUpperCase(); // mask with 0xFF to ensure unsigned byte
        return (hex.length() == 1 ? "0" : "") + hex;
    }

    /**
     * Converts an entire array of bytes to a space-separated string of hexadecimals (e.g., 0x00 0x01).
     * This is a convenient wrapper for {@link #fromBytesToHexString(byte[], int, int)} with default parameters.
     *
     * @param data The array of bytes to convert.
     * @return A space-separated string of hexadecimal representations of the byte array (e.g., "0x00 0x01").
     */
    public static String fromBytesToHexString(byte[] data) {
        return fromBytesToHexString(data, 0, data.length);
    }

    /**
     * Parses a string representation of a number in a given base (binary, decimal, hexadecimal)
     * and converts it into a byte if possible.
     *
     * @param base   The base of the number (2 for binary, 10 for decimal, 16 for hexadecimal).
     * @param number The string representation of the number to parse.
     * @return An Optional containing the resulting byte if the number is valid, or an empty Optional if the parsing fails or the number is negative.
     */
    public static Optional<Byte> fromBaseToByte(int base, String number) {

        number = switch (base) {
            case 2 -> number.replace("0b", "");
            case 10 -> number;
            case 16 -> number.replace("0x", "");
            default -> {
                Logger.error("Not a valid base: '" + base + "' when  trying to convert " + number);
                yield "";
            }
        };

        if (number.isEmpty()) // skip empty strings or negatives
            return Optional.empty();
        if (number.startsWith("-")) { // skip empty strings or negatives
            Logger.error("Trying to convert negative number: '" + number + "'");
            return Optional.empty();
        }
        try {
            int result = Integer.parseInt(number, base);
            // Check if the parsed value fits in a byte (0 to 255)
            if (result >= 0 && result <= 0xFF)
                return Optional.of((byte) result); // return the byte value
            return Optional.of((byte) (result / 256)); // return LSB byte value
        } catch (java.lang.NumberFormatException e) {
            Logger.error("Bad number format: " + number + " for base " + base);
            return Optional.empty();
        }
    }

    /**
     * Parses an array of numbers in ASCII format to a byte array.
     *
     * @param base    The base of these numbers (e.g., 2 for binary, 10 for decimal, 16 for hexadecimal).
     * @param numbers The array of strings representing numbers to parse.
     * @return The resulting byte array containing all parsed values.
     */
    public static byte[] fromBaseToBytes(int base, String[] numbers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String number : numbers)
            fromBaseToByte(base, number).ifPresent(out::write);
        return out.toByteArray();
    }
    /**
     * Converts a delimited string of hexes to a byte array
     *
     * @param line The delimited line (will split on space, comma and semicolon)
     * @return The resulting array or an empty one if failed
     */
    public static byte[] fromHexStringToBytes(String line) {
        if (line == null || line.trim().isEmpty()) {
            Logger.error("Input line is null or empty.");
            return new byte[0];
        }
        // Clean the line: lowercase and remove 'h'
        line = line.toLowerCase().replace("h", "");

        // Split the string by delimiters and convert to byte array
        byte[] result = Tools.fromBaseToBytes(16, Tools.splitList(line));
        if (result.length == 0) {
            Logger.error("Failed to convert " + line);
        }
        return result;
    }
    /**
     * Converts a optionally space delimited string of binary nibbles to a byte array
     *
     * @param line The delimited line (will split on space)
     * @return The resulting array or an empty array if conversion fails
     */
    public static byte[] fromBinaryStringToBytes(String line) {
        if (line == null || line.trim().isEmpty()) {
            Logger.error("Input line is null or empty.");
            return new byte[0];  // Return an empty array for empty or invalid input
        }

        var ori=line;
        // Remove spaces from the line
        line = line.replace(" ", "");
        // Check if the length is a multiple of 8, indicating valid binary data
        if( line.length()%8!=0 ){
            Logger.error("Tried to convert binary '"+ori+"' to bytes, but incorrect amount of characters.");
            return new byte[0];
        }
        // Split the line into 8-bit chunks and convert to bytes
        byte[] result = Tools.fromBaseToBytes(2, Tools.splitListOnLength(line,8));
        if (result.length == 0) {
            Logger.error("Failed to convert '" + ori + "' to bytes.");
        }
        return result;
    }
    /**
     * Converts a delimited string of decimals to a byte array
     *
     * @param line The delimited line (will split on space, comma, and semicolon)
     * @return The resulting array or an empty array if conversion fails
     */
    public static byte[] fromDecStringToBytes(String line) {
        if (line == null || line.trim().isEmpty()) {
            Logger.error("Input line is null or empty.");
            return new byte[0];  // Return an empty array for empty or invalid input
        }

        // Attempt to convert the decimal string to bytes
        byte[] result = Tools.fromBaseToBytes(10, Tools.splitList(line));
        if (result.length == 0) {
            Logger.error("Failed to convert '" + line + "' to bytes.");
        }
        return result; // Return the resulting byte array (or empty array if no valid conversion)
    }

    /**
     * Search the given text for regex matches and alter those with the value by appending and/or prepending.
     * @param txt The text to check/alter
     * @param filter The filter to apply on the matches (only those that match this will be altered)
     * @param regex The regex to look for
     * @param prepend The string to add in front
     * @param append The string to add in the back
     * @return The altered text, or the original if it failed
     */
    public static String alterMatches(String txt, String filter, String regex, String prepend, String append ){
        try {

            var pat = Pattern.compile(regex);
            Matcher matcher = pat.matcher(txt);

            // Use StringBuilder to efficiently build the altered string
            StringBuilder result = new StringBuilder();

            int lastEnd = 0;
            while (matcher.find()) {
                String match = matcher.group();
                // Only alter matches that pass the filter
                if (match.matches(filter)) {
                    // Append text before the match, then prepend and append the altered match
                    result.append(txt, lastEnd, matcher.start());
                    result.append(prepend).append(match).append(append);
                } else {
                    // Just append the match if it doesn't match the filter
                    result.append(txt, lastEnd, matcher.end());
                }
                lastEnd = matcher.end();
            }

            // Append the remaining part of the string after the last match
            result.append(txt.substring(lastEnd));

            return result.toString(); // Return the altered text
        }catch( Exception e) {
            // Log the error and return the original text
            Logger.error("Failed to alter matches: " + e.getMessage());
            return txt; // Return the original text if an error occurs
        }
    }
    /**
     * Replaces all the occurrences of the byte size hex escape sequences (fe.\x10) with their respective value
     * @param txt The text in which to replace them
     * @return The resulting bytes
     */
    public static String fromEscapedStringToBytes( String txt) {
        if (txt == null || txt.isEmpty()) {
            return txt;
        }
        // Replace the known ones like \t, \r and \n
        txt = txt.replace("\\t","\t")
                    .replace("\\r","\r")
                    .replace("\\n","\n")
                    .replace("\\0","\0")
                    .replace("\\e","\\x1B");

        // First extract all the hexes
        var matcher = HEX_PATTERN.matcher(txt);//apply the pattern

        // Use StringBuilder for efficient string manipulation
        StringBuilder sb = new StringBuilder(txt.length());

        // Loop through and handle the hex replacement
        int lastAppendPos = 0;
        while (matcher.find()) {
            sb.append(txt, lastAppendPos, matcher.start());  // Append text before the match
            try {
                // Convert the hex escape sequence to a character
                int hexValue = Integer.parseInt(matcher.group(1), 16);
                sb.append((char) hexValue);  // Append the converted character
            } catch (NumberFormatException e) {
                Logger.error("Failed to convert: " + matcher.group(0));
            }
            lastAppendPos = matcher.end();  // Update the last append position
        }

        // Append any remaining text after the last match
        sb.append(txt, lastAppendPos, txt.length());

        return sb.toString();
    }

    /**
     * Converts a string to a byte array, replacing known escape sequences
     * (such as \t, \r, \n, and \0) with their corresponding byte representations.
     *
     * @param txt The input string to convert. May contain escape sequences.
     * @return A byte array representing the string with escape sequences replaced,
     *         or an empty byte array if the input string is null or empty.
     */
    public static byte[] fromStringToBytes( String txt) {
        if (txt == null || txt.isEmpty()) {
            return new byte[0];
        }
        // Replace the known ones like \t, \r and \n
        txt = txt.replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\0", "\0");
        return txt.getBytes();
    }

    /**
     * Splits a line into an array of strings based on multiple delimiters: space, semicolon,
     * comma, or pipe ('|'). The order of preference for delimiters is: space, semicolon,
     * comma, and pipe.
     *
     * @param line The string to split. May contain any of the supported delimiters.
     * @return An array of strings resulting from splitting the input line.
     *         If the input is null or empty, an empty array is returned.
     */
    public static String[] splitList(String line) {
        if (line == null || line.isEmpty()) {
            return new String[]{};
        }
        // Regex to match any of the delimiters (space, tab, semicolon, comma, or pipe)
        return DELIMITER_PATTERN.split(line);
    }

    /**
     * Splits a string into an array using space, semicolon, comma, or pipe as delimiters.
     * Ensures the array has at least `minCount` elements, filling with `filler` if necessary.
     *
     * @param line The string to split.
     * @param minCount The minimum number of elements in the resulting array.
     * @param filler The string used to fill if the array has fewer than `minCount` elements.
     * @return An array of strings. Returns an empty array if the input is null or empty.
     */
    public static String[] splitList(String line, int minCount, String filler) {
        if (line == null || line.isEmpty()) {
            return new String[]{};
        }
        var list = new ArrayList<>(Arrays.asList(splitList(line)));

        while (list.size() < minCount)
            list.add(filler);
        return list.toArray(String[]::new);
    }

    /**
     * Splits a string into substrings of a specified length.
     *
     * @param line The string to split. If null, an empty array will be returned.
     * @param chars The length of each substring.
     * @return An array of substrings. Returns an empty array if the input is null or an array with a single element if the string is shorter than the specified length.
     */
    public static String[] splitListOnLength(String line,int chars) {
        if (line == null)
            return new String[0];

        ArrayList<String> pieces = new ArrayList<>();
        int old=0;
        while( old < line.length()) {
            pieces.add(line.substring(old, Math.min(old + chars, line.length())) );
            old+=chars;
        }
        return pieces.toArray(new String[0]);
    }

    /**
     * Converts meters to kilometers with the given amount of decimals
     * @param m The amount of meters
     * @param decimals The amount of decimals
     * @return The formatted result
     */
    public static String metersToKm(double m, int decimals) {
        if (m > 5000)
            return MathUtils.roundDouble(m / 1000, 1) + "km";
        return MathUtils.roundDouble(m, decimals) + "m";
    }
    /* ***************************************** * O T H E R *************************************************** */

    /**
     * Loops through network interfaces and retrieves either the MAC address or IP address
     * based on the provided parameters.
     *
     * @param displayName The display name of the network interface to match (e.g., "eth0").
     * @param ipv4        If true, returns an IPv4 address; otherwise, returns an IPv6 address.
     * @param ipOrMac     Specifies whether to retrieve the "MAC" address or "ip" address.
     * @return A string representing the desired network information, or an empty string if no match found.
     */
    private static String loopNetworkInterfaces(String displayName, boolean ipv4, String ipOrMac ){
        var join = new StringJoiner("\r\n");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // filters out 127.0.0.1 and inactive interfaces
                if (iface.isLoopback() || !iface.isUp())
                    continue;

                if (ipOrMac.equalsIgnoreCase("MAC")) {
                    var mac = getMACfromInterface(iface, displayName);
                    if (!mac.isEmpty())
                        return mac;
                } else if (ipOrMac.equalsIgnoreCase("ip")) {
                    // Get IP address if requested and add it to the StringJoiner
                    join.add(getIpFromInterface(iface, displayName,ipv4) );
                }
            }
        } catch (SocketException e) {
            System.err.println("Socket error while getting MAC");
        }
        return join.toString();
    }

    /**
     * Retrieves the MAC address of a network interface, formatted as a string.
     * If the network interface's display name matches the specified display name, the MAC address is returned.
     * The MAC address is formatted as a hexadecimal string with colons separating each byte.
     *
     * @param iface       The {@link NetworkInterface} whose MAC address is to be retrieved.
     * @param displayName The display name of the network interface to match.
     * @return The MAC address as a string in hexadecimal format, or an empty string if the interface doesn't match the display name or has no MAC address.
     * @throws SocketException If an error occurs while retrieving the network interface details.
     */
    private static String getMACfromInterface(NetworkInterface iface, String displayName) throws SocketException {
        var hardwareAddress = iface.getHardwareAddress();
        if (hardwareAddress == null)
            return ""; // No MAC address for this interface

        String mac = Tools.fromBytesToHexString(hardwareAddress).replace(" ", ":").replace("0x", "");
        if (iface.getDisplayName().equalsIgnoreCase(displayName))
            return mac;
        return ""; // Return empty string if display name doesn't match
    }

    /**
     * Retrieves an IP address from a network interface.
     *
     * <p>If a display name is provided and matches the interface, this returns the first matching IP address
     * (IPv4 or IPv6, depending on the flag). If the display name is empty, it collects all matching IPs from all interfaces
     * and returns them as a formatted string with MAC address info included.</p>
     *
     * @param iface       The network interface to inspect.
     * @param displayName The name of the interface to filter by (e.g., "wlan0"), or empty to include all.
     * @param ipv4        True to retrieve an IPv4 address, false for IPv6.
     * @return A single IP address if a display name is specified and matched; otherwise, a list of matching IPs with MAC info.
     * Returns an empty string if no matching address is found or the interface has no hardware address.
     * @throws SocketException If a socket error occurs while accessing the interface.
     */
    private static String getIpFromInterface(NetworkInterface iface, String displayName, boolean ipv4) throws SocketException {
        var hardwareAddress = iface.getHardwareAddress();
        if (hardwareAddress == null)
            return ""; // No MAC address for this interface
        var mac = Tools.fromBytesToHexString(hardwareAddress).replace(" ", ":").replace("0x", "");

        var join = new StringJoiner("\r\n");
        Enumeration<InetAddress> addresses = iface.getInetAddresses();
        while (addresses.hasMoreElements()) {
            var inetAddress = addresses.nextElement();
            var hostAddress = inetAddress.getHostAddress();

            if (displayName.isEmpty()) {
                // Filter by IPv4 or IPv6
                if ((hostAddress.contains(":") && !ipv4) || (hostAddress.contains(".") && ipv4))
                    join.add(iface.getDisplayName() + " -> " + hostAddress + " [" + mac + "]");
            } else if (iface.getDisplayName().equalsIgnoreCase(displayName)
                    && (hostAddress.contains(":") && !ipv4)
                    || (hostAddress.contains(".") && ipv4)) {
                return hostAddress;
            }
        }
        return join.toString();
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
    public static String getIP(String displayName, boolean ipv4 ) {
        return loopNetworkInterfaces(displayName, ipv4, "ip");
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
    public static String getSerialPorts(boolean html) {
        var response = new StringJoiner(html ? "<br>" : "\r\n", "Ports found: ","");
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
        int estimatedThreadCount = currentGroup.activeCount();
        Thread[] lstThreads = new Thread[estimatedThreadCount];

        // Use enumerate() safely by synchronizing the group
        int actualThreadCount = currentGroup.enumerate(lstThreads);

        Arrays.stream(Arrays.copyOf(lstThreads, actualThreadCount))
                .forEach(lt -> join.add("Thread ID:" + lt.getId() + " = " + lt.getName()));
        return join.toString();
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

    /**
     * Checks if the current process is running with root (administrator) privileges on a Linux-based system.
     * The method performs the following checks:
     * 1. If the operating system is not Linux, it immediately returns {@code true}, indicating no root privileges.
     * 2. If the operating system is Linux, it executes the command {@code id -u} to retrieve the current user's ID.
     *    - If the result is "0", it means the current user has root privileges. The method will return {@code false}.
     *    - If the result is not "0", the method returns {@code true}, indicating the absence of root privileges.
     * 3. In case of any {@link IOException} during the command execution, the method will log the error and return {@code true}.
     *
     * @return {@code true} if the process does not have root privileges or the system is not Linux; {@code false} if the process has root privileges on a Linux system.
     */
    public static boolean hasNoRootRights() {
        if (!System.getProperty("os.name").toLowerCase().startsWith("linux")) {
            Logger.warn("Not running linux, so no root");
            return true;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("id", "-u");
            var process = pb.start();

            BufferedReader stdInput
                    = new BufferedReader(new InputStreamReader(
                    process.getInputStream()));

            var userId = stdInput.readLine();
            // Close the BufferedReader to release the resources
            stdInput.close();
            // If the result is 0, we have root rights
            return !userId.equalsIgnoreCase("0");
        } catch (IOException e) {
            Logger.error("Error checking root privileges: ", e);
            return true; // Return true if there's an error (no root access)
        }
    }

    public static String[] endSplit(String ori, String splitter) {
        // If the splitter is not found, return the original string and an empty string
        if (!ori.contains(splitter))
            return new String[]{ori, ""};
        // Find the last occurrence of the splitter
        int index = ori.lastIndexOf(splitter);
        // Return the part before the splitter and the part after it
        return new String[]{ori.substring(0, index), ori.substring(index + splitter.length())};
    }
}
