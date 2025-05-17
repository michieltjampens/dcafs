package io.hardware.gpio;

import com.diozero.api.DigitalInputDevice;
import com.diozero.api.DigitalInputEvent;
import com.diozero.api.function.DeviceEventConsumer;
import org.tinylog.Logger;
import util.data.vals.FlagVal;

public class InputPin extends FlagVal implements DeviceEventConsumer<DigitalInputEvent> {
    DigitalInputDevice input;
    long lastTrigger = 0;
    long debounceMs = 50;

    public InputPin(String group, String name, String unit, DigitalInputDevice input) {
        super(group, name, unit);
        this.input = input;
        value = input.getValue();

        input.addListener(this);
    }

    public void setDebounceMs(long ms) {
        this.debounceMs = ms;
    }

    @Override
    public boolean isUp() {
        return input.getValue();
    }

    @Override
    public void accept(DigitalInputEvent event) {
        Logger.info("Trigger: " + event);

        var newState = event.getValue();
        if (event.getEpochTime() - lastTrigger < debounceMs) {
            lastTrigger = event.getEpochTime();
            Logger.info("Ignored trigger because to quick");
            return;
        }
        if (newState) { //FALSE -> TRUE
            Logger.info("Doing raiseBlock");
            raiseBlock.start();
        } else { // TRUE -> FALSE
            Logger.info("Doing fallBlock");
            fallBlock.start();
        }
        value = newState;
        lastTrigger = event.getEpochTime();
    }
}
