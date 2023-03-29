package io.stream;

import io.stream.BaseStream;
import io.telnet.TelnetCodes;
import util.tools.Tools;
import util.xml.XMLdigger;
import util.xml.XMLfab;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.StringJoiner;

public class StreamCmds {
    static String[] WHEN={"open","close","idle","!idle","hello","wakeup","asleep"};
    static String[] SERIAL={"serial","modbus"};
    static String[] NEWSTREAM={"addserial","addmodbus","addtcp","addudpclient","addlocal"};
    public static String replyToCommand(String request, boolean html, Path settingsPath ) {

        var cmds = request.split(",");
        String id = cmds[0];

        if( cmds.length < 2 )
            return "! Need atleast two arguments: ss:id/add,cmd";

        if (id.equalsIgnoreCase("?")) {
            String cyan = html ? "" : TelnetCodes.TEXT_CYAN;
            String green = html ? "" : TelnetCodes.TEXT_GREEN;
            String ora = html ? "" : TelnetCodes.TEXT_ORANGE;
            String reg = html ? "" : TelnetCodes.TEXT_YELLOW + TelnetCodes.UNDERLINE_OFF;

            StringJoiner join = new StringJoiner("\r\n");

            join.add("").add(cyan+"Add new streams"+reg)
                    .add(green+" ss:addtcp,id,ip:port "+reg+"-> Add a TCP stream to xml and try to connect")
                    .add(green+" ss:addudp,id,ip:port "+reg+"-> Add a UDP stream to xml and try to connect")
                    .add(green+" ss:addserial,id,port:baudrate"+reg+" -> Add a serial stream to xml and try to connect" );
            join.add("").add(cyan+"Interact with stream objects"+reg)
                    .add(green+" ss:reload<,id> "+reg+"-> Reload the stream with the given id or all if no id is specified.")
                    .add(green+" ss:store,id "+reg+"-> Update the xml entry for this stream");
            join.add("").add(cyan+"Alter the stream settings"+reg)
                    .add(green+" ss:streamid,ttl,value "+reg+"-> Alter the ttl")
                    .add(green+" ss:streamid,eol,value "+reg+"-> Alter the eol string")
                    .add(green+" ss:streamid,baudrate,value "+reg+"-> Alter the baudrate of a serial/modbus stream")
                    .add(green+" ss:streamid,addwrite,when:data "+reg+"-> Add a triggered write, possible when are hello (stream opened) and wakeup (stream idle)")
                    .add(green+" ss:streamid,addcmd,when:data "+reg+"-> Add a triggered cmd, options for 'when' are open,idle,!idle,close")
                    .add(green+" ss:streamid,echo,on/off "+reg+"-> Sets if the data received on this stream will be returned to sender");
            return join.toString();
        }

        var dig = XMLdigger.goIn(settingsPath,"dcafs").goDown("streams");
        if( dig.isInvalid())
            return "! No streams yet";

        if( Arrays.asList(NEWSTREAM).contains(cmds[0]) ){ // Trying to add a new stream
            dig.peekAt("stream","id",cmds[1]);
            if( dig.hasValidPeek())
                return "! Already a stream with that id, try something else?";

            var fabOpt = XMLfab.alterDigger(dig);
            if( fabOpt.isEmpty())
                return "! Failed to create fab";

            var fab = fabOpt.get();
            var type = cmds[0].substring(3);

            switch (type) {
                case "tcp","udpclient" -> {
                    if (cmds.length != 3)
                        return "! Not enough arguments: ss:"+cmds[0]+",streamid,ip:port";
                    addBaseToXML(fab,cmds[1],type);
                    fab.addChild("address", cmds[2])
                        .build();
                    return type.toUpperCase()+" stream added";
                }
                case "serial", "modbus" -> {
                    if (cmds.length < 3)
                        return "! Not enough arguments: ss:"+cmds[0]+"streamid,port(,baudrate)";
                    addBaseToXML(fab,cmds[1],type);
                    fab.addChild("port", cmds[2]);
                    fab.addChild("serialsettings", (cmds.length == 4 ? cmds[3] : "19200") + ",8,1,none")
                            .build();
                    return "Stream added";
                }
                case "udpserver" -> {
                    return "UDP server stream added";
                }
            }
            return "! No such stream yet " + id;
        }

        if( dig.peekAt("stream","id",cmds[0]).hasValidPeek() )
            return "! No such stream yet: "+cmds[0];

        dig.goDown("stream","id",id);
        // At this point, the digger is pointing to the path node for the given id
        // But this might be an import....
        var fabOpt = XMLfab.alterDigger(dig); // Create a fab with parentnode the path node
        if( fabOpt.isEmpty())
            return "! No valid fab created";
        var fab=fabOpt.get();

        switch (cmds[1]) {
            case "addwrite", "addcmd" -> {
                if (cmds.length < 3) {
                    return "! Bad amount of arguments, should be ss:streamid," + cmds[1] + ",when:data";
                }
                if (cmds[2].split(":").length == 1)
                    return "! Doesn't contain a proper when:data pair";
                var data = request.substring(request.indexOf(":") + 1);
                var when = cmds[2].substring(0, cmds[2].indexOf(":"));
                if (Arrays.asList(WHEN).contains(when)) {
                    fab.addChild(cmds[1].substring(3), data).attr("when", when).build();
                    return "Added triggered " + cmds[1].substring(3) + " to " + cmds[0];
                }
                return "! Failed to add, invalid when";
            }
            case "echo" -> {
                if( cmds.length != 2 ) // Make sure we got the correct amount of arguments
                    return "! Bad amount of arguments, should be ss:streamid,echo,on/off";
                var state = Tools.parseBool(cmds[2],false);
                if (state) {
                    fab.alterChild("echo", "on");
                } else {
                    fab.removeChild("echo");
                }
                fab.build();
                return "Echo altered";
            }
            case "label" -> {
                if( cmds[2].isEmpty()||cmds[2].equalsIgnoreCase("void")){
                    fab.removeChild("label").build();
                    return "Label removed";
                }
                fab.alterChild("label", cmds[2]).build();
                return "Label altered to " + cmds[2];
            }
            case "ttl" -> {
                if (!cmds[2].equals("-1")) {
                    fab.alterChild("ttl", cmds[2]);
                } else {
                    fab.removeChild("ttl");
                }
                fab.build();
                return "TTL altered";
            }
            case "eol" -> {
                fab.alterChild("eol", cmds[2]);
                fab.build();
                return "EOL altered";
            }
            case "baudrate" -> {
                var type = dig.attr("type","");
                if (Arrays.asList(SERIAL).contains(type)) {
                    dig.goDown("serialsettings");
                    if (dig.isValid()) {
                        var old = dig.current().map(x -> x.getTextContent()).orElse("");
                        old = cmds[2] + old.substring(old.indexOf(","));
                        fab.alterChild("serialsettings", old);
                    } else {
                        fab.addChild("serialsettings", cmds[2] + ",8,1,none");
                    }
                    fab.build();
                    return "Baudrate altered";
                }
                return "! Not a Serial port, no baudrate to change";
            }
        }

        return "! unknown command: ss:"+request;
    }
    private static void addBaseToXML( XMLfab fab, String id, String type ){
        fab.addChild("stream").attr("id", id).attr("type", type).down();
        fab.addChild("eol", "crlf");
    }
}
