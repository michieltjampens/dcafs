package das;

import io.email.Email;
import io.email.EmailSending;
import io.Writable;
import io.forward.EditorForward;
import io.forward.FilterForward;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.cmds.HistoryCmds;
import util.cmds.StoreCmds;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import worker.Datagram;
import worker.DebugWorker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.BlockingQueue;

public class CommandPool {

	private final ArrayList<Commandable> stopCommandable = new ArrayList<>();
	private final HashMap<String,Commandable> commandables = new HashMap<>();

	private ArrayList<ShutdownPreventing> sdps;

	private final String workPath;
	private final Path settingsPath;

	static final String UNKNOWN_CMD = "unknown command";

	private EmailSending sendEmail = null;

	private String shutdownReason="";
	private final BlockingQueue<Datagram> dQueue;
	/* ******************************  C O N S T R U C T O R *********************************************************/

	public CommandPool(String workPath, BlockingQueue<Datagram> dQueue ){
		this.workPath=workPath;
		this.dQueue=dQueue;
		settingsPath = Path.of(workPath,"settings.xml");
		Logger.info("CommandPool started with workpath: "+workPath);
	}
	/**
	 * Add an implementation of the Commandable interface
	 * @param id The first part of the command (so whatever is in front of the : )
	 * @param cmdbl The implementation
	 */
	public void addCommandable( String id, Commandable cmdbl){
		if( id.equalsIgnoreCase("stop")||id.equalsIgnoreCase("")) {
			if( !stopCommandable.contains(cmdbl))
				stopCommandable.add(cmdbl);
		}else{
			// No use in having repeated commandables
			var oldOpt = commandables.entrySet().stream().filter( ent -> ent.getValue().equals(cmdbl)).findFirst();
			if(oldOpt.isPresent()){
				var old = oldOpt.get();
				commandables.remove(old.getKey());
				id+=";"+old.getKey();
			}
			commandables.put(id, cmdbl);
		}
	}
	public void addShutdownPreventing( ShutdownPreventing sdp){
		if( sdps==null)
			sdps = new ArrayList<>();
		sdps.add(sdp);
	}
	public String getShutdownReason(){
		return shutdownReason;
	}
	/* ****************************  S E T U P - C H E C K U P: Adding different parts from dcafs  *********************/

	/**
	 * To be able to send emails, access to the emailQueue is needed
	 * 
	 * @param sendEmail A reference to the emailworker
	 */
	public void setEmailSender(EmailSending sendEmail) {
		this.sendEmail = sendEmail;
	}
	/* ************************************ * R E S P O N S E *************************************************/

	public void emailResponse( Datagram d ) {
		Logger.info( "Executing email command ["+d.getData()+"], origin: " + d.getOriginID() );
		emailResponse( d, "Bot Reply" );
	}

	public void emailResponse(Datagram d, String header) {
		/* If there's no valid queue, can't do anything */
		if ( sendEmail!=null ) {
			Logger.info("Asked to email to " + d.getOriginID() + " but no worker defined.");
			return;
		}
		/* Notification to know if anyone uses the bot. */
		if ( (!d.getOriginID().startsWith("admin") && !sendEmail.isAddressInRef("admin",d.getOriginID()) ) && header.equalsIgnoreCase("Bot Reply")  ) {
			sendEmail.sendEmail( Email.toAdminAbout("DCAFSbot").content("Received '" + d.getData() + "' command from " + d.getOriginID()) );
		}
		/* Processing of the question */
		d.setData( d.getData().toLowerCase());

		/* Writable is in case the question is for realtime received data */
		String response = createResponse( d, false, true );

		if (!response.toLowerCase().contains(UNKNOWN_CMD)) {
			response = response.replace("[33m ", "");
			sendEmail.sendEmail( Email.to(d.getOriginID()).subject(header).content(response.replace("\r\n", "<br>")));
		} else {
			sendEmail.sendEmail(
					Email.to(d.getOriginID())
							.subject(header)
							.content("Euh " + d.getOriginID().substring(0, d.getOriginID().indexOf(".")) + ", no idea what to do with '" + d.getData() + "'..."));
		}
	}

