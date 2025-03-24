package io.telnet;

import das.Commandable;
import das.Paths;
import io.Writable;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.tinylog.Logger;
import util.LookAndFeel;
import util.tools.TimeTools;
import util.tools.Tools;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * Simplistic telnet server.
 */
public class TelnetServer implements Commandable {

    EventLoopGroup bossGroup = new NioEventLoopGroup(1);	// Server thread group
    EventLoopGroup workerGroup;	// Worker thread group

    ChannelFuture telnetFuture;
    
    int port = 2323;
    String title = "dcafs";
    String ignore = "";

    ArrayList<Writable> writables = new ArrayList<>();
    private final ArrayList<String> messages=new ArrayList<>();
    private String defColor = TelnetCodes.TEXT_LIGHT_GRAY;
    private final HashMap<String,ArrayList<String>> cmdHistory = new HashMap<>();
    private long maxAge=3600;
    private Path tinylogPath;
    private final boolean bootOk;

    public TelnetServer( EventLoopGroup eventGroup, boolean bootOk ) {
        this.workerGroup = eventGroup;
        this.bootOk=bootOk;
        tinylogPath = Paths.storage();
        readSettingsFromXML();
    }
    public String getTitle(){
        return title;
    }
    public void addMessage( String message ){
        messages.add(message);
    }

