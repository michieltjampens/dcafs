package das;

import io.Writable;
import io.email.Email;
import io.email.EmailSending;
import io.email.EmailWorker;
import io.forward.EditorForward;
import io.forward.FilterForward;
import io.matrix.MatrixClient;
import io.telnet.TelnetCodes;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.cmds.AdminCmds;
import util.cmds.HistoryCmds;
import util.data.StoreCmds;
import util.tools.TimeTools;
import util.tools.Tools;
import worker.Datagram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class CommandPool {

	private final ArrayList<Commandable> stopCommandable = new ArrayList<>(); // the ones that 'stop' sending data
	private final HashMap<String,Commandable> commandables = new HashMap<>(); // The regular ones
	static final String UNKNOWN_CMD = "unknown command"; // Default reply if the requested cmd doesn't exist
	private EmailSending sendEmail = null; // Object to send emails
	private String shutdownReason=""; // The reason given for shutting down
	/* ******************************  C O N S T R U C T O R *********************************************************/
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
			// No use in having repeated commandables, if the commandable is already in the map, add the id to the key
			var oldOpt = commandables.entrySet().stream().filter( ent -> ent.getValue().equals(cmdbl)).findFirst();
			if(oldOpt.isPresent()){
				var old = oldOpt.get();
				commandables.remove(old.getKey());
				id+=";"+old.getKey();
			}else{
				if( commandables.get(id)!=null){
					Logger.error("Overwriting an existing commandable with same id: "+id);
				}
			}
			commandables.put(id, cmdbl);
		}
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

	/**
	 * When the result of the datagram should be send to an email
	 * @param d The datagram to process
	 */
	public void emailResponse( Datagram d ) {
		Logger.info( "Executing email command ["+d.getData()+"], origin: " + d.getOriginID() );
		emailResponse( d, "Bot Reply" );
	}
	/**
	 * When the result of the datagram should be sent to an email
	 * @param d The datagram to process
	 * @param subject The subject of  the email
	 */
	public void emailResponse(Datagram d, String subject) {
		/* If there's no valid queue, can't do anything */
		if ( sendEmail!=null ) {
			Logger.info("Asked to email to " + d.getOriginID() + " but no worker defined.");
			return;
		}
		/* Notification to know if anyone uses the bot. */
		if ( (!d.getOriginID().startsWith("admin") && !sendEmail.isAddressInRef("admin",d.getOriginID()) ) && subject.equalsIgnoreCase("Bot Reply")  ) {
			sendEmail.sendEmail( Email.toAdminAbout("DCAFSbot").content("Received '" + d.getData() + "' command from " + d.getOriginID()) );
		}
		/* Processing of the question */
		d.setData( d.getData().toLowerCase());

		/* Writable is in case the question is for realtime received data */
		String response = executeCommand( d, false, true );

		if (!response.toLowerCase().contains(UNKNOWN_CMD)) {
			response = response.replace("[33m ", "");
			sendEmail.sendEmail( Email.to(d.getOriginID()).subject(subject).content(response.replace("\r\n", "<br>")));
		} else {
			sendEmail.sendEmail(
					Email.to(d.getOriginID())
							.subject(subject)
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
	public String executeCommand(Datagram d, boolean remember) {
		return executeCommand( d, remember, false);
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
	public String executeCommand(Datagram d, boolean remember, boolean html) {

		String question = d.getData();

		if (remember) // If to store commands in the raw log (to have a full simulation when debugging)
			Logger.tag("RAW").info("system\t" + question);

		// If the question contains -r that means it shouldn't contain telnet code
		boolean removeTelnetCodes=false;
		if( question.contains( " -r")){
			removeTelnetCodes=true;
			question=question.replace(" -r","");
		}

		var wr = d.getWritable();

		// Some receivers of the result prefer html style (if possible)
		if( wr!=null && (wr.id().contains("matrix") || wr.id().startsWith("file:")))
			html=true;

		String[] split = new String[]{question,""};
		if( question.contains(":")){ // might contain more than one ':' so split on the first one
			split[0]=question.substring(0, question.indexOf(":"));
			split[1]=question.substring(question.indexOf(":")+1);
		}
		split[0]=split[0].toLowerCase(); // make sure the cmd is in lowercase

		// Check the repo's for a match for the given command
		String result = findCommand(split, html, d);

		if( result.startsWith("! No such cmd group") ){
			result = createMatrixNodeIfAsked(result,split[1]);
			result = createEmailNodeIfAsked(result,split[1]);
		}
		if( wr == null )
			return result + (html ? "<br>" : "\r\n");

		if( !wr.id().contains("telnet"))
			removeTelnetCodes=true;

		if( removeTelnetCodes ) // Remove the telnetcodes
			result = TelnetCodes.removeCodes(result);

		if( d.getLabel().startsWith("matrix")) { // and the label starts with matrix
			wr.writeLine(d.getOriginID()+"|"+result); // Send the data but add the origin in front
		}else if (wr.id().startsWith("file:")) { // if the target is a file
			result = result.replace("<br>",System.lineSeparator()); // make sure it uses eol according to system
			result = result.replaceAll("<.{1,2}>",""); // remove other simple html tags
			wr.writeLine(result); // send the result
		}else if(!d.isSilent()) { // Check if the receiver actually wants the reply
			wr.writeLine(d.getOriginID(),result);
		}

		// If the receiver is a telnet session, change coloring on short results based on a ! prepended (! means bad news)
		if( !html && wr.id().startsWith("telnet") && result.length()<150)
			result = (result.startsWith("!")?TelnetCodes.TEXT_ORANGE:TelnetCodes.TEXT_GREEN)+result+TelnetCodes.TEXT_DEFAULT;

		return result + (html ? "<br>" : "\r\n");
	}

	private String findCommand(String[] split, boolean html, Datagram d) {
		String result = checkLocalCommandables(split, html, d);// First check the standard commandables
		if (!result.equals(UNKNOWN_CMD)) return result;// Meaning a standard first cmd

		result = checkCommandables(split[0], split[1], html, d);// Check the stored Commandables
		if (!result.equals(UNKNOWN_CMD)) return result; // So a stored one

		result = checkTaskManagers(split[0], split[1], d.getWritable(), html);
		if (!result.equals(UNKNOWN_CMD)) return result;// Check if it matches the id of a TaskManager

		Logger.error("No cmd found with " + split[0] + ":" + split[1] + (d.getWritable() != null ? " requested by " + d.getWritable().id() + "." : "."));
		return "! No such cmd group: |" + split[0] + "|"; // No result, so probably bad cmd
	}

	public void quickCommand(Datagram d) {
		var cmd = d.getData().split(":", 2);
		var arg = cmd.length == 2 ? cmd[1] : "";

		var result = checkCommandables(cmd[0], arg, false, d);// Check the stored Commandables
		if (!result.equals(UNKNOWN_CMD)) return; // So a stored one

		result = checkLocalCommandables(cmd, false, d);// First check the standard commandables
		if (!result.equals(UNKNOWN_CMD)) return;// Meaning a standard first cmd

		result = checkTaskManagers(cmd[0], arg, d.getWritable(), false);
		if (!result.equals(UNKNOWN_CMD)) return;// Check if it matches the id of a TaskManager

		Logger.error("No cmd found with " + cmd[0] + ":" + arg + (d.getWritable() != null ? " requested by " + d.getWritable().id() + "." : "."));
	}
	private String createMatrixNodeIfAsked(String result, String subCmd){
		if( result.contains("matrix")){
			if( subCmd.startsWith("add")) {
				// TODO: Passwords that contain a , ...?
				if( MatrixClient.addBlankElement(Paths.settings(), subCmd.split(",")) ) {
					result = "Matrix element added to settings.xml.";
				}else{
					result = "! No valid settings.xml?";
				}
			}else{
				result = "! No matrix yet, only available cmd is 'matrix:add,user,pass,room' to add element to xml," +
						" user,pass and room are optional at this stage.";
			}
		}
		return result;
	}
	private String createEmailNodeIfAsked( String result, String subCmd){
		if( result.contains("email")){
			if( subCmd.equals("add")) {
				if( EmailWorker.addBlankElement(Paths.settings(),true,false) ) {
					result = "Blank email element added to settings.xml, go fill it in!";
				}else{
					result = "! No valid settings.xml?";
				}
			}else{
				result = "! No email yet, only available cmd is 'email:add' to add element to xml";
			}
		}
		return result;
	}
	/**
	 * Checks if the cmd/question is for a standard commandable
	 * @param split the question split on :
	 * @param html True means the answer should use html
	 * @return The answer
	 */
	private String checkLocalCommandables(String[] split, boolean html, Datagram d) {
		var eol = html ? "<br>" : "\r\n"; // change the eol depending on html or not
		var wr = d.getWritable();
		return switch (split[0]) { // check if it's a built-in cmd instead of a commandable one
			case "admin" -> AdminCmds.doADMIN(split[1],sendEmail,commandables.get("tm"), html);
			case "help", "h", "?" -> doHelp(split, eol);
			case "upgrade" -> doUPGRADE(split, wr, eol);
			case "retrieve" -> doRETRIEVE(split, wr, eol);
			case "sd" -> doShutDown(split, eol);
			case "serialports" -> Tools.getSerialPorts(html);
			case "conv" -> Tools.convertCoordinates(split[1].split(";"));
			case "store" -> doStoreCommands( split[1], wr, html );
			case "history" -> HistoryCmds.replyToCommand(split[1],html,Paths.settings().getParent());
			case "log" -> doTinyLogCommands( split[1] );
			case "commandable" -> doCommandable(split[1], (Commandable) d.payload());
			case "", "stop", "nothing" -> {
				stopCommandable.forEach(c -> c.replyToCommand("","", wr, false));
				yield "Clearing requests";
			}
			default -> UNKNOWN_CMD;
		};
	}
	/**
	 * Check the list of Commandable's for the matching one and ask the question
	 * @param cmd The cmd (group)
	 * @param question The question to ask
	 * @param html True means the answer should use html
	 * @param d The original datagram send to ask the question
	 * @return The answer
	 */
	private String checkCommandables(String cmd, String question, boolean html, Datagram d) {
		final String f = cmd.replaceAll("\\d+","_"); // For special ones like sending data
		var cmdOpt = commandables.entrySet().stream()
				.filter( ent -> {
					String key = ent.getKey();
					if( key.equals(cmd)||key.equals(f))
						return true;
					return Arrays.stream(key.split(";")).anyMatch(k->k.equals(cmd)||k.equals(f));
				}).map(Map.Entry::getValue).findFirst();

		if (cmdOpt.isEmpty())
			return UNKNOWN_CMD;

		// If requested cmd exists
		String result;
		if (d.payload() != null) {
			result = cmdOpt.get().payloadCommand(cmd, question, d.payload());
		} else {
			result = cmdOpt.get().replyToCommand(cmd, question, d.getWritable(), html);
		}
		if (result == null || result.isEmpty()) {
			Logger.error("Got a null as response to " + question);
			return "! Something went wrong processing: " + question;
		}
		return result;
	}

	/**
	 * If the cmd didn't have a corresponding commandable, check if there's a TaskManager of which the ID matches the cmd.
	 * If so, the question is the task(set) to execute.
	 * @param tmId The TaskManager id to look for
	 * @param taskId The task(set) id
	 * @param wr The writable of the object asking (if any)
	 * @param html True means the answer should use html
	 * @return The answer
	 */
	private String checkTaskManagers(String tmId, String taskId, Writable wr,boolean html){
		var nl = html ? "<br>" : "\r\n";

		var tmCmd = commandables.get("tm");
		if (tmCmd == null) {
			Logger.warn("Tried to issue tm cmd without tm existing");
			return UNKNOWN_CMD;
		}
		String res = switch (taskId) {
			case "?", "list" ->
					tmCmd.replyToCommand("tm", tmId + ",sets", wr, html) + nl + tmCmd.replyToCommand("tm", tmId + ",tasks", wr, html);
			case "reload" -> tmCmd.replyToCommand("tm", tmId + ",reload", wr, html);
			default -> tmCmd.replyToCommand("tm", tmId + ",run," + taskId, wr, html);
		};
		if (!res.toLowerCase().startsWith("! no such taskmanager") &&
				!(res.toLowerCase().startsWith("! no taskmanager") && taskId.split(":").length==1))
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
	 * Executes commands that are related to store
	 * @param subCmd The command to execute, with arguments delimited with ,
	 * @return The result of the command
	 */
	private String doStoreCommands( String subCmd, Writable wr, boolean html ){
		var ans = StoreCmds.replyToCommand( subCmd, html );
		if( !subCmd.startsWith("?")) {
			if( subCmd.equalsIgnoreCase("global")) {
				doCmd("rtvals","reload",wr);// reload the global rtvals
			}else{
				doCmd("ss", subCmd.split(",")[0]+",reloadstore", wr);
			}
		}
		return ans;
	}

	/**
	 * Executes commands that are related to tinylog logging framework
	 * @param subCmd The command to execute, with arguments delimited with ,
	 * @return The result of the command
	 */
	private String doTinyLogCommands( String subCmd ){
		if( subCmd.contains(",")){
			String level = subCmd.substring(0,subCmd.indexOf(","));
			String mess = subCmd.substring(level.length()+1);
			switch( level ){
				case "info" -> Logger.info(mess);
				case "warn" -> Logger.warn(mess);
				case "error" -> Logger.error(mess);
			}
			return "Message logged";
		}
		return "! Not enough arguments";
	}

	private String doCommandable(String sub, Commandable target) {
		addCommandable(sub, target);
		return "Commandable added";
	}
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
		
		String[] spl = request[1].split(",");

		return switch (spl[0]) {
			case "?" -> {
				StringJoiner join = new StringJoiner("\r\n");
				join.add("Allows to update a script or the main settings file");
				join.add("upgrade:tmscript,tm id -> Try to update the given taskmanagers script")
						.add("upgrade:settings -> Try to update the settings.xml");
				yield LookAndFeel.formatCmdHelp(join.toString(),false);
			}
			case "tmscript" -> doUpgradeOfTaskManagerScript(spl[1],wr); // fe. update:tmscript,tmid
			case "settings" -> doUpgradeOfSettingsFile();
			default -> "! No such subcommand in upgrade: " +spl[0];
		};
	}
	private String doUpgradeOfTaskManagerScript( String subCmd, Writable wr){
		var tmCmd = commandables.get("tm");
		var ori = tmCmd.replyToCommand("tm", "getpath," + subCmd, wr, false);
		if (ori.isEmpty())
			return "! No such script";

		Path p = Path.of(ori);
		Path to = Path.of(ori.replace(".xml", "") + "_" + TimeTools.formatUTCNow("yyMMdd_HHmm") + ".xml");
		Path refr = Paths.storage().resolve( "attachments").resolve(subCmd);

		try {
			if (Files.exists(p) && Files.exists(refr)) {
				Files.copy(p, to);    // Make a backup if it doesn't exist yet
				Files.move(refr, p, StandardCopyOption.REPLACE_EXISTING);// Overwrite

				// somehow reload the script
				return tmCmd.replyToCommand("tm", "reload," + subCmd, wr, false);// Reloads based on id
			} else {
				Logger.warn("Didn't find the needed files.");
				return "! Couldn't find the correct files. (maybe check spelling?)";
			}
		} catch (IOException e) {
			Logger.error(e);
			return "! Error when trying to upgrade tmscript.";
		}
	}
	private String doUpgradeOfSettingsFile(){
		Path p = Paths.settings();
		Path to = Paths.storage().resolve( "settings_" + TimeTools.formatNow("yyMMdd_HHmm") + ".xml");
		Path refr = Paths.storage().resolve( "attachments" ).resolve("settings.xml");

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

		return switch (spl[0]) {
			case "?" -> {
				StringJoiner join = new StringJoiner(eol, "", eol);
				join.add("retrieve:tmscript,tm id,<email/ref> -> Request the given TaskManager script through email")
						.add("retrieve:settings,<email/ref> -> Request the current settings.xml through email");
				yield join.toString();
			}
			case "tmscript", "tmscripts" -> doRetrieveOfTaskManagerScript(spl,request,wr);
			case "setup", "settings" -> doRetrieveOfSettingsFile(spl);
			default -> "! No such subcommand in retrieve: " + spl[0];
		};
	}
	private String doRetrieveOfTaskManagerScript(String[] spl, String[] request, Writable wr){
		if (spl.length < 3)
			return "! Not enough arguments retrieve:type,tmid,email in " + request[0] + ":" + request[1];
		var p = commandables.get("tm").replyToCommand("tm", "getpath," + spl[1], wr, false);
		if (p.isEmpty())
			return "! No such script";
		sendEmail.sendEmail(Email.to(spl[2]).subject("Requested tm script: " + spl[1]).content("Nothing to say").attachment(p));
		return "Tried sending " + spl[1] + " to " + spl[2];
	}
	private String doRetrieveOfSettingsFile( String[] spl ){

		if (Files.notExists(Paths.settings())) {
			return "! No such file: " + Paths.settings();
		}
		if (spl.length != 2)
			return "! Not enough arguments, expected retrieve:setup,email/ref";
		sendEmail.sendEmail(Email.to(spl[1]).subject("Requested file: settings.xml").content("Nothing to say").attachment(Paths.settings()));
		return "Tried sending settings.xml to " + spl[1];
	}
	/* *******************************************************************************/

	/**
	 * Execute command to shut down dcafs, can be either sd or shutdown or sd:reason
	 * 
	 * @param request The full command split on the first :
	 * @param eol The eol to use
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	private String doShutDown( String[] request, String eol ){
		if( request[1].equals("?") )
			return "sd:reason -> Shutdown the program with the given reason, use force as reason to skip checks";
		shutdownReason = request[1].isEmpty()?"Telnet requested shutdown":request[1];
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
					join.add("   -> For sending data to a stream without eol, use 'ctrl+s'");
					join.add("   -> For sending ESC to a stream, use '\\e'");
					join.add("   -> ...").add("");
				break;
			case "filter": return FilterForward.getHelp(eol);
			case "math": break;
			case "editor": return EditorForward.getHelp(eol);
			default:	return "! No such subcommand in help: "+request[1];
		}
		return join.toString();
	}


}
