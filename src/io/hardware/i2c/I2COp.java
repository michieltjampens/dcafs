package io.hardware.i2c;

import java.util.ArrayList;

public interface I2COp {

    ArrayList<Double> doOperation(I2cDevice device);
    void setDelay( long millis);
    long getDelay();
    String toString();

}
