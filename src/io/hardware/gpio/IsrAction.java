package io.hardware.gpio;

import com.diozero.api.DigitalInputEvent;

public interface IsrAction {
    void trigger(DigitalInputEvent event);
}
