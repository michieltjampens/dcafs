package util.cmds;

import das.Commandable;
import io.email.Email;
import io.email.EmailSending;
import io.hardware.gpio.InterruptPins;
import io.telnet.TelnetCodes;
import org.apache.commons.lang3.SystemUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;
import util.tools.Tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.StringJoiner;

public class AdminCmds {
    public static String doADMIN(String args, EmailSending sendEmail, Commandable tmCmd, String workPath, boolean html ){
        String nl = html?"<br":"\r\n";
        String[] cmds = args.split(",");
        switch (cmds[0]) {
            case "?" -> {
                String gre = html ? "" : TelnetCodes.TEXT_GREEN;
                String reg = html ? "" : TelnetCodes.TEXT_DEFAULT;
                StringJoiner join = new StringJoiner(nl);
                join.add(gre + "admin:getlogs" + reg + " -> Send last/current info and error log to admin email")
                        .add(gre + "admin:getlastraw" + reg + " -> Send last raw log to admin email")
                        .add(gre + "admin:adddebugnode" + reg + " -> Adds a debug node with default values")
                        .add(gre + "admin:clock" + reg + " -> Get the current timestamp")
                        .add(gre + "admin:checkgpios" + reg + " -> Checks the gpio's found by diozero lib")
                        .add(gre + "admin:regex,<regex>,<match>" + reg + " -> Test a regex")
                        .add(gre + "admin:ipv4" + reg + " -> Get the IPv4 and MAC of all network interfaces")
                        .add(gre + "admin:ipv6" + reg + " -> Get the IPv6 and MAC of all network interfaces")
                        .add(gre + "admin:gc" + reg + " -> Fore a java garbage collection")
                        .add(gre + "admin:lt" + reg + " -> Show all threads")
                        .add(gre + "admin:reboot" + reg + " -> Reboot the computer (linux only)")
                        .add(gre + "admin:sleep,x" + reg + " -> Sleep for x time (linux only");
                return join.toString();
            }
            case "checkgpios","checkgpio" ->{
                if (SystemUtils.IS_OS_WINDOWS)
                    return "No use checking for gpios on windows";
              return InterruptPins.checkGPIOS();
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
                        sendEmail.sendEmail(Email.toAdminAbout("Raw.log").subject("File attached (probably)").attachment(path));
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
                if (cmds.length != 3)
                    return "! Invalid amount of parameters";
                return "Matches? " + cmds[1].matches(cmds[2]);
            }
            case "ipv4" -> {
                return Tools.getIP("", true);
            }
            case "ipv6" -> {
                return Tools.getIP("", false);
            }
            case "sleep" -> {
                return doSLEEP(cmds, tmCmd);
            }
            case "lt" -> {
                return Tools.listThreads(html);
            }
            case "gc" -> {
                System.gc();
                return "Tried to execute GC";
            }
            case "phypower" -> {
                if (!System.getProperty("os.name").toLowerCase().startsWith("linux")) {
                    return "! Only Linux supported for now.";
                }
                if( cmds.length<3)
                    return "! Not enough arguments: admin:phypower,interface,on/off";
                boolean power = Tools.parseBool(cmds[2],false);
                try {
                    String regVal = power?"0x3100":"0x3900";
                    ProcessBuilder pb = new ProcessBuilder("bash", "-c", "phytool write "+cmds[1]+"/1/0 "+regVal);
                    pb.inheritIO();

                    Logger.error("Toggled Eth at " + TimeTools.formatLongUTCNow());
                    pb.start();
                } catch (IOException e) {
                    Logger.error(e);
                }
                return power?"Enabled phy":"Powered down phy";
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
                return "! No such subcommand in admin:" + args;
            }
        }
    }
    /**
     * Try to put the computer to sleep, only works on linux
     * @param cmds Array containing sleep,rtc nr, time (fe.5m for 5 minutes)
     * @return Feedback
     */
    public static String doSLEEP(String[] cmds, Commandable tmCmd ){
        if( cmds.length!=3 ){
            return "admin:sleep,rtc,<time> -> Let the processor sleep for some time using an rtc fe. sleep:1,5m sleep 5min based on rtc1";
        }
        String os = System.getProperty("os.name").toLowerCase();
        if( !os.startsWith("linux")){
            return "! Only Linux supported for now.";
        }

        int seconds = (int) TimeTools.parsePeriodStringToSeconds(cmds[2]);

        try {
            StringJoiner tempScript = new StringJoiner( "; ");
            tempScript.add("echo 0 > /sys/class/rtc/rtc"+cmds[1]+"/wakealarm");
            tempScript.add("echo +"+seconds+" > /sys/class/rtc/rtc"+cmds[1]+"/wakealarm");
            tempScript.add("echo mem > /sys/power/state");

            ProcessBuilder pb = new ProcessBuilder("bash","-c", tempScript.toString());
            pb.inheritIO();
            Process process;

            Logger.error("Started sleep attempt at "+TimeTools.formatLongUTCNow());
            process = pb.start();
            process.waitFor();
            Logger.error("Woke up again at "+TimeTools.formatLongUTCNow());

            // do wake up stuff
            if( tmCmd != null ){
                tmCmd.replyToCommand("tm","run,*:wokeup",null,false);
            }
        } catch (IOException | InterruptedException e) {
            Logger.error(e);
        }
        return "Waking up at "+TimeTools.formatLongUTCNow();
    }
}