    public void readSettingsFromXML( ) {
        var dig = Paths.digInSettings("settings").digDown("telnet");
        if( dig.isValid()){
            port = dig.attr("port",2323);
            title = dig.attr( "title", "DCAFS");
            ignore = dig.attr( "ignore", "");
            defColor = TelnetCodes.colorToCode( dig.peekAt("textcolor").value("lightgray"), TelnetCodes.TEXT_LIGHT_GRAY );
            dig.goUp();
            maxAge = TimeTools.parsePeriodStringToSeconds( dig.peekAt("maxrawage").value("1h"));
            var pth = dig.peekAt("tinylog").value("");
            tinylogPath = pth.isEmpty()?Paths.storage():Path.of(pth);
        }else {
            addBlankTelnetToXML();
        }
    }
    public static void addBlankTelnetToXML( ){
        Paths.fabInSettings("settings")
                .addParentToRoot("telnet", "Settings related to the telnet server").attr("title", "DCAFS").attr("port", 2323)
                .addChild("textcolor","lightgray")
                .build();
    }
    public void run(){
            
        ServerBootstrap b = new ServerBootstrap();			// Server bootstrap connection
        b.group(bossGroup, workerGroup)						// Adding thread groups to the connection
            .channel(NioServerSocketChannel.class)			// Setting up the connection/channel
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch){
                        var pipeline = ch.pipeline();
                        pipeline.addLast("decoder", new ByteArrayDecoder())
                                .addLast("encoder", new ByteArrayEncoder())
                                .addLast( new ReadTimeoutHandler(1800) );// close connection after set time without traffic

                        // and then business logic.
                        TelnetHandler handler = new TelnetHandler( ignore,bootOk ) ;
                        handler.setTitle(title);
                        var remoteIp = ch.remoteAddress().getAddress().toString().substring(1);
                        cmdHistory.computeIfAbsent(remoteIp, k -> new ArrayList<>());
                        handler.setCmdHistory(cmdHistory.get(remoteIp));
                        handler.setDefaultColor(defColor);
                        var time = Tools.getLastRawAge(tinylogPath);
                        if( time>maxAge){
                            handler.addOneTime("Raw data is older then allowed! Something wrong with tinylog? Age:" + TimeTools.convertPeriodToString(time, TimeUnit.SECONDS));
                        }
                        messages.forEach(handler::addOneTime);
                        writables.add(handler.getWritable());
                        pipeline.addLast( handler );
                        messages.forEach( m -> handler.writeLine(TelnetCodes.TEXT_RED+m+TelnetCodes.TEXT_DEFAULT));
                    }
                });	// Let clients connect to the DAS interface

        try {
            Logger.info("Trying to start the telnet server on port "+port);
            telnetFuture = b.bind(port);
            telnetFuture.addListener((ChannelFutureListener) future -> {
                if( !future.isSuccess() ){
                    Logger.error( "Failed to start the Telnet server (bind issue?), shutting down." );
                    System.exit(0);
                }else if( future.isSuccess() ){
                    Logger.info( "Started the Telnet server." );
                }
            });
            telnetFuture.sync();	// Connect on port 23 (default)
            
        } catch (InterruptedException  e) {
            if(e.getMessage().contains("bind")){
                Logger.error("Telnet port "+port+" already in use. Shutting down.");
                System.exit(0);
            }
            Logger.error("Issue trying to connect...");
            Logger.error(e);
            // Restore interrupted state...
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String replyToCommand(Datagram d) {
        var args = d.argList();
        if (d.cmd().equalsIgnoreCase("nb") || args[0].equalsIgnoreCase("nb")) {
            int s = writables.size();
            writables.remove(d.getWritable());
            return (s==writables.size())?"! Failed to remove":"Removed from targets";
        }else {
            switch (args[0]) {
                case "?" -> {
                    return doCmdHelp();
                }
                case "error" -> {
                    if (args.length < 2)
                        return "! Not enough arguments, telnet:error,message";
                    var error = d.args().substring(6);
                    messages.add(error);
                    writables.removeIf(w -> !w.writeLine(TelnetCodes.TEXT_RED + error + TelnetCodes.TEXT_DEFAULT));
                    return "";
                }
                case "broadcast" -> {
                    return doBroadCastCmd(args, d.args());
                }
                case "write" -> {
                    var wrs = writables.stream().filter(w -> w.id().equalsIgnoreCase(args[1])).toList();
                    if (wrs.isEmpty())
                        return "! No such id";
                    var mes = TelnetCodes.TEXT_MAGENTA + d.originID() + ": " + d.args().substring(7 + args[1].length()) + TelnetCodes.TEXT_DEFAULT;
                    wrs.forEach(w -> w.writeLine(mes));
                    return mes.replace(TelnetCodes.TEXT_MAGENTA, TelnetCodes.TEXT_ORANGE);
                }
                case "bt" -> {
                    return "Currently has " + writables.size() + " broadcast targets.";
                }
                default -> {
                    return "! No such subcommand in " + d.getData();
                }
            }
        }
    }
    private String doCmdHelp(){
        var join = new StringJoiner("\r\n");
        join.add("Commands for the telnet interface");
        join.add("telnet:broadcast,message -> Broadcast the message to all active telnet sessions at info level.")
                .add("telnet:broadcast,!message -> Broadcast the message to all active telnet sessions at error level.")
                .add("telnet:broadcast,level,message -> Broadcast the message to all active telnet sessions at the given level. (info,warn,error)")
                .add("telnet:bt -> Get the broadcast target count")
                .add("telnet:nb or nb -> Disable showing broadcasts");
        return LookAndFeel.formatCmdHelp(join.toString(),false);
    }
    private String doBroadCastCmd( String[] cmds, String args){
        String send;
        if (cmds.length < 2)
            return "! Not enough arguments, telnet:broadcast,level,message or telnet:broadcast,message for info level";
        switch (cmds[1]) {
            case "warn" -> send = TelnetCodes.TEXT_ORANGE + args.substring(15);
            case "error" -> send = TelnetCodes.TEXT_RED + args.substring(16);
            case "info" -> send = TelnetCodes.TEXT_GREEN + args.substring(15);
            default -> {
                var d = args.substring(10);
                if (d.startsWith("!")) {
                    send = TelnetCodes.TEXT_RED + d.substring(1);
                } else {
                    send = TelnetCodes.TEXT_GREEN + d;
                }
            }
        }
        writables.removeIf(w -> !w.writeLine(send + TelnetCodes.TEXT_DEFAULT));
        return "Broadcasted";
    }
    public String payloadCommand( String cmd, String args, Object payload){
        return "! No such cmds in "+cmd;
    }
    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
}
