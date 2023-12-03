package io.matrix;

import io.Writable;

import java.util.ArrayList;

public class Room implements Writable {

    private String localId="";
    private String url="";
    private ArrayList<Writable> targets;
    private String hello="";
    private String welcome="";
    private String bye="";

    private String alias="";
    private boolean connected=false;

    public Room(String localId){
        this.localId=localId;
    }

    public static Room withID(String localId ){
        return new Room(localId);
    }
    public Room url(String url){
        this.url=url;
        return this;
    }
    public String id(){
        return localId;
    }
    public String url(){
        return url;
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
        return hello;
    }
    public Room leaving(String bye){
        this.bye=bye;
        return this;
    }
    public void addTarget( Writable wr){
        if( targets==null)
            targets = new ArrayList<>();
        targets.add(wr);
    }
    public void writeToTargets( String line ){
        targets.forEach(wr->wr.writeLine("matrix:"+localId,line));
    }
    public void connected( boolean state){
        this.connected=state;
    }
    /* Writable */
    @Override
    public boolean writeLine(String data) {
        return false;
    }

    @Override
    public boolean writeLine(String origin, String data) {
        return false;
    }

    @Override
    public boolean writeBytes(byte[] data) {
        return writeLine(new String(data));
    }
    @Override
    public boolean writeString(String data) {
        return writeLine(data);
    }
    @Override
    public boolean isConnectionValid() {
        return connected;
    }

    @Override
    public Writable getWritable() {
        return this;
    }
}