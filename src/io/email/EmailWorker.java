package io.email;

import das.Commandable;
import das.Core;
import das.Paths;
import io.Writable;
import io.collector.BufferCollector;
import io.collector.CollectorFuture;
import jakarta.activation.*;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.FlagTerm;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.LookAndFeel;
import util.tools.FileTools;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;
import worker.Datagram;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class EmailWorker implements CollectorFuture, EmailSending, Commandable {

	static double megaByte = 1024.0 * 1024.0;

	ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	// Email sending settings
	double doZipFromSizeMB = 0.5; // From which attachment size the attachment should be zipped (in MB)
	double maxSizeMB = 1; // Maximum size of attachments to send (in MB)

	/* Queues that hold new emails and retries */
	private final ArrayList<Email> retryQueue = new ArrayList<>(); // Queue holding emails to be
																		// send/retried
	private final Map<String, String> emailBook = new HashMap<>(); // Hashmap containing the reference to email address
																	// 'translation'
	private final ArrayList<Permit> permits = new ArrayList<>();

    /* Outbox */
	MailBox outbox = new MailBox();
	boolean outboxAuth = false; // Whether to authenticate

	/* Inbox */
	MailBox inbox = new MailBox();
	Session inboxSession;

	int errorCount = 0; // Amount of errors encountered
	boolean sendEmails = true; // True if sending emails is allowed

	/* Standard SMTP properties */
	Properties props = System.getProperties(); // Properties of the connection
	static final String MAIL_SMTP_CONNECTIONTIMEOUT = "mail.smtp.connectiontimeout";
	static final String MAIL_SOCKET_TIMEOUT = "60000";
	static final String MAIL_SMTP_TIMEOUT = "mail.smtp.timeout";
	static final String MAIL_SMTP_WRITETIMEOUT = "mail.smtp.writetimeout";

	private int busy = 0; // Indicate that an email is being send to the server
	private int sendRequests=0;
	private int MAX_EMAILS=5;
	java.util.concurrent.ScheduledFuture<?> retryFuture; // Future of the retry checking thread

	/* Reading emails */
	java.util.concurrent.ScheduledFuture<?> checker; // Future of the inbox checking thread

	int maxQuickChecks = 0; // Counter for counting down how many quick checks for incoming emails are done
							// before slowing down again
	int checkIntervalSeconds = 300; // Email check interval, every 5 minutes (default) the inbox is checked for new
									// emails
	int maxEmailAgeInHours=-1;
	String allowedDomain = ""; // From which domain are emails accepted

	boolean deleteReceivedZip = true;

	Session mailSession = null;

	long lastInboxConnect = -1; // Keep track of the last timestamp that a inbox connection was made

	HashMap<String, DataRequest> buffered = new HashMap<>();	// Store the requests made for data via email


	static final String XML_PARENT_TAG = "email";
	static final String TIMEOUT_MILLIS="10000";

	private ScheduledFuture<?> slowCheck = null;
	private ScheduledFuture<?> fastCheck = null;
	private ScheduledFuture<?> clear = null;
	private boolean ready=false;
	/**
	 * Constructor for this class
	 *
	 */
	public EmailWorker( ) {
		if( readFromXML() )
			init();
	}
	/**
	 * Initialises the worker by enabling the retry task and setting the defaults
	 * settings, if reading emails is enabled this starts the thread for this.
	 */
	public void init() {
		setOutboxProps();

		if (!inbox.server.isBlank() && !inbox.user.equals("user@email.com")) {
			// Check the inbox every x minutes
			checker = scheduler.scheduleAtFixedRate(new Check(), checkIntervalSeconds,checkIntervalSeconds, TimeUnit.SECONDS);

		}
		inboxSession = Session.getDefaultInstance(new Properties());
		alterInboxProps(inboxSession.getProperties());

		MailcapCommandMap mc = (MailcapCommandMap) CommandMap.getDefaultCommandMap();
		mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html");
		mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml");
		mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain");
		mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed");
		mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822");
		CommandMap.setDefaultCommandMap(mc);
		ready=true;
	}

	/**
	 * Request the queue in which emails are place for sending
	 * 
	 * @return The BlockingQueue to hold the emails to send
	 */
	public EmailSending getSender(){
		return this;
	}
	/**
	 * Get the amount of emails in the retry queue, meaning those that need to be send again.
	 * @return Amount of backlog emails
	 */
	public int getRetryQueueSize(){
		return retryQueue.size();
	}
	/**
	 * Read the settings from the XML file and apply them
	 */
	public boolean readFromXML(){

		mailSession = null;

		XMLdigger xml = XMLdigger.goIn(Paths.settings(),"dcafs","settings","email");

		if( !xml.isValid() )
			return false;

		// Sending
		xml.digDown("*"); // Get all child nodes of 'email'
		while( xml.iterate()){
			switch (xml.tagName("")) {
				case "outbox" -> {
					if (xml.hasPeek("server")) {
						outbox.setServer(xml.value(""), xml.attr("port", 25));            // The SMTP server
						outbox.setLogin(xml.attr("user", ""), xml.attr("pass", ""));
						outbox.hasSSL = xml.attr("ssl", false);
					} else {
						Logger.error("No server defined for the outbox");
					}
					outbox.from = xml.peekAt("from").value("dcafs@email.com");// From emailaddress
					doZipFromSizeMB = xml.peekAt("zip_from_size_mb").value(10);// Max unzipped filesize
					deleteReceivedZip = xml.peekAt("delete_rec_zip").value(true);
					maxSizeMB = xml.peekAt("max_size_mb").value(15.0);
					MAX_EMAILS = xml.peekAt("maxemails").value(5);
				}
				case "inbox" -> {
					if (xml.hasPeek("server")) {
						inbox.setServer(xml.value(""), xml.attr("port", 25));            // The SMTP server
						inbox.setLogin(xml.attr("user", ""), xml.attr("pass", ""));
						inbox.hasSSL = xml.attr("ssl", false);
					} else {
						Logger.error("No server defined for the inbox");
					}
					String interval = xml.peekAt("checkinterval").value("5m");
					checkIntervalSeconds = (int) TimeTools.parsePeriodStringToSeconds(interval);
					allowedDomain = xml.peekAt("allowed").value("");
				}
				case "book" -> xml.current().ifPresent(this::readEmailBook);
				case "permits" -> xml.current().ifPresent(this::readPermits);
				default -> Logger.error("Unknown node in email: " + xml.tagName(""));
			}
		}
		return true;
	}

	/**
	 * Reads the content of the xml element containing the id -> email references
	 * @param email The element containing the email info
	 */
	private void readEmailBook( Element email ){
		emailBook.clear();  // Clear previous references
		XMLdigger xml = XMLdigger.goIn(email).digDown("entry"); // dig to entries

		while( xml.iterate() ){
			String addresses = xml.value("");
			String ref = xml.attr( "ref", "");
			if( !ref.isBlank() && !addresses.isBlank() ){
				addTo(ref, addresses);
			}else{
				Logger.warn("email book entry has empty ref or address");
			}
		}
	}
	private void readPermits( Element perms){
		permits.clear(); // clear previous permits
		XMLdigger xml = XMLdigger.goIn(perms).digDown("*");

		while( xml.iterate() ){ // Go through the denies
			boolean denies = xml.tagName("").equals("denies");
			var ref = xml.attr("ref","");
			String val = xml.value("");

			if(ref.isEmpty() || val.isEmpty()){
				Logger.warn("Empty permit ref/val!");
				continue;
			}
			boolean regex = xml.attr("regex",false);
			permits.add(new Permit(denies, ref, val, regex));
		}
	}
	/**
	 * This creates the bare settings in the xml
	 * 
	 * @param settingsPath Path to the settings.xml
	 * @param sendEmails Whether to include sending emails
	 * @param receiveEmails Whether to include checking for emails
	 * @return True if changes were written to the xml
	 */
	public static boolean addBlankElement(Path settingsPath, boolean sendEmails, boolean receiveEmails ){
		var fab = XMLfab.withRoot(settingsPath, "dcafs","settings");
		if( fab.getChild("email").isPresent() ) // Don't overwrite if already exists?
			return false;

		fab.digRoot(XML_PARENT_TAG);

		if( sendEmails ){
			fab.addParentToRoot("outbox","Settings related to sending")
					 .addChild("server", "host/ip").attr("user").attr("pass").attr("ssl","yes").attr("port","993")
					  .addChild("from","das@email.com")
					  .addChild("zip_from_size_mb","3")
					  .addChild("delete_rec_zip","yes")
					  .addChild("max_size_mb","10");
		}
		if( receiveEmails ){	
			fab.addParentToRoot("inbox","Settings for receiving emails")
					   .addChild("server", "host/ip").attr("user").attr("pass").attr("ssl","yes").attr("port","465")
					   .addChild("checkinterval","5m");
		}
		fab.addParentToRoot("book","Add entries to the emailbook below")
				.addChild("entry","admin@email.com").attr("ref","admin");

		fab.build();
		return true;
	}

	private boolean writePermits(){
		if( Paths.settings() != null ){
			var fab = XMLfab.withRoot(Paths.settings(),"dcafs","settings","email");
			fab.selectOrAddChildAsParent("permits");
			fab.clearChildren();
			for( var permit : permits ){
				fab.addChild(permit.denies?"deny":"allow",permit.value).attr("ref",permit.ref);
				if(permit.regex)
					fab.attr("regex","yes");
			}
			return fab.build();
		}else{
			Logger.error("Tried to write permits but no valid xml yet");
			return false;
		}
	}
	/**
	 * Add an email address to an id
	 * 
	 * @param id The id to add address to
	 * @param email The email address to add to the id
	 */
	public void addTo(String id, String email){
		String old = emailBook.get(id);				// Get any emailadresses already linked with the id, if any
		email = email.replace(";", ",");		// Multiple emailaddresses should be separated with a colon, so alter any semi-colons
		if( old != null && !old.isBlank()){	// If an emailadres was already linked, add the new one(s) to it
			emailBook.put(id, old+","+email);
		}else{									// If not, put the new one
			emailBook.put(id, email);
		}		
		Logger.info( "Set "+id+" to "+ emailBook.get(id)); // Add this addition to the status log
	}
	/**
	 * Request the content of the 'emailbook'
	 * @return Listing of all the emails and references in the emailbook
	 */
	public String getEmailBook( ){
		StringJoiner b = new StringJoiner( "\r\n", "-Emailbook-\r\n", "");		
		for( Map.Entry<String,String> ele : emailBook.entrySet()){
			b.add(ele.getKey()+" -> "+ele.getValue() );
		}
		return b.toString();
	}
	/**
	 * Gets the settings used by the EmailWorker
	 * 
	 * @return Listing of all the current settings as a String
	 */
	public String getSettings(){
		StringJoiner b = new StringJoiner( "\r\n","--Email settings--\r\n","\r\n");
		b.add("-Sending-");
		b.add("Server: "+outbox.server+":"+outbox.port);
		b.add("SSL: "+outbox.hasSSL);
		b.add("From (send replies): "+outbox.from);
		b.add("Attachments zip size:"+doZipFromSizeMB);
		b.add("Maximum attachment size:"+maxSizeMB);
	
		b.add("").add("-Receiving-");
		b.add("Inbox: "+inbox.server+":"+inbox.port);
		if( checker == null || checker.isCancelled() || checker.isDone()){
			b.add( "Next inbox check in: Never,checker stopped");
		}else{
			b.add("Inbox check in: "
					+ TimeTools.convertPeriodToString(checker.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS)
					+"/"
					+ TimeTools.convertPeriodToString(checkIntervalSeconds, TimeUnit.SECONDS)
					+" Last ok: "+getTimeSincelastInboxConnection());
		}
		
		b.add("User: "+inbox.user);
		b.add("SSL: "+inbox.hasSSL);
		b.add("Allowed: " + allowedDomain );
		return b.toString();
	}
	public String getTimeSincelastInboxConnection(){
		if( lastInboxConnect == -1 )
			return "never";
		return TimeTools.convertPeriodToString(Instant.now().toEpochMilli() - lastInboxConnect, TimeUnit.MILLISECONDS) + " ago";
	}
	/**
	 * Set the properties for sending emails
	 */
	private void setOutboxProps(){
		if(!outbox.server.isEmpty()){
			props.setProperty("mail.host", outbox.server);
			props.setProperty("mail.transport.protocol", "smtp");

			props.put("mail.smtp.port", outbox.port );

			if( outbox.hasSSL ){
				props.put("mail.smtp.ssl.enable", "true");				
			}
			
			if( !outbox.pass.isBlank() || !outbox.user.isBlank() ){
				props.put("mail.smtp.auth", "true");
				props.put("mail.smtp.user", outbox.user);
				outboxAuth = true;
			}

			// Set a fixed timeout of 60s for all operations the default timeout is "infinite"
			props.put(MAIL_SMTP_CONNECTIONTIMEOUT, MAIL_SOCKET_TIMEOUT);
			props.put(MAIL_SMTP_TIMEOUT, MAIL_SOCKET_TIMEOUT);
			props.put(MAIL_SMTP_WRITETIMEOUT, MAIL_SOCKET_TIMEOUT);
		}
	}
	/**
	 * Checks if a certain email address is part of the list associated with a certain ref
	 * @param ref The reference to check
	 * @param address The emailaddress to look for
	 * @return True if found
	 */
	public boolean isAddressInRef( String ref, String address ){
		String list = emailBook.get(ref);
		return list.contains(address);
	}
	@Override
	public String replyToCommand(Datagram d) {

		if (d.args().equalsIgnoreCase("addblank")) {
			if( EmailWorker.addBlankElement(  Paths.settings(), true,true) )
				return "Adding default email settings";
			return "! Failed to add default email settings";
		}
		if( !ready ){
			if (d.args().equals("reload")
					&& XMLfab.withRoot(Paths.settings(), "dcafs","settings").getChild("email").isPresent() ){
				if( !readFromXML() )
					return "! No proper email node yet";
			}else{
				return "! No EmailWorker initialized (yet), use email:addblank to add blank to xml.";
			}
		}
		// Allow a shorter version to email to admin, replace it to match the standard command
		d.args(d.args().replace("toadmin,", "send,admin,"));
		String[] cmds = d.argList();
		return switch (cmds[0]) {
			case "?" -> getHelp(d.asHtml());
			case "reload" -> readFromXML()?"Settings reloaded":"! Reload failed";
			case "refs" -> getEmailBook();
			case "setup", "status" -> getSettings();
			case "deliver" -> {
				if (d.payload() != null && !(d.payload() instanceof Email))
					yield "! No valid payload in datagram";
				if (cmds.length != 2)
					yield "! Missing to";
				sendEmail((Email) d.payload());
				yield "Added email to queue";
			}
			case "send" -> {
				if (cmds.length != 4)
					yield "! Wrong amount of arguments -> email:send,ref/email,subject,content";

				// Check if the subject contains time request
				cmds[2] = cmds[2].replace("{localtime}", TimeTools.formatNow("HH:mm"));
				cmds[2] = cmds[2].replace("{utctime}", TimeTools.formatUTCNow("HH:mm"));
				sendEmail(Email.to(cmds[1]).subject(cmds[2]).content(cmds[3]));
				yield "Tried to send email";
			}
			case "checknow" -> {
				checker.cancel(false);
				checker = scheduler.schedule(new Check(), 1, TimeUnit.SECONDS);
				yield "Will check emails asap.";
			}
			case "interval" -> {
				if (cmds.length != 2)
					yield "! Wrong amount of arguments -> email:interval,period (fe.5m for minutes)";
				checkIntervalSeconds = (int) TimeTools.parsePeriodStringToSeconds(cmds[1]);
				yield "Interval changed to " + checkIntervalSeconds + " seconds (todo:save to settings.xml)";
			}
			case "addallow", "adddeny" -> doAddAllowDenyCmd(cmds);
			case "spam" -> {
				var cl = clear != null ? "in " + clear.getDelay(TimeUnit.SECONDS) + "s" : "never";
				yield "Busy at " + busy + " and sendrequests at " + sendRequests + ", clearing " + cl;
			}
			default -> "! No such subcommand in email: " + d.args();
		};
	}
	private String getHelp( boolean html ){
		StringJoiner b = new StringJoiner(html ? "<br>" : "\r\n");
		b.add("EmailWorker takes care of sending and receiving emails.")
				.add( "email:reload -> Reload the settings found in te XML.")
				.add( "email:refs -> Get a list of refs and emailadresses.")
				.add( "email:send,to,subject,content -> Send an email using to with subject and content")
				.add( "email:setup -> Get a listing of all the settings.")
				.add( "email:checknow -> Checks the inbox for new emails")
				.add( "email:addallow,from,cmd(,isRegex) -> Adds permit allow node, default no regex")
				.add( "email:adddeny,from,cmd(,isRegex) -> Adds permit deny node, default no regex")
				.add( "email:interval,x -> Change the inbox check interval to x");
		return LookAndFeel.formatCmdHelp(b.toString(),html);
	}
	private String doAddAllowDenyCmd( String[] cmds){
		if (cmds.length < 3) {
			return "! Wrong amount of arguments -> email:" + cmds[0] + ",from,cmd(,isRegex)";
		}
		boolean regex = cmds.length == 4 && Tools.parseBool(cmds[3], false);
		permits.add(new Permit(cmds[0].equals("adddeny"), cmds[1], cmds[2], regex));
		return writePermits() ? "Permit added" : "! Failed to write to xml";
	}
	/**
	 * Send an email
	 * @param email The email to send
	 */
	public void sendEmail( Email email ){
		if( !sendEmails ){
			Logger.warn("Sending emails disabled!");
			return;
		}
		if( email.isValid()) {
			applyBook(email);
			sendRequests++;
			if( busy < MAX_EMAILS) {
				busy++;
				scheduler.schedule(() -> sendEmail(email, false),busy,TimeUnit.SECONDS); // Send the email with a second delay
				if( busy==1 )
					clear = scheduler.schedule(this::clearBusy,MAX_EMAILS+2,TimeUnit.SECONDS);
			}else{
				if( sendRequests < 10 || sendRequests%20==0) {
					Logger.error("Warning, probably spamming, tried to send more than "+MAX_EMAILS+" emails in "+(MAX_EMAILS+2)+" seconds, ignoring email. (reqs:"+sendRequests+")");
				}
			}
		}else{
			Logger.error("Tried to send an invalid email");
		}
	}
	private void clearBusy(){
		Logger.info("Clearing busy at "+busy +" and "+sendRequests+" requests");
		busy=0;
		sendRequests=0;
	}
	/**
	 * Alter the 'to' field in the email from a possible reference to an actual emailaddress
	 * @param email The email to check and maybe alter
	 */
	private void applyBook( Email email ){
		StringJoiner join = new StringJoiner(",");
		for( String part : email.toRaw.split(",")){
			if( part.contains("@")) {
				join.add(part);
			}else{
				String found = emailBook.get(part);
				if( found !=null){
					join.add(found);
				}else{
					Logger.error("Invalid ref given for email "+part);
				}
			}
		}
		email.toRaw=join.toString();
	}

	/**
	 * Enable or disable sending of emails
	 * @param send True for sending, false if not
	 */
	public void setSending( boolean send ){
		sendEmails=send;
	}


	@Override
	public boolean removeWritable(Writable wr) {
		return false;
	}
	/* *********************************  W O R K E R S ******************************************************* */

	/**
	 * Method to send an email
	 */
	private void sendEmail(Email email, boolean retry) {

		try {
			if (mailSession == null) {
				if (outboxAuth) {
					mailSession = Session.getInstance(props, new Authenticator() {
						@Override
						protected PasswordAuthentication getPasswordAuthentication() {
							return new PasswordAuthentication(outbox.user, outbox.pass);
						}
					});
				} else {
					mailSession = Session.getInstance(props, null);
				}
				//mailSession.setDebug(true); 	// No need for extra feedback
			}

			var message = buildMessage(email);
			boolean hasAttachment = false;
			if ( email.hasAttachment() ) { // If there's no attachment, this changes the content type
				hasAttachment=addAttachment(email,message);
			}

			// Send the complete message parts
			Logger.debug("Trying to send email to " + email.toRaw + " through " + outbox.server + "!");
			Transport.send(message);

			if( hasAttachment ){
				try {
					var safe = Paths.settings().getParent().toAbsolutePath().toString();
					var zip = Path.of(email.attachment+".zip").normalize().toAbsolutePath();
					if( zip.toString().startsWith(safe) )
						Files.deleteIfExists(zip);
					if (email.deleteOnSend()) {
						var reg = Path.of(email.attachment+".zip").normalize().toAbsolutePath();
						if( reg.toString().startsWith(safe) )
							Files.deleteIfExists(reg);
					}
				} catch (IOException e) {
					Logger.error(e);
				}
			}

			errorCount = 0;
			if( !retryQueue.isEmpty() ){
				retryFuture.cancel(true); // stop the retry attempt
				//Only got one thread so we can submit before clearing without worrying about concurrency

				retryQueue.forEach( em -> {
					Logger.info("Retrying email to "+em.toRaw);
					scheduler.execute( ()-> sendEmail(em,false));
				} );
				retryQueue.clear();
			}
		} catch (MessagingException ex) {
			handleError( ex, email, retry );
		}
	}

	/**
	 * Process the errors that occurred during the attempt to send an email
	 * @param ex The error that occurred
	 * @param email The email that was being sent
	 * @param retry If it's a retry of an older email or not
	 */
	private void handleError(MessagingException ex, Email email, boolean retry){
		Logger.error("Failed to send email: " + ex);
		email.addAttempt();
		if( !retry ){ // If this wasn't a retry
			if( retryQueue.isEmpty() || retryFuture == null || retryFuture.isDone() ) {
				Logger.info("Scheduling a retry after 10 seconds");
				retryFuture = scheduler.schedule( ()-> sendEmail(email,true), 10, TimeUnit.SECONDS);
			}
			Logger.info("Adding email to " + email.toRaw + " about " + email.subject + " to resend queue. Error count: " + errorCount);
			retryQueue.add(email);
		}else{
			if( email.isFresh(maxEmailAgeInHours) ) { // If the email is younger than the preset age
				Logger.info("Scheduling a successive retry after " + TimeTools.convertPeriodToString(Math.min(30 * email.getAttempts(), 300), TimeUnit.SECONDS) + " with "
						+ retryQueue.size() + " emails in retry queue");

				retryFuture = scheduler.schedule(()-> sendEmail(email, true), Math.min(30 * email.getAttempts(), 300), TimeUnit.SECONDS);
			}else{// If the email is older than the preset age
				retryQueue.removeIf(em -> !em.isFresh(maxEmailAgeInHours)); // Remove all old emails
				if( !retryQueue.isEmpty()){ // Check if any emails are left, and if so send the first one
					retryFuture = scheduler.schedule(()-> sendEmail(retryQueue.get(0), true), 300, TimeUnit.SECONDS);
				}
			}
		}
		errorCount++;
	}
	/**
	 * Get the info from the email and build a message with it
	 * @param email The email info to send
	 * @return The build message
	 * @throws MessagingException Something went wrong building the message
	 */
	private Message buildMessage(Email email) throws MessagingException {
		Message message = new MimeMessage(mailSession);

		String subject = email.subject;
		if (email.subject.endsWith(" at.")) { //macro to get the local time added
			subject = email.subject.replace(" at.", " at " + TimeTools.formatNow("HH:mm") + ".");
		}
        try {
            message.setSubject( MimeUtility.encodeText(subject, "UTF-8", "B") );
        } catch (UnsupportedEncodingException e) {
            Logger.error("Failed to encode subject.");
        }

        if (!email.hasAttachment())  // If there's no attachment, this changes the content type
			message.setContent(email.content, "text/html");

		String from = email.from.isEmpty()?(outbox.getFromStart()+"<" + outbox.from + ">"):email.from;
		message.setFrom(new InternetAddress( from ));
		for (String single : email.toRaw.split(",")) {
			try {
				message.addRecipient(Message.RecipientType.TO, new InternetAddress(single.split("\\|")[0]));
			} catch (AddressException e) {
				Logger.warn("Issue trying to convert: " + single.split("\\|")[0] + "\t" + e.getMessage());
				Logger.error(e.getMessage());
			}
		}
		return message;
	}
	/**
	 * Add the attachment information from the email to the message
	 * @param email The email to handle the attachment from
	 * @param message The message being build
	 * @throws MessagingException Something went wrong altering the message
	 */
	private boolean addAttachment( Email email, Message message ) throws MessagingException {
		String attach;

		int a = email.attachment.indexOf("[");
		// If a [ ... ] is present this means that a datetime format is enclosed and then [...] will be replaced
		// with format replaced with actual current datetime
		// eg [HH:mm] -> 16:00
		if (a != -1) {
			int b = email.attachment.indexOf("]");
			String dt = email.attachment.substring(a + 1, b);
			dt = TimeTools.formatUTCNow(dt); //replace the format with datetime
			attach = email.attachment.substring(0, a) + dt + email.attachment.substring(b + 1); // replace it in the attachment
			Logger.info("Changed " + email.attachment + " to " + attach);
		} else { // Nothing special happens
			attach = email.attachment;
		}

		Path path = Path.of(attach).normalize().toAbsolutePath();
		if( !path.startsWith(Paths.storage().toAbsolutePath().toString()) ) {
			Logger.error("Attach at "+path+ " didn't reside in "+Paths.storage());
			return false;
		}

		try {
			if (Files.notExists(path)) { // If the attachment doesn't exist
				email.attachment = "";
				message.setContent(email.content, "text/html");
				message.setSubject( message.getSubject() + " [attachment not found!]"); // Notify the receiver that is should have had an attachment
				return false;
			} else if (Files.size(path) > doZipFromSizeMB * megaByte ) { // If the attachment is larger than the zip limit
				FileTools.zipFile(path); // zip it
				attach += ".zip"; // rename attachment
				Logger.info("File zipped because of size larger than " + doZipFromSizeMB + "MB. Zipped size:" + Files.size(path) / megaByte + "MB");
				path = Path.of(attach);// Changed the file to archive, zo replace file
				if (Files.size(path) > maxSizeMB * megaByte) { // If the zip file it too large to send, maybe figure out way to split?
					email.attachment = "";
					message.setContent(email.content, "text/html");
					message.setSubject(message.getSubject() + " [ATTACHMENT REMOVED because size constraint!]");
					Logger.info("Removed attachment because to big (>" + maxSizeMB + "MB)");
					return false;
				}
			}
		} catch (IOException e) {
			Logger.error(e);
			return false;
		}

		// Create the message part
		BodyPart messageBodyPart = new MimeBodyPart();

		// Fill the message
		messageBodyPart.setContent(email.content, "text/html");

		// Create a multipart message
		Multipart multipart = new MimeMultipart();

		// Set text message part
		multipart.addBodyPart(messageBodyPart);

		// Part two is attachment
		messageBodyPart = new MimeBodyPart();
		DataSource source = new FileDataSource(attach);
		messageBodyPart.setDataHandler(new DataHandler(source));
		messageBodyPart.setFileName(Path.of(attach).getFileName().toString());
		multipart.addBodyPart(messageBodyPart);

		// Add the attachment info to the message
		message.setContent(multipart);

		return true;
	}
	/**
	 * Set the properties for sending emails
	 */
	private static void alterInboxProps( Properties props ){
		// Set a fixed timeout of 10s for all operations the default timeout is "infinite"		
		props.put( "mail.imap.connectiontimeout", TIMEOUT_MILLIS); //10s timeout on connection
		props.put( "mail.imap.timeout", TIMEOUT_MILLIS);
		props.put( "mail.imap.writetimeout", TIMEOUT_MILLIS);
		props.put( MAIL_SMTP_TIMEOUT, TIMEOUT_MILLIS);    
		props.put( MAIL_SMTP_CONNECTIONTIMEOUT, TIMEOUT_MILLIS);
	}
	private List<String> findTo( String from ){
		if( from.startsWith(inbox.user)){
			var ar = new ArrayList<String>();
			ar.add("echo");
			return ar;
		}
		return emailBook.entrySet().stream().filter(e -> e.getValue().contains(from)).map(Map.Entry::getKey).collect(Collectors.toList());
	}

	/**
	 * Check if the email cmd from a certain sender is denied to run, admin commands are only allow for admins unless
	 * explicit permission was given
	 * @param refs List of ref's the 'from' belongs to
	 * @param from The sender
	 * @param subject The command to execute
	 * @return True if this sender hasn't got the permission
	 */
	private boolean isDenied(List<String> refs, String from, String subject){
		boolean deny=isAdminCommand(subject);
		if( deny && refs.contains("admin") )
			return false;

		if( from.startsWith(inbox.user+"@") )
			return false;

		if( !permits.isEmpty() ){
			for( Permit permit : permits){
				if ( matchesPermit( permit,refs,from,subject ) ){
					return permit.denies;
				}
			}
		}
		return deny;
	}

	/**
	 * Check if it's an admin command
	 * @param subject The command requested
	 * @return True if it's an admin command
	 */
	private boolean isAdminCommand(String subject) {
		return subject.contains("admin") ||
				subject.startsWith("sd") ||
				subject.startsWith("shutdown") ||
				subject.startsWith("sleep") ||
				subject.startsWith("update") ||
				subject.startsWith("retrieve:set");
	}

	/**
	 * Check if the sender is permitted to issue admin commands
	 * @param permit The permit
	 * @param refs List of ref's the 'from' belongs to
	 * @param from The origin
	 * @param subject The cmd
	 * @return True if ok
	 */
	private boolean matchesPermit(Permit permit, List<String> refs, String from, String subject) {
		boolean match = permit.ref.contains("@") ? permit.ref.equals(from) : refs.contains(permit.ref);
		return match && (permit.regex ? subject.matches(permit.value) : subject.equals(permit.value));
	}
	/**
	 * Class that checks for emails at a set interval.
	 */
	public class Check implements Runnable {

		@Override
		public void run() {
			ClassLoader tcl = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(Session.class.getClassLoader());
			boolean ok = false;

			try( Store inboxStore = inboxSession.getStore("imaps")) {	// Store implements autoCloseable

				inboxStore.connect( inbox.server, inbox.port, inbox.user, inbox.pass );
				
			    Folder inbox = inboxStore.getFolder( "INBOX" );
			    inbox.open( Folder.READ_WRITE );				
			    // Fetch unseen messages from inbox folder
			    Message[] messages = inbox.search(
			        new FlagTerm(new Flags(Flags.Flag.SEEN), false));

			    if( messages.length == 0 ) // No message means nothing to do
					return;

				Logger.info( "Messages found: "+messages.length );
			    for ( Message message : messages ) {
					String from = message.getFrom()[0].toString();
					from = from.substring(from.indexOf("<")+1, from.length()-1);
					String cmd = message.getSubject();

					if( doEarlyAbortChecks(from,message) )
						continue;

					String to = message.getRecipients(Message.RecipientType.TO)[0].toString();
					String body = getTextFromMessage(message);

					if( checkifForAnother( message, to, from, body ) )
						continue;

					maxQuickChecks = 5;
					Logger.info("Command: " + cmd + " from: " + from );

					processAttachments( message );
					processCommand( cmd, from, body );

					message.setFlag(Flags.Flag.DELETED, true);
				}
				ok=true;
				lastInboxConnect = Instant.now().toEpochMilli();							    		
			}catch(com.sun.mail.util.MailConnectException  f ){	
				Logger.error("Couldn't connect to host: "+inbox.server);
			} catch ( MessagingException | IOException e1) {
				Logger.error(e1.getMessage());
			}catch(RuntimeException e){
				Logger.error("Runtime caught");
				Logger.error(e);
			}finally{
				Thread.currentThread().setContextClassLoader(tcl);						
			}

			if( !ok ){ // If no connection could be made schedule a sooner retry
				Logger.warn("Failed to connect to inbox, retry scheduled. (last ok: "+getTimeSincelastInboxConnection()+")");
				// Only schedule new one if none is being waited for
				if( slowCheck == null || slowCheck.isCancelled() || slowCheck.isDone() )
					slowCheck = scheduler.schedule( new Check(), 60, TimeUnit.SECONDS);
			}else if( maxQuickChecks > 0){
				// If an email was received, schedule an earlier check. This way follow-ups are responded to quicker
				maxQuickChecks--;
				Logger.info("Still got "+maxQuickChecks+" to go...");
				if( fastCheck == null || fastCheck.isCancelled() || fastCheck.isDone() )
					fastCheck = scheduler.schedule( new Check(), Math.min(checkIntervalSeconds/3, 30), TimeUnit.SECONDS);
			}
	   }
	}

	/**
	 * Check the origin of the email to see if that origin is actually allowed to talk to dcafs
	 * @param from The origin
	 * @param message The message received
	 * @return True if the email needs to be ignored
	 * @throws MessagingException The problems that occurred during use of message
	 */
	private boolean doEarlyAbortChecks(String from, Message message ) throws MessagingException {
		var tos = findTo(from);
		var cmd = message.getSubject();

		if( tos.isEmpty()){
			sendEmail( Email.to(from).subject("My admin doesn't allow me to talk to strangers...") );
			sendEmail( Email.toAdminAbout("Got spam? ").content("From: "+from+" "+cmd) );
			Logger.warn("Received spam from: "+from);
			message.setFlag(Flags.Flag.DELETED, true); // delete spam
			return true;
		}

		if( isDenied(tos, from, cmd) ){
			sendEmail( Email.to(from).subject("Not allowed to use "+cmd).content("Try asking an admin for permission?") );
			sendEmail( Email.toAdminAbout("Permission issue?").content("From: "+from+" -> "+cmd) );
			Logger.warn(from+" tried using "+cmd+" without permission");
			message.setFlag(Flags.Flag.DELETED, true); // delete things without permission
			return true;
		}
		return false;
	}

	/**
	 * Check if the message is multipart and if so if it contains an attachment, if so extract it
	 * @param message The message received
	 * @throws MessagingException The problems that occurred
	 */
	private void processAttachments( Message message) throws MessagingException {
		if ( message.getContentType()!=null && message.getContentType().contains("multipart") ) {
			try {
				Object objRef = message.getContent();
				if(objRef instanceof Multipart){

					Multipart multiPart = (Multipart) message.getContent();
					if( multiPart != null ){
						for (int i = 0; i < multiPart.getCount(); i++) {
							MimeBodyPart part = (MimeBodyPart) multiPart.getBodyPart(i);

							if ( part != null && Part.ATTACHMENT.equalsIgnoreCase( part.getDisposition() )) {
								Logger.info("Attachment found:"+part.getFileName());
								Path p = Path.of("attachments",part.getFileName()).normalize().toAbsolutePath();
								if( p.toString().startsWith( Paths.storage().toAbsolutePath().toString())) {
									Files.createDirectories(p.getParent());

									part.saveFile(p.toFile());

									if (p.getFileName().toString().endsWith(".zip")) {
										Logger.info("Attachment zipped!");
										FileTools.unzipFile(p.toString(), "attachments");
										Logger.info("Attachment unzipped!");
										if (deleteReceivedZip)
											Files.deleteIfExists(p);
									}
								}
							}
						}
					}
				}else{
					Logger.error("Can't work with this thing: "+message.getContentType());
				}
			} catch (Exception e) {
				Logger.error("Failed to read attachment");
				Logger.error(e);
			}
		}
	}
	private void processCommand( String cmd, String from, String body){
		cmd = cmd.substring(0,cmd.indexOf(" for")); // remove everything not related to the command

		if( cmd.startsWith("label:")&&cmd.length()>7){ // email acts as data received from sensor, no clue on the use case yet
			for( String line : body.split("\r\n") ) {
				if( line.isEmpty()){
					break;
				}
				Core.addToQueue( Datagram.build(line).label(cmd.split(":")[1]).origin(from) );
			}
		}else{
			// Retrieve asks files to be emailed, if this command is without email append from address
			if( cmd.startsWith("retrieve:") && !cmd.contains(",")){
				cmd += ","+from;
			}
			var d = Datagram.build(cmd).label("email").origin(from);
			if( cmd.contains(":")) { // only relevant for commands that contain :
				DataRequest req = new DataRequest(from, cmd);
				d.writable(req.getWritable());
				buffered.put(req.getID(), req);
			}
			d.origin(from);
			Core.addToQueue( d );
		}
	}

	/**
	 * If the email has a 'for' in the subject it means it's for a certain instance of dcafs, check if this matches
	 * @param message The message received
	 * @param to The
	 * @param from The origin email address
	 * @param body The content of the email
	 * @return True if not for us, to know to skip the rest of the processing
	 * @throws MessagingException Problems that might occur during checking the message
	 */
	private boolean checkifForAnother( Message message, String to, String from, String body ) throws MessagingException {
		var cmd = message.getSubject();
		if( cmd.contains(" for ")) { // meaning for multiple client checking this inbox
			Logger.info("Checking if meant for me... "+outbox.getFromStart()+" in "+cmd);
			if (!cmd.contains( outbox.getFromStart() )) { // the subject doesn't contain the id
				message.setFlag(Flags.Flag.SEEN, false);// revert the flag to unseen
				Logger.info("Email read but meant for another instance...");
				return true; // go to next message
			}else{
				String newSub = cmd.replaceFirst(",?"+outbox.getFromStart(),"");
				Logger.info( "Altered subject: "+newSub+"<");
				if( !newSub.endsWith("for ")){ // meaning NOT everyone read it
					message.setFlag(Flags.Flag.SEEN, false);
					Logger.info( "Not yet read by "+newSub.substring(newSub.indexOf(" for ")+5));

					Logger.info("Someone else wants this...");
					sendEmail( Email.to(to).from(from).subject(newSub).content(body) ); // make sure the other can get it
				}else{
					Logger.info( "Only one/Last one read it");
				}
			}
		}
		return false;
	}
	/**
	 * Retrieve the text content of an email message
	 * @param message The message to get content from
	 * @return The text content if any, delimited with \r\n
	 * @throws MessagingException Something went wrong handling the message
	 * @throws IOException Something went wrong while checking the message
	 */
	private static String getTextFromMessage(Message message) throws MessagingException, IOException {
		String result = "";
		if (message.isMimeType("text/plain")) {
			result = message.getContent().toString();
		} else if (message.isMimeType("multipart/*")) {
			MimeMultipart mimeMultipart = (MimeMultipart) message.getContent();
			result = getTextFromMimeMultipart(mimeMultipart);
		}
		return result;
	}

	/**
	 * Retrieve the text from a mimemultipart
	 * @param mimeMultipart The part to get the text from
	 * @return The text found
	 * @throws MessagingException Something went wrong handling the message
	 */
	private static String getTextFromMimeMultipart(
			MimeMultipart mimeMultipart)  throws MessagingException, IOException{

		StringJoiner result = new StringJoiner("\r\n");
		int count = mimeMultipart.getCount();
		for (int i = 0; i < count; i++) {
			BodyPart bodyPart = mimeMultipart.getBodyPart(i);
			if (bodyPart.isMimeType("text/plain")) {
				result.add(String.valueOf(bodyPart.getContent()));
				break; // without break same text appears twice in my tests
			} else if (bodyPart.isMimeType("text/html")) {
				String html = (String) bodyPart.getContent();
				result.add(html); // maybe at some point do actual parsing?
			} else if (bodyPart.getContent() instanceof MimeMultipart){
				result.add( getTextFromMimeMultipart((MimeMultipart)bodyPart.getContent()));
			}
		}
		return result.toString();
	}
	@Override
	public void collectorFinished( String id, String message, Object result) {

		DataRequest req = buffered.remove(id.split(":")[1]);
		if( req == null ){
			Logger.error("No such DataRequest: "+id);
			return;
		}

		if( req.getBuffer().isEmpty() ){
			Logger.error("Buffer returned without info");
		}else{
			Logger.info("Buffer returned with info");
			sendEmail( Email.to(req.to).subject("Buffered response to "+req.about).content(String.join("<br>", req.getBuffer())) );
		}
	}

	public class DataRequest{
		BufferCollector bwr;
		String to;
		String about;

		public DataRequest( String to, String about ){
			bwr = BufferCollector.timeLimited(String.valueOf(Instant.now().toEpochMilli()), "60s", scheduler);
			bwr.addListener(EmailWorker.this);
			this.to=to;
			this.about=about;
		}
		public String getID(){
			return bwr.id();
		}
		public List<String> getBuffer(){
			return bwr.getBuffer();
		}
		public Writable getWritable(){
			return bwr.getWritable();
		}
	}
	private static class MailBox{
		String server = ""; // Server to send emails with
		int port = -1; // Port to contact the server on
		boolean hasSSL = false; // Whether the outbox uses ssl
		String user = ""; // User for the outbox
		String pass = ""; // Password for the outbox user
		String from = "dcafs"; // The email address to use as from address

		public void setServer(String server, int port){
			this.port = port;
			this.server=server;
		}
		public void setLogin( String user, String pass ){
			this.user=user;
			this.pass=pass;
		}
		public String getFromStart(){
			return from.substring(0,from.indexOf("@"));
		}
	}
	private static class Permit {
		boolean denies;
		boolean regex;
		String value;
		String ref;

		public Permit(boolean denies,String ref,String value,boolean regex){
			this.denies=denies;
			this.ref=ref;
			this.value=value;
			this.regex=regex;
		}
	}
}
