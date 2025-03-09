package io.telnet;

public class TelnetCodes {

	private TelnetCodes() {
		throw new IllegalStateException("Utility class");
	}

	// IAC Options
	public static final byte IAC = (byte) 0xFF;

	public static final byte WILL =(byte)251;
	public static final byte DO = (byte)252;
	public static final byte WONT = (byte)253;
	public static final byte DONT = (byte)254;

	public static final byte ECHO = (byte)1;
	public static final byte SURPRESS_GO_AHEAD = (byte)3;
	public static final byte[] WILL_SGA = new byte[]{IAC,WILL,SURPRESS_GO_AHEAD};
	public static final byte[] WILL_ECHO = new byte[]{IAC,WILL,ECHO};

	// Escape Characters
	public static final String ESCAPE = Character.toString((char)27);

	public static final String TEXT_RESET = ESCAPE + "[0m";

	public static final String TEXT_BRIGHT = ESCAPE+"[1m";
	public static final String TEXT_FAINT = ESCAPE+"[2m";
	public static final String TEXT_REGULAR = ESCAPE+"[22m";


	public static final String TEXT_BOLD    = ESCAPE+"[1m";
	public static final String TEXT_ITALIC    = ESCAPE+"[3m";
	public static final String TEXT_UNDERLINE = ESCAPE+"[4m";
	public static final String UNDERLINE_OFF  = ESCAPE+"[24m";

	public static final String TEXT_BLINK = ESCAPE+"[5m";  		//NOPE
	public static final String TEXT_STRIKETHROUGH = ESCAPE+"[9m";
	public static final String TEXT_BLACK  = ESCAPE+"[0;30m";
	public static final String TEXT_GRAY  = ESCAPE+"[1;90m";
	public static final String TEXT_RED    = ESCAPE+"[0;31m";
	public static final String TEXT_GREEN  = ESCAPE+"[0;32m";
	public static final String TEXT_YELLOW = ESCAPE+"[0;33m";
	public static final String TEXT_BRIGHT_YELLOW  = ESCAPE+"[1;33m";
	public static final String TEXT_BLUE   		 =  ESCAPE+"[0;34m";
	public static final String TEXT_BRIGHT_BLUE    =  ESCAPE+"[1;34m";
	public static final String TEXT_MAGENTA = ESCAPE+"[0;35m";
	public static final String TEXT_BRIGHT_MAGENTA = ESCAPE+"[1;35m";
	public static final String TEXT_CYAN    = ESCAPE+"[0;36m";
	public static final String TEXT_BRIGHT_CYAN    = ESCAPE+"[1;36m";
	public static final String TEXT_ORANGE  = ESCAPE+"[0;38;5;208m";
	public static final String TEXT_LIGHT_GRAY   = ESCAPE + "[0;37m";
	public static final String TEXT_WHITE   = ESCAPE + "[1;37m";

	public static final String BG_LIGHT_GREY = ESCAPE+"[48;5;7m";
	public static final String BG_DARK_GREY = ESCAPE+"[48;5;8m";
	public static final String BACK_BLACK = ESCAPE + "[40m";
	public static final String BACK_RED = ESCAPE + "[41m";
	public static final String BACK_GREEN = ESCAPE + "[42m";
	public static final String BACK_YELLOW = ESCAPE + "[43m";
	public static final String BACK_BLUE =  ESCAPE + "[44m";
	public static final String BACK_MAGENTA =  ESCAPE + "[45m";
	public static final String BACK_CYAN =  ESCAPE + "[46m";
	public static final String BACK_WHITE = ESCAPE + "[47m";

	
	public static final String TEXT_FRAMED = ESCAPE + "[51m";   //NOPE	
	public static final String TEXT_ENCIRCLED = ESCAPE + "[52m";//NOPE
	public static final String TEXT_OVERLINED = ESCAPE + "[53m";//NOPE
	
	public static final String HIDE_SCREEN = ESCAPE + "[2J";//Hides everything up to the cursor, doesn't reset the cursor
	public static final String CURSOR_LINESTART = ESCAPE + "[0;0F";

	public static final String PREV_LINE = ESCAPE + "[F"; // works
	public static final String CLEAR_LINE = ESCAPE + "[2K"; // works
	public static final String CLEAR_LINE_END = ESCAPE + "[K";
	public static final String CURSOR_LEFT = ESCAPE + "[1D";
	public static final String CURSOR_RIGHT = ESCAPE + "[1C";

	public static final String TEXT_DEFAULT = "[tdc]";

	public static String cursorLeft( int steps ){
		return ESCAPE + "["+steps+"D";
	}

	/**
	 * Convert a textual color to the telnet code for it
	 * @param color The color to convert
	 * @param error The telnetcode to use if the color isn't recognized
	 * @return The resulting telnet code
	 */
	public static String colorToCode( String color, String error ){
		return switch (color) {
			case "yellow" -> TelnetCodes.TEXT_BRIGHT_YELLOW;
			case "dimyellow" -> TelnetCodes.TEXT_YELLOW;
			case "white" -> TelnetCodes.TEXT_WHITE;
			case "orange" -> TelnetCodes.TEXT_ORANGE;
			case "gray" -> TelnetCodes.TEXT_GRAY;
			case "lightgray" -> TelnetCodes.TEXT_LIGHT_GRAY;
			case "green" -> TelnetCodes.TEXT_GREEN;
			case "cyan" -> TelnetCodes.TEXT_BRIGHT_CYAN;
			case "dimcyan" -> TelnetCodes.TEXT_CYAN;
			case "magenta" -> TelnetCodes.TEXT_BRIGHT_MAGENTA;
			case "dimmagenta" -> TelnetCodes.TEXT_MAGENTA;
			case "red" -> TelnetCodes.TEXT_RED;
			case "dimblue" -> TelnetCodes.TEXT_BLUE; // hardly readable on black
			case "blue" -> TelnetCodes.TEXT_BRIGHT_BLUE;
			case "black" -> TelnetCodes.TEXT_BLACK;
			default -> error;
		};
	}
	public static String removeCodes( String input ){
		//[48;5;7m
		//"[1C"
		//"[0;0F"
		// "[0;38;5;208m"
		// char(27)
		input=input.replace("[tdc]","");
		return input.replaceAll(ESCAPE+"\\[\\d(;)?\\d*(;)?\\d*(;)?\\d*(m)?","");
	}
	public static String toReadableIAC( byte b ) {
		return switch (b) {
			// IAC Control
			case (byte) 240 -> "SE";
			case (byte) 241 -> "NOP";
			case (byte) 242 -> "DM";
			case (byte) 243 -> "BRK";
			case (byte) 244 -> "IP";
			case (byte) 245 -> "AO";
			case (byte) 246 -> "AYT";
			case (byte) 247 -> "EC";
			case (byte) 248 -> "EL";
			case (byte) 249 -> "GA";
			case (byte) 250 -> "SB";
			case (byte) 251 -> "WILL";
			case (byte) 252 -> "WONT";
			case (byte) 253 -> "DO";
			case (byte) 254 -> "DONT";
			case (byte) 255 -> "IAC";
			// Negotiations
			case (byte) 1 -> "ECHO";
			case (byte) 3 -> "SGA";
			case (byte) 5 -> "STATUS";
			case (byte) 6 -> "TimingMark";
			case (byte) 24 -> "TermType";
			case (byte) 31 -> "Window Size";
			case (byte) 32 -> "Term Speed";
			case (byte) 33 -> "Rem Flow Cont";
			case (byte) 34 -> "LineMode";
			case (byte) 36 -> "EnvVar";
			default -> "" + (int) b;
		};
	}
}