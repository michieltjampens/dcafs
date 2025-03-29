package io.matrix;

import io.Writable;

import java.util.ArrayList;

public class Room implements Writable {

    private String localId="";
    private String url="";
    private ArrayList<Writable> targets;
    private String hello="";
    private String welcome="";

    private boolean connected=false;
    private final Writable client;
    private final ArrayList<String> cmds=new ArrayList<>();

    public Room(String localId, Writable client){
        this.client=client;
        this.localId=localId;
    }

    public static Room withID(String localId,Writable client ){
        return new Room(localId,client);
    }
    public Room url(String url){
        this.url=url;
        return this;
    }
    public String id(){
        return "matrix:"+localId;
    }
    public String url(){
        return url;
    }
    public void addTriggeredCmd(String cmd){
        cmds.add(cmd);
    }
    public ArrayList<String> getTriggeredCmds(){
        return cmds;
    }
    public Room welcome(String welcome){
        this.welcome=welcome;
        return this;
    }
    public Room entering(String entering){
        this.hello=entering;
        return this;
    }
    public String entering(){
        return hello+" "+welcome;
    }
    public void addTarget( Writable wr){
        if( targets==null)
            targets = new ArrayList<>();
        targets.add(wr);
    }

    public void removeTarget(Writable wr) {
        if (targets != null)
            targets.remove(wr);
    }
    public void writeToTargets( String line ){
        if( targets!=null)
            targets.forEach(wr->wr.writeLine("matrix:"+localId,line));
    }
    public void connected( boolean state){
        this.connected=state;
    }
    /* Writable */
    @Override
    public boolean writeLine(String origin, String data) {
        return client.writeLine(origin,data);
    }

    @Override
    public boolean writeBytes(byte[] data) {
        return writeLine("", new String(data));
    }
    @Override
    public boolean writeString(String data) {
        return writeLine("", data);
    }
    @Override
    public boolean isConnectionValid() {
        return connected;
    }

}