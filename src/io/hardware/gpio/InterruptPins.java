package io.hardware.gpio;

import com.diozero.api.*;
import com.diozero.api.function.DeviceEventConsumer;
import com.diozero.sbc.LocalSystemInfo;
import das.Commandable;
import io.Writable;
import org.tinylog.Logger;
import util.xml.XMLdigger;
import worker.Datagram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;

public class InterruptPins implements DeviceEventConsumer<DigitalInputEvent>, Commandable {

    private final HashMap<String,DigitalInputDevice> inputs = new HashMap<>();
    private final HashMap<Integer,ArrayList<String>> isrs = new HashMap<>();
    private final BlockingQueue<Datagram> dQueue;
    private final Path settings;
    private final CustomBoard board;

    public InterruptPins(BlockingQueue<Datagram> dQueue, Path settings){
        this.dQueue=dQueue;
        this.settings=settings;
        board = new CustomBoard(LocalSystemInfo.getInstance(),settings);

        readFromXml();
    }
    public CustomBoard getBoard(){
        return board;
    }
    private void readFromXml(){
        var dig = XMLdigger.goIn(settings,"dcafs","gpios");
        if( dig.isValid() ){
            for( var isr: dig.digOut("gpio")){
                int pin = isr.attr("nr",-1);
                PinInfo pinInfo;
                if( pin == -1 ){
                    var name = isr.attr("name","");
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
                if( isr.hasPeek("interrupt") ) {
                    isr.usePeek();
                    GpioEventTrigger trigger = switch (isr.attr("edge", "falling")) {
                        case "falling" -> GpioEventTrigger.FALLING;
                        case "rising" -> GpioEventTrigger.RISING;
                        case "both" -> GpioEventTrigger.BOTH;
                        default -> GpioEventTrigger.NONE;
                    };
                    GpioPullUpDown pud = switch (isr.attr("pull", "none")) {
                        case "up" -> GpioPullUpDown.PULL_UP;
                        case "down" -> GpioPullUpDown.PULL_DOWN;
                        default -> GpioPullUpDown.NONE;
                    };
                    var ic = addGPIO(pinInfo, trigger, pud);

                    if (ic.isPresent()) {
                        ArrayList<String> list = isrs.get(pinInfo.getDeviceNumber());
                        for (var cmd : isr.digOut("cmd")) {
                            if (list == null)
                                list = new ArrayList<>();
                            list.add(cmd.value(""));
                        }
                        if (list != null)
                            isrs.put(pinInfo.getDeviceNumber(), list);
                    }
                }
            }
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
        var match = inputs.entrySet().stream().filter( x -> x.getValue().getGpio()==event.getGpio() ).findFirst();
        if( match.isPresent() ){
            Logger.info("Interrupt {} -> {}", match.get().getKey(), event);
        }else{
            Logger.info("Interrupt -> {}", event);
        }
        // Check the event is for one of the interrupt gpio's and if so, execute the commands
        var list = isrs.get(event.getGpio());
        if( list!=null )
            list.forEach( cmd -> dQueue.add( Datagram.system(cmd)));
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
}
