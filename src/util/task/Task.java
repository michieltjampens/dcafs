package util.task;

import io.Writable;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.RealtimeValues;
import util.taskblocks.CheckBlock;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLdigger;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Random;
import java.util.StringJoiner;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Task implements Comparable<Task>{

	String value = "";					// Value of the task, the text that something will be done with if checks succeed
	byte[] bytes;
	String when;
	
	String id;							// The identifier of this task
	int errorOccurred=0;
	String badReq="";

	/* Interval */
	long interval = 0;					// If it's an interval task, this is where the interval is stored
	long startDelay = 0;				// If it's an interval task, this is where the delay before first execution is stored
	TimeUnit unit = TimeUnit.SECONDS;	// The time unit of the interval and start delay
	boolean enableOnStart=true;			// If the task should be started on startup

	/* Retry and also channel in a way*/
	int retries=-1;				// How many retries if allowed for a retry task
	int runs = -1;
	int attempts=0;

	ScheduledFuture<?> future;	// The future if the task is scheduled, this way it can be cancelled
	boolean enabled = false;	// Whether or not the task is currently enabled
	String keyword="";
	
	/* output:channel */
	String reply ;				// The reply the task want's to receive as response to the send txt
	long replyInterval=3;
	int replyRetries=5;

	Writable writable;
	String stream="";			// The channel name for the output (as read from the xml)
	
	/* output:file */
	Path outputFile;			// To store the path to the file is the output is file

	/* output:email */
	String attachment="";		// Link to the file to attach to an email

	/* trigger:Clock */
	LocalTime time;				// The time at which the task is supposed to be executed
	ArrayList<DayOfWeek> taskDays;   // On which days the task is to be executed
	String[] args;
	boolean utc = false;											// If the time is in UTC or not

	/* Output */
	enum OUTPUT { SYSTEM, MANAGER, LOG, FILE, EMAIL, STREAM, MQTT, I2C, TELNET, MATRIX }    // The different options for the output, see manual for further explanation
	OUTPUT out = OUTPUT.SYSTEM;											// The output of the task, default is system
	String outputRef="";												// The actual output fe. channel, email address, filename, sms number etc

	/* Link */ 
	enum LINKTYPE { NONE , DISABLE_24H, NOT_TODAY, DO_NOW, SKIP_ONE} // The options for the linking of a task
	String link ;						// If it has a link with a task (can be itself)
	LINKTYPE linktype=LINKTYPE.NONE;	// The type of link
	boolean doToday=true;				// If the task is allowed to be executed today
	int skipExecutions=0;				// How many executions should be skipped

	public enum TRIGGERTYPE {KEYWORD,CLOCK,INTERVAL,DELAY,EXECUTE,RETRY,WHILE,WAITFOR} // The trigger possibilities
	TRIGGERTYPE triggerType = TRIGGERTYPE.EXECUTE;								  	   // Default trigger type is execute (no trigger)

	int reqIndex=-1;
	int checkIndex=-1;

	/* Taskset */ 
	private String taskset="";			// The taskset this task is part of
	private int tasksetIndex=-1;		// The index the task has in the taskset it's part of
	private boolean stopOnFail=true;	// Whether this task should stop the taskset on failure
	private StringJoiner buildError;
	/* *************************************  C O N S T R U C T O R S ***********************************************/
	/**
	 * Constructor that parses an Element to get all the info
	 * @param tsk The element for a task
	 */
	public Task(Element tsk, RealtimeValues rtvals, ArrayList<CheckBlock> sharedChecks){
		buildError=new StringJoiner("\r\n");
		var dig = XMLdigger.goIn(tsk);

		when  = dig.attr("state","always"); //The state that determines if it's done or not

		if( tsk.getTagName().equalsIgnoreCase("while")||tsk.getTagName().equalsIgnoreCase("waitfor")){
			Pair<Long,TimeUnit> period = TimeTools.parsePeriodString(dig.attr("interval",""));
			interval = period.getKey();
			unit = period.getValue();
			runs = dig.attr("checks",1);

			switch (tsk.getTagName()) {
				//case "retry": triggerType =TRIGGERTYPE.RETRY; break;
				case "while" -> triggerType = TRIGGERTYPE.WHILE;
				case "waitfor" -> triggerType = TRIGGERTYPE.WAITFOR;
			}
			var check = dig.attr("check","");
			if( check.isEmpty() )
				check = tsk.getTextContent();
			if( !check.isEmpty() ){
				for( int a=0;a<sharedChecks.size();a++ ){
					if( sharedChecks.get(a).matchesOri(check)){
						reqIndex=a;
						break;
					}
				}
				if( checkIndex==-1){
					var cb = CheckBlock.prepBlock(rtvals,check);
					if( sharedChecks.isEmpty()) {
						cb.setSharedMem(new ArrayList<>());
					}else{
						cb.setSharedMem(sharedChecks.get(0).getSharedMem());
					}
					if( !cb.build() ) {
						buildError.add("Failed to parse " + check );
						Logger.error(buildError);
					}
					sharedChecks.add(cb);
					reqIndex = sharedChecks.size()-1;
				}
			}
		}else{
			id = dig.attr("id", String.valueOf(new Random().nextLong())).toLowerCase();

			reply = dig.attr( "reply", "");
			var wind = dig.attr("replywindow", "");
			if( !wind.isEmpty()){
				if( wind.contains(",") ) {
					var items = wind.split(",");
					replyInterval = TimeTools.parsePeriodStringToSeconds(items[0]);
					if(NumberUtils.isParsable(items[1])) {
						replyRetries = NumberUtils.toInt(items[1],replyRetries);
					}else {
						replyRetries = 1;
					}
				}else{
					replyInterval = TimeTools.parsePeriodStringToSeconds(wind);
					replyRetries = 1;
				}
			}

			link = dig.attr("link", "");
			stopOnFail = dig.attr("stoponfail",true);
			enableOnStart = dig.attr("atstartup",true);

			String req = dig.attr( "req", "");
			if( !req.isEmpty() ){
				for( int a=0;a<sharedChecks.size();a++ ){
					if( sharedChecks.get(a).matchesOri(req)){
						if( !sharedChecks.get(a).isValid()) {
							buildError.add( "Failed to parse req: " + req);
						}
						reqIndex=a;
						break;
					}
				}
				if( reqIndex==-1){
					var cb = CheckBlock.prepBlock(rtvals,req);
					if( sharedChecks.isEmpty()) {
						cb.setSharedMem(new ArrayList<>());
					}else{
						cb.setSharedMem(sharedChecks.get(0).getSharedMem());
					}
					if( !cb.build() ) {
						buildError.add("Failed to parse " + req);
						Logger.error(buildError);
					}else {
						sharedChecks.add(cb);
						reqIndex = sharedChecks.size() - 1;
					}
				}
			}
			String check = dig.attr("check", "");
			if( !check.isEmpty() ){
				for( int a=0;a<sharedChecks.size();a++ ){
					if( sharedChecks.get(a).matchesOri(check)){
						checkIndex=a;
						break;
					}
				}
				if( checkIndex==-1){
					var cb = CheckBlock.prepBlock(rtvals,check);
					if( sharedChecks.isEmpty()) {
						cb.setSharedMem(new ArrayList<>());
					}else{
						cb.setSharedMem(sharedChecks.get(0).getSharedMem());
					}
					if( !cb.build() ){
						buildError.add("Failed to parse " + check);
						Logger.error(buildError);
					}
					sharedChecks.add(cb);
					checkIndex = sharedChecks.size()-1;
				}
			}

			convertOUT( dig.attr("output", "system") );
			convertTrigger( dig );


			if( tsk.getFirstChild() != null ){
				value = tsk.getFirstChild().getTextContent(); // The control command to execute
				if( value.startsWith("\\h(") ){
					bytes=Tools.fromHexStringToBytes( value.substring(3, value.lastIndexOf(")") ) );
				}else if( value.startsWith("\\d(") ){
					bytes = Tools.fromDecStringToBytes( value.substring(3, value.indexOf(")")) );
				}else{
					bytes=value.getBytes();
				}
			}else{
				Logger.tag("TASK").info("["+(taskset.isEmpty()?"noset":taskset)+"] Task of type "+ triggerType +" without value.");
			}
			/* Actions to take depending on the kind of output, meaning elements that are only present for certain outputs */
			if( out == OUTPUT.EMAIL)
				attachment = dig.attr( "attachment", "");

			/* Link related items */
			if(!link.isBlank()) { // If link is actually mentioned
				String[] linking = link.toLowerCase().split(":");
				switch (linking[0]) {
					case "disable24h" -> linktype = LINKTYPE.DISABLE_24H;// Disable for 24hours
					case "nottoday" -> linktype = LINKTYPE.NOT_TODAY;// Disable for the rest of the day (UTC)
					case "donow" -> linktype = LINKTYPE.DO_NOW;// Execute the linked task now
					case "skipone" -> linktype = LINKTYPE.SKIP_ONE;// Skip one execution of the linked task
				}
				link = linking[1];
			}
		}
	}
	public void setArgs( String[] args) {
		this.args=args;
	}
	public String getValue(){
		if( args==null)
			return value;
		var val = value;
		for( int a=0;a<args.length;a++)
			val = val.replace("i"+a,args[a]);
		return val;
	}
	public String getBuildError(){
		return buildError.toString();
	}
	public int getReqIndex( ){
		return reqIndex;
	}
	public int getCheckIndex(){
		return checkIndex;
	}
	public boolean isEnableOnStart(){
		return enableOnStart;
	}
	public void errorIncrement(){
		errorOccurred++;
		if( errorOccurred > 10 ){
			Logger.error("Task caused to many failed rtval issues when looking for "+badReq+", cancelling.");
			cancelFuture(false);
		}
	}
	public void cancelFuture( boolean mayInterruptIfRunning){
		if( future != null)
			future.cancel(mayInterruptIfRunning);
	}
	public boolean stopOnFailure(){
		return stopOnFail;
	}
	public ScheduledFuture<?> getFuture(){
		return this.future;
	}
	public String getID(  ){
		return this.id;
	}
	public void reset(){
		attempts=0;
		future.cancel( false );
		Logger.tag("TASK").info("Reset executed for task in "+this.taskset);
	}
	/* ************************************  T R I G G E R  **********************************************************/
	/**
	 * Retrieve the kind of trigger this task uses
	 * @return String representation of the trigger (uppercase)
	 */
	public TRIGGERTYPE getTriggerType() {
		return triggerType;
	}
	/**
	 * Retrieve the time associated with trigger:clock
	 * @return The readable time
	 */
	public LocalTime getTime(){
		return time;
	}
	private void convertTrigger( XMLdigger dig ){
		var cmd = dig.matchAttr("interval","delay","time", "utctime", "localtime","retry", "while", "waitfor","trigger");
		var trigger = dig.attr(cmd,"");
		String[] items;
		if( cmd.equals("trigger")){
			trigger = trigger.replace(";", ",").toLowerCase();
			trigger = trigger.replace("=",":");
			cmd = trigger.substring(0, trigger.indexOf(":"));
			items = trigger.substring(trigger.indexOf(":")+1).split(",");
		}else if(cmd.isEmpty() ){
			triggerType = TRIGGERTYPE.EXECUTE;
			return;
		}else{// Any of other keywords
			items = trigger.split(",");
		}

		switch (cmd) {  /* time:07:15 or time:07:15,thursday */
			case "time", "utctime", "localtime" -> {
				if (!cmd.startsWith("local"))
					utc = true;
				time = LocalTime.parse(items[0], DateTimeFormatter.ISO_LOCAL_TIME);
				taskDays = TimeTools.convertDAY(items.length == 2 ? items[1] : "");
				triggerType = TRIGGERTYPE.CLOCK;
			}    /* retry:10s,-1 */
			/* while:10s,2 */
			case "retry", "while", "waitfor" -> { /* waitfor:10s,1 */
				Pair<Long, TimeUnit> period = TimeTools.parsePeriodString(items[0]);
				interval = period.getKey();
				unit = period.getValue();
				if (items.length > 1) {
					runs = Tools.parseInt(items[1], -1);
				}
				switch (cmd) {
					case "retry" -> triggerType = TRIGGERTYPE.RETRY;
					case "while" -> triggerType = TRIGGERTYPE.WHILE;
					case "waitfor" -> triggerType = TRIGGERTYPE.WAITFOR;
				}
			}
			case "delay" -> {    /* delay:5m3s */
				startDelay = TimeTools.parsePeriodStringToMillis(items[0])+50;
				unit = TimeUnit.MILLISECONDS;
				triggerType = TRIGGERTYPE.DELAY;
			}
			case "interval" -> { /* interval:5m3s or interval:10s,5m3s*/
				retries = 5;
				runs = 5;
				if (items.length == 1) {//Just interval
					interval = TimeTools.parsePeriodStringToMillis(items[0]);
					unit = TimeUnit.MILLISECONDS;
					startDelay = 50;   // First occurrence is directly
				} else {//Delay and interval
					startDelay = TimeTools.parsePeriodStringToMillis(items[0])+50;
					interval = TimeTools.parsePeriodStringToMillis(items[1]);
					unit = TimeUnit.MILLISECONDS;
				}
				triggerType = TRIGGERTYPE.INTERVAL;
			}
			default -> {
				this.keyword = trigger;
				triggerType = TRIGGERTYPE.KEYWORD;
			}
		}
	}
	/* ***************************************************************************************************/
	/**
	 * If the task is scheduled, this sets the future object
	 * @param future The future to set
	 */
	public void setFuture(java.util.concurrent.ScheduledFuture<?> future){
		this.future=future;
	}
	/* ****************************************  O U T P U T ************************************************************/
	/**
	 * Convert the string representation of the output to usable objects
	 * @param output The string output
	 */
	private void convertOUT( String output ){		
		if( !output.isBlank() ){
			
			String[] o = output.split(":");
			if( o.length>=2)
				outputRef=o[1];
			switch (o[0].toLowerCase()) {
				case "file" -> {
					out = OUTPUT.FILE;
					outputFile = Path.of(o[1]);
				}
				case "email" -> out = OUTPUT.EMAIL;
				case "channel", "stream" -> {
					out = OUTPUT.STREAM;
					stream = o[1].toLowerCase();
				}
				case "matrix" -> {
					out = OUTPUT.MATRIX;
					stream = o[1].toLowerCase();
				}
				case "log" -> out = OUTPUT.LOG;
				case "manager" -> out = OUTPUT.MANAGER;
				case "mqtt" -> {
					out = OUTPUT.MQTT;
					stream = o[1].toLowerCase();
				}
				case "i2c" -> {
					out = OUTPUT.I2C;
					stream = o.length == 2 ? o[1].toLowerCase() : "";
				}
				case "telnet" -> out = OUTPUT.TELNET;
				default -> out = OUTPUT.SYSTEM;
			}
		}
	}	

	public void setWritable( Writable writable ){
		this.writable=writable;
	}

	/* *******************************************  L I N K **********************************************************/
	/**
	 * Check if the task should run on a specifick day of the week
	 * @param day The day of the week to check
	 * @return True if it should run
	 */
	public boolean runOnDay( DayOfWeek day ){
		return taskDays.contains(day);
	}
	public boolean runNever(  ){
		return taskDays.isEmpty();
	}

	/* ************************************  F O L L O W  U P ********************************************************/
	/**
	 * Check if the task is part of a taskset that step's through tasks
	 * @return True if the task is part of a taskset
	 */
	public boolean hasNext() {
		return tasksetIndex !=-1;
	}
	/**
	 * Get the short name of the taskset this task is part of
	 * @return The short name of the taskset
	 */
	public String getTaskset( ){
		return taskset;
	}
	/**
	 * Get the index of this task in the taskset it is part of
	 * @return The index of this task in the set
	 */
	public int getIndexInTaskset(){
		return tasksetIndex;
	}
	/**
	 * Set the taskset this task is part of and the index in the set
	 * @param taskset The short name of the taskset
	 * @param index The index in the taskset
	 */
	public void setTaskset( String taskset, int index ){
		this.taskset = taskset;
		this.tasksetIndex = index;
	}
	/* **************************************  U T I L I T Y *******************************************************/
	/**
	 * Compare this task to another task in respect to execution, which task is supposed to be executed earlier.
	 */
	@Override
	public int compareTo(Task to) {
		if( to.future != null ) {
			long timeTo = to.future.getDelay(TimeUnit.SECONDS);
			if( future != null ) {
				long thisOne = future.getDelay(TimeUnit.SECONDS);
				return (int) (thisOne - timeTo);
			}
		}
		return 0;
	}
	/**
	 * 
	 */
	public String toString(){
		String suffix="";
		switch (triggerType) {
			case CLOCK -> {
				if (future != null) {
					suffix = " scheduled at " + this.time + (utc ? " [UTC]" : "") + " next occurrence in " + TimeTools.convertPeriodToString(future.getDelay(TimeUnit.SECONDS), TimeUnit.SECONDS);
					if (future.getDelay(TimeUnit.SECONDS) < 0)
						suffix = ".";
				}
			}
			case DELAY -> {
				suffix = " after " + TimeTools.convertPeriodToString(startDelay, unit);
				if (future == null) {
					suffix += " [Not started]";
				} else if (future.isDone()) {
					suffix += " [Done]";
				} else {
					var delay = TimeTools.convertPeriodToString(future.getDelay(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
					suffix += " [Waiting... " + delay + "]";
				}
			}
			case INTERVAL -> {
				long next = future==null?-1:future.getDelay(TimeUnit.MILLISECONDS);
				suffix = " every " + TimeTools.convertPeriodToString(interval, unit) + (startDelay <= 0 ? "." : " after initial delay " + TimeTools.convertPeriodToString(startDelay, unit))
						+ (next <= 0 ? "." : (" next occurrence in " + TimeTools.convertPeriodToString(next, TimeUnit.MILLISECONDS)));
			}
			case KEYWORD -> suffix = " if " + keyword;
			default -> {
			}
		}
		if( !when.equals("always")&&!when.isBlank()) {
			suffix += " if state is "+when;
		}
		//if( preReq != null) {
		//	suffix += " "+preReq.toString();
		//}else{
			suffix +=".";
		//}
		return switch (out) {
			case STREAM -> "Sending '" + value.replace("\r", "").replace("\n", "") + "' to " + stream + suffix;
			case EMAIL -> "Emailing '" + value + "' to " + outputRef + suffix;
			case FILE -> "Writing '" + value + "' in " + outputFile + suffix;
			case LOG -> "Logging: '" + value + "' to " + outputRef + suffix;
			case MANAGER -> "Executing manager command: '" + value + "'  " + suffix;
			case MQTT -> "Executing mqtt command: '" + value + "'  " + suffix;
			case I2C -> "Sending " + value + " to I2C device " + suffix;
			case TELNET -> "Sending " + value + " to telnet sessions at level " + outputRef + suffix;
			default -> "Executing '" + value + "'" + suffix;
		};
	}
}