package util.cmds;

import das.Commandable;
import das.Paths;
import io.email.Email;
import io.email.EmailSending;
import io.hardware.gpio.InterruptPins;
import org.apache.commons.lang3.SystemUtils;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLfab;
import worker.Datagram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.StringJoiner;
import java.util.regex.Pattern;

public class AdminCmds {
    public static String doADMIN(String arg, EmailSending sendEmail, Commandable tmCmd, boolean html) {

        String[] args = arg.split(",");
        return switch (args[0]) {
            case "?" -> doHelpCmd(html);
            case "checkgpios","checkgpio" ->{
                if (SystemUtils.IS_OS_WINDOWS)
                    yield "No use checking for gpios on windows";
                yield InterruptPins.checkGPIOS();
            }
            case "getlogs" -> doGetLogsCmd(sendEmail);
            case "getlastraw" -> doGetLastRawCmd(sendEmail, args);
            case "clock" -> TimeTools.formatLongUTCNow();
            case "regex" -> {
                if (args.length != 3)
                    yield "! Invalid amount of parameters";
                yield "Matches? " + args[1].matches(Pattern.quote(args[2]));
            }
            case "ipv4" -> Tools.getIP("", true);
            case "ipv6" -> Tools.getIP("", false);
            case "sleep" -> doSleepCmd(args, tmCmd);
            case "lt" -> Tools.listThreads(html);
            case "gc" -> {
                System.gc();
                yield "Tried to execute GC";
            }
            case "phypower" -> doPhyPowrCmd(args);
            case "reboot" -> doRebootCmd();
            case "addstatuscheck" -> {
                var fab = XMLfab.withRoot(Paths.settings(), "dcafs", "settings", "statuscheck");
                fab.addChild("interval").content("1h");
                fab.addChild("email").content("admin");
                fab.addChild("matrix");
                fab.build();
                yield "Statuscheck node added";
            }
            default -> "! No such subcommand in admin:" + arg;
        };
    }

    private static String doHelpCmd(boolean html) {
        var help = new StringJoiner("\r\n");
        help.add("Commands that are normally only needed by admins..., use with caution.");
        help.add("admin:getlogs -> Send last/current info and error log to admin email")
                .add("admin:getlastraw -> Send last raw log to admin email")
                .add("admin:adddebugnode -> Adds a debug node with default values")
                .add("admin:clock -> Get the current timestamp")
                .add("admin:checkgpios -> Checks the gpio's found by diozero lib")
                .add("admin:regex,<regex>,<match> -> Test a regex")
                .add("admin:ipv4 -> Get the IPv4 and MAC of all network interfaces")
                .add("admin:ipv6 -> Get the IPv6 and MAC of all network interfaces")
                .add("admin:gc -> Fore a java garbage collection")
                .add("admin:lt -> Show all threads")
                .add("admin:reboot -> Reboot the computer (linux only)")
                .add("admin:sleep,x -> Sleep for x time (linux only)")
                .add("admin:phypower,chip,interface,on/off -> Put the phy to sleep, chips: ksz,lan,rtl")
                .add("admin:addstatuscheck -> Adds the statuscheck node");
        return LookAndFeel.formatHelpCmd(help.toString(), html);
    }

    private static String doGetLogsCmd(EmailSending sendEmail) {
        if (sendEmail != null) {
            sendEmail.sendEmail(Email.toAdminAbout("Statuslog").subject("File attached (probably)")
                    .attachment(Paths.storage().resolve("logs").resolve("info.log")));
            sendEmail.sendEmail(Email.toAdminAbout("Errorlog").subject("File attached (probably)")
                    .attachment(Paths.storage().resolve("logs").resolve("errors.log")));
            return "Sending logs (info,errors) to admin...";
        }
        return "! No email functionality active.";
    }

