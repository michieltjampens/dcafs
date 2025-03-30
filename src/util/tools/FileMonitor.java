package util.tools;

import das.Commandable;
import das.Core;
import io.Writable;
import org.tinylog.Logger;
import util.xml.XMLdigger;
import worker.Datagram;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

public class FileMonitor implements Commandable {

    WatchService service;
    ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
    ArrayList<ReactionInfo> files = new ArrayList<>();
    static final int BUFFER_SIZE = 256;
    Path root;

    public FileMonitor(Path root){
        this.root=root;

        readFromXML();
    }
    /**
     * Read the monitor setup from settings.xml
     * @return True if read properly
     */
    public boolean readFromXML( ){

        var dig = XMLdigger.goIn(root.resolve("settings.xml"),"dcafs","monitor");
        if( dig.isInvalid())
            return false;

        /* Check for simple file modification alerts */
        dig.digDown("file");
        while(dig.iterate()){
            var id = dig.attr("id","fm"+files.size());
            var p = dig.attr("path",null,root);
            if( p.isEmpty())
                continue;
            String val = dig.attr("onmodify","");
            boolean read = dig.attr("read",true);

            var mon = new ReactionInfo(id,p.get(), val);
            if( read )
                mon.enableReading();

            files.add( mon );
            watcher.submit(() -> watch(mon));
        }
        return true;
    }
    public void addSimpleWatch( String id, Path p, Function<String,Integer> act){
        if (service != null)
            return;
        try {
            service = FileSystems.getDefault().newWatchService();
            Files.createFile(p);
            var mon = new ReactionInfo(id, p, act, false);
            files.add(mon);
            watcher.submit(() -> watch(mon));
        } catch (IOException e) {
            Logger.error(e);
        }
    }
    private void watch( ReactionInfo mon )  {
        int blank=0;
        if( !mon.exists() ){
            Logger.warn("No such file to monitor: "+mon.getFilename());
            return;
        }
        try {
            if(service == null)
                service = FileSystems.getDefault().newWatchService();

            var wk = mon.register(service);
            mon.updatePosition();
            service.take();
            for (var event : wk.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;

                String fileName = ev.context().toString();

                if( mon.isFolder()){
                    if( kind == ENTRY_CREATE ){
                        Logger.info("File created: "+fileName);
                        var lines = new ArrayList<String>();
                        FileTools.readTxtFile(lines,mon.file.resolve(fileName));
                        lines.forEach(mon::sendLine);
                    }
                }else if( kind == ENTRY_MODIFY && fileName.equals(mon.getFilename()) ){
                    if( mon.hasCmd() ) {
                        Core.addToQueue(Datagram.system(mon.getCmd()));
                        Logger.info("Executing '"+mon.getCmd()+"' because modified "+mon.getFilename());
                    }
                    blank = read(wk,mon,"");
                }

            }
            // reset the key
            wk.reset();
        }catch(InterruptedException | IOException e){
            Logger.error(e);
        }
        watcher.schedule(()->watch(mon),Math.max(blank,1), TimeUnit.SECONDS);
    }
    private int read( WatchKey wk, ReactionInfo mon, String sub )  {
        int blank=0;
        if( mon.handlesReads() ) {
            byte[] array = new byte[BUFFER_SIZE];
            int read = 0;
            Path toRead = mon.file;
            if( !sub.isBlank())
                toRead=toRead.resolve(sub);
            try (FileInputStream stream = new FileInputStream(toRead.toFile())) {
                stream.getChannel().position(mon.position);
                read = stream.read(array, 0, BUFFER_SIZE);
            } catch (IOException r) {
                Logger.error(r);
            }
            if(read > 0) {
                String rec = new String(Arrays.copyOfRange(array,0,read));
                wk.reset();
                wk.cancel();
                blank=mon.handleRead(rec);
            }else{
                mon.handleIssue();
            }
        }else{
            blank=mon.handleNoRead();
        }
        return blank;
    }

    @Override
    public String replyToCommand(Datagram d) {
        if (d.cmd().equalsIgnoreCase("fm")) {
            var list = files.stream().filter(fm -> fm.id.equalsIgnoreCase(d.args())).toList();
            if( list.isEmpty())
                return "No such filemonitor";
            list.forEach(fm -> fm.addTarget(d.getWritable()));
            return "Target added";
        }
        return "! No such subcommand in " + d.getData();
    }
    @Override
    public boolean removeWritable(Writable wr) {
        files.forEach( fm -> fm.targets.remove(wr));
        return true;
    }

    /**
     * Simple class to contain all required info on what to do after an event
     */
    private static class ReactionInfo {
        private String id="";
        private final Path file;
        private Function<String, Integer> action;
        private String cmd="";
        private long position=-1;
        private boolean issue=false;
        private final ArrayList<Writable> targets=new ArrayList<>();

        public ReactionInfo(String id,Path file, Function<String, Integer> action, boolean alteration) throws IOException {
            this.id=id;
            this.file=file;
            this.action=action;
            if( alteration )
                position = Files.size(file);
        }
        public ReactionInfo(String id, Path file, String cmd) {
            this.id=id;
            this.file=file;
            this.cmd=cmd;

            Logger.info("Monitoring '"+file+"', on modify: "+cmd);
        }
        public void enableReading(){
            position=0;
        }
        public void addTarget(Writable wr){
            targets.add(wr);
        }
        public void updatePosition() throws IOException{
            if( position==-1)
                return;
            position = Files.size(file);
        }
        public WatchKey register( WatchService ws ) throws IOException {
            if( isFolder()){
                return file.register(ws, ENTRY_CREATE);
            }
            return file.getParent().register(ws, ENTRY_CREATE);
        }
        public boolean isFolder(){
            return Files.isDirectory(file);
        }
        public boolean handlesReads(){
            return position !=-1;
        }
        public int handleRead(String rec){
            position += rec.length();
            issue=false;
            targets.forEach(wr -> wr.writeLine(id, rec));
            return action.apply(rec);
        }
        public void sendLine(String line){
            if( targets.isEmpty())
                return;
            targets.forEach(wr -> wr.writeLine(id, line));
        }
        public int handleNoRead(){
            return action.apply("");
        }
        public void handleIssue(){
            if( issue ) {
                position=0;
                issue=false;
            }else {
                issue = true;
            }
        }
        public String getFilename(){
            return file.getFileName().toString();
        }
        public boolean hasCmd(){
            return !cmd.isBlank();
        }
        public String getCmd(){
            return cmd;
        }
        public boolean exists(){
            return Files.exists(file);
        }
    }
}
