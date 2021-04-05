package das;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.email.EmailWorker;
import com.fazecast.jSerialComm.SerialPort;
import com.hardware.i2c.I2CWorker;
import com.stream.Writable;
import com.stream.StreamPool;
import com.stream.forward.FilterForward;
import com.stream.tcp.TcpServer;

import com.telnet.TelnetCodes;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import util.database.*;
import util.xml.XMLfab;
import util.gis.GisTools;
import util.math.MathUtils;
import util.task.TaskManager;
import util.tools.FileTools;
import util.tools.Tools;
import util.xml.XMLtools;
import worker.Datagram;
import worker.Generic;
import util.tools.TimeTools;

/**
 * Handles a server-side channel.
 */
public class BaseReq {

	protected static DateTimeFormatter secFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	protected RealtimeValues rtvals; // To have access to the current values
	protected StreamPool streampool = null; // To be able to interact with attached devices
	protected TcpServer trans = null; // To be able to make requests to the TransServer
	protected EmailWorker emailWorker; // To be able to send emails and get status
	protected IssueCollector issues=null;
	protected DatabaseManager dbManager;

	protected String title = "";

	protected DAS das;

	Map<String, Method> methodMapping = new HashMap<>();
	String name = "";
	protected String workPath = "";
	private int qState = 0;
	private Element tempElement;
	private XMLfab fab;
	private int tempInt=0;
	Document xml;

	static final String UNKNOWN_CMD = "unknown command";
	/* ******************************  C O N S T R U C T O R *********************************************************/
	/**
	 * Constructor requiring a link to the @see RealtimeValues for runtime values
	 * @param rtvals The current RealtimeValues
	 */
	public BaseReq(RealtimeValues rtvals){
		this.rtvals = rtvals;
		this.name = this.getClass().getName().split("\\.")[1];
	}
	/**
	 * Constructor requiring a link to the @see RealtimeValues for runtime values and @see IssueCollector to notify problems
	 * @param rtvals The current RealtimeValues
	 * @param issues The collector for the issues created by the BaseReq
	 */
	public BaseReq(RealtimeValues rtvals, IssueCollector issues) {
		this(rtvals);
		this.issues = issues;
	}
	/* ****************************  S E T U P - C H E C K U P: Adding different parts from DAS  *********************/
	/**
	 * Give the DAS object so it has access to everything it might need
	 * 
	 * @param das The reference to verything including itself... should be removed in the future
	 */
	public void setDAS(DAS das) {
		this.das = das;
	}
	/**
	 * Set the current working directory
	 * 
	 * @param path Path to the application
	 */
	public void setWorkPath(String path) {
		this.workPath = path;
	}

	/**
	 * To be able to send emails, access to the emailQueue is needed
	 * 
	 * @param emailWorker An reference to the emailworker
	 */
	public void setEmailWorker(EmailWorker emailWorker) {
		this.emailWorker = emailWorker;
	}

	/**
	 * To interact with streams/channels, access to the streampool is needed
	 * 
	 * @param streampool  A reference to the streampool
	 */
	public void setStreamPool(StreamPool streampool) {
		this.streampool = streampool;
	}
	public Optional<FilterForward> getFilter(String id) {
		return streampool.getFilter(id);
	}
	/**
	 * To handle data requests, access to the TransServer is needed
	 * 
	 * @param trans A reference to the TransServer
	 */
	public void setTcpServer(TcpServer trans) {
		this.trans = trans;
	}

	/**
	 * To have access to the realtime values
	 * 
	 * @param rtvals A reference to the RealtimeValues
	 */
	public void setRealtimeValues(RealtimeValues rtvals) {
		this.rtvals = rtvals;
	}

	/**
	 * Method to retrieve the RealtimeValues used by BaseReq
	 * 
	 * @return The currently used RealtimeValues
	 */
	public RealtimeValues getRealtimeValues() {
		return this.rtvals;
	}

	/**
	 * Set the IssueCollector to get answers from it
	 * 
	 * @param issues The currently used IssueCollector
	 */
	public void setIssues(IssueCollector issues) {
		this.issues = issues;
	}
	/**
	 * Check if the given issue is currently active
	 * 
	 * @param issue The issue to check
	 * @return True if it's active, false if it isn't (or doesn't exists)
	 */
	public boolean checkIssue(String issue) {
		return issues.isActive(issue);
	}

	/**
	 * Set the DatabaseManager to get answers from it
	 * 
	 * @param manager The sqlitesManager currently used
	 */
	public void setSQLitesManager(DatabaseManager manager) {
		this.dbManager = manager;
	}

	/* ************************************ * R E S P O N S E *************************************************/
	/**
	 * Request the current value of a parameter
	 * 
	 * @param parameter The parameter to receive
	 * @param defaultVal The value to return if the parameter wasn't found
	 * @return The requested parameter or defaultVal
	 */
	public String getRTval(String parameter, double defaultVal) {
		return ""+rtvals.getRealtimeValue(parameter.toLowerCase(), defaultVal);
	}
	
	public void emailResponse( Datagram d ) {
		emailResponse( d, "Bot Reply" );
	}

	public void emailResponse(Datagram d, String header) {
		/* If there's no valid queue, can't do anything */
		if (emailWorker == null) {
			Logger.info("Asked to email to " + d.getOriginID() + " but no worker defined.");
			return;
		}
		/* Notification to know if anyone uses the bot. */
		if ( (!d.getOriginID().startsWith("admin") && !emailWorker.isAddressInRef("admin",d.getOriginID()) ) && header.equalsIgnoreCase("Bot Reply")  ) {
			emailWorker.sendEmail("admin", "DASbot", "Received '" + d.getMessage() + "' command from " + d.getOriginID() );			
		}
		/* Processing of the question */
		d.setMessage( d.getMessage().toLowerCase());

		/* Writable is in case the question is for realtime received data */
		String response = createResponse( d.getMessage(), d.getWritable(), false, true );

		if (!response.toLowerCase().contains(UNKNOWN_CMD)) {
			response = response.replace("[33m ", "");
			emailWorker.sendEmail(d.getOriginID(), header, response.replace("\r\n", "<br>"));
		} else {
			emailWorker.sendEmail(d.getOriginID(), header,
					"Euh " + d.getOriginID().substring(0, d.getOriginID().indexOf(".")) + ", no idea what to do with '" + d.getMessage() + "'...");
		}
	}

	/**
	 * A question is asked to the BaseReq through this method, a TransDescriptor is
	 * passed for streaming data questions
	 * 
	 * @param question The command/Question to process
	 * @param wr
	 * @param remember Whether or not the command should be recorded in the raw data
	 * @return The response to the command/question
	 */
	public String createResponse(String question, Writable wr, boolean remember) {
		return createResponse(question, wr, remember, false);
	}

	/**
	 * A question is asked to the BaseReq through this method, a TransDescriptor is
	 * passed for streaming data questions
	 * 
	 * @param question The command/Question to process
	 * @param wr  Writable in order to be able to respond to streaming
	 *                 data questions
	 * @param remember Whether or not the command should be recorded in the raw data
	 * @param html     If the response should you html encoding or not
	 * @return The response to the command/question
	 */
	public String createResponse(String question, Writable wr, boolean remember, boolean html) {

		String result = UNKNOWN_CMD;

		if (!html) // if html is false, verify that the command doesn't imply the opposite
			html = question.endsWith("html");

		question = question.replace("html", "");

		if (remember) // Whether or not to store commands in the raw log (to have a full simulation when debugging)
			Logger.tag("RAW").info("1\tsystem\t" + question);

		int dp = question.indexOf(":");

		String[] split = new String[]{"",""};
		if( dp != -1){
			split[0]=question.substring(0, question.indexOf(":"));
			split[1]=question.substring(question.indexOf(":")+1);
		}else{
			split[0]=question;
		}
		split[0]=split[0].toLowerCase();
		String find = split[0].replaceAll("[0-9]+", "_");
		
		if( find.equals("i_c") || find.length() > 3 ) // Otherwise adding integrated circuits with their name is impossible
			find = split[0];
			
		find = find.isBlank() ? "nothing" : find;
		
		Method m = methodMapping.get(find);		

		if (m != null) {
			try {
				result = m.invoke( this, split, wr, html).toString();
			} catch (IllegalAccessException | IllegalArgumentException e) {
				Logger.warn("Invoke Failed: " + question);
				result = "Error during invoke.";
			}catch (InvocationTargetException e) {
				Throwable originalException = e.getTargetException();
				Logger.error( "'"+originalException+"' at "+originalException.getStackTrace()[0].toString()+" when processing: "+question);
				Logger.error(e);
			 }
		}
		if( m == null || (m!= null && result.startsWith(UNKNOWN_CMD)) ){
			var tm = das.taskManagers.get(split[0]);
			if( tm != null){
				if( split[1].equals("?")||split[1].equals("list")){
					return tm.getTaskSetListing(html ? "<br>" : "\r\n")+tm.getTaskListing(html ? "<br>" : "\r\n");
				}else{
					if( tm.hasTaskset(split[1])){
						return tm.startTaskset(split[1]);
					}else{
						return (tm.startTask(split[1])?"Task started ":"No such task(set) ")+split[1];
					}
				}
			}
		}
		if(m==null) {
			Logger.warn("Not defined:" + question + " because no method named " + find + ".");
		}
		return result + (html ? "<br>" : "\r\n");
	}