	/**
	 * A question is asked to the BaseReq through this method, a Writable is
	 * passed for streaming data questions
	 *
	 * @param d The datagram to process
	 * @param remember If the command should be recorded in the raw data
	 * @return The response to the command/question
	 */
	public String createResponse( Datagram d, boolean remember) {
		return createResponse( d, remember, false);
	}

	/**
	 * A question is asked to the BaseReq through this method, a Writable is
	 * passed for streaming data questions
	 * 
	 * @param d The datagram to process
	 * @param remember If the command should be recorded in the raw data
	 * @param html     If the response should you html encoding or not
	 * @return The response to the command/question
	 */
	public String createResponse( Datagram d, boolean remember, boolean html) {

		String question = d.getData();
		var wr = d.getWritable();

		if( wr!=null && (wr.id().contains("matrix") || wr.id().startsWith("file:"))){
			html=true;
		}
		String result;

		if (remember) // If to store commands in the raw log (to have a full simulation when debugging)
			Logger.tag("RAW").info("system\t" + question);

		String[] split = new String[]{"",""};
		if( question.contains(":")){ // might contain more than one ':' so split on the first one
			split[0]=question.substring(0, question.indexOf(":"));
			split[1]=question.substring(question.indexOf(":")+1);
		}else{
			split[0]=question;
		}
		split[0]=split[0].toLowerCase();
		var eol = html ? "<br>" : "\r\n";

		result = switch (split[0]) {
			case "admin" -> doADMIN(split, wr, html);
			case "help", "h", "?" -> doHelp(split, eol);
			case "upgrade" -> doUPGRADE(split, wr, eol);
			case "retrieve" -> doRETRIEVE(split, wr, eol);
			case "sd" -> doShutDown(split, wr, eol);
			case "serialports" -> Tools.getSerialPorts(html);
			case "conv" -> Tools.convertCoordinates(split[1].split(";"));
			case "store" -> {
				var ans = StoreCmds.replyToCommand(split[1],html,settingsPath);
				if( !split[1].startsWith("?")) {
					if( split[1].equalsIgnoreCase("global")) {
						doCmd("ss", "reloadstore," + split[1].split(",")[0], wr);
					}else{
						doCmd("rtvals","reload",wr);// reload the global rtvals
					}
				}
				yield ans;
			}
			case "history" -> HistoryCmds.replyToCommand(split[1],html,settingsPath.getParent());
			case "", "stop", "nothing" -> {
				stopCommandable.forEach(c -> c.replyToCommand("","", wr, false));
				yield "Clearing requests";
			}
			default -> UNKNOWN_CMD;
		};

		if( result.equals(UNKNOWN_CMD)) // Meaning bad first cmd
			result = checkCommandables(split[0],split[1],wr,html);// Check the stored Commandables

		if( result.equals(UNKNOWN_CMD)) // Meaning no such first cmd in the commandables
			result = checkTaskManagers(split[0],split[1],wr,html);

		if( result.equals(UNKNOWN_CMD))
			result = "! No such cmd group: "+ split[0];

		if( wr!=null ) {
			if( d.getLabel().startsWith("matrix")) {
				wr.writeLine(d.getOriginID()+"|"+result);
			}else if (wr.id().startsWith("file:")) {
				result = result.replace("<br>",System.lineSeparator());
				result = result.replaceAll("<.{1,2}>","");
				wr.writeLine(result);
			}else if(!d.isSilent()) {
				wr.writeLine(result);
			}
		}
		if( !html && wr!=null && wr.id().startsWith("telnet") && result.length()<50)
			result = (result.startsWith("!")?TelnetCodes.TEXT_ORANGE:TelnetCodes.TEXT_GREEN)+result+TelnetCodes.TEXT_DEFAULT;

		return result + (html ? "<br>" : "\r\n");
	}
	private String checkCommandables(String cmd, String question, Writable wr,boolean html){
		final String f = cmd.replaceAll("\\d+","_"); // For special ones like sending data
		var cmdOpt = commandables.entrySet().stream()
				.filter( ent -> {
					String key = ent.getKey();
					if( key.equals(cmd)||key.equals(f))
						return true;
					return Arrays.stream(key.split(";")).anyMatch(k->k.equals(cmd)||k.equals(f));
				}).map(Map.Entry::getValue).findFirst();

		if( cmdOpt.isPresent()) { // If requested cmd exists
			String[] s = {cmd,question};
			String result = cmdOpt.get().replyToCommand(cmd,question, wr, html);
			if( result == null){
				Logger.error("Got a null as response to "+question);
				return "! Something went wrong processing: "+question;
			}
			return result;
		}
		return UNKNOWN_CMD;
	}
	private String checkTaskManagers(String cmd, String question, Writable wr,boolean html){
		var nl = html ? "<br>" : "\r\n";

		String res = switch (question) {
			case "?", "list" -> doCmd("tm", cmd + ",sets", wr) + nl + doCmd("tm", cmd + ",tasks", wr);
			case "reload" -> doCmd("tm", "reload," + cmd, wr);
			default -> doCmd("tm", "run," + cmd + ":" + question, wr);
		};
		if (!res.toLowerCase().startsWith("! no such taskmanager") )
			return res;
		return UNKNOWN_CMD;
	}
	/* ****************************************** C O M M A N D A B L E ********************************************* */
	private String doCmd( String id, String command, Writable wr){
		for( var cmd : commandables.entrySet() ){
			var spl = cmd.getKey().split(";");
			if( Arrays.stream(spl).anyMatch( x->x.equalsIgnoreCase(id)) ){
				return cmd.getValue().replyToCommand(id,command,wr,false);
			}
		}
		Logger.error("No "+id+" available");
		return "! No "+id+" available";
	}
	/* ********************************************************************************************/
	/**
	 * Try to update a file received somehow (email or otherwise)
	 * Current options: dcafs,script and settings (dcafs is wip)
	 * 
	 * @param request The full command update:something
	 * @param wr The 'writable' of the source of the command
	 * @param eol The eol to use
	 * @return Descriptive result of the command
	 */
	public String doUPGRADE(String[] request, Writable wr, String eol) {
		
		Path p;
		Path to;
		Path refr;

		String[] spl = request[1].split(",");

		switch (spl[0]) {
			case "?" -> {
				StringJoiner join = new StringJoiner(eol);
				join.add(TelnetCodes.TEXT_GREEN+"upgrade:tmscript,tm id"+TelnetCodes.TEXT_DEFAULT+" -> Try to update the given taskmanagers script")
						.add(TelnetCodes.TEXT_GREEN+"upgrade:settings"+TelnetCodes.TEXT_DEFAULT+" -> Try to update the settings.xml");
				return join.toString();
			}
			case "tmscript" -> {//fe. update:tmscript,tmid
				var ori = doCmd("tm", "getpath," + spl[1], wr);
				if (ori.isEmpty())
					return "! No such script";
				p = Path.of(ori);
				to = Path.of(ori.replace(".xml", "") + "_" + TimeTools.formatUTCNow("yyMMdd_HHmm") + ".xml");
				refr = Path.of(workPath, "attachments", spl[1]);
				try {
					if (Files.exists(p) && Files.exists(refr)) {
						Files.copy(p, to);    // Make a backup if it doesn't exist yet
						Files.move(refr, p, StandardCopyOption.REPLACE_EXISTING);// Overwrite

						// somehow reload the script
						return doCmd("tm", "reload," + spl[1], wr);// Reloads based on id
					} else {
						Logger.warn("Didn't find the needed files.");
						return "! Couldn't find the correct files. (maybe check spelling?)";
					}
				} catch (IOException e) {
					Logger.error(e);
					return "! Error when trying to upgrade tmscript.";
				}
			}
			case "settings" -> {
				p = Path.of(workPath, "settings.xml");
				to = Path.of(workPath, "settings_" + TimeTools.formatNow("yyMMdd_HHmm") + ".xml");
				refr = Path.of(workPath, "attachments" + File.separator + "settings.xml");
				try {
					if (Files.exists(p) && Files.exists(refr)) {
						Files.copy(p, to);    // Make a backup if it doesn't exist yet
						Files.copy(refr, p, StandardCopyOption.REPLACE_EXISTING);// Overwrite
						shutdownReason = "Replaced settings.xml";    // restart das
						System.exit(0);
						return "Shutting down...";
					} else {
						Logger.warn("Didn't find the needed files.");
						return "Couldn't find the correct files. (maybe check spelling?)";
					}
				} catch (IOException e) {
					Logger.error(e);
					return "! Error when trying to upgrade settings.";
				}
			}
			default -> {
				return "! No such subcommand in upgrade: " +spl[0];
			}
		}
	}
	/**
	 * Command to retrieve a setup file, can be settings.xml or a script
	 * fe. retrieve:script,scriptname.xml or retrieve:setup for the settings.xml
	 * 
	 * @param request The full command update:something
	 * @param wr The 'writable' of the source of the command
	 * @param eol The eol to use
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doRETRIEVE(String[] request, Writable wr, String eol) {
		
		if( sendEmail==null)
			return "Can't retrieve without EmailWorker";

		String[] spl = request[1].split(",");

		switch (spl[0]) {
			case "?" -> {
				StringJoiner join = new StringJoiner(eol, "", eol);
				join.add("retrieve:tmscript,tm id,<email/ref> -> Request the given taskmanager script through email")
						.add("retrieve:settings,<email/ref> -> Request the current settings.xml through email");
				return join.toString();
			}
			case "tmscript", "tmscripts" -> {
				if (spl.length < 3)
					return "! Not enough arguments retrieve:type,tmid,email in " + request[0] + ":" + request[1];
				var p = doCmd("tm", "getpath," + spl[1], wr);
				if (p.isEmpty())
					return "! No such script";
				sendEmail.sendEmail(Email.to(spl[2]).subject("Requested tm script: " + spl[1]).content("Nothing to say").attachment(p));
				return "Tried sending " + spl[1] + " to " + spl[2];
			}
			case "setup", "settings" -> {
				Path set = Path.of(workPath, "settings.xml");
				if (Files.notExists(set)) {
					return "! No such file: " + set;
				}
				if (spl.length != 2)
					return "! Not enough arguments, expected retrieve:setup,email/ref";
				sendEmail.sendEmail(Email.to(spl[1]).subject("Requested file: settings.xml").content("Nothing to say").attachment(workPath + File.separator + "settings.xml"));
				return "Tried sending settings.xml to " + spl[1];
			}
			default -> {
				return "! No such subcommand in retrieve: " + spl[0];
			}
		}
	}
	/* *******************************************************************************/