    private static String doPhyPowrCmd(String[] cmds) {
        if (Tools.hasNoRootRights()) {
            return "! Not linux or no root rights";
        }

        if (cmds.length < 3)
            return "! Not enough arguments: admin:phypower,chip,interface,on/off";

        boolean power = Tools.parseBool(cmds[3], false);
        try {
            String regVal;
            if (cmds[1].startsWith("ksz") || cmds[1].startsWith("lan")) {
                regVal = power ? "0x3100" : "0x3900";
            } else if (cmds[1].startsWith("rtl")) {
                regVal = power ? "0x1000" : "0x1800";
            } else {
                return "! Unknown chip, supported: kz,lan,rtl";
            }

            // Validate that the interface name matches the expected format (e.g., eth0, eth1, etc.)
            String iface = cmds[2];
            if (!iface.matches("eth\\d+") && !iface.matches("end\\d+")) {
                return "! Invalid interface name. Expected format: eth0, eth1, etc.";
            }

            String bash = "phytool write " + iface + "/1/0 " + regVal;
            Logger.info("Executing " + bash);
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", bash);
            pb.inheritIO(); // Needed?
            pb.start();

            Logger.info("Toggled " + cmds[2] + " at " + TimeTools.formatLongUTCNow());
        } catch (IOException e) {
            Logger.error(e);
            return "! Failed to alter " + cmds[2];
        }
        return (power ? "Enabled " : "Powered down ") + cmds[2];
    }

    private static String doRebootCmd() {
        if (Tools.hasNoRootRights())
            return "! Not linux or no root rights";

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

    private static String doGetLastRawCmd(EmailSending sendEmail, String[] args) {
        if (sendEmail == null)
            return "! No email functionality active.";

        Path it = Paths.storage().resolve("raw").resolve(TimeTools.formatUTCNow("yyyy-MM"));
        try (var list = Files.list(it)) {
            var last = list.filter(f -> !Files.isDirectory(f)).max(Comparator.comparingLong(f -> f.toFile().lastModified()));
            if (last.isPresent()) {
                var path = last.get();
                if (args.length > 1) {
                    sendEmail.sendEmail(Email.to(args[1]).subject("Raw data file").content("Raw File attached (probably)").attachment(path));
                } else {
                    sendEmail.sendEmail(Email.toAdminAbout("raw.log").content("File attached (probably)").attachment(path));
                }
                return "Tried sending " + path;
            }
            return "! File not found";
        } catch (IOException e) {
            Logger.error(e);
            return "! Something went wrong trying to get the file";
        }
    }
    /**
     * Try to put the computer to sleep, only works on linux
     * @param cmds Array containing sleep,rtc nr, time (fe.5m for 5 minutes)
     * @return Feedback
     */
    public static String doSleepCmd(String[] cmds, Commandable tmCmd) {
        if( cmds.length!=3 ){
            return "admin:sleep,rtc,<time> -> Let the processor sleep for some time using an rtc fe. sleep:1,5m sleep 5min based on rtc1";
        }
        String os = System.getProperty("os.name").toLowerCase();
        if( !os.startsWith("linux")){
            return "! Only Linux supported for now.";
        }

        // Validate and sanitize user input (cmds[1] and cmds[2])
        String rtcId = cmds[1];
        if (!rtcId.matches("\\d+"))   // Ensure rtcId is a numeric value
            return "! Invalid RTC ID.";

        int seconds = (int) TimeTools.parsePeriodStringToSeconds(cmds[2]);

        try {
            StringJoiner tempScript = new StringJoiner( "; ");
            tempScript.add("echo 0 > /sys/class/rtc/rtc"+rtcId+"/wakealarm");
            tempScript.add("echo +"+seconds+" > /sys/class/rtc/rtc"+rtcId+"/wakealarm");
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
                tmCmd.replyToCommand(Datagram.system("tm", "run,*:wokeup"));
            }
        } catch (IOException | InterruptedException e) {
            Logger.error(e);
        }
        return "Waking up at "+TimeTools.formatLongUTCNow();
    }
}
