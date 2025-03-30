package io.matrix;

import das.Commandable;
import das.Core;
import das.Paths;
import io.Writable;
import io.forward.MathForward;
import org.apache.commons.lang3.math.NumberUtils;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.LookAndFeel;
import util.data.RealtimeValues;
import util.tools.FileTools;
import util.tools.Tools;
import util.xml.XMLfab;
import worker.Datagram;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MatrixClient implements Writable, Commandable {

    private static final String root = "_matrix/";
    private static final String client = root + "client/v3/";
    private static final String media = root + "media/v3/";
    private static final String login = client + "login";
    private static final String sync = client + "sync";
    private static final String user = client + "user/";
    private static final String roomsBaseUrl = client + "rooms/";
    /*
    private static String push = client+"pushers";
    private static String logout = client+"logout";
    private static String logout_all = client+"logout/all";
    private static String whoami = client+"account/whoami";
    private static String presence = client+"presence/";
    private static String knockBaseUrl = client+"knock/";
    private static String upload = media+"upload/";
    private static String keys = client+"keys/";
    private static String addPush =push+"/set";
    */

    HashMap<String, Room> rooms = new HashMap<>();

    String userName;
    String pw;
    String server;

    ScheduledExecutorService executorService = Executors.newScheduledThreadPool(2);
    String accessToken = "";
    String deviceID = "";
    String userID;
    HashMap<String,String> files = new HashMap<>();
    String since = "";
    HttpClient httpClient;

    MathForward math;
    boolean downloadAll=true;
    Path dlFolder=Path.of("downloads");
    private final HashMap<String,String> macros = new HashMap<>();

    static final long RETRY_MAX = 90;
    static final long RETRY_STEP = 15;
    private long retry=RETRY_STEP;

    private final ArrayList<String[]> failedMessages = new ArrayList<>();
    private String filterID="";

    private static final String ROOM_ID_REGEX = "^[!][a-zA-Z0-9_-]+:[a-zA-Z0-9.-]+$";
    private static final String EVENT_ID_REGEX = "^\\$[a-zA-Z0-9_-]+:[a-zA-Z0-9.-]+$";

    public MatrixClient(RealtimeValues rtvals ){
        math = new MathForward(null,rtvals);
        readFromXML();
    }

    /**
     * Reads the settings from the global settingsfile
     */
    private void readFromXML( ){
        var dig = Paths.digInSettings("matrix");

        String user = dig.attr("user","");
        if( user.isEmpty()) {
            Logger.error("Invalid matrix user");
            return;
        }

        server = "http://"+dig.peekAt("server").value("");
        if( !user.contains(":")) { // If the format is @xxx:yyyy.zzz
            Logger.error("Matrix user must be of @username:sever format");
            return;
        }
        userID=user;
        userName=user.substring(1,user.indexOf(":"));
        if( server.length()<8) // Meaning nothing after http etc
            server = "http://"+user.substring(user.indexOf(":")+1);

        server += server.endsWith("/")?"":"/";
        pw = dig.attr("pass","");

        for( var macro : dig.peekOut("macro") )
            macros.put(macro.getAttribute("id"),macro.getTextContent());

        for( var rm : dig.digOut("room") ){
            var rs = Room.withID( rm.attr("id",""),this )
                    .url( dig.peekAt("url").value("") )
                    .entering( dig.peekAt("entering").value(""))
                    .welcome( dig.peekAt("greet").value(""));
            for( Element cmd : dig.peekOut("cmd") ){
                rs.addTriggeredCmd(cmd.getTextContent());
            }
            rooms.put(rs.id(),rs);
        }
    }
    public void login(){
        // https://matrix.org/docs/api/#post-/_matrix/client/v3/login
        var json = new JSONObject().put("type","m.login.password")
                .put("identifier",new JSONObject().put("type","m.id.user").put("user",userName))
                .put("password",pw);

        httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .build();

        asyncPOST( login, json, res -> {
                    if (res.statusCode() == 200) {
                        retry = RETRY_STEP;
                        Logger.info("matrix -> Logged into the Matrix network");
                        JSONObject j = new JSONObject(res.body());
                        accessToken = j.getString("access_token");
                        deviceID = j.getString("device_id");

                        setupFilter();

                        sync(true);
                        for (var room : rooms.values())
                            joinRoom(room, null);
                        return true;
                    }
                    Logger.warn("matrix -> Failed to login to the matrix network (code:" + res.statusCode() + ")");
                    processError(res);
                    return false;
                }
                , fail -> {
                    Logger.error(fail.getMessage());
                    executorService.schedule(this::login, retry, TimeUnit.SECONDS);
                    retry += retry < RETRY_MAX ? RETRY_STEP : 0;
                    return false;
                }
        );
    }

    public void hasFilter(){
        asyncGET(user+userID+"/filter/"+filterID,
                            res -> {
                                    if( res.statusCode()!=200){
                                        retry=RETRY_STEP;
                                        Logger.warn("matrix -> No such filter yet.");
                                        return false;
                                    }else{
                                        Logger.debug("matrix -> Active filter:"+res.body());
                                        return true;
                                    }
                                });
    }

    private void setupFilter(){

        Optional<String> filterOpt;
        try{
            filterOpt = FileTools.getResourceStringContent(this.getClass(),"/filter.json");
        }catch( Exception e ){
            Logger.error(e);
            return;
        }
        if( filterOpt.isEmpty()){
            Logger.error("Couldn't find the filter resource");
            return;
        }
        asyncPOST( user+userID+"/filter",new JSONObject(new JSONTokener(filterOpt.get())), res -> {
                                if( res.statusCode() == 200 ) {
                                    var body = new JSONObject(res.body());
                                    filterID = body.getString("filter_id");
                                    Logger.info("matrix -> Filter Uploaded, got id "+filterID);
                                    hasFilter();
                                    return true;
                                }
                                processError(res);
                                return true;
                            });
    }

    public void sync( boolean first){
        String filter = filterID.isEmpty()?"":"&filter="+filterID;
        try {
            String url = server+sync +"?access_token="+accessToken+"&timeout=10000"+filter+"&set_presence=online";
            var request = HttpRequest.newBuilder(new URI(url+(since.isEmpty()?"":("&since="+since))));
            try {
                httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                        .thenApply(res -> {
                            var body = new JSONObject(res.body());
                            if (res.statusCode() == 200) {
                                if( !failedMessages.isEmpty() ){
                                    int delay=0;
                                    while( !failedMessages.isEmpty()){
                                        var fm = failedMessages.remove(0);
                                        executorService.schedule(()->sendMessage(fm[0],fm[1]),delay,TimeUnit.SECONDS);
                                        delay++;
                                    }

                                }
                                retry=RETRY_STEP;
                                since = body.getString("next_batch");
                                if (!first) {
                                    try {
                                        /*var b = body.getJSONObject("device_one_time_keys_count");
                                         if (b != null && b.getInt("signed_curve25519") == 0) {
                                             keyClaim();
                                        }*/
                                        processRoomEvents(body);
                                    } catch (org.json.JSONException e) {
                                        Logger.error("Matrix -> Json error: " + e.getMessage());
                                    }
                                }
                                executorService.execute(() -> sync(false));
                                return true;
                            }
                            executorService.schedule(() -> sync(false),retry,TimeUnit.SECONDS);
                            processError(res);
                            return false;
                        }).exceptionally( t -> {
                            Logger.error(t.getMessage());
                            executorService.schedule(()->sync(false),retry,TimeUnit.SECONDS);
                            retry += retry <RETRY_MAX?RETRY_STEP:0;
                            return false;
                        });
            }catch(IllegalArgumentException e){
                Logger.error("matrix -> "+e.getMessage());
            }
        } catch ( Exception e ) {
            Logger.error(e);
        }
    }

    /**
     * Join a specific room (by room id not alias) and make it the active one
     * @param room The room to join
     * @param wr The writable if any to output the result to
     */
    public void joinRoom(Room room, Writable wr ){

        asyncPOST( roomsBaseUrl +room.url()+"/join",new JSONObject().put("reason","Feel like it"),
                res -> {
                    var body = new JSONObject(res.body());
                    if( res.statusCode()==200 ){
                        // Joined the room
                        Logger.info("matrix -> Joined the room! " + body.getString("room_id"));
                        room.connected(true);
                        if( !room.entering().isEmpty())
                            sendMessage(room.url(), room.entering().replace("{user}",userName) );
                        for( var cmd : room.getTriggeredCmds() )
                            Core.addToQueue(Datagram.system(cmd).writable(room).toggleSilent());
                        if( wr!=null) {
                            wr.writeLine(id(), "Joined " + room.id() + " at " + room.url());
                        }
                        return true;
                    }
                    Logger.error("Failed to join the room.");
                    if( wr!=null)
                        wr.writeLine(id(), "Failed to join " + room.id() + " because " + res.body());
                    processError(res);
                    return false;
                });
    }

    public void processRoomEvents(JSONObject js){

        var join = getJSONSubObject(js,"rooms","join");
        if( join.isEmpty())
            return;

        var joinObject = join.get();

        // Get room id
        var originRoom = js.keys().next();

        // Get events
        var events = getJSONArray(joinObject, originRoom, "timeline", "events");

        if( events.isEmpty())
            return; // Return if no events

        for (var event : events)
            processEvent(event, originRoom, joinObject);

    }

    private void processEvent(JSONObject event, String originRoom, JSONObject joinObject) {
        String eventID = "";
        if (event.has("event_id"))
            eventID = event.getString("event_id");
        String from = event.getString("sender");
        if (!eventID.isEmpty())
            confirmRead(originRoom, eventID); // Confirm received

        if (from.equalsIgnoreCase(userID))// Ignore echo?
            return;

        switch (event.getString("type")) {
            case "m.room.redaction" -> Logger.info("Ignored redaction event");
            case "m.room.message" -> {
                var room = roomByUrl(joinObject.keys().next());
                var content = event.getJSONObject("content");
                String body = content.getString("body");
                switch (content.getString("msgtype")) {
                    case "m.image", "m.file" -> {
                        files.put(body, content.getString("url"));
                        Logger.info("Received link to " + body + " at " + files.get(body));
                        if (downloadAll)
                            downloadFile(body, null, originRoom);
                    }
                    case "m.text" -> {
                        final String send = body;
                        room.ifPresent(r -> r.writeToTargets(send));
                        if (body.startsWith("das") || body.startsWith(userName)) { // check if message for us
                            processDcafsMtext(body, originRoom, room.orElse(null), from);
                        } else if (body.equalsIgnoreCase("hello?")) {
                            sendMessage(originRoom, "Yes?");
                        } else {
                            Logger.debug(from + " said " + body + " to someone/everyone");
                        }
                    }
                    default -> Logger.info("Event of type:" + event.getString("type"));
                }
            }
            default -> Logger.info("matrix -> Ignored:" + event.getString("type"));
        }
    }
    private void processDcafsMtext( String body , String originRoom, Room room, String from){
        body = body.substring(body.indexOf(":")+1).trim();
        if (body.matches(".+=[0-9]*$")) {
            var sp = body.split("=");
            double d = NumberUtils.toDouble(sp[1].trim(), Double.NaN);
            if (Double.isNaN(d)) {
                sendMessage(originRoom, "Invalid number given, can't parse " + sp[1]);
            } else {
                math.addNumericalRef(sp[0].trim(), d);
                sendMessage(originRoom, "Stored " + sp[1] + " as " + sp[0]);
            }
        } else if (body.startsWith("solve ") || body.matches(".+=[a-zA-Z?]+?")) {
            solveMath(body, originRoom);
        } else { // Respond to commands
            var d = Datagram.build(body).label("matrix").origin(originRoom + "|" + from);
            d.writable(Objects.requireNonNullElse(room, this));
            Core.addToQueue(d);
        }
    }

    private void solveMath(String body, String originRoom) {
        var split = body.split("=");
        var op = split[0];
        if (op.startsWith("*"))
            op = op.substring(2);
        op = op.replace("solve ", "").trim();

        var ori = op;
        op = Tools.alterMatches(op, "^[^{]+", "[{]?[a-zA-Z:]+", "{d:matrix_", "}");
        var dbl = math.solveOp(op);
        if (Double.isNaN(dbl)) {
            sendMessage(originRoom, "Failed to process: " + ori);
            return;
        }

        var res = "" + dbl;
        if (res.endsWith(".0"))
            res = res.substring(0, res.indexOf("."));
        if (split.length == 1 || split[1].equalsIgnoreCase("?")) {
            if (res.length() == 1) {
                sendMessage(originRoom, "No offense but... *raises " + res + " fingers*");
            } else {
                sendMessage(originRoom, ori + " = " + res);
            }
        } else {
            math.addNumericalRef(split[1], dbl);
            sendMessage(originRoom, "Stored " + res + " as " + split[1]);
        }
    }
    public Optional<Room> roomByUrl(String url){
        for( var room : rooms.values() ){
            if( room.url().equals(url))
                return Optional.of(room);
        }
        return Optional.empty();
    }

    /**
     * Send a confirmation on receiving an event
     * @param room The room the event occurred in
     * @param eventID The id of the event
     */
    public void confirmRead(String room, String eventID){
        if (isInvalidRoomId(room)) {
            Logger.error("Can't send matrix message because room id is invalid.");
            return;
        }
        if( !isValidEventId(eventID)){
            Logger.error("Can't send matrix message because event id is invalid.");
            return;
        }
        asyncPOST( roomsBaseUrl +room+"/receipt/m.read/"+eventID,new JSONObject(),
                res -> {
                    if(res.statusCode()==200){
                        return true;
                    }
                    processError(res);
                    return false;
                });
    }
    public static boolean isValidEventId(String eventId) {
        Pattern pattern = Pattern.compile(EVENT_ID_REGEX);
        Matcher matcher = pattern.matcher(eventId);
        return matcher.matches();
    }
    /**
     * Upload a file to the repository
     * @param roomid The room to use (id)
     * @param path The path to the file
     */
    public void sendFile( String roomid, Path path, Writable wr){

        if (isInvalidRoomId(roomid)) {
            Logger.error("Can't send matrix message because room id is invalid.");
            return;
        }
        try{
            String url=server+media + "upload";
            if( !accessToken.isEmpty())
                url+="?access_token="+accessToken;
            url+="&Content-Type=m.file";
            url+="&filename="+path.getFileName().toString();

            var request = HttpRequest.newBuilder(new URI(url))
                    .POST(HttpRequest.BodyPublishers.ofFile(path))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> {
                        if( res.statusCode()==200){
                            System.out.println(res.body());
                            String mxc = new JSONObject(res.body()).getString("content_uri"); // Got a link... now post it?
                            if( !roomid.isEmpty())
                                shareFile(rooms.get(roomid).url(),mxc,path.getFileName().toString());
                            if( wr!=null)
                                wr.writeLine(id(), "File upload succeeded");
                            return true;
                        }
                        if( wr!=null)
                            wr.writeLine(id(), "File upload failed: " + res.body());
                        processError(res);
                        return false;
                    } );
        } catch (URISyntaxException | FileNotFoundException e) {
            Logger.error(e);
        }
    }
    public boolean downloadFile( String id, Writable wr, String originRoom ){
        String mxc=files.get(id);
        if( mxc.isEmpty())
            return false;

        try{
            StringBuilder urlBuilder = new StringBuilder(server).append(media).append("download").append(mxc.substring(5));
            if (!accessToken.isEmpty()) {
                urlBuilder.append("?access_token=").append(URLEncoder.encode(accessToken, StandardCharsets.UTF_8));
            }
            URI finalUri = new URI(urlBuilder.toString());
            HttpRequest request = HttpRequest.newBuilder(finalUri)
                    .build();

            var p = Paths.storage().resolve(dlFolder).resolve(id);
            Files.createDirectories(p.getParent());
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofFile(p))
                    .thenApply( res -> {
                        if( res.statusCode()==200){
                            if( wr!=null)
                                wr.writeLine(id(), "File received");
                            if( !originRoom.isEmpty())
                                sendMessage(originRoom,"Downloaded the file.");
                        }else{
                            Logger.error(res);
                            if( wr!=null)
                                wr.writeLine(id(), "File download failed with code: " + res.statusCode());
                            if( !originRoom.isEmpty())
                                sendMessage(originRoom,"Download failed.");
                        }
                        return 0;
                    } )
                    .exceptionally(e -> {
                        Logger.error(e);
                        if (!originRoom.isEmpty()) {
                            sendMessage(originRoom, "Error when trying to download the file.");
                        }
                        return 0;
                    });
        } catch (URISyntaxException | IOException e) {
            Logger.error(e);
            if( !originRoom.isEmpty())
                sendMessage(originRoom,"Error when trying to download the file.");
        }
        return true;
    }
    public void shareFile( String room, String mxc, String filename ){
        if (isInvalidRoomId(room)) {
            Logger.error("Can't send matrix message because room id is invalid.");
            return;
        }
        var j = new JSONObject()
                .put("body",filename)
                .put("url",mxc)
                .put("mimetype","text/plain")
                .put("msgtype", "m.file");

        try {
            String url = server+ roomsBaseUrl +room+"/send/m.room.message/"+ Instant.now().toEpochMilli()+"?access_token="+accessToken;
            var request = HttpRequest.newBuilder(new URI(url))
                    .PUT(HttpRequest.BodyPublishers.ofString( j.toString()))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> {
                        System.out.println(res.toString());

                        if( res.statusCode()==200 ){
                            Logger.info("matrix -> Link send! ");
                        }else{
                            processError(res);
                        }
                        return 0;
                    });

        } catch (URISyntaxException e) {
            Logger.error(e);
        }
    }
    public void broadcast( String message ){
        rooms.forEach( (k, v) -> sendMessage(v.url(),message) );
    }
    public void sendMessage( String room, String message ){
        if(httpClient==null){
            Logger.error("Can't send matrix message if httpClient is null");
            return;
        }
        if (isInvalidRoomId(room)) {
            Logger.error("Can't send matrix message because room id is invalid.");
            return;
        }

        message=message.replace("\r","");
        String nohtml = message.replace("<br>","\n");
        nohtml = nohtml.replaceAll("<.?b>|<.?u>",""); // Alter bold

        if(message.toLowerCase().startsWith("unknown command"))
            message = "Either you made a typo or i lost that cmd... ;)";
        final String mes = message;
        var j = new JSONObject().put("body",nohtml)
                                .put("msgtype", "m.text")
                                .put("formatted_body", message.replace("\n","<br>"))
                                .put("format","org.matrix.custom.html");

        try {
            String url = server + roomsBaseUrl +room+"/send/m.room.message/"+ Instant.now().toEpochMilli()+"?access_token="+accessToken;
            var request = HttpRequest.newBuilder(new URI(url))
                    .PUT(HttpRequest.BodyPublishers.ofString( j.toString()))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply( res -> {
                        if( res.statusCode()!=200 ){
                            processError(res);
                            failedMessages.add(new String[]{room,mes});
                        }
                        return 0;
                    })
                    .exceptionally( t -> {
                        Logger.error(t.getMessage());
                        failedMessages.add( new String[]{room,mes} );
                        return 0;
                    });

        } catch (URISyntaxException e) {
            Logger.error(e);
        }
    }

    public static boolean isInvalidRoomId(String roomId) {
        Pattern pattern = Pattern.compile(ROOM_ID_REGEX);
        Matcher matcher = pattern.matcher(roomId);
        return !matcher.matches();
    }
    /* ******** Helper methods ****** */
    private void processError( HttpResponse<String> res ){
        JSONObject body;
        if( res.body() !=null) {
            body = new JSONObject(res.body());
        }else{
            Logger.error("Failed to process error, body is null but code is "+res.statusCode());
            return;
        }
        switch( res.statusCode() ) {
            case 200: // Not an error
                return;
            case 302:
                Logger.error("Errorcode: 302 -> Redirect to SSO interface");
                return;
            case 402:
                Logger.error("matrix -> " + body.getString("error"));
                String error = body.getString("error");
                switch (error) {
                    case "You are not invited to this room.":
                        Logger.error("Not allowed to join this room, invite only.");
                        //requestRoomInvite(room); break;
                    case "You don't have permission to knock":
                        break;
                }
            case 403: case 500:
                Logger.error("matrix -> ("+res.statusCode()+") "+body.getString("error"));
                break;
            default:
                Logger.error( "Code:"+res.statusCode()+" -> "+body.getString("error"));
                break;
        }
    }
    private void asyncGET( String url, CompletionEvent onCompletion){
        try{
            url=server+url;
            if( !accessToken.isEmpty())
                url+="?access_token="+accessToken;
            var request = HttpRequest.newBuilder(new URI(url))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(onCompletion::onCompletion);
        } catch (URISyntaxException e) {
            Logger.error(e);
        }
    }
    private void asyncPOST( String url, JSONObject data, CompletionEvent onCompletion){
        asyncPOST(url,data,onCompletion,fail -> {
                                            Logger.error(fail.getMessage());
                                            executorService.schedule(this::login,retry,TimeUnit.SECONDS);
                                            Logger.info("Retrying in "+retry+"s");
                                            retry += retry <RETRY_MAX?RETRY_STEP:0;
                                            return false;
                                        });
    }
    private void asyncPOST( String url, JSONObject data, CompletionEvent onCompletion, FailureEvent onFailure){
        if( data==null){
            Logger.error("matrix -> No valid data received to send to "+url);
            return;
        }

        try{
            url=server+url;
            if( !accessToken.isEmpty())
                url+="?access_token="+accessToken;
            var request = HttpRequest.newBuilder(new URI(url))
                    .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(10, TimeUnit.SECONDS)
                    .thenApply(onCompletion::onCompletion)
                    .exceptionally(onFailure::onFailure);
        } catch (URISyntaxException e) {
            Logger.error(e);
        }
    }
    private Optional<JSONObject> getJSONSubObject(JSONObject obj, String... keys){
        for (String key : keys) {
            if (!obj.has(key))
                return Optional.empty();
            obj = obj.getJSONObject(key);
        }
        return Optional.of(obj);
    }
    private ArrayList<JSONObject> getJSONArray(JSONObject obj, String... keys){
        ArrayList<JSONObject> events = new ArrayList<>();
        for( int a=0;a<keys.length-1;a++){
            if( !obj.has(keys[a]))
                return events;
            obj=obj.getJSONObject(keys[a]);
        }

        if( obj.has(keys[keys.length-1])){
            var ar = obj.getJSONArray(keys[keys.length-1]);
            for( int a=0;a<ar.length();a++){
                events.add(ar.getJSONObject(a));
            }
        }
        return events;
    }
    /* ******************* Writable **************************** */
    @Override
    public boolean writeString(String data) {
        return writeLine("", data);
    }
    @Override
    public boolean writeLine(String origin, String data) {
        var d = data.split("\\|"); //0=room,1=from,2=data
        if( d.length<3) {
            if( rooms.size()==1){
                rooms.values().forEach( r -> sendMessage(r.url(),data));
                return true;
            }
            Logger.warn("matrix -> Trying to send message, but no active rooms yet.");
        }else{
            sendMessage(d[0],d[2]);
        }
        return true;
    }
    @Override
    public boolean writeBytes(byte[] data) {
        return false;
    }

    @Override
    public String id() {
        return "matrix:"+userName;
    }

    @Override
    public boolean isConnectionValid() {
        return true;
    }

    @Override
    public String replyToCommand(Datagram d) {

        var args = d.argList();
        var wr = d.getWritable();

        Path p;
        StringJoiner j = new StringJoiner("\r\n");
        switch (args[0]) {
            case "?" -> {
                return doHelpCmd(d.asHtml());
            }
            case "restart" -> {
                readFromXML();
                login();
                return "Tried reloading";
            }
            case "rooms" -> {
                rooms.forEach((key, val) -> j.add(key + " -> " + val.url()));
                return j.toString();
            }
            case "join" -> {
                if (args.length < 3)
                    return "! Not enough arguments: matrix:join,roomid,url";
                var rs = Room.withID(args[1], this).url(args[2]);
                rooms.put(args[1], rs);
                joinRoom(rs, wr);
                return "Tried to join room";
            }

            /* *************** Files ********************* */
            case "files" -> {
                j.setEmptyValue("! No files yet");
                files.keySet().forEach(j::add);
                return j.toString();
            }
            case "share" -> {
                if (args.length < 3)
                    return "! Not enough arguments: matrix:share,roomid,filepath";
                p = Path.of(args[2]);
                if (Files.notExists(p))
                    return "! No such file";

                if (!rooms.containsKey(args[1]))
                    return "No such room (yet): " + args[1];

                sendFile(rooms.get(args[1]).url(), p, wr);
                return "File shared with " + args[1];
            }
            case "upload" -> {
                if (args.length < 2)
                    return "! Not enough arguments: matrix:upload,filepath";
                p = Path.of(args[1]);

                if (Files.notExists(p))
                    return "! No such file rest";

                sendFile("", p, wr);
                return "File uploaded.";
            }
            case "down" -> {
                if (args.length < 2)
                    return "! Not enough arguments: matrix:down,filepath";
                if (downloadFile(args[1], wr, ""))
                    return "Valid file chosen";
                return "! No such file";
            }
            case "addblank" -> {
                MatrixClient.addBlankElement(Paths.settings(), args);
                return "Blank matrix node added";
            }
            case "addmacro" -> {
                if (args.length < 2)
                    return "! Not enough arguments: matrix:addmacro,key,value";
                var f = XMLfab.withRoot(Paths.settings(), "dcafs", "settings", "matrix");
                f.addChild("macro", args[2]).attr("key", args[1]);
                macros.put(args[1], args[2]);
                return "Macro added to xml";
            }
            case "sync" -> {
                sync(false);
                return "Initiated sync";
            }
            default -> {
                var room = rooms.get("matrix:" + args[0]);
                if (room == null)
                    return "! No such subcommand or room.";

                if (args.length == 1) {
                    room.addTarget(wr);
                    return "Added " + wr.id() + " as target for room " + room.id();
                }

                return switch (args[1]) {
                    case "say", "txt" -> {
                        if (args.length < 3)
                            yield "! Not enough arguments: matrix:roomid,say/text,message";
                        String what = d.args().substring(d.args().indexOf(",") + 1);
                        sendMessage(room.url(), what);
                        yield "Message send to " + room.id() + ". (this doesn't mean it arrived)";
                    }
                    case "leave" -> "! Not implemented yet...";
                    default -> "!No such subcommand yet.";
                };
            }
        }
    }

    private static String doHelpCmd(boolean html) {
        var help = new StringJoiner("\r\n");
        help.add("Used to monitor a matrix room for messages and send messages.");
        help.add("Rooms")
                .add("matrix:rooms -> Give a list of all the joined rooms")
                .add("matrix:roomid,leave -> Leave the given room")
                .add("matrix:join,roomid,url -> Join a room with the given id and url")
                .add("matrix:roomid,say,message -> Send the given message to the room");
        help.add("Files")
                .add("matrix:files -> Get a listing of all the file links received")
                .add("matrix:down,fileid -> Download the file with the given id to the downloads map")
                .add("matrix:upload,path -> Upload a file with the given path");
        help.add("Other")
                .add("matrix:restart -> Log out & reload");
        help.add("matrix:share,roomid,path -> Upload a file with the given path and share the link in the room");
        return LookAndFeel.formatHelpCmd(help.toString(), html);
    }

    public static boolean addBlankElement( Path settingsPath,String[] split ){
        if( Files.notExists(settingsPath))
            return false;
        var user = split.length>=2?split[1]:"";
        var pass = split.length>=3?split[2]:"";
        var room = split.length>=4?split[3]:"";
        var fab = XMLfab.withRoot(settingsPath, "dcafs", "matrix");
        fab.attr("user","@"+user+":matrix.org").attr("pass",pass)
                .addChild("server","matrix-client.matrix.org")
                .addChild("room").attr("id","roomid").down()
                .addChild("url",room+":matrix.org")
                .addChild("entering", "Hello!")
                .addChild("leaving", "Bye :(")
                .addChild("greet", "Welcome");
        fab.build();
        return true;
    }

    @Override
    public boolean removeWritable(Writable wr) {
        rooms.values().forEach(room -> room.removeTarget(wr));
        return true;
    }

}
