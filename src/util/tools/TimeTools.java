package util.tools;

import org.tinylog.Logger;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class TimeTools {

    static final public DateTimeFormatter LONGDATE_FORMATTER_UTC = DateTimeFormatter
                                                                        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                                                                        .withZone(ZoneOffset.UTC)
                                                                        .withLocale(Locale.ENGLISH);
    static final public DateTimeFormatter SHORTDATE_FORMATTER_UTC = DateTimeFormatter
                                                                        .ofPattern("yyyy-MM-dd HH:mm:ss")
                                                                        .withZone(ZoneOffset.UTC)
                                                                        .withLocale(Locale.ENGLISH);
    static final public DateTimeFormatter LONGDATE_FORMATTER = DateTimeFormatter
                                                                    .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                                                                    .withLocale(Locale.ENGLISH);

    private TimeTools(){
        throw new IllegalStateException("Utility class");
    }
    /**
     * This converts a string representation of a timestamp to a different string representation
     * @param date The input timestamp
     * @param inputFormat The format of the input timestamp
     * @param outputFormat The desired format for the timestamp
     * @return The input timestamp with the desired output format
     */
    public static String reformatDate(String date, String inputFormat, String outputFormat){
        try{
            if( inputFormat.startsWith("epochsec"))
                date=date+"000";
            if( inputFormat.startsWith("epochmillis")){
                Instant instant = Instant.ofEpochMilli(Long.parseLong(date));
                return DateTimeFormatter.ofPattern(outputFormat).withLocale(Locale.ENGLISH).withZone(ZoneId.of("UTC")).format(instant);
            }else {
                LocalDateTime dt = LocalDateTime.parse(date, DateTimeFormatter.ofPattern(inputFormat).withLocale(Locale.ENGLISH));
                return dt.format(DateTimeFormatter.ofPattern(outputFormat));
            }
        }catch(DateTimeParseException e){
            if( e.getMessage().contains("Unable to obtain LocalDateTime from TemporalAccessor")){
                try{
                    LocalDate dt = LocalDate.parse(date, DateTimeFormatter.ofPattern(inputFormat).withLocale(Locale.ENGLISH));
                    return dt.format( DateTimeFormatter.ofPattern(outputFormat) );
                }catch(DateTimeParseException f) {
                    Logger.error(f.getMessage());
                }
            }else{
                Logger.error(e);
            }
        }
        return "";
    }
    public static String reformatTime(String date, String inputFormat, String outputFormat){
        if( date.isEmpty() ){
            Logger.error("Can't reformat an empty date from "+inputFormat+" to "+outputFormat);
            return date;
        }
        try {
            LocalTime dt = LocalTime.parse(date, DateTimeFormatter.ofPattern(inputFormat));
            return dt.format(DateTimeFormatter.ofPattern(outputFormat));
        }catch(DateTimeParseException e){
            Logger.error(e.getMessage());
        }
        return "";
    }
    /**
     * Parses a given date+time with a given format to a localdatetime
     * @param dt The given datetime
     * @param format The formate the datetime is in
     * @return Parsed datetime
     */
    public static LocalDateTime parseDateTime( String dt , String format ){
        if( dt==null)
            return null;
        try {
            DateTimeFormatter formatter =
                    new DateTimeFormatterBuilder().appendPattern(format)
                            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
                            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
                            .toFormatter();
            return LocalDateTime.parse(dt, formatter);
        }catch( DateTimeParseException e ){
            Logger.error("Failed to parse "+dt+ " because "+e.getMessage());
            return null;
        }
    }
    /**
     * Gets the current datetime and formats it
     * @param outputFormat The format to use e.g. yy/MM/dd HH:mm
     * @return If successful it returns the requested date, if not an empty string
     */
    public static String formatNow(String outputFormat) {
        return formatNowLocalOrUTC(false,outputFormat);
    }
    public static String formatUTCNow(String outputFormat) {
        return formatNowLocalOrUTC(true,outputFormat);
    }
    private static String formatNowLocalOrUTC(boolean utc, String outputFormat){
        try {
            var lc = utc?LocalDateTime.now(ZoneId.of("UTC")):LocalDateTime.now();
            return lc.format(DateTimeFormatter.ofPattern(outputFormat).withLocale(Locale.ENGLISH));
        }catch( IllegalArgumentException e){
            Logger.error(e.getMessage());
        }
        return "BAD:"+outputFormat;
    }
    /**
     * Gets the current UTC datetime and formats it according to the standard 'long' format yyyy-MM-dd HH:mm:ss.SSS
     * @return If successful it returns the requested date, if not an empty string
     */
    public static String formatLongUTCNow( ) {
        return LONGDATE_FORMATTER_UTC.format(Instant.now());
    }
    /**
     * Gets the current UTC datetime and formats it according to the standard 'long' format yyyy-MM-dd HH:mm:ss.SSS
     * @return If successful it returns the requested date, if not an empty string
     */
    public static String formatShortUTCNow( ) {
        return SHORTDATE_FORMATTER_UTC.format(Instant.now());
    }
    public static String formatLongNow( ) {
        return LONGDATE_FORMATTER.withZone( ZoneId.systemDefault() ).format(Instant.now());
    }

    /**
     * Calculate the delay till the next occurrence of a 'clean' interval start
     * fe. 60000 = 10min => if now 13:42, next at 13:50 or 8min so 8*60*1000
     * @param interval_millis The interval in milliseconds
     * @return Amount of millis calculated till next
     */
    public static long millisDelayToCleanTime( long interval_millis ){

        if( interval_millis%1000 != 0 )
            return interval_millis;

        var res = secondsDelayToCleanTime(interval_millis / 1000);
        return res / 1000;
    }

    public static long secondsDelayToCleanTime(long interval_millis) {
        // Meaning clean seconds
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime first = now.withNano(0);

        long sec = interval_millis / 1000;
        if (sec < 60) { // so less than a minute
            int secs = (int) ((now.getSecond() / sec + 1) * sec);
            if (secs >= 60) {
                first = first.plusMinutes(1).withSecond(secs - 60);
            } else {
                first = first.withSecond(secs);
            }
        } else if (sec < 3600) { // so below an hour
            first = handleBelowHour(sec);
        } else { // more than an hour
            first = handleAboveHour(sec);
        }
        Logger.info("Found next at " + first);
        return Duration.between(LocalDateTime.now(), first).toMillis();
    }
    private static LocalDateTime handleBelowHour(long sec){
        int mins;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime first = now.withNano(0);
        if( sec%60==0){ // so clean minutes
            sec /= 60;
            mins = (int) (((now.getMinute()/sec+1))*sec);
            first = first.withSecond(0);
        }else{ // so combination of minutes and seconds...
            long m_s= now.getMinute()* 60L +now.getSecond();
            int res = (int) ((m_s/sec+1)*sec);
            mins = res/60;
            first = first.withSecond(res%60);
        }
        if( mins >= 60 ){
            first = first.plusHours(1).withMinute( mins - 60);
        }else {
            first = first.withMinute( mins );
        }
        return first;
    }
    private static LocalDateTime handleAboveHour( long sec ){
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime first = now.withNano(0).withMinute(0).withSecond(0);
        int h = (int) sec/3600;
        int m = (int) sec/60;
        int hs;
        if( sec % 3600 == 0 ){ // clean hours
            hs = h*(now.getHour()/h+1);
        }else{ // hours and min (fe 1h30m or 90m)
            long h_m= now.getHour()*60+now.getMinute();
            int res = (int) (h_m/m+1)*m;
            first = first.withMinute(res%60);
            hs = res/60;
        }
        if( hs>23 ){
            first = first.plusDays(hs/24).withHour( hs%24 );
        }else{
            first = first.withHour( hs );
        }
        return first;
    }
    /**
     * Parses the given time period to the equivalent amount of TimeUnits
     * @param period The period that needs to be parsed
     * @return The equivalent amount of seconds or 0 if empty period
     */
    private static long parsePeriodString( String period, TimeUnit unit ){

        if( period.isEmpty())
            return 0;

        period = period.toUpperCase().replace("SEC", "S").replace("MIN", "M");
        period = period.replace("DAY","D").replace("DAYS","D");
        period = period.replace(" ",""); // remove spaces

        long total=0;
        
    	try {
            int dIndex = period.indexOf("D");
            if( dIndex != -1 ){ // If D is present in the string
                total = Tools.parseInt( period.substring(0, dIndex), 0 )*24L; // get the number in front
                period = period.substring(dIndex+1); // Remove the used part
            }
            total *= 60L; // Go from days to hours
            int hIndex = period.indexOf("H");
	    	if( hIndex != -1 ){ // If H is present in the string
	    		total += Tools.parseInt( period.substring(0, hIndex), 0 ); // get the number in front
                period = period.substring(hIndex+1);// Remove the used part
	    	}
	    	total *= 60L; // Go from hours to minutes

            int mIndex = period.indexOf("M");
            int msIndex = period.indexOf("MS");
	    	if( mIndex !=- 1 && mIndex != msIndex){ // if M for minute found and the M is not part of ms
	    		total += Tools.parseInt(period.substring(0, mIndex), 0);
                period = period.substring(mIndex+1);// Remove the used part
	    	}
            total *= 60L; // Go from minutes to seconds
            int sIndex = period.indexOf("S");
            msIndex = period.indexOf("MS");
	    	if( sIndex!= -1 && sIndex!=msIndex+1){ // If S for second found but not as part of ms
	    		total += Tools.parseInt( period.substring(0, sIndex), 0 );
                period = period.substring(sIndex+1);// Remove the used part
            }
	    	// Now total should contain the converted part in seconds, millis not yet included
            if( msIndex!= -1){
                int millis = Tools.parseInt(period.substring(0, msIndex), 0);
                if( unit == TimeUnit.SECONDS ){
                    total += millis/1000;   // Users asked seconds, so add rounded
                }else if (unit == TimeUnit.MILLISECONDS ){
                    total = total*1000+millis;
                }                              
            }else if( unit == TimeUnit.MILLISECONDS ){
                // No ms in the string, so multiply the earlier summed seconds
                return total*1000;
            }
	    }catch( java.lang.ArrayIndexOutOfBoundsException e) {
			Logger.error("Error parsing period to seconds:"+period);
            return -1;
        }
    	return total;
    }

    public static long parsePeriodStringToSeconds( String period ){
        return parsePeriodString(period, TimeUnit.SECONDS);
    }
    public static long parsePeriodStringToMillis( String period ){
    	return parsePeriodString(period, TimeUnit.MILLISECONDS);   	
    }
    /**
     * Converts an amount of time unit to a string period
     * @param amount The amount of the time unit    
     * @param unit The time unit of the amount
     * @return The string formatted period
     */
    public static String convertPeriodToString(long amount, TimeUnit unit) {
        return switch (unit) {
            case MILLISECONDS -> convertMilliseconds(amount);
            case SECONDS -> convertSeconds(amount);
            case MINUTES -> convertMinutes(amount);
            case HOURS -> convertHours(amount, 0);
            default -> amount + "h"; // Fallback if unit is not handled
        };
    }
    private static String convertMilliseconds( long amount ){
        if( amount < 5000 ){
            if( amount%1000==0)
                return amount/1000+"s";
            return amount+"ms";
        }
        amount /= 1000; //seconds
        return convertSeconds(amount);
    }
    private static String convertSeconds( long amount ){
        if( amount < 90 ){
            return amount+"s";
        }else if( amount < 3600 ){
            return (amount-amount%60)/60+"m"+(amount%60==0?"":amount%60+"s");
        }
        var round = amount % 60 > 30;
        amount /= 60; //minutes
        if(round)
            amount++;
        return convertMinutes(amount);
    }
    private static String convertMinutes( long amount ){
        if( amount < 120 ){
            return amount+" min";
        }else if( amount < 1440 ){
            return (amount-amount%60)/60+"h"+(amount%60==0?"":amount%60+"m");
        }
        var round = amount % 60 > 30;
        var min=amount%60;
        amount /= 60;
        if(round)
            amount++;
        return convertHours(amount,min);
    }
    private static String convertHours( long amount,long min ){
        if( amount > 24*7 || amount%24==0){
            return amount/24+" days";
        }else if( amount > 24 ){
            return amount/24+"d "+amount%24+"h";
        }else if( amount > 12){
            return amount+" hours";
        }else{
            return amount+" h "+min+"m";
        }
    }
    /* ********************** C A L C U L A T I O N S ****************************************** */

    /**
     * Calculates the seconds till a certain time in UTC
     *
     * @param time The time in standard format
     * @return Amount of seconds till the given time
     */
    public static long calcSecondsTo(String time) {
        OffsetTime now = OffsetTime.now(ZoneOffset.UTC);
        OffsetTime then = LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm:ss")).atOffset(ZoneOffset.UTC);

        if (now.isBefore(then)) {
            return Duration.between(now, then).toSeconds();
        } else {
            return Duration.between(then, now).toSeconds() + 86400;
        }
    }

    public static long calcSecondsTo(String time, ArrayList<DayOfWeek> validDays) {

        if (validDays.isEmpty())
            return -1;

        if (time.length() <= 5)
            time = time + ":00";

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime tillTime = now.with(LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm:ss")));

        tillTime = tillTime.plusNanos(now.getNano());

        if (tillTime.isBefore(now.plusNanos(100000))) // If already happened today
            tillTime = tillTime.plusDays(1);

        while (!validDays.contains(tillTime.getDayOfWeek()))
            tillTime = tillTime.plusDays(1);

        return (int) Duration.between(now, tillTime).getSeconds() + 1;
    }
    /**
     * Calculates the seconds to midnight in UTC
     * @return Seconds till midnight in UTC
     */
    public static long secondsToMidnight(){
        OffsetTime midnight = OffsetTime.of(23, 59, 59, 0, ZoneOffset.UTC);
        return Duration.between( OffsetTime.now(ZoneOffset.UTC),midnight).toSeconds()+1;
    }

    /**
     * Takes a timestamp and adds a rollCount amount of unit to it after first resetting it to the previous value
     * Meaning if it's 1 MONTH, the timestamp is first reset to the first day of the month etc.
     * @param init True only does the reset, for the initial timestamp (so the one to use now, not when to rollover)
     * @param rolloverTimestamp The timestamp to update
     * @param rollCount the amount of units to apply
     * @param rollUnit the unit to apply (MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS) (TimeUnit doesn't contain weeks...)
     */
    public static LocalDateTime applyTimestampRollover(boolean init, LocalDateTime rolloverTimestamp, int rollCount, ChronoUnit rollUnit){
        Logger.debug(" -> Original date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
        rolloverTimestamp = rolloverTimestamp.withSecond(0).withNano(0);
        int count = init?0:rollCount;

        if(rollUnit == ChronoUnit.MINUTES ){
            if( init )
                return rolloverTimestamp.minusMinutes( rolloverTimestamp.getMinute()%rollCount );//make sure it's not zero
            int min = rollCount-rolloverTimestamp.getMinute()%rollCount; // So that 'every 5 min is at 0 5 10 15 etc
            return rolloverTimestamp.plusMinutes(min == 0 ? rollCount : min);//make sure it's not zero
        }

        rolloverTimestamp = rolloverTimestamp.withMinute(0);
        if(rollUnit== ChronoUnit.HOURS)
            return rolloverTimestamp.plusHours( count );

        rolloverTimestamp = rolloverTimestamp.withHour(0);
        if(rollUnit== ChronoUnit.DAYS )
            return rolloverTimestamp.plusDays( count );

        if(rollUnit== ChronoUnit.WEEKS){
            rolloverTimestamp = rolloverTimestamp.minusDays(rolloverTimestamp.getDayOfWeek().getValue()-1);
            return rolloverTimestamp.plusWeeks(count);
        }
        rolloverTimestamp = rolloverTimestamp.withDayOfMonth(1);
        if(rollUnit== ChronoUnit.MONTHS)
            return rolloverTimestamp.plusMonths(count);

        rolloverTimestamp = rolloverTimestamp.withMonth(1);
        if(rollUnit== ChronoUnit.YEARS)
            rolloverTimestamp=rolloverTimestamp.plusMonths(count);

        return rolloverTimestamp;
    }

    /**
     * Calculate the seconds till the occurrence of the time
     *
     * @param timeAsString The time to calculate the seconds for
     * @return The time in seconds till the next execution
     */
    private static int calculateSecondsTillTime( String timeAsString ){

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var time = LocalTime.parse(timeAsString, DateTimeFormatter.ISO_LOCAL_TIME);

        LocalDateTime tillTime = now.with(time); // Use the time of the task
        tillTime=tillTime.plusNanos(now.getNano());

        if( tillTime.isBefore(now.plusNanos(100000)) ) // If already happened today
            tillTime=tillTime.plusDays(1);

        return (int) Duration.between( now, tillTime ).getSeconds()+1;
    }

    /**
     * Convert the string representation of a ChronoUnit to the object
     * This covers a couple more cases than the valueOf method
     * @param unit The string the convert
     * @return The resulting ChronoUnit or FOREVER if no valid string was given
     */
    public static ChronoUnit parseToChronoUnit(String unit ){

        unit = unit.replace("s", ""); // So both singular and plural are handled

        for (ChronoUnit chronoUnit : ChronoUnit.values()) {
            if (chronoUnit.toString().toLowerCase().startsWith(unit.toLowerCase())) {
                return chronoUnit;
            }
        }
        if( unit.equals("century"))
            return ChronoUnit.CENTURIES;

        Logger.error("Invalid unit given " + unit + ", defaulting to forever");
        return ChronoUnit.FOREVER;
    }
    /**
     * Convert the string representation of the days for execution to objects
     * @param day The string representation of the days
     */
    public static ArrayList<DayOfWeek> convertDAY( String day ){
        ArrayList<DayOfWeek> daysList = new ArrayList<>();

        if( day.isBlank() ) // default is all
            day = "all";

        // Check if the whole week with or without weekend is asked
        if( day.startsWith("weekday")||day.equals("all")||day.equals("always")){
            daysList.add( DayOfWeek.MONDAY);
            daysList.add( DayOfWeek.TUESDAY);
            daysList.add( DayOfWeek.WEDNESDAY);
            daysList.add( DayOfWeek.THURSDAY);
            daysList.add( DayOfWeek.FRIDAY);

            if(!day.startsWith("weekday")){
                daysList.add(DayOfWeek.SATURDAY);
                daysList.add(DayOfWeek.SUNDAY);
            }
            return daysList;
        }
        // Specific days are requested
        day = day.toUpperCase(); // Convert input to uppercase for consistency
        for (DayOfWeek dow : DayOfWeek.values()) {
            var pref = dow.toString().substring(0, 2); // Get first two characters of each day
            if (day.contains(pref)) {
                daysList.add(dow);
            }
        }
        return daysList;
    }
    public static long secondsSinceMidnight(){
        var now = LocalDateTime.now();
        var midnight = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
        return Duration.between(midnight, now).getSeconds();
    }
}