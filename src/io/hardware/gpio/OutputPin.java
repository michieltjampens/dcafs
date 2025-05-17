package io.hardware.gpio;

import com.diozero.api.DigitalOutputDevice;
import util.data.vals.FlagVal;

public class OutputPin extends FlagVal {
    DigitalOutputDevice output;

    public OutputPin(String group, String name, String unit, DigitalOutputDevice output) {
        super(group, name, unit);
        this.output = output;
    }

    @Override
    public void update(boolean state) {
        output.setValue(state);
    }

    @Override
    public void toggleState() {
        output.toggle();
    }

    @Override
    public boolean isUp() {
        return output.isOn();
    }
}