	/* *******************************************************************************************/
	/**
	 * Search the class for relevant methods.
	 */
	public void getMethodMapping() {

		Class<?> reqdata = this.getClass();
		ArrayList<Method> methods = new ArrayList<>();

		methods.addAll(Arrays.asList(reqdata.getDeclaredMethods()));

		if (reqdata.getSuperclass() == BaseReq.class) { // To make sure that both the child and the parent class are
														// searched
			methods.addAll(Arrays.asList(reqdata.getSuperclass().getDeclaredMethods()));
		}
		for (Method method : methods) { // Filter the irrelevant methods out
			String com = method.getName();
			if (com.length() >= 3 && com.startsWith("do")) { // Needs to be atleast 3 characters long and start with
																// 'do'
				com = com.substring(2); // Remove the 'do'
				String high = ""; // 'high' will contain the capital letters from the command to form the
									// alternative command
				for (int a = 0; a < com.length(); a++) {
					char x = com.charAt(a);
					if (Character.isUpperCase(x) || x == '_') {
						high += x;
					}
				}
				methodMapping.put(com.toLowerCase(), method);				
				if (high.length() != com.length()) { // if both commands aren't the same
					methodMapping.put(high.toLowerCase(), method);				
				}
			}
		}
		Logger.info("Found " + methodMapping.size() + " usable methods/commands.");
	}

