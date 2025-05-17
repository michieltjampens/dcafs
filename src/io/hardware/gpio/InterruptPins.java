package io.hardware.gpio;

import com.diozero.api.*;
import com.diozero.api.function.DeviceEventConsumer;
import com.diozero.sbc.LocalSystemInfo;
import das.Commandable;
import das.Core;
import das.Paths;
import io.Writable;
import org.tinylog.Logger;
import util.data.vals.IntegerVal;
import util.data.vals.RealVal;
import util.data.vals.Rtvals;
import util.xml.XMLdigger;
import worker.Datagram;

import java.util.*;
import java.util.stream.Collectors;

public class InterruptPins implements DeviceEventConsumer<DigitalInputEvent>, Commandable {

    private final HashMap<String,DigitalInputDevice> inputs = new HashMap<>();
    private final HashMap<Integer,ArrayList<IsrAction>> isrs = new HashMap<>();
    private final CustomBoard board;
    private final Rtvals rtvals;

    public InterruptPins(Rtvals rtvals) {
        this.rtvals=rtvals;
        board = new CustomBoard(LocalSystemInfo.getInstance(), Paths.settings());

        readFromXml();
    }
    public CustomBoard getBoard(){
        return board;
    }
    private void readFromXml(){
        var dig = Paths.digInSettings("gpios");

        if (!dig.isValid())
            return;

        for (var isrDig : dig.digOut("gpio")) {

            int pin = isrDig.attr("nr", -1);
            Logger.info("Processing: " + pin);
            PinInfo pinInfo;
            if (pin == -1) {
                var name = isrDig.attr("name", "");
                pinInfo = board.getByName(name);
                if (pinInfo == null) {
                    Logger.error("Couldn't find match for pin name <" + name + ">");
                    continue;
                }
                Logger.info("Matched " + name + " to " + pinInfo.getName());
            } else {
                pinInfo = board.getByGpioNumber(pin).orElse(null);
                if (pinInfo == null) {
                    Logger.error("Couldn't find match for pin nr " + pin);
                    continue;
                }
                Logger.info("Matched pin nr " + pin + " to " + pinInfo.getName());
            }

            if (!isrDig.hasPeek("interrupt"))
                continue;
            isrDig.usePeek();

            var trigger = initializeGpioTrigger(isrDig, pinInfo);
            if (trigger == null)
                continue;

            if (isrDig.hasPeek("cmd")) {
                var list = isrDig.digOut("cmd").stream()
                        .map(cmd -> cmd.value(""))
                        .collect(Collectors.toSet());
                addIsrAction(pinInfo.getDeviceNumber(), new IsrCmd(list));
                continue;
            }

            peekAtCounter(isrDig, pinInfo);
            peekAtFrequency(isrDig, pinInfo);
            peekAtPeriod(isrDig, pinInfo, trigger);
        }
    }

    private GpioEventTrigger initializeGpioTrigger(XMLdigger isrDig, PinInfo pinInfo) {
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

        if (addGPIO(pinInfo, trigger, pud).isEmpty())
            return trigger;
        Logger.error("Something went wrong trying to add gpio " + pinInfo.getName());
        return null;
    }

    private void peekAtCounter(XMLdigger isrDig, PinInfo pinInfo) {
        if (!isrDig.hasPeek("counter"))
            return;
        var val = isrDig.value("");
        var intOpt = rtvals.getIntegerVal(val);
        if (intOpt.isPresent()) {
            Logger.info("(isr) -> Added counting isr saving to " + val
                    + " after interrupt on " + pinInfo.getDeviceNumber() + ".");
            addIsrAction(pinInfo.getDeviceNumber(), new IsrCounter(intOpt.get()));
        } else {
            Logger.error("(isr) -> No such int yet '" + val + "'");
        }
    }

    private void peekAtFrequency(XMLdigger isrDig, PinInfo pinInfo) {
        if (!isrDig.hasPeek("frequency"))
            return;

        var samples = isrDig.attr("samples", 2);
        var updateRate = isrDig.attr("updaterate", 1);
        var val = isrDig.value("");

        var realOpt = rtvals.getRealVal(val);
        if (realOpt.isPresent()) {
            Logger.info("(isr) -> Added frequency isr saving to " + val
                    + " after interrupt on " + pinInfo.getDeviceNumber() + ".");
            addIsrAction(pinInfo.getDeviceNumber(), new IsrFrequency(realOpt.get(), samples, updateRate));
        } else {
            Logger.error("(isr) -> No such real yet '" + val + "'");
        }
    }

