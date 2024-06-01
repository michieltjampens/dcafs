package io.hardware.gpio;

import com.diozero.api.*;
import com.diozero.api.function.DeviceEventConsumer;
import com.diozero.sbc.LocalSystemInfo;
import das.Commandable;
import io.Writable;
import org.tinylog.Logger;
import util.data.IntegerVal;
import util.data.RealVal;
import util.data.RealtimeValues;
import util.tools.Tools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

public class InterruptPins implements DeviceEventConsumer<DigitalInputEvent>, Commandable {

    private final HashMap<String,DigitalInputDevice> inputs = new HashMap<>();
    private final HashMap<Integer,ArrayList<IsrAction>> isrs = new HashMap<>();
    private final BlockingQueue<Datagram> dQueue;
    private final Path settings;
    private final CustomBoard board;
    private final RealtimeValues rtvals;

    public InterruptPins(BlockingQueue<Datagram> dQueue, Path settings, RealtimeValues rtvals){
        this.dQueue=dQueue;
        this.settings=settings;
        this.rtvals=rtvals;
        board = new CustomBoard(LocalSystemInfo.getInstance(),settings);

        readFromXml();
    }
    public CustomBoard getBoard(){
        return board;
    }
    private void readFromXml(){
        var dig = XMLdigger.goIn(settings,"dcafs","gpios");
        if( dig.isValid() ){
            for( var isrDig: dig.digOut("gpio")){
                int pin = isrDig.attr("nr",-1);
                PinInfo pinInfo;
                if( pin == -1 ){
                    var name = isrDig.attr("name","");
                    pinInfo = board.getByName(name);
                    if( pinInfo==null){
                        Logger.error("Couldn't find math for pin name <"+name+">");
                        continue;
                    }else{
                        Logger.info( "Matched "+name+" to "+pinInfo.getName());
                    }
                }else{
                    pinInfo = board.getByGpioNumber(pin).orElse(null);
                    if( pinInfo==null){
                        Logger.error("Couldn't find math for pin nr "+pin);
                        continue;
                    }else{
                        Logger.info( "Matched pin nr "+pin+" to "+pinInfo.getName());
                    }
                }
                if( isrDig.hasPeek("interrupt") ) {
                    isrDig.usePeek();
                    GpioEventTrigger trigger = switch (isrDig.attr("edge", "falling")) {
                        case "falling" -> GpioEventTrigger.FALLING;
                        case "rising" -> GpioEventTrigger.RISING;
                        case "both" -> GpioEventTrigger.BOTH;
                        default -> GpioEventTrigger.NONE;
                    };
                    GpioPullUpDown pud = switch (isrDig.attr("pull", "none")) {
                        case "up" -> GpioPullUpDown.PULL_UP;
                        case "down" -> GpioPullUpDown.PULL_DOWN;
                        default -> GpioPullUpDown.NONE;
                    };
                    var ic = addGPIO(pinInfo, trigger, pud);

                    if (ic.isPresent()) {
                        if( isrDig.hasPeek("cmd")){
                            var cmds = isrDig.digOut("cmd");
                            var list = cmds.stream().map( cmd -> cmd.value("")).collect(Collectors.toSet());
                            addIsrAction(pinInfo.getDeviceNumber(), new IsrCmd(list));
                        }else {
                            if( isrDig.hasPeek("counter") ) {
                                var val = isrDig.peekAt("counter").value("");
                                var intOpt = rtvals.getIntegerVal(val);
                                if (intOpt.isPresent()) {
                                    addIsrAction(pinInfo.getDeviceNumber(), new IsrCounter(intOpt.get()));
                                } else {
                                    Logger.error("(isr) -> No such int yet '" + val + "'");
                                }
                            }
                            if( isrDig.hasPeek("frequency") ){
                                var samples = isrDig.peekAt("frequency").attr("samples",2);
                                var updateRate = isrDig.attr("updaterate",1);
                                var val = isrDig.value("");

                                var realOpt = rtvals.getRealVal(val);
                                if (realOpt.isPresent()) {
                                    addIsrAction(pinInfo.getDeviceNumber(), new IsrFrequency(realOpt.get(), samples, updateRate));
                                } else {
                                    Logger.error("(isr) -> No such real yet '" + val + "'");
                                }
                            }else {
                                if (trigger == GpioEventTrigger.BOTH) {
                                    var idle = Tools.parseBool(isrDig.attr("idle", "high"), true);
                                    addIsrAction(pinInfo.getDeviceNumber(), new IsrPulse(idle));
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private void addIsrAction( int pin, IsrAction action ){
        var list = isrs.get(pin);
        if( list == null ) {
            list = new ArrayList<>();
            list.add(action);
            isrs.put(pin,list);
        }else{
            list.add(action);
        }
    }
    public static String checkGPIOS(){
        var board = new CustomBoard( LocalSystemInfo.getInstance() );
        return board.checkGPIOs();
    }
    private Optional<DigitalInputDevice> addGPIO(PinInfo gpio, GpioEventTrigger event, GpioPullUpDown pud ){
        try {
            var input = DigitalInputDevice.Builder.builder(gpio).setTrigger(event).setPullUpDown(pud).build();
            input.addListener(this);
            inputs.put(gpio.getName(),input);
            Logger.info("Setting interruptGpio ({},{}) consumer", input.getGpio(),gpio.getName());
            return Optional.of(input);
        }catch( RuntimeIOException e  ){
            Logger.error(e);
            Logger.info( "Failed to add "+gpio.getName()+" as interrupt");
            return Optional.empty();
        }
    }
    public String getStatus(String eol){
        var join = new StringJoiner(eol);
        for( var input : inputs.entrySet())
            join.add(input.getKey()+" -> "+(input.getValue().isActive()?"high":"low"));
        return join.toString();
    }
    @Override
    public void accept(DigitalInputEvent event) {
        // Check the event is for one of the interrupt gpio's and if so, execute the commands
        var isrActions = isrs.get(event.getGpio());
        if( isrActions!=null )
            isrActions.forEach( isr -> isr.trigger(event) );
    }

    @Override
    public String replyToCommand(String cmd, String args, Writable wr, boolean html) {
        return "";
    }

    @Override
    public String payloadCommand(String cmd, String args, Object payload) {

        return "";
    }

    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
    private static class IsrCounter implements IsrAction {
        IntegerVal counter;
        long last=0;
        public IsrCounter( IntegerVal counter ){
            this.counter=counter;
        }
        public void trigger( DigitalInputEvent event ){
            counter.increment();
            Logger.info( "Pulses counted: "+counter.asValueString() );
        }
    }
    private static class IsrFrequency implements IsrAction {
        RealVal frequency;
        int samples;
        ArrayList<Long> stamps = new ArrayList<>();
        int updateRate=1;
        int counter=0;

        public IsrFrequency( RealVal frequency, int samples, int updateRate ){
            this.frequency=frequency;
            this.samples=samples==1?2:samples;
            this.updateRate=updateRate;
        }
        public void trigger( DigitalInputEvent event ){
            // Could probably be made more efficient is update rate is low and trigger interval is high?
            // As in when you want the average of 10 samples but only care every 100, ignore first 90?
            stamps.add( event.getNanoTime() ); // Always store time of last trigger
            if( stamps.size()==samples ){ // Wait till enough samples collected
                counter ++; // Increment counter that determines update rate of the realval
                if( counter == updateRate ) { // If the counter matches the requested rate, actually calculate frequency
                    var res = (double)1000000000/(event.getNanoTime()-stamps.remove(0) );
                    frequency.updateValue(res / (samples - 1));
                    Logger.info("Frequency: " + frequency.asValueString());
                    counter=0;
                }else{// If not, just remove old sample
                    stamps.remove(0);
                }
            }
        }
    }
    private static class IsrPulse implements IsrAction{
        long last=0;
        boolean idle;
        long negativeOffset = Long.MAX_VALUE;
        long positiveOffset = Long.MIN_VALUE;

        public IsrPulse(boolean idle){
            this.idle=idle;
        }
        public void trigger(DigitalInputEvent event){
            if( event.getValue() != idle ) {
                if( last !=0) {
                    var res = Math.toIntExact((event.getNanoTime()-last)/1000);
                    Logger.info("Interval:" +res+"us");
                }
                last=event.getNanoTime();
            }else{
                long duration = event.getNanoTime()-last;
                int us = Math.toIntExact(duration/1000);
                int offset = 5000-us;
                Logger.info("Duration of pulse: "+duration+"ns or "+ us+"us ("+offset+"us)");
                negativeOffset = Math.min(offset, negativeOffset);
                positiveOffset = Math.max(offset, positiveOffset);
                Logger.info("Offset:" +negativeOffset+" -> "+positiveOffset );
            }
        }
    }
    private class IsrCmd implements IsrAction{
        ArrayList<String> cmds = new ArrayList<>();

        public IsrCmd( Set<String> list ){
            cmds.addAll(list);
        }
        public void trigger(DigitalInputEvent event){
            Logger.info("Interrupt -> {}", event);
            cmds.forEach(cmd -> dQueue.add(Datagram.system(cmd)));
        }
    }

}