	/* ******************************************  C O M M A N D S ****************************************************/
	/**
	 * Command that creates a list of all available commands. Then execute a request
	 * with '?' from each which should return the info.
	 * 
	 * @param request The full request as received, [0]=method and [1]=command
	 * @param wr The writable of the source of the command
	 * @param html    True if the command needs to be hmtl formatted
	 * @return Response to the request
	 */
	public String doCMDS(String[] request, Writable wr, boolean html) {
		String nl = html ? "<br>" : "\r\n";
		
		StringJoiner join = new StringJoiner( nl, "List of base commands:"+nl,"");
		
		boolean wildcard = request[1].startsWith("*")&&request[1].endsWith("*");
		String filter = wildcard?"":request[1];

		ArrayList<String> titles = new ArrayList<>();
		methodMapping.keySet().stream().filter( x -> x.startsWith(filter))
									   .forEach( titles::add );

		Collections.sort(titles); // Sort it so that the list is alphabetical

		ArrayList<String> results = new ArrayList<>();
		for (String t : titles) {
			String result;
			try {
				if (t.equals("cmds")) // ignore the cmds command otherwise endless loop
					continue;
					
				result = methodMapping.get(t).invoke(this, new String[]{t,"?"}, null, false).toString(); // Execute command with '?'

				if ( result.isBlank() || result.toLowerCase().startsWith(UNKNOWN_CMD) || result.toLowerCase().startsWith("No")) {
					results.add(t);
				} else {
					result = result.replace("<title>", t);
					if (!result.startsWith(t)) {
						results.add(t+result+nl);
					} else {
						results.add(nl+result);
					}
				}
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				Logger.error(e.getMessage()+" while trying "+t);
				join.add(t);
			}
		}
		if( request[1].startsWith("*")&&request[1].endsWith("*") ){
			request[1]=request[1].replace("*", "");
			boolean lastBlank=false; // So that there arent two empty lines in succession
			for( String l : results){
				for( String sub : l.split(nl) ){
					if( sub.isBlank() && !lastBlank ){						
						join.add(sub);
						lastBlank=true;
					 }else if( sub.contains(request[1]) ){
						join.add(sub);
						lastBlank=false;
					 }
				}
			}						
		}else{
			results.stream().forEach(join::add);
		}
		return join.toString();
	}
	/* ******************************************************************************/
	/**
	 * Calculate the checksum of the given item, for now only rawyesterday exists
	 * @param request The full command checksum:something
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Calculated checksum
	 */
	public String doCHECKSUM( String[] request, Writable wr, boolean html ){
		
		// Check for files with wildcard? 2019-07-24_RAW_0.log.zip		
		StringBuilder b = new StringBuilder();

		switch( request[1] ){
			case "rawyesterday":
				String yesterday = "raw"+File.separator+"zipped"+File.separator+TimeTools.formatNow( "yyyy-MM", -1)+File.separator+TimeTools.formatNow( "yyyy-MM-dd", -1)+"_RAW_x.log.zip";
				int cnt=0;
				String path = yesterday.replace("x", ""+cnt);
				boolean ok = Files.exists( Paths.get(path) );
				
				while(ok){					
					String md5 = MathUtils.calculateMD5( Paths.get(path) );
					b.append(path).append("\t").append(md5).append("\r\n");
					cnt++;
					path = yesterday.replace("x", ""+cnt);
					ok = Files.exists( Paths.get(path) );
				}
				return b.toString();
			case "?":
				return "checksum:rawyesterday -> Calculate checksum of the stored raw data (WiP";	
			default:
				return UNKNOWN_CMD+": "+request[1];
		}
	}  
	/* ********************************************************************************************/
	/**
	 * Try to update a file received somehow (email or otherwise)
	 * Current options: das,script and settings (das is wip)
	 * 
	 * @param request The full command update:something
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doUPDATE(String[] request, Writable wr, boolean html) {
		
		Path p=null;
		Path to=null;
		Path refr=null;

		String[] spl = request[1].split(",");

		switch (spl[0]) {
			case "?":
				StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
				join.add( "update:das -> Try to update DAS Core")
					.add( "update:script,<script name> -> Try to update the given script")
					.add( "update:settings -> Try to update the settings.xml");
				return join.toString();			
			case "das":
				Logger.info("Trying to update DAS...");
				p = Paths.get("DAS.jar");
				to = Paths.get("DAS_" + TimeTools.formatNow("yyMMdd_HHmm") + ".jar");
				refr = Paths.get("attachments"+File.separator+"DAS.jar");
				try {
					if( !Files.exists(p) ){
						return "Didn't find an active DAS.jar?";
					}
					if( Files.exists(refr) ){
						Files.copy(p, to );	// Make a backup if it doesn't exist yet
						Logger.info("Made a backup copy...");
					}else{
						Logger.warn("Didn't find the needed files: ");
						return "Didn't find the attachment.";
					}
				} catch (IOException e) {
					e.printStackTrace();
					return "Something went wrong: "+e.getMessage();
				}
				break;
			case "script"://fe. update:script,tasks.xml
				if( !spl[1].endsWith(".xml"))
					spl[1] += ".xml";
				p = Paths.get("scripts",spl[1]);
				to = Paths.get("scripts",spl[1].replace(".xml", "")+"_" + TimeTools.formatUTCNow("yyMMdd_HHmm") + ".xml");
				refr = Paths.get("attachments",spl[1]);
				try {
					if( Files.exists(p) && Files.exists(refr) ){
						Files.copy(p, to );	// Make a backup if it doesn't exist yet
						Files.move(refr, p , StandardCopyOption.REPLACE_EXISTING );// Overwrite
						
						// somehow reload the script
						return das.reloadTaskmanager(spl[1]);// Reloads based on filename OR id
					}else{
						Logger.warn("Didn't find the needed files.");
						return "Couldn't find the correct files. (maybe check spelling?)";
					}
				} catch (IOException e) {
					Logger.error(e);
				}
				break;
			case "setup":
				p = Paths.get("settings.xml");
				to = Paths.get( "settings"+"_" + TimeTools.formatNow("yyMMdd_HHmm") + ".xml");
				refr = Paths.get("attachments"+File.separator+"settings.xml");
				try {
					if( Files.exists(p) && Files.exists(refr) ){
						Files.copy(p, to );	// Make a backup if it doesn't exist yet
						Files.copy(refr, p , StandardCopyOption.REPLACE_EXISTING );// Overwrite
						das.setShutdownReason( "Replaced settings.xml" );    // restart das
						System.exit(0);
					}else{
						Logger.warn("Didn't find the needed files.");
						return "Couldn't find the correct files. (maybe check spelling?)";
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
				break;
			default: return UNKNOWN_CMD+": "+spl[0];
		}
		return UNKNOWN_CMD+": "+spl[0];
	}
	/**
	 * Command to retrieve a setup file, can be settings.xml or a script
	 * eg. retrieve:script,scriptname.xml or retrieve:setup for the settings.xml
	 * 
	 * @param request The full command update:something
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doRETRIEVE(String[] request, Writable wr, boolean html) {
		
		if( emailWorker==null)
			return "Can't retrieve without EmailWorker";

		String[] spl = request[1].split(",");
		
		switch( spl[0] ){
			case "?":
				StringJoiner join = new StringJoiner(html?"<br>":"\r\n","",html?"<br>":"\r\n");
				join.add( "retrieve:script,<scriptname>,<email/ref> -> Request the given script through email")
					.add( "retrieve:setup,<email/ref> -> Request the current settings.xml through email");
				return join.toString();
			case "script":case "scripts":
				if( spl.length <2 )
					return "Not enough arguments retrieve:type,filename,email in "+request[0]+":"+request[1];
				if( !spl[1].endsWith(".xml"))
					spl[1] += ".xml";		

				Path p = Paths.get("scripts",spl[1]);
				if( Files.notExists(p) ){
					return "No such file: "+p.toString();
				}
				emailWorker.sendEmail(spl[2], "Requested file: "+spl[1], "Nothing to say", p.toString(),false);
				return "Tried sending "+spl[1]+" to "+spl[2];
			case "setup":
			case "settings":
				Path set = Paths.get("settings.xml");
				if( Files.notExists(set) ){
					return "No such file: "+set.toString();
				}
				if( spl.length!=2)
					return "Not enough arguments, expected retrieve:setup,email/ref";
				emailWorker.sendEmail(spl[1], "Requested file: settings.xml", "Nothing to say", "settings.xml",false);
				return "Tried sending settings.xml to "+spl[1];
			default: return UNKNOWN_CMD+":"+spl[0];
		}
	}
	/* *******************************************************************************/
	/**
	 * Execute commands associated with the TransServer
	 * 
	 * @param request The full command split on the first :
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doTRANS( String[] request, Writable wr, boolean html ){
	
		if( trans==null){
			if( request[1].startsWith("create") ){
				String[] split = request[1].split(",");				
				int port = split.length==2?Integer.parseInt(split[1]):-1;
				das.addTcpServer(port);
				trans.alterXML();
				trans.run();
				return "Tried to create and start the TransServer";
			}
			return "No TransServer defined.";
		}
		return trans.replyToRequest(request[1], wr);
	}
	public String doRAW( String[] request, Writable wr, boolean html ){
		if( streampool.addForwarding(request[1], wr ) ){
			return "Request for "+request[0]+":"+request[1]+" ok.";
		}else{
			return "Request for "+request[0]+":"+request[1]+" failed.";
		}
	}
	public String doMATH(String[] request, Writable wr, boolean html ){
		if( streampool.addForwarding( "math:"+request[1], wr ) ){
			return "Request for "+request[0]+":"+request[1]+" ok.";
		}else{
			return "Request for "+request[0]+":"+request[1]+" failed.";
		}
	}
	public String doFILTER( String[] request, Writable wr, boolean html ){
		if( streampool.addForwarding( "filter:"+request[1], wr ) ){
			return "Request for "+request[0]+":"+request[1]+" ok.";
		}else{
			return "Request for "+request[0]+":"+request[1]+" failed.";
		}
	}
	public String doEDITOR( String[] request, Writable wr, boolean html ){
		if( streampool.addForwarding( "editor:"+request[1], wr ) ){
			return "Request for "+request[0]+":"+request[1]+" ok.";
		}else{
			return "Request for "+request[0]+":"+request[1]+" failed.";
		}
	}
	public String doCALC( String[] request, Writable wr, boolean html ){
		if( request[1].equals("reqs")){
			return rtvals.getRequestList("calc:reqs");
		}
		rtvals.addRequest(wr, "calc:"+request[1]);
		return "Request added: calc:"+request[1];
	}
	public String doSTORE( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return "store:rtval,value -> Store the value as the given rtval";
		String[] spl = request[1].split(",");
		if( spl.length==2){
			rtvals.setRealtimeValue(spl[0], NumberUtils.createDouble(spl[1]));
			return "Value saved.";
		}
		return "Unknown command: "+request[0]+":"+request[1];
	}
	public String doRTVAL( String[] request, Writable wr, boolean html ){
		if( request[1].equals("reqs") )
			return rtvals.getRequestList("rtval:reqs");

		if( request[1].equals("?") )
			return "rtval:x -> Get the realtimevalue x at 1Hz.";
		try{
			if( request[1].equals("") ){
				StringJoiner b = new StringJoiner(html?"<br>":"\r\n");
				b.setEmptyValue("No options yet.");
				b.add("");
				for( String rt : rtvals.getRealtimePairs() )
					b.add(rt);

				return "RTval options:"+b.toString();
			}
			if( request[1].endsWith("*")){
				request[1] = StringUtils.removeEnd(request[1],"*");
				rtvals.getRealtimeParameters().stream().filter(x -> x.startsWith(request[1]))
						.forEach( param -> rtvals.addRequest(wr,"rtval:"+param));
			}else if( request[1].startsWith("*")){
				request[1] = request[1].substring(1);
				rtvals.getRealtimeParameters().stream().filter(x -> x.startsWith(request[1]))
						.forEach( param -> rtvals.addRequest(wr,"rtval:"+param));
			}else{
				rtvals.addRequest(wr, "rtval:"+request[1]);
			}
			return "Request added";
		}catch(NullPointerException e){
			Logger.error(e);			
			return "Null pointer...";
		}
	}

	/**
	 * Execute commands associated with the @see StreamPool
	 * 
	 * @param request The full command split on the first :
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doStreamS(String[] request, Writable wr, boolean html ){
		if( streampool == null ){
			return "No StreamPool defined.";
		}
		return streampool.replyToCmd(request[1], html);
	}
	public String doFilterForward( String[] request, Writable wr, boolean html ){
		if( streampool == null ){
			return "No StreamPool defined.";
		}
		return streampool.replyToFilterCmd(request[1], wr, html);
	}
	public String doMathForward( String[] request, Writable wr, boolean html ){
		if( streampool == null ){
			return "No StreamPool defined.";
		}
		return streampool.replyToMathCmd(request[1], wr, html);
	}
	public String doRIOS( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return "rios -> Get a list of the currently active streams.";
		return doStreamS( new String[]{"streams","rios"}, wr, html);
	}
	public String doH_( String[] request, Writable wr, boolean html ){
		if( streampool == null )
			return "No StreamPool defined.";

		if( request[1].equals("?") ){
			return "Hx:y -> Send the hex y to stream x";
		}
		int nr = Tools.parseInt( request[0].substring(1), -1 );    		
		if( nr >= 0 && nr <= streampool.getStreamCount()){
			String channel = streampool.getStreamID(nr);
			
			boolean ok = !streampool.writeBytesToStream(channel, Tools.fromHexStringToBytes(request[1]),true ).isEmpty();

			if( !ok )
				return "Failed to send "+request[1]+" to "+channel;
			return "Sending command '"+request[1]+"' to "+channel;
		}else{
			switch( streampool.getStreamCount() ){
				case 0:
					return "No streams active to send data to.";
				case 1:
					return "Only one stream active. S1:"+streampool.getStreamID(0);
				default:
					return "Invalid number chosen! Must be between 1 and "+streampool.getStreamCount();    					    			
			}
		}
	}
	public String doS_( String[] request, Writable wr, boolean html ){	
		
		if( streampool == null )
			return "No StreamPool defined.";

		if( request[1].equals("?") ){
			return "Sx:y -> Send the string y to stream x";
		}
		if( request[1].isEmpty() )
			return "No use sending an empty string";

		String stream = streampool.getStreamID( Tools.parseInt( request[0].substring(1), 0 ) -1);
		if( !stream.isEmpty()){
			request[1] = request[1].replace("<cr>", "\r").replace("<lf>", "\n"); // Normally the delimiters are used that are chosen in settings file, extra can be added
			
			if( !streampool.writeToStream(stream, request[1], "" ).isEmpty() )
				return "Sending '"+request[1]+"' to "+stream;
			return "Failed to send "+request[1]+" to "+stream;
			
		}else{
			switch( streampool.getStreamCount() ){
				case 0:
					return "No streams active to send data to.";
				case 1:
					return "Only one stream active. S1:"+streampool.getStreamID(0);
				default:
					return "Invalid number chosen! Must be between 1 and "+streampool.getStreamCount();    					    			
			}
		}
	}

	/**
	 * Execute commands associated with the @see IssueCollector
	 * 
	 * @param request The full command split on the first :
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doISSUEs( String[] request, Writable wr, boolean html ){			
		if( issues == null )
			return "No IssueCollector defined.";	
        return issues.replyToSingleRequest(request[1], html); 
	}

	/**
	 * Execute commands associated with serialports on the system
	 * 
	 * @param request The full command split on the first :
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doSERIALPORTS( String[] request, Writable wr, boolean html ){
		StringBuilder response = new StringBuilder();
		
		if( request[1].equals("?") )
			return " -> Get a list of available serial ports on the PC running DAS.";

		response.append("Ports found: ").append(html ? "<br>" : "\r\n");
		for( SerialPort p : SerialPort.getCommPorts())
			response.append(p.getSystemPortName()).append(html ? "<br>" : "\r\n");
		response.append(html?"<br>":"\r\n");
		return response.toString();
	}
	/**
	 * Execute command to shutdown DAS, can be either sd or shutdown or sd:reason
	 * 
	 * @param request The full command split on the first :
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Descriptive result of the command, "Unknown command if not recognised
	 */
	public String doShutDown( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return " -> Shutdown the program ";	
		String reason = request[1].isEmpty()?"Telnet requested shutdown":request[1];
		das.setShutdownReason( reason );
		System.exit(0);                    
		return "Shutting down program..."+ (html?"<br>":"\r\n");
	}
	/**
	 * Get the content of the help.txt
	 * 
	 * @param request The full command split on the first :
	 * @param wr The writable of the source of the command
	 * @param html Whether or not to use html for newline etc
	 * @return Content of the help.txt or 'No telnetHelp.txt found' if not found
	 */
	public String doHelp( String[] request, Writable wr, boolean html ){		
		String nl = html?"<br":"\r\n";
		StringJoiner join = new StringJoiner(nl,"",nl);
		join.setEmptyValue(UNKNOWN_CMD+": "+request[0]+":"+request[1]);
		switch(request[1]){
			case "?":
					join.add("help -> First use tips");
				break;
				case "":
					join.add(TelnetCodes.TEXT_RED+"General commands"+TelnetCodes.TEXT_YELLOW);
					join.add("  st -> Get the current status of das, lists streams, databases etc");
					join.add("  cmds -> Get al list of all available commands").add("");
					join.add(TelnetCodes.TEXT_RED+"General tips"+TelnetCodes.TEXT_YELLOW)
						.add("   -> Look at settings.xml file (in das.jar folder) in a viewer to see what das does")
						.add("   -> Open two or more telnet instances fe. one for commands and other for live data").add("");
					join.add(TelnetCodes.TEXT_RED+"Recommended workflow:"+TelnetCodes.TEXT_YELLOW);
					join.add(TelnetCodes.TEXT_GREEN+"1) Connect to a data source"+TelnetCodes.TEXT_YELLOW)
						.add("   -> For udp, tcp and serial, use streams:? or ss:? for relevant commands")
						.add("   -> For MQTT, use mqtt:? for relevant commands")
						.add("   -> For I2C/SPI check the manual and then use i2c:?");
					join.add(TelnetCodes.TEXT_GREEN+"2) Look at incoming data"+TelnetCodes.TEXT_YELLOW)
						.add("   -> raw:id:stream -> Show the data received at the stream with the given id eg. raw:id:gps")
						.add("   -> raw:label:stream -> Show the data received at the streams with the given label")
						.add("   -> mqtt:forward,id -> Show the data received from the mqtt broker with the given id")
						.add("   -> mqtt:forward,id -> Show the data received from the mqtt broker with the given id")
						.add("   -> i2c:forward,id -> Show the data received from the i2c device with the given id");
					join.add(TelnetCodes.TEXT_GREEN+"3) Alter the data stream to a delimited set of values"+TelnetCodes.TEXT_YELLOW)
						.add("   -> Use MathForward to apply arithmetic operations on it, see mf:?")
						.add("      See the result with math:id")
						.add("   -> If the stream contains various messages, split it out using FilterForward, see ff:?")
						.add("      See the result with filter:id");
					join.add(TelnetCodes.TEXT_GREEN+"4) Collect the data after the optional math and filter"+TelnetCodes.TEXT_YELLOW)
						.add("   -> Use generics to store the data in memory, see gens:?")
						.add("   -> Use MathCollector to calculate averages, standard deviation etc, see mc:? (todo:implementing commands)")
						.add("   -> See a snapshot of the data in memory with rtvals or rtval:name to receive updates on a specific on")
						.add("   -> Use ValMap to collect data that's formatted according to param,value (or any other delimiter)");
					join.add(TelnetCodes.TEXT_GREEN+"5) Create/connect to a database"+TelnetCodes.TEXT_YELLOW);
					join.add("   -> Send dbm:? for commands related to the database manager");
					join.add(TelnetCodes.TEXT_GREEN+"6) Somehow get the data received in 1 into 5"+TelnetCodes.TEXT_YELLOW);
					join.add("   -> See the manual about how to use generics (Reference Guide -> Generics)");
					join.add(TelnetCodes.TEXT_GREEN+"7) Do other things"+TelnetCodes.TEXT_YELLOW);
					join.add("   -> For scheduling events, check taskmanager");
					join.add("   -> ...").add("");
				break;
			case "start": 

				break;
			default:	return UNKNOWN_CMD+":"+request[1];
		}
		return join.toString();
	}

