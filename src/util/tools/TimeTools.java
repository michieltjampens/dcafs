package util.tools;

import org.apache.commons.lang3.tuple.Pair;
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
            Logger.error(e);
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
        try{
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern(outputFormat).withLocale(Locale.ENGLISH));
        }catch( IllegalArgumentException e){
            Logger.error(e.getMessage());
        }
        return "BAD:"+outputFormat;
    }
    public static String formatUTCNow(String outputFormat) {
        try {
            return LocalDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern(outputFormat).withLocale(Locale.ENGLISH));
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
        LocalDateTime now = LocalDateTime.now();

        if( interval_millis%1000 == 0 ){ //meaning clean seconds
            long sec = interval_millis/1000;
            LocalDateTime first = now.withNano(0);
            if( sec < 60 ){ // so less than a minute
                int secs = (int)((now.getSecond()/sec+1)*sec);
                if( secs >= 60 ){
                    first = first.plusMinutes(1).withSecond( secs - 60);
                }else {
                    first = first.withSecond( secs );
                }
            }else if( sec < 3600 ) { // so below an hour
                int mins;
                if( sec%60==0){ // so clean minutes
                    sec /= 60;
                    mins = (int) (((now.getMinute()/sec+1))*sec);
                    first = first.withSecond(0);
                }else{ // so combination of minutes and seconds...
                    long m_s= now.getMinute()*60+now.getSecond();
                    int res = (int) ((m_s/sec+1)*sec);
                    mins = res/60;
                    first = first.withSecond(res%60);
                }
                if( mins >= 60 ){
                    first = first.plusHours(1).withMinute( mins - 60);
                }else {
                    first = first.withMinute( mins );
                }
            }else{ // more than an hour
                first = first.withMinute(0).withSecond(0);
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
            }
            Logger.info("Found next at "+first);
            return Duration.between( LocalDateTime.now(),first).toMillis();
        }
        return interval_millis;
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
        int d = period.indexOf("D");
        int h = period.indexOf("H");
    	int m = period.indexOf("M");
        int s = period.indexOf("S");
        int ms = period.indexOf("MS");
        long total=0;
        
    	try {
            if( d != -1 ){ // If D is present in the string
                total += Tools.parseInt( period.substring(0, d), 0 ); // get the number in front and multiply for h->s
                d+=1;// increment the index to skip te letter
            }else{
                d=0; // No h found, so change the index to 0 to mimic it
            }
            total *= 24L; // Go from days to hours
	    	if( h != -1 ){ // If H is present in the string
	    		total += Tools.parseInt( period.substring(d, h), 0 ); // get the number in front and multiply for h->s
	    		h+=1;// increment the index to skip te letter
	    	}else{
	    		h=d; // No h found, so change the index to 0 to mimic it
	    	}
	    	total *= 60L; // Go from hours to minutes
	    	if( m !=- 1 && m != ms){ // if M for minute found and the M is not part of ms
	    		total += Tools.parseInt(period.substring(h, m), 0);
	    		m+=1;// increment the index to skip te letter
	    	}else{
	    		m=h; // no M found so last was h (e.g. 1h10s)
	    	}
            total *= 60L; // Go from minutes to seconds
	    	if( s!= -1 && s!=ms+1){ // If S for second found but not as part of ms
	    		total += Tools.parseInt( period.substring(m, s), 0 );
            }else{
                s=m;
            }
	    	// Now total should contain the converted part in seconds, millis not yet included
            if( ms!= -1){
                int millis = Tools.parseInt( period.substring(s, ms), 0 );
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

    public static Pair<Long,TimeUnit> parsePeriodString( String period ){
        
        period = period.toUpperCase();
        period = period.replace("DAYS", "D");
        period = period.replace("HOURS", "H");
        period = period.replace("SEC", "S");
        period = period.replace("MIN", "M");
        int d = period.indexOf("D");
        int h = period.indexOf("H");
    	int m = period.indexOf("M");
        int s = period.indexOf("S");
        int ms = period.indexOf("MS");
        long total=0;
        
    	try {
            // Days
            if( d != -1 ){    		
	    		total += Tools.parseInt( period.substring(0, d), 0 );
                d+=1;
                if( d==period.length() ){
                    return Pair.of(total,TimeUnit.DAYS);
                }
	    	}else{
	    		d=0;
            }
            total *= 24;
            // Hours
	    	if( h != -1 ){    		
	    		total += Tools.parseInt( period.substring(d, h), 0 );
                h+=1;
                if( h==period.length() ){
                    return Pair.of(total,TimeUnit.HOURS);
                }
	    	}else{
	    		h=0;
            }
            total *= 60; //in minutes
            // Minutes
	    	if( m !=- 1 && m != ms){
	    		total += Tools.parseInt(period.substring(h, m), 0);
                m+=1;
                if( m==period.length() ){
                    return Pair.of(total,TimeUnit.MINUTES);
                }
	    	}else{
	    		m=h;
            }
            total *= 60; //in seconds

	    	if( s!= -1 && s!=ms+1){
                total += Tools.parseInt( period.substring(m, s), 0 );
                s++;
                if( s==period.length() ){
                    return Pair.of(total,TimeUnit.SECONDS);
                }
            }else{
                s=m;
            }    
            total *= 1000; //in millis
            if( ms!= -1){ 
                total += Tools.parseInt( period.substring(s, ms), 0 );                      
            }	
            return Pair.of(total,TimeUnit.MILLISECONDS);
	    }catch( java.lang.ArrayIndexOutOfBoundsException e) {
            Logger.error("Error parsing period to seconds:"+period);
            return Pair.of(total,null);
        }   
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
	public static String convertPeriodtoString( long amount, TimeUnit unit ){		
		boolean round=false;
		if( unit == TimeUnit.MILLISECONDS ){
			if( amount < 5000 ){
                if( amount%1000==0)
                    return amount/1000+"s";
				return amount+"ms";
			}
			amount /= 1000; //seconds
			unit = TimeUnit.SECONDS;
		}
		if( unit == TimeUnit.SECONDS ){
			if( amount < 90 ){
				return amount+"s";
			}else if( amount < 3600 ){
				return (amount-amount%60)/60+"m"+(amount%60==0?"":amount%60+"s");
			}
			if( amount % 60 > 30 )
				round=true;
			amount /= 60; //minutes
			if(round)
				amount++;
			round=false;
			unit = TimeUnit.MINUTES;
		}
		long min=0;
		if( unit == TimeUnit.MINUTES ){
			if( amount < 120 ){
				return amount+" min";
			}else if( amount < 1440 ){
				return (amount-amount%60)/60+"h"+(amount%60==0?"":amount%60+"m");
			}
			
			if( amount % 60 > 30 )
				round=true;
			min=amount%60;
			amount /= 60;
			if(round)
				amount++;
			unit = TimeUnit.HOURS;
		}
		if( unit == TimeUnit.HOURS ){
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
		return amount+"h";
    }
    /* ********************** C A L C U L A T I O N S ****************************************** */
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
            if( init ) {
                rolloverTimestamp = rolloverTimestamp.minusMinutes( rolloverTimestamp.getMinute()%rollCount );//make sure it's not zero
            }else{
                int min = rollCount-rolloverTimestamp.getMinute()%rollCount; // So that 'every 5 min is at 0 5 10 15 etc
                rolloverTimestamp = rolloverTimestamp.plusMinutes(min == 0 ? rollCount : min);//make sure it's not zero
            }
        }else{
            rolloverTimestamp = rolloverTimestamp.withMinute(0);
            if(rollUnit== ChronoUnit.HOURS){
                rolloverTimestamp = rolloverTimestamp.plusHours( count );
            }else{
                rolloverTimestamp = rolloverTimestamp.withHour(0);
                if(rollUnit== ChronoUnit.DAYS ){
                    rolloverTimestamp = rolloverTimestamp.plusDays( count );
                }else{
                    if(rollUnit== ChronoUnit.WEEKS){
                        rolloverTimestamp = rolloverTimestamp.minusDays(rolloverTimestamp.getDayOfWeek().getValue()-1);
                        rolloverTimestamp = rolloverTimestamp.plusWeeks(count);
                    }else{
                        rolloverTimestamp = rolloverTimestamp.withDayOfMonth(1);
                        if(rollUnit== ChronoUnit.MONTHS){
                            rolloverTimestamp=rolloverTimestamp.plusMonths(count);
                        }else{
                            rolloverTimestamp = rolloverTimestamp.withMonth(1);
                            if(rollUnit== ChronoUnit.YEARS){
                                rolloverTimestamp=rolloverTimestamp.plusMonths(count);
                            }
                        }
                    }
                }
            }
        }
        Logger.debug(" -> Next rollover date: "+ rolloverTimestamp.format(TimeTools.LONGDATE_FORMATTER));
        return rolloverTimestamp;
    }

    /**
     * Convert the string representation of a ChronoUnit to the object
     * This covers a couple more cases than the valueOf method
     * @param unit The string the convert
     * @return The resulting ChronoUnit or FOREVER if no valid string was given
     */
    public static ChronoUnit parseToChronoUnit(String unit ){

        unit=unit.replace("s",""); // So both single and multiple are found
        return switch (unit) {
            case "sec","second" -> ChronoUnit.SECONDS;
            case "minute", "min" -> ChronoUnit.MINUTES;
            case "hour" -> ChronoUnit.HOURS;
            case "day" -> ChronoUnit.DAYS;
            case "week" -> ChronoUnit.WEEKS;
            case "month" -> ChronoUnit.MONTHS;
            case "year" -> ChronoUnit.YEARS;
            case "decade" -> ChronoUnit.DECADES;
            case "century","centurie" -> ChronoUnit.CENTURIES; // Not a type, because s has been removed earlier
            default -> {
                Logger.error("Invalid unit given "+unit+", defaulting to forever");
                yield ChronoUnit.FOREVER;
            }
        };
    }
    /**
     * Convert the string representation of the days for execution to objects
     * @param day The string representation of the days
     */
    public static ArrayList<DayOfWeek> convertDAY( String day ){
        ArrayList<DayOfWeek> daysList = new ArrayList<>();

        if( day.isBlank() )
            day = "all";

        if( day.startsWith("weekday")||day.equals("all")||day.equals("always")){
            daysList.add( DayOfWeek.MONDAY);
            daysList.add( DayOfWeek.TUESDAY);
            daysList.add( DayOfWeek.WEDNESDAY);
            daysList.add( DayOfWeek.THURSDAY);
            daysList.add( DayOfWeek.FRIDAY);
        }
        if(day.equals("all")||day.equals("always")) {
            daysList.add(DayOfWeek.SATURDAY);
            daysList.add(DayOfWeek.SUNDAY);
        }
        if( daysList.isEmpty()){
            if( day.contains("mo"))
                daysList.add(DayOfWeek.MONDAY);
            if( day.contains("tu"))
                daysList.add(DayOfWeek.TUESDAY);
            if( day.contains("we"))
                daysList.add(DayOfWeek.WEDNESDAY);
            if( day.contains("th"))
                daysList.add(DayOfWeek.THURSDAY);
            if( day.contains("fr"))
                daysList.add(DayOfWeek.FRIDAY);
            if( day.contains("sa"))
                daysList.add(DayOfWeek.SATURDAY);
            if( day.contains("su"))
                daysList.add(DayOfWeek.SUNDAY);
        }
        daysList.trimToSize();
        return daysList;
    }
    public static long secondsSinceMidnight(){
        var now = LocalDateTime.now();
        var midnight = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
        return Duration.between(midnight, now).getSeconds();
    }
}