	/**
	 * Execute command to shut down dcafs, can be either sd or shutdown or sd:reason
	 * 
	 * @param request The full command split on the first :
	 * @param wr The 'writable' of the source of the command
	 * @param eol The eol to use
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	private String doShutDown( String[] request, Writable wr, String eol ){
		if( request[1].equals("?") )
			return "sd:reason -> Shutdown the program with the given reason, use force as reason to skip checks";
		String reason = request[1].isEmpty()?"Telnet requested shutdown":request[1];
		if( !request[1].equalsIgnoreCase("force") && sdps!=null) {
			for (var sdp : sdps) {
				if (sdp.shutdownNotAllowed()) {
					if (wr != null)
						wr.writeLine("Shutdown prevented by " + sdp.getID());
					return "! Shutdown prevented by " + sdp.getID();
				}
			}
		}
		shutdownReason = reason;
		System.exit(0);                    
		return "Shutting down program..."+ eol;
	}
	/**
	 * Get some basic help info
	 * 
	 * @param request The full command split on the first :
	 * @param eol The eol to use
	 * @return Content of the help.txt or 'No telnetHelp.txt found' if not found
	 */
	private String doHelp( String[] request, String eol ){

		StringJoiner join = new StringJoiner(eol,"",eol);
		join.setEmptyValue(UNKNOWN_CMD+": "+request[0]+":"+request[1]);
		switch(request[1]){
			case "?":
					join.add("help -> First use tips");
				break;
				case "":
					join.add(TelnetCodes.TEXT_RED+"General commands"+TelnetCodes.TEXT_DEFAULT);
					join.add("  st -> Get the current status of dcafs, lists streams, databases etc").add("");
					join.add(TelnetCodes.TEXT_RED+"General tips"+TelnetCodes.TEXT_DEFAULT)
						.add("   -> Look at settings.xml file (in dcafs.jar folder) in a viewer to see what dcafs does")
						.add("   -> Open two or more telnet instances fe. one for commands and other for live data").add("");
					join.add(TelnetCodes.TEXT_RED+"Recommended workflow:"+TelnetCodes.TEXT_DEFAULT);
					join.add(TelnetCodes.TEXT_GREEN+"1) Connect to a data source"+TelnetCodes.TEXT_DEFAULT)
						.add("   -> For udp, tcp and serial, use streams:? or ss:? for relevant commands")
						.add("   -> For MQTT, use mqtt:? for relevant commands")
						.add("   -> For I2C/SPI check the manual and then use i2c:?");
					join.add(TelnetCodes.TEXT_GREEN+"2) Look at received data"+TelnetCodes.TEXT_DEFAULT)
						.add("   -> raw:streamid -> Show the data received at the stream with the given id eg. raw:gps")
						.add("   -> mqtt:forward,id -> Show the data received from the mqtt broker with the given id")
						.add("   -> i2c:id -> Show the data received from the i2c device with the given id");
					join.add(TelnetCodes.TEXT_GREEN+"3) Build the path for the data"+TelnetCodes.TEXT_DEFAULT)
						.add("   -> Check pf:? for relevant commands")
						.add("   -> Use math to apply arithmetic operations on it")
						.add("   -> Use editor to apply string operations on it")
						.add("   -> Use filter to work on individual lines")
						.add("   -> Finally use store to write values to memory or database");
					join.add(TelnetCodes.TEXT_GREEN+"4) Create/connect to a database"+TelnetCodes.TEXT_DEFAULT);
					join.add("   -> Send dbm:? for commands related to the database manager");
					join.add(TelnetCodes.TEXT_GREEN+"5) Do other things"+TelnetCodes.TEXT_DEFAULT);
					join.add("   -> For scheduling events, check taskmanager");
					join.add("   -> ...").add("");
				break;
			case "filter": return FilterForward.getHelp(eol);
			case "math": break;
			case "editor": return EditorForward.getHelp(eol);
			default:	return "! No such subcommand in help: "+request[1];
		}
		return join.toString();
	}
	private String doADMIN( String[] request, Writable wr, boolean html ){
		String nl = html?"<br":"\r\n";
		String[] cmd = request[1].split(",");
		switch (cmd[0]) {
			case "?" -> {
				String gre = html ? "" : TelnetCodes.TEXT_GREEN;
				String reg = html ? "" : TelnetCodes.TEXT_DEFAULT;
				StringJoiner join = new StringJoiner(nl);
				join.add(gre + "admin:getlogs" + reg + " -> Send last/current info and error log to admin email")
						.add(gre + "admin:adddebugnode" + reg + " -> Adds a debug node with default values")
						.add(gre + "admin:clock" + reg + " -> Get the current timestamp")
						.add(gre + "admin:regex,<regex>,<match>" + reg + " -> Test a regex")
						.add(gre + "admin:ipv4" + reg + " -> Get the IPv4 and MAC of all network interfaces")
						.add(gre + "admin:ipv6" + reg + " -> Get the IPv6 and MAC of all network interfaces")
						.add(gre + "admin:gc" + reg + " -> Fore a java garbage collection")
						.add(gre + "admin:lt" + reg + " -> Show all threads")
						.add(gre + "admin:reboot" + reg + " -> Reboot the computer (linux only)")
						.add(gre + "admin:sleep,x" + reg + " -> Sleep for x time (linux only");
				return join.toString();
			}
			case "getlogs" -> {
				if (sendEmail != null) {
					sendEmail.sendEmail(Email.toAdminAbout("Statuslog").subject("File attached (probably)")
							.attachment(Path.of(workPath, "logs", "info.log")));
					sendEmail.sendEmail(Email.toAdminAbout("Errorlog").subject("File attached (probably)")
							.attachment(Path.of(workPath, "logs", "errors.log")));
					return "Sending logs (info,errors) to admin...";
				}
				return "No email functionality active.";
			}
			case "getlastraw" -> {
				Path it = Path.of(workPath, "raw", TimeTools.formatUTCNow("yyyy-MM"));
				if (sendEmail == null)
					return "! No email functionality active.";
				try (var list = Files.list(it)) {
					var last = list.filter(f -> !Files.isDirectory(f)).max(Comparator.comparingLong(f -> f.toFile().lastModified()));
					if (last.isPresent()) {
						var path = last.get();
						sendEmail.sendEmail(Email.toAdminAbout("Taskmanager.log").subject("File attached (probably)").attachment(path));
						return "Tried sending " + path;
					}
					return "! File not found";
				} catch (IOException e) {
					Logger.error(e);
					return "! Something went wrong trying to get the file";
				}
			}
			case "clock" -> {
				return TimeTools.formatLongUTCNow();
			}
			case "regex" -> {
				if (cmd.length != 3)
					return "! Invalid amount of parameters";
				return "Matches? " + cmd[1].matches(cmd[2]);
			}
			case "ipv4" -> {
				return Tools.getIP("", true);
			}
			case "ipv6" -> {
				return Tools.getIP("", false);
			}
			case "sleep" -> {
				return doSLEEP(cmd, wr);
			}
			case "lt" -> {
				return Tools.listThreads(html);
			}
			case "gc" -> {
				System.gc();
				return "Tried to execute GC";
			}
			case "reboot" -> {
				if (!System.getProperty("os.name").toLowerCase().startsWith("linux")) {
					return "! Only Linux supported for now.";
				}
				try {
					ProcessBuilder pb = new ProcessBuilder("bash", "-c", "shutdown -r +1");
					pb.inheritIO();

					Logger.error("Started restart attempt at " + TimeTools.formatLongUTCNow());
					pb.start();

					System.exit(0); // shutting down das
				} catch (IOException e) {
					Logger.error(e);
				}
				try {
					ProcessBuilder pb = new ProcessBuilder("sh", "-c", "reboot now");
					pb.inheritIO();

					Logger.error("Started restart attempt at " + TimeTools.formatLongUTCNow());
					pb.start();

					System.exit(0); // shutting down das
				} catch (IOException e) {
					Logger.error(e);
				}
				return "! Never gonna happen?";
			}
			default -> {
				return "! No such subcommand in admin:" + request[1];
			}
		}
	}
	/**
	 * Try to put the computer to sleep, only works on linux
	 * @param cmd Array containing sleep,rtc nr, time (fe.5m for 5 minutes)
	 * @param wr The writable to use if anything needs it
	 * @return Feedback
	 */
	public String doSLEEP( String[] cmd, Writable wr ){
		if( cmd.length!=3 ){
			return "admin:sleep,rtc,<time> -> Let the processor sleep for some time using an rtc fe. sleep:1,5m sleep 5min based on rtc1";
		}
		String os = System.getProperty("os.name").toLowerCase();
		if( !os.startsWith("linux")){
			return "! Only Linux supported for now.";
		}
		
		int seconds = (int) TimeTools.parsePeriodStringToSeconds(cmd[2]);
		
		try {
			StringJoiner tempScript = new StringJoiner( "; ");
			tempScript.add("echo 0 > /sys/class/rtc/rtc"+cmd[1]+"/wakealarm");
			tempScript.add("echo +"+seconds+" > /sys/class/rtc/rtc"+cmd[1]+"/wakealarm");
			tempScript.add("echo mem > /sys/power/state");

			ProcessBuilder pb = new ProcessBuilder("bash","-c", tempScript.toString());
			pb.inheritIO();
			Process process;

			Logger.error("Started sleep attempt at "+TimeTools.formatLongUTCNow());
			process = pb.start();
			process.waitFor();
			Logger.error("Woke up again at "+TimeTools.formatLongUTCNow());

			// do wake up stuff
			var tmCmd = commandables.get("tm");
			if( tmCmd != null ){
				tmCmd.replyToCommand("tm","run,*:wokeup",wr,false);
			}
		} catch (IOException | InterruptedException e) {
			Logger.error(e);
		}
		return "Waking up at "+TimeTools.formatLongUTCNow();
	}
}