	public String doListThread( String[] request, Writable wr, boolean html ){	
		if( request[1].equals("?") )
			return " -> Get a list of the currently active threads";

		StringBuilder response = new StringBuilder();
		ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
        Thread[] lstThreads = new Thread[currentGroup.activeCount()];
		currentGroup.enumerate(lstThreads);
		response.append("\r\n");
		for (Thread lstThread : lstThreads)
			response.append("Thread ID:" + lstThread.getId() + " = " + lstThread.getName() + "\r\n");
		return response.toString();   
	}

	public String doADMIN( String[] request, Writable wr, boolean html ){	
		
		String[] cmd = request[1].split(",");
		switch( cmd[0] ){
			case "?":
				StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
				join.add("admin:getlogs -> Get the latest logfiles")
					.add("admin:sms -> Send a test SMS to the admin number")
					.add("admin:haw -> Stop all workers")
					.add("admin:clock -> Get the current timestamp")
					.add("admin:regix,<regex>,<match> -> Test a regex")
					.add("admin:sqlfile,yes/no -> Start/stop logging queries to raw/yyyy-MM/SQL_queries.log")
					.add("admin:ipv4 -> Get the IPv4 and MAC of all network interfaces")
					.add("admin:ipv6 -> Get the IPv6 and MAC of all network interfaces")
					.add("admin:methodcall -> Get the time passed since a certain BaseWorker method was called");
				return join.toString();
			case "getlogs":
				if( emailWorker == null )
					return "Failed to send logs to admin, no worker.";
				emailWorker.sendEmail( "admin","Statuslog","File attached (probably)", workPath+"logs"+File.separator+"info.log", false );
				emailWorker.sendEmail( "admin","Errorlog","File attached (probably)", workPath+"logs"+File.separator+"errors_"+TimeTools.formatUTCNow("yyMMdd")+".log", false );
				return "Sending logs (info,errors) to admin...";
			case "sms":
				das.sendSMS("admin", "Test");
				return "Trying to send SMS\r\n";
			case "haw":
				das.haltWorkers();
				return "\r\nStopping all worker threads."; 
			case "clock": return TimeTools.formatLongUTCNow();
			case "regex":
				if( cmd.length != 3 )
					return "Invalid amount of parameters";
				return "Matches? "+cmd[1].matches(cmd[2]);
			case "methodcall":
				return das.getBaseWorker().getMethodCallAge( html?"<br>":"\r\n" );
			case "ipv4": return Tools.getIP("", true);
			case "ipv6": return Tools.getIP("", false);
			case "reboot":
				String os = System.getProperty("os.name").toLowerCase();
				if( !os.startsWith("linux")){
					return "Only Linux supported for now.";
				}
				try {
					ProcessBuilder pb = new ProcessBuilder("bash","-c","shutdown -r +1");
					pb.inheritIO();
					Process process;

					Logger.error("Started restart attempt at "+TimeTools.formatLongUTCNow());
					process = pb.start();
					//process.waitFor();
					System.exit(0); // shutting down das
				} catch (IOException e) {
					Logger.error(e);
				}
				return "Never gonna happen?";

			default: return UNKNOWN_CMD+" : "+request[1];
		} 
	}	


	public String doEMAIL( String[] request, Writable wr, boolean html ){	
		
		if( request[1].equalsIgnoreCase("addblank") ){
			if( EmailWorker.addBlankEmailToXML( das.getXMLdoc(), true,true) )
				return "Adding default email settings";
			return "Failed to add default email settings";
		}
		
		if( emailWorker == null )
			return "No EmailWorker defined (yet), use email:addblank to add blank to xml.";
		return emailWorker.replyToSingleRequest(request[1], html);
	}


	public String doREQTASKS( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return ":x -> Send a list of all the taskset executions to x";

		if(  request[1].equals("") )
			return "No recipient given.";
		
		if( emailWorker != null ){
			emailWorker.sendEmail(request[1],"Executed tasksets","Nothing to add","logs/tasks.csv", false );
			return "Sending log of taskset execution to "+request[1]; 
		}
		return "Failed to send Taskset Execution list.";
	}


