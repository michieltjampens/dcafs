package io.hardware.i2c;

import com.diozero.api.I2CDevice;

import java.util.ArrayList;

public interface I2COp {

    ArrayList<Double> doOperation(ExtI2CDevice device);
    void setDelay( long millis);
    long getDelay();
    String toString();

}
