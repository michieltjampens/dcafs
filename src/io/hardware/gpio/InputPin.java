package io.hardware.gpio;

import com.diozero.api.DigitalInputDevice;
import com.diozero.api.DigitalInputEvent;
import com.diozero.api.function.DeviceEventConsumer;
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
        value = input.getValue();
        return value;
    }

    @Override
    public void accept(DigitalInputEvent event) {
        //Logger.info("Trigger: " + event);
        var newState = event.getValue();
        if (event.getEpochTime() - lastTrigger < debounceMs) {
            lastTrigger = event.getEpochTime();
            return;
        }

        if (newState) { //FALSE -> TRUE
            raiseBlock.start();
        } else { // TRUE -> FALSE
            fallBlock.start();
        }
        value = newState;
        lastTrigger = event.getEpochTime();
    }
}