	public String doTaskManager( String[] request, Writable wr, boolean html ){					
		String nl = html?"<br>":"\r\n";
		StringJoiner response = new StringJoiner(nl);
		String[] cmd = request[1].split(",");

		if( das.taskManagers.isEmpty() && !cmd[0].equalsIgnoreCase("addblank"))
			return "No TaskManagers active, only tm:addblank available.";

		TaskManager tm;

		switch( cmd[0] ){
			case "?":
				response.add( "tm:reloadall -> Reload all the taskmanagers")
						.add( "tm:stopall -> Stop all the taskmanagers")
						.add( "tm:managers -> Get a list of currently active TaskManagers")
						.add( "tm:remove,x -> Remove the manager with id x")
						.add( "tm:run,id:task(set) -> Run the given task(set) from taskmanager id, taskset has priority if both exist")
						.add( "tm:addblank,id -> Add a new taskmanager, creates a file etc")
						.add( "tm:x,y -> Send command y to manager x");
				return response.toString();
				case "addtaskset":
					if( cmd.length != 3)
						return "Not enough parameters, need tm:addtaskset,id,tasksetid";
					tm = das.taskManagers.get(cmd[1]);
					if( tm !=null ) {
						if( tm.addBlankTaskset(cmd[2]) ){
							return "Taskset added";
						}
						return "Failed to add taskset";
					}
					return "No such TaskManager "+cmd[1];
				case "addblank":
					if( cmd.length != 2)
						return "Not enough parameters, need tm:addblank,id";

					// Add to the settings xml
					try {
						Files.createDirectories(Paths.get("scripts"));
					} catch (IOException e) {
						Logger.error(e);
					}
					XMLfab tmFab = XMLfab.withRoot(das.getXMLdoc(), "das","settings");
					tmFab.addChild("taskmanager","scripts"+File.separator+cmd[1]+".xml").attr("id",cmd[1]).build();
					tmFab.build();

					// Create an empty file
					XMLfab.withRoot(Paths.get("scripts",cmd[1]+".xml"), "tasklist")
						.comment("Any id is case insensitive")
						.comment("Reload the script using tm:reload,"+cmd[1])
						.comment("If something is considered default, it can be omitted")
						.comment("There's no hard limit to the amount of tasks or tasksets")
						.comment("Task debug info has a separate log file, check logs/tasks.log")
						.addParent("tasksets","Tasksets are sets of tasks")
							.comment("Below is an example taskset")
							.addChild("taskset").attr("run","oneshot").attr("id","example")
							.comment("run can be either oneshot (start all at once) or step (one by one), default is oneshot")
								.down().addChild("task","Hello World from "+cmd[1]).attr("output","log:info")
										.addChild("task","Goodbye :(").attr("output","log:info").attr("trigger","delay:2s")
								.up()
						.addParent("tasks","Tasks are single commands to execute")
							.comment("Below is an example task, this will be called on startup or if the script is reloaded")
							.addChild("task","taskset:example").attr("output","system").attr("trigger","delay:1s")
							.comment("This task will wait a second and then start the example taskset")
							.comment("A task doesn't need an id but it's allowed to have one")
							.comment("Possible outputs: stream:id , system (default), log:info, email:ref, manager")
							.comment("Possible triggers: delay, interval, while,")
							.comment("For more extensive info and examples, check Reference Guide - Taskmanager in the manual")
						.build();

				// Add it to das		
				das.addTaskManager(cmd[1], Paths.get("scripts",cmd[1]+".xml"));
				
				return "Tasks script created, use tm:reload,"+cmd[1]+" to run it.";
			case "reload":
				if( cmd.length != 2)
					return "Not enough parameters, missing id";
				tm = das.taskManagers.get(cmd[1]);
				if( tm == null)
					return "No such TaskManager: "+cmd[1];
				if( tm.reloadTasks() )
					return "Tasks reloaded";
				return "Tasks failed to reload";
			case "reloadall": 
				for(TaskManager tam : das.taskManagers.values() )
					tam.reloadTasks();
				return "Reloaded all TaskManagers.";
			case "stopall":
				for(TaskManager tam : das.taskManagers.values() )
					tam.stopAll("baseReqManager");
				return "Stopped all TaskManagers.";   
			case "managers": case "list":
				response.add("Currently active TaskManagers:");
				das.taskManagers.keySet().forEach(response::add);
				return response.toString();
			case "run":
				if( cmd.length != 2)
					return "Not enough parameters, missing manager:taskset";
				String[] task = cmd[1].split(":");
				tm = das.taskManagers.get(task[0]);
				if( tm == null)
					return "No such taskmanager: "+task[0];
				if( tm.hasTaskset(task[1])){
					return tm.startTaskset(task[1]);
				}else{
					return tm.startTask(task[1])?"Task started":"No such task(set) "+task[1];
				}
			case "remove":
				if( das.taskManagers.remove(cmd[1]) == null ){
					return "Failed to remove the TaskManager, unknown key";
				}else{
					return "Removed the TaskManager";
				}
			default:				
				if( cmd.length==1)
					return UNKNOWN_CMD+": "+ Arrays.toString(request);

				tm = das.taskManagers.get(cmd[0]);
				if( tm != null ){
					return tm.replyToCmd( request[1].substring(request[1].indexOf(",")+1), html);
				}else{
					return "No such TaskManager: "+cmd[0];
				}				
		}    
	}

	
	public String doSEttings( String[] request, Writable wr, boolean html ){	
		if( request[1].equals("?") )
			return " -> Get a list of the settings";

		return das.getSettings();
	}

	public String doNOTHING( String[] request, Writable wr, boolean html ){	
		if( request[1].equals("?") )
			return " -> Clear the datarequests";
		if( wr != null ){
			rtvals.removeRequest(wr);
			streampool.removeForwarding(wr);
			das.getI2CWorker().ifPresent( i2c -> i2c.removeTarget(wr));
		}
		return "Clearing all data requests\r\n";
	}	

	
	public String doSTatus( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return " -> Get a status update";

		String response = "";	
		try{
			response =  das.getStatus(html);   
		}catch( java.lang.NullPointerException e){
			Logger.error("Nullpointer Error status: "+e.getMessage()+" at "+e.getStackTrace().toString());
		}
		return response;       	
	}
	
	
	public String doRTVALS( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return " -> Get a list of all rtval options";
		return rtvals.getFilteredRTVals(request[1],html?"<br>":"\r\n");			
	}
	
	public String doRTTEXTS( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return " -> Get a list of all rttext options";
		return rtvals.getFilteredRTTexts(request[1],html?"<br>":"\r\n");		
	}
	public String doRTS( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return " -> Get a list of all rtvals & rttext options";
		return rtvals.getFilteredRTVals(request[1],html?"<br>":"\r\n")
				+(html?"<br>":"\r\n")+(html?"<br>":"\r\n")
				+rtvals.getFilteredRTTexts(request[1],html?"<br>":"\r\n");
	}
	public String doWayPoinTS( String[] request, Writable wr, boolean html ){		
		return rtvals.getWaypoints().replyToSingleRequest(request[1], html, 0.0 );
	}
	