    private void peekAtPeriod(XMLdigger isrDig, PinInfo pinInfo, GpioEventTrigger trigger) {
        if (!isrDig.hasPeek("period"))
            return;

        if (trigger != GpioEventTrigger.BOTH) {
            Logger.error("(i2c) -> Period isr requires triggering on 'both' edges.");
        } else {
            var idle = isrDig.peekAt("period").attr("idle", true);
            var val = isrDig.value("");
            var periodOpt = rtvals.getRealVal(val);
            if (periodOpt.isEmpty()) {
                Logger.error("(isr) -> No such real " + val + ".");
            } else {
                Logger.info("(isr) -> Added period isr saving to " + val + " with idle " + (idle ? "high" : "low"));
                addIsrAction(pinInfo.getDeviceNumber(), new IsrPeriod(periodOpt.get(), idle));
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
        Logger.debug( "Trigger: " + event );
        if( isrActions!=null ) {
            isrActions.parallelStream().forEach(isr -> isr.trigger(event));
        }
    }

    @Override
    public String replyToCommand(Datagram d) {
        if (d.payload() == null)
            return "";

        var args = d.argList();
        if( args[0].equals("watch") ){
            var input = inputs.get(args[1]);
            if( input == null ) {
                Logger.error("(isr) -> Not a valid gpio given: "+args[1]);
                return "! Not a valid gpio given: " + args[1];
            }
            if (d.payload() instanceof DeviceEventConsumer) {
                var dev = (DeviceEventConsumer<DigitalInputEvent>) d.payload();
                input.addListener(dev);
                Logger.info("(isr) -> Added callback to "+args[1]);
                return "Callback attached for "+args[1];
            }
            Logger.error("(isr) -> Not proper layout for "+args[1]);
            return "! No proper payload for "+args[1];
        }
        return "! Unknown cmd: " + d.getData();
    }

    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
    private static class IsrCounter implements IsrAction {
        IntegerVal counter;

        public IsrCounter( IntegerVal counter ){
            this.counter=counter;
        }
        public void trigger( DigitalInputEvent event ){
            counter.update(counter.value() + 1);
            Logger.info("Pulses counted: " + counter.asString());
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
            stamps.ensureCapacity(samples);
        }
        public void trigger( DigitalInputEvent event ){
            // Could probably be made more efficient if update rate is low and trigger interval is high?
            // As in when you want the average of 10 samples but only care every 100, ignore first 90?
            stamps.add( event.getNanoTime() ); // Always store time of last trigger
            if( stamps.size()==samples ){ // Wait till enough samples collected
                counter ++; // Increment counter that determines update rate of the realval
                if( counter == updateRate ) { // If the counter matches the requested rate, actually calculate frequency
                    var res = (double)1000000000/((double) (event.getNanoTime() - stamps.remove(0)) /(samples-1));
                    frequency.update(res);
                    Logger.debug("Frequency: " + frequency.asString() + frequency.unit());
                    counter=0;
                }else{// If not, just remove old sample
                    stamps.remove(0);
                }
            }
        }
    }
    private static class IsrPeriod implements IsrAction{
        long last=0;
        boolean idle;
        RealVal period;

        public IsrPeriod(RealVal period, boolean idle){
            this.period=period;
            this.idle=idle;
        }
        public void trigger(DigitalInputEvent event){
            if( event.getValue() != idle ) {
                last=event.getNanoTime();
            }else if(last!=0){
                int us = Math.toIntExact((event.getNanoTime()-last)/1000);
                period.update(us);
                Logger.info("Duration of pulse: " + us+"us");
            }
        }
    }

    private static class IsrCmd implements IsrAction {
        ArrayList<String> cmds = new ArrayList<>();

        public IsrCmd( Set<String> list ){
            cmds.addAll(list);
        }
        public void trigger(DigitalInputEvent event){
            Logger.debug("Interrupt -> {}", event);
            cmds.forEach(cmd -> Core.addToQueue(Datagram.system(cmd)));
        }
    }

}