	public String doCONVert( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?") )
			return " -> Convert a coordinate in the standard degrees minutes format";		
		
		BigDecimal bd60 = BigDecimal.valueOf(60);	            	
		StringBuilder b = new StringBuilder();
		String[] items = request[1].split(";");
		ArrayList<Double> degrees = new ArrayList<>();
		
		for( String item : items ){
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
	
	public String doI2C( String[] request, Writable wr, boolean html ){						
		
		String[] cmd = request[1].split(",");

		switch( cmd[0] ){
			case "?":
				StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
				join.add("i2c:detect,<controller> -> Detect the devices connected to a certain controller")
					.add("i2c:list -> List all registered devices and their commands")
					.add("i2c:cmds -> List all registered devices and their commands including comms")
					.add("i2c:reload -> Reload the command file(s)")
					.add("i2c:forward,device -> Show the data received from the given device")
					.add("i2c:adddevice,id,bus,address,script -> Add a device on bus at hex addres that uses script")
					.add("i2c:<device>,<command> -> Send the given command to the given device");
				return join.toString();
			case "list": return das.getI2CDevices(false);
			case "cmds": return das.getI2CDevices(true);
			case "reload": return das.reloadI2CCommands();
			case "forward": return das.addI2CDataRequest(cmd[1],wr)?"Added forward":"No such device";
			case "listeners": return das.getI2CListeners();
			case "debug":
				if( cmd.length == 2){
					if( das.getI2CWorker().map( i2c -> i2c.setDebug(cmd[1].equalsIgnoreCase("on"))).orElse(false) )
						return "Debug"+cmd[1];
					return "Failed to set debug, maybe no i2cworker yet?";
				}else{
					return "Incorrect number of variables: i2c:debug,on/off";
				}
			case "adddevice":
				if( cmd.length != 5)
					return "Incorrect number of variables: i2c:adddevice,id,bus,address,script";
				if( das.getI2CWorker().isEmpty()) // if no worker yet, make it
					das.addI2CWorker();
				if( I2CWorker.addDeviceToXML(XMLfab.withRoot(das.getXMLdoc(),"das","settings"),
						cmd[1], //id
						Integer.parseInt(cmd[2]), //bus
						cmd[3], //address in hex
						cmd[4] //script
						)) {
					// Check if the script already exists, if not build it
					var p = Paths.get("devices",cmd[4]+".xml");
					if( !Files.exists(p)){
						XMLfab.withRoot(p,"commandset").attr("script",cmd[4])
								.addParent("command","An empty command to start with")
									.attr("id","cmdname").attr("info","what this does")
								.build();
						das.getI2CWorker().ifPresent(
								worker -> worker.readSettingsFromXML(das.getXMLdoc())
						);
						return "Device added, created blank script at "+p.toString();
					}else{
						return "Device added, using existing script";
					}

				}
				return "Failed to add device to XML";
			case "detect":
				if( cmd.length == 2){
					return I2CWorker.detectI2Cdevices( Integer.parseInt(cmd[1]) );
				}else{
					return "Incorrect number of variables: i2c:detect,<bus>";
				}
			default:
				if( cmd.length!=2)
					return UNKNOWN_CMD+": "+request[0]+":"+request[1];

				if( wr!=null && wr.getID().equalsIgnoreCase("telnet") ){
					das.addI2CDataRequest(cmd[0],wr);
				}
				if( das.runI2Ccommand(cmd[0], cmd[1]) ){					
					return "Command added to the queue.";
				}else{
					return "Failed to add command to the queue, probably wrong device or command";
				}
		}
	}
	
	public String doMQTT( String[] request, Writable wr, boolean html ){		
		
		String[] cmd = request[1].split(",");
		String nl = html ? "<br>" : "\r\n";

		switch( cmd[0] ){
			//mqtt:brokers
			case "brokers": return das.getMqttBrokersInfo();
			//mqtt:subscribe,ubidots,aanderaa,outdoor_hub/1844_temperature
			case "subscribe":
				if( cmd.length == 4){
					das.addMQTTSubscription(cmd[1], cmd[2], cmd[3]);
					return nl+"Subscription added, send 'mqtt:store,"+cmd[1]+"' to save settings to xml";
				}else{
					return nl+"Incorrect amount of cmd: mqtt:subscribe,brokerid,label,topic";
				}
			case "unsubscribe":
				if( cmd.length == 3){
					if( das.removeMQTTSubscription(cmd[1], cmd[2]) ){
						return nl+"Subscription removed, send 'mqtt:store,"+cmd[1]+"' to save settings to xml";
					}else{
						return nl+"Failed to remove subscription, probably typo?";
					}
				}else{
					return nl+"Incorrect amount of cmd: mqtt:unsubscribe,brokerid,topic";
			}
			case "reload":
				if( cmd.length == 2){
					das.reloadMQTTsettings(cmd[1]);
					return nl+"Settings for "+cmd[1]+" reloaded.";
				}else{
					return "Incorrect amount of cmd: mqtt:reload,brokerid";
				}
			case "store":
				if( cmd.length == 2){
					das.updateMQTTsettings(cmd[1]);
					return nl+"Settings updated";
				}else{
					return "Incorrect amount of cmd: mqtt:store,brokerid";
				}
			case "forward":
				if( cmd.length == 2){
					das.getMqttWorker(cmd[1]).ifPresent( x -> x.addRequest(wr));
					return "Forward requested";
				}else{
					return "Incorrect amount of cmd: mqtt:forward,brokerid";
				}
			case "send":
				if( cmd.length != 3){
					Logger.warn( "Not enough arguments, expected mqtt:send,brokerid,topic:value" );
					return "Not enough arguments, expected mqtt:send,brokerid,topic:value";
				}else if( !cmd[2].contains(":") ){
					return "No proper topic:value given, got "+cmd[2]+" instead.";
				}
				if( das.getMqttWorker(cmd[1]).isEmpty() ){
					Logger.warn("No such mqttworker to so send command "+cmd[1]);
					return "No such MQTTWorker: "+cmd[1];
				}
				String[] topVal = cmd[2].split(":");
				double val = rtvals.getRealtimeValue(topVal[1], -999);
				das.getMqttWorker(cmd[1]).ifPresent( w -> w.addWork(topVal[0],""+val));
				return "Data send to "+cmd[1];
			case "?":
				StringJoiner response = new StringJoiner(nl);	
				response.add( "mqtt:brokers -> Get a listing of the current registered brokers")
						.add( "mqtt:subscribe,brokerid,label,topic -> Subscribe to a topic with given label on given broker")
						.add( "mqtt:unsubscribe,brokerid,topic -> Unsubscribe from a topic on given broker")
						.add( "mqtt:unsubscribe,brokerid,all -> Unsubscribe from all topics on given broker")
						.add( "mqtt:forward,brokerid -> Forwards the data received from the given broker to the issueing writable")
						.add( "mqtt:send,brokerid,topic:value -> Sends the value to the topic of the brokerid")
						.add( "mqtt:store,brokerid -> Store the current settings of the broker to the xml.")
						.add( "mqtt:reload,brokerid -> Reload the settings for the broker from the xml.")
						.add( "mqtt:? -> Show this message");
				return response.toString();
			default: return UNKNOWN_CMD+": "+cmd[0];
		}
	}
	
	public String doSLEEP( String[] request, Writable wr, boolean html ){
		if( request[1].equals("?")){
			return "sleep:<time> -> Let the processor sleep for time fe. sleep:5m";
		}
		String os = System.getProperty("os.name").toLowerCase();
		if( !os.startsWith("linux")){
			return "Only Linux supported for now.";
		}
		
		int seconds = 90;
		if( !request[1].isEmpty() ){
			seconds = TimeTools.parsePeriodStringToSeconds(request[1]);
		}
		
		try {
			StringJoiner tempScript = new StringJoiner( "; ");
			tempScript.add("echo 0 > /sys/class/rtc/rtc0/wakealarm");
			tempScript.add("echo +"+seconds+" > /sys/class/rtc/rtc0/wakealarm");
			tempScript.add("echo mem > /sys/power/state");

			ProcessBuilder pb = new ProcessBuilder("bash","-c", tempScript.toString());
			pb.inheritIO();
			Process process;

			Logger.error("Started sleep attempt at "+TimeTools.formatLongUTCNow());
			process = pb.start();
			process.waitFor();
			Logger.error("Woke up again at "+TimeTools.formatLongUTCNow());
			// do wake up stuff
			das.startKeywordTask("sleep:wokeup");
		} catch (IOException | InterruptedException e) {
			Logger.error(e);
		}
		return "Waking up at "+TimeTools.formatLongUTCNow();
	}
	public String doGENericS( String[] request, Writable wr, boolean html ){

		StringJoiner join = new StringJoiner(html?"<br":"\r\n");
		String[] cmd = request[1].split(",");

		switch(cmd[0]){
			case "?":
				join.add("")
					.add(TelnetCodes.TEXT_RED+"Purpose"+TelnetCodes.TEXT_YELLOW)
					.add("  Generics (gens) are used to take delimited data and store it as rtvals or in a database.");
				join.add(TelnetCodes.TEXT_BLUE+"Notes"+TelnetCodes.TEXT_YELLOW)
					.add("  - ...");
				join.add("").add(TelnetCodes.TEXT_GREEN+"Create a Generic"+TelnetCodes.TEXT_YELLOW)
					.add("  generics:fromtable,dbid,dbtable,gen id[,delimiter] -> Create a generic according to a table, delim is optional, def is ','")
					.add("  gens:addblank,id,format -> Create a blank generic with the given id and format")
					.add("      Format options:")
					.add("       r = a real number, i = an integer number")
					.add("       m = macro, this value can be used as part as the rtval")
					.add("       s = skip, this won't show up in the xml but will increase the index counter")
					.add("       eg. 1234,temp,19.2,hum,55 ( with 1234 = serial number")
					.add("           -> serial number,title,temperature reading,title,humidity reading")
					.add("           -> msrsi -> macro,skip,real,skip,integer");
				join.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW);
				join.add("  gens:? -> Show this info")
					.add("  generics:reload -> Reloads all generics")
					.add("  generics:list -> Lists all generics");

				return join.toString();
			case "reload": 
				das.loadGenerics(true);
				return das.getBaseWorker().getGenericInfo();
			case "fromtable": 
				if(cmd.length < 4 )
					return "To few parameters, gens:fromtable,dbid,table,gen id,delimiter";
				var db = das.getDatabase(cmd[1]);
				if( db ==null)
					return "No such database found "+cmd[1];
				if( db.buildGenericFromTable(XMLfab.withRoot(das.getXMLdoc(), "das","generics"),cmd[2],cmd[3],cmd.length>4?cmd[4]:",") ){
					return "Generic written";
				}else{
					return "Failed to write to xml";
				}
			case "addblank":
				if( cmd.length < 3 )
					return "Not enough arguments, must be generics:addblank,id,format[,delimiter]";
				return Generic.addBlankToXML(das.getXMLdoc(), cmd[1], cmd[2],cmd.length==4?cmd[3]:",");
			case "list": 
				return das.getBaseWorker().getGenericInfo();
			default:
				return UNKNOWN_CMD+": "+cmd[0];
		}
	}
	public String doMYsqlDump(String[] request, Writable wr, boolean html ){
		String[] cmds = request[1].split(",");
		switch( cmds[0] ){
			case "?": 	return " myd:run,dbid,path -> Run the mysqldump process for the given database";
			case "run":
				if( cmds.length != 3 )
					return "Not enough arguments, must be mysqldump:run,dbid,path";
				Database db = das.getDatabase(cmds[1]);
				if( db == null )
					return "No such database "+cmds[1];
				if( db instanceof SQLiteDB )
					return "Database is an sqlite, not mysql/mariadb";
				if( db instanceof SQLDB ){
					SQLDB sql =(SQLDB)db;
					if( sql.isMySQL() ){
						// do the dump
						String os = System.getProperty("os.name").toLowerCase();
						if( !os.startsWith("linux")){
							return "Only Linux supported for now.";
						}
						try {
							ProcessBuilder pb = new ProcessBuilder("bash","-c", "mysqldump "+sql.getTitle()+" > "+cmds[2]+";");
							pb.inheritIO();
							Process process;
				
							Logger.info("Started dump attempt at "+TimeTools.formatLongUTCNow());
							process = pb.start();
							process.waitFor();
							// zip it?
							if( Files.exists(Paths.get(cmds[2]))){
								if(FileTools.zipFile(Paths.get(cmds[2]))==null) {
									Logger.error("Dump of "+cmds[1]+" created, but zip failed");
									return "Dump created, failed zipping.";
								}
								// Delete the original file
								Files.deleteIfExists(Paths.get(cmds[2]));
							}else{
								Logger.error("Dump of "+cmds[1]+" failed.");
								return "No file created...";
							}
							Logger.info("Dump of "+cmds[1]+" created, zip made.");
							return "Dump finished and zipped at "+TimeTools.formatLongUTCNow();
						} catch (IOException | InterruptedException e) {
							Logger.error(e);
							Logger.error("Dump of "+cmds[1]+" failed.");
							return "Something went wrong";
						}
					}else{
						return "Database isn't mysql/mariadb";
					}
				}else{
					return "Database isn't regular SQLDB";
				}
			default:
				return UNKNOWN_CMD+": "+request[0]+":"+request[1];
		}
	}
	public String doDataBaseManager( String[] request, Writable wr, boolean html ){
		String[] cmds = request[1].split(",");
		
		StringJoiner join = new StringJoiner(html?"<br":"\r\n");
		Database db=null;

		String id = cmds.length>=2?cmds[1]:"";
		String dbName = cmds.length>=3?cmds[2]:"";
		String address = cmds.length>=4?cmds[3]:"";
		String user = cmds.length>=5?cmds[4]:"";
		String pass="";

		if( user.contains(":")){
			pass = user.substring(user.indexOf(":")+1);
			user = user.substring(0,user.indexOf(":"));
		}

		switch( cmds[0] ){
			case "?":
				join.add(TelnetCodes.TEXT_MAGENTA+"The databasemanager connects to databases, handles queries and fetches table information");
				join.add(TelnetCodes.TEXT_GREEN+"Glossary"+TelnetCodes.TEXT_YELLOW)
						.add("  alias -> the alias of a column is the reference to use instead of the column name to find the rtval, empty is not used")
						.add("  macro -> an at runtime determined value that can be used to define the rtval reference").add("");
				join.add(TelnetCodes.TEXT_GREEN+"Connect to a database"+TelnetCodes.TEXT_YELLOW)
						.add("  dbm:addmssql,id,db name,ip:port,user:pass -> Adds a MSSQL server on given ip:port with user:pass")
						.add("  dbm:addmysql,id,db name,ip:port,user:pass -> Adds a MSSQL server on given ip:port with user:pass")
						.add("  dbm:addmariadb,id,db name,ip:port,user:pass -> Adds a MariaDB server on given ip:port with user:pass")
						.add("  dbm:addsqlite,id,filename -> Creates an empty sqlite database, filename optional default db/id.sqlite")
						.add("  dbm:addinfluxdb,id,db name,ip:port,user:pass -> Adds a Influxdb server on given ip:port with user:pass")
					.add("").add(TelnetCodes.TEXT_GREEN+"Working with tables"+TelnetCodes.TEXT_YELLOW)
						.add("  dbm:addtable,id,tablename,format (format eg. tirc timestamp(auto filled system time),int,real,char/text)")
						.add("  dbm:tables,id -> Get info about the given id (tables etc)")
						.add("  dbm:fetch,id -> Read the tables from the database directly, not overwriting stored ones.")
					.add("").add(TelnetCodes.TEXT_GREEN+"Other"+TelnetCodes.TEXT_YELLOW)
						.add("  dbm:addserver,id -> Adds a blank database server node to xml")
						.add("  dbm:alter,id,param:value -> Alter things like idle, flush and batch (still todo)")
						.add("  dbm:alter,id,param:value -> Alter things like idle, flush and batch (still todo)")
						.add("  dbm:reload,id -> (Re)loads the database with the given id fe. after changing the xml")
						.add("  dbm:status -> Show the status of all managed database connections")
						.add("  st -> Show the current status of the databases (among other things)");
				return join.toString();	
			case "reload": 
				if( cmds.length<2)
					return "No id given";
				var dbr = das.reloadDatabase(cmds[1]);
				if( dbr!=null){
					String error = dbr.getLastError();
					return error.isEmpty()?"Database reloaded":error;
				}
				return "No such database found";
			case "addserver":
					DatabaseManager.addBlankServerToXML( das.getXMLdoc(), "mysql", cmds.length>=2?cmds[1]:"" );
					return "Added blank database server node to the settings.xml";
			case "addmysql":
				var mysql = SQLDB.asMYSQL(address,dbName,user,pass);
				mysql.setID(id);
				if( mysql.connect(false) ){
					mysql.getCurrentTables(false);
					mysql.writeToXml( XMLfab.withRoot(das.getXMLdoc(),"das","settings","databases"));
					das.getDatabaseManager().addSQLDB(id,mysql);
					return "Connected to MYSQL database and stored in xml as id "+id;
				}else{
					return "Failed to connect to database.";
				}
			case "addmssql":
				var mssql = SQLDB.asMSSQL(address,dbName,user,pass);
				mssql.setID(id);
				if( mssql.connect(false) ){
					mssql.getCurrentTables(false);
					mssql.writeToXml( XMLfab.withRoot(das.getXMLdoc(),"das","settings","databases"));
					das.getDatabaseManager().addSQLDB(id,mssql);
					return "Connected to MYSQL database and stored in xml as id "+id;
				}else{
					return "Failed to connect to database.";
				}
			case "addmariadb":
				if( cmds.length<5)
					return "Not enough arguments: dbm:addmariadb,id,db name,ip:port,user:pass";
				var mariadb = SQLDB.asMARIADB(address,dbName,user,pass);
				mariadb.setID(id);
				if( mariadb.connect(false) ){
					mariadb.getCurrentTables(false);
					mariadb.writeToXml( XMLfab.withRoot(das.getXMLdoc(),"das","settings","databases"));
					das.getDatabaseManager().addSQLDB(id,mariadb);
					return "Connected to MariaDB database and stored in xml with id "+id;
				}else{
					return "Failed to connect to database.";
				}
			case "addsqlite":
				if( !dbName.contains(File.separator))
					dbName = "db"+File.separator+(dbName.isEmpty()?id:dbName)+".sqlite";
				var sqlite = SQLiteDB.createDB(id,Paths.get(dbName));
				if( sqlite.connect(false) ){
					das.getDatabaseManager().addSQLiteDB(id,sqlite);
					sqlite.writeToXml( XMLfab.withRoot(das.getXMLdoc(),"das","settings","databases") );
					return "Created SQLite at "+dbName+" and wrote to settings.xml";
				}else{
					return "Failed to create SQLite";
				}
			case "addinfluxdb": case "addinflux":
				var influx = new Influx(address,dbName,user,pass);
				if( influx.connect(false)){
					das.getDatabaseManager().addInfluxDB(id,influx);
					influx.writeToXml( XMLfab.withRoot(das.getXMLdoc(),"das","settings","databases") );
					return "Connected to InfluxDB and stored it in xml with id "+id;
				}else{
					return "Failed to connect to InfluxDB";
				}
			case "addtable":
				if( cmds.length < 4 )
					return "Not enough arguments, needs to be dbm:addtable,dbId,tableName,format";
				if( DatabaseManager.addBlankTableToXML( das.getXMLdoc(), cmds[1], cmds[2], cmds[3] ) )
					return "Added a partially setup table to "+cmds[1]+" in the settings.xml, edit it to set column names etc";
				return "No such database found or influxDB.";
			case "fetch": 
				if( cmds.length < 2 )
					return "Not enough arguments, needs to be dbm:fetch,dbId";
				db = das.getDatabase(cmds[1]);
				if( db==null)
					return "No such database";
				if( db.getCurrentTables(false) )
					return "Tables fetched, run dbm:tables,"+cmds[1]+ " to see result.";
				if( db.isValid(1) )
					return "Failed to get tables, but connection valid...";
				return "Failed to get tables because connection not active.";
			case "tables":
				if( cmds.length < 2 )
					return "Not enough arguments, needs to be dbm:tables,dbId";
				db = das.getDatabase(cmds[1]);
				if( db==null)
					return "No such database";
				return db.getTableInfo(html?"<br":"\r\n");
			case "alter":
				return "Not yet implemented";
			case "status": case "list":
				return das.getDatabaseManager().getStatus();
			case "store":
				if( cmds.length < 3 )
					return "Not enough arguments, needs to be dbm:store,dbId,tableid";
				if( rtvals.writeRecord(cmds[1],cmds[2]) )
					return "Wrote record";
				return "Failed to write record";
			default:
				return UNKNOWN_CMD+": "+request[0]+":"+request[1];
		}
	}
	public String doDAS( String[] request, Writable wr, boolean html ){
		
		if(request[1].equals("?")){
			return " -> Starts the DAS Core setup";
		}

		if( request[1].equals("!")){
			qState=0;
			tempElement=null;
			return "";
		}
		boolean emptyReq=request[1].isEmpty();
		switch(qState){
			case 0: qState=1; return "Welcome to DAS setup, please select a subject:\r\n"+
							 "1. email\r\n2. streams\r\n3. transserver\r\n4. Generics\r\n"
							 +"Send the number of chosen subject, send ! to quit at any time.";
			case 1: 
				int nr = Tools.parseInt(request[1], -999);
				switch( nr ){
					case 1: 
							qState=101;
							if( EmailWorker.inXML(das.getXMLdoc())){
								return "Starting the emailworker setup.\r\nDo send emails Q&A?";	
							}else{
								EmailWorker.addBlankEmailToXML( das.getXMLdoc(), true, true);
								das.addEmailWorker();
								return "Starting the emailworker setup.\r\nNo settings found, adding defaults, do send emails Q&A?y/n";
							}						
					case 2: qState = 201; return "Starting the streams setup.\r\n1. Add new stream\r\n2. Alter existing stream\r\n";	
					case 3: 
							if( das.hasTransServer()){
								qState = 301; 
								return "Starting the transserver setup\r\n1. For adding a default";
							}else{
								qState = 302; 
								return "Starting the transserver setup, on which port should it listen?";
							}
					case 4: qState=401; break; 

					default: return "Invalid number given, try again or send ! to stop.";
				}
		}

		switch( qState ){
			/* Email Worker */
			case 101: 
					if( request[1].equals("y") ){	
						qState=102;
						return "Server to send emails? (ip:port)";
					}else if( request[1].equals("n")){
						
						qState=105;
						return "Do the receiving emails Q&A?";	
					}else{
						return request[1]+" is not a valid answer, try again.";
					}			
			case 102:
					das.getEmailWorker().setOutboxServer(request[1]);
			case 103: 
					qState=104; 
					das.getEmailWorker().setInboxUser(request[1]); 
					return "What is the password for the inbox? (current: "+das.getEmailWorker().getInboxPass()+")";	
			case 104: qState=105; das.getEmailWorker().setInboxPass(request[1]); return "At which interval to check for new emails? fe. 5m"; 
			case 105: qState=106; return "What is the server? (current: "+das.getEmailWorker().getOutboxServer()+")";
			case 106: qState=107; das.getEmailWorker().setOutboxServer(request[1]); return das.getEmailWorker().getSettings()+"\r\n Store? y/n";
			case 107: 
					if( request[1].equals("y")){
						das.getEmailWorker().updateSettingsInXML(das.getXMLdoc());
					}else if( request[1].equals("n")){
						
					}else{
						return "Invalid answer given, try again or send ! to stop.";
					}
			/* Streams */
			case 200: qState=201; return "1. Add new stream\r\n2. Alter existing stream\r\n";
			case 201: 
					switch( request[1] ){
						case "1": qState=203; return "What is the type of the stream?\r\n1. TCP\r\n2. UPD\r\n3. Serial";
						case "2": qState=252; return "Alter which one?\r\n"+das.getStreamPool().getStreamList();
						default: return "Wrong input, try again or use ! to quit";
					}
			case 218: qState=203; return "What is the type of the stream?\r\n1. TCP\r\n2. UPD\r\n3. Serial"; 
			case 203:
				xml = das.getXMLdoc();
				tempElement = xml.createElement("stream");
				switch( request[1] ){
					case "1": qState = 212; tempElement.setAttribute("type","tcp");    
								return "Creating a TCP stream.\r\nWhat is the address? ip:port";
					case "2": qState = 212; tempElement.setAttribute("type","udp");    
								return "Creating an UDP stream.\r\nWhat is the address? ip:port (sending) or just port (listening)";
					case "3": qState = 232; tempElement.setAttribute("type","serial"); 
						return "Creating a Serial stream.\r\nWhat are the settings for the serialport? eg. 9600,8,1,even";
					default: return "Wrong input, try again or use ! to quit";
				}
			case 211: 	qState = 212; return "What is the address? ip:port";
			case 221: 	qState = 212; return "What is the address? ip:port (sending) or just port (listening)";
			case 231: 	qState = 232; return "What are the settings of the serialport? eg 9600,8,1,even";
			case 232: 	qState = 212; 
						XMLtools.createChildTextElement(xml, tempElement, "serialsettings", request[1]);
						return "What is the name of the port? (COMx or ttySx)";
			case 212: 	XMLtools.createChildTextElement( xml, tempElement, "address", request[1] );
			case 213: 	qState=214; return "What is the id for the stream?";
			case 214: 	qState=215;
						tempElement.setAttribute("id", request[1]);
						return "So id is "+request[1]+", what is the label? (or empty to ask id again)";
			case 215: 	if( request[1].isBlank()) 
							qState = 213;
						qState=216;
						XMLtools.createChildTextElement(xml, tempElement, "label", request[1]);
						return "What is the used delimiter? cr,crlf,lf,lfcr";
			case 216: 
						qState=217;
						XMLtools.createChildTextElement(xml, tempElement, "delimiter", request[1]);
						return "Last question, after how long should the data be considered invalid? eg. 5m (or -1 for never)";
			case 217: 
						XMLtools.createChildTextElement(xml, tempElement, "ttl", request[1]);
						Element root = XMLtools.getFirstElementByTag(xml, "streams" );
						if( root == null ){
							Element settings = XMLtools.getFirstElementByTag( xml, "das" );
							root = xml.createElement("streams"); 
							settings.appendChild(root);
						}
						root.appendChild(tempElement);
						if( XMLtools.updateXML(xml) ){
							streampool.readSettingsFromXML( xml );
							qState=218;
							return "Updated the settings successfully and reloaded.\r\nReply ! to quit or empty to return to create another.";
						}else{
							qState=219;
							return "Failed to update the XML, no idea why... Retry?";
						}
									
			case 252: // Alter an existing stream
				qState=0;
				return "Not implemented yet. Send ! to close the QA or press enter to start over.";

			/* TransServer */
			case 301:	
				qState=999;
				return "Still to implement";
			case 302:	
				int port = Tools.parseInt(request[1], -1);
				if( port !=-1 ){
					das.addTcpServer(port);
					das.startTransServer();
					qState=999;					
					return "Starting the server and adding it to the settings.xml";
				}else{
					qState=301;return "Invalid number, on which port does the server need to run?";
				}
			
			/* Generics */
			case 401: 
				qState++; 
				xml = das.getXMLdoc();
				fab = XMLfab.withRoot(xml, "generics");
				return "What is the ID for this generic?";
			case 402: qState++; tempInt=0;fab.addParent("generic").attr("id",request[1]);
				return "What is the delimiter?";
			case 403: qState++; fab.attr("delimiter",request[1]);
				return "Database ID to write to? (multiple should be separated with ,)";
			case 404: qState++; fab.attr("dbid",request[1]);
				return "In which table?";
			case 405: qState++; fab.attr("table",request[1]);
				return "What does the data have to start with? (empty=no requirements)";
			case 406: qState++; 
				if(!emptyReq)
					fab.attr("startswith",request[1]);	
				return "Now adding the elements, which type (m=macro,i=integer,r=real)";
			case 410:
						switch(request[1]){
							case "m": qState=411; return "At which index? (empty="+tempInt+")";
						//	case "t": qState=408; break;
							case "i": qState=412; break;
							case "r": qState=413; break;
						//	case "e": qState=411; break;
							default: qState=410;return "Try again... m, i or r";
						}
						return "And the title?";
			case 411: qState=416;fab.addChild("macro").attr("index",emptyReq?""+tempInt:request[1]);tempInt++; return "Add another? y/n";
			//case 408: qState=415;fab.addChild("timestamp",request[1]); return "At which index? (empty="+tempInt+")";
			case 412: qState=415;fab.addChild("integer",request[1]);return "At which index? (empty="+tempInt+")";
			case 413: qState=415;fab.addChild("real",request[1]); return "At which index? (empty="+tempInt+")";
			//case 411: qState=415;fab.addChild("epochmilli",request[1]); return "At which index? (empty="+tempInt+")";

			case 415:qState++;fab.attr("index",emptyReq?""+tempInt:request[1]);tempInt++;
						return "Add another? y/n";
			case 416:
					if( request[1].equalsIgnoreCase("y")){
						qState=406;
						return "Which type (m=macro,i=integer,r=real)";
					}else{
						qState=0;
						fab.build();
						das.loadGenerics(true);
						return "Generic added to xml. Send ! to close the setup or press enter to start over.";
					}
			

			case 999: 
				qState=0;
				return "That's it. Send ! to close the setup or press enter to start over.";
		}
		return "Beats me...";
	}
}
