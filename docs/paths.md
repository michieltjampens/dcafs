# Paths

## What is it?

Paths are the name for the xml scripts used in dcafs to determine the 'path' data takes from 'raw' to 'processed'. 

Paths have four distinct nodes/steps:
- Filter/if/case : These check if the data meets certain 'rules'. If not, no following steps will be done.
- Editor : These are used to alter the data using string operations fe. adding/removing things.
- Math : These alter the data using mathematical formulas, fe. applying calibration coëfficients
- Store : This is most often the final step. Once the data is fully processed, store it in rtvals and potentially trigger database inserts

This document will explain these steps.

## Filter

The filter has three different versions which determine how the rest is handled.
- if : Like regular programming. If ok, the steps within the node are executed. Following nodes are unaffected.
- filter : Data needs to pass the checks in order for the following steps to be done.
- case : Similar to a switch statement, once one succeeds the following ones aren't processed.

Simple example.
```xml
 <path delimiter="," id="gps">
        <!-- Global filter, check if data received is valid nmea  -->
        <filter check="nmea"/>
		<!-- If it's valid nmea, check if it's a GPGGA string -->
		<if start="$GPGGA">
            <!-- Do things here if it starts with $GPGGA -->
        </if>
</path>
```

## Nested structures

Filter allows the use of `if` nodes. The default behaviour is that all if's are tried.
Meaning that if the data is altered in one if, the next one starts from that data.

```xml

<path delimiter="," id="gps">
    <if start="$GPGGA">
        <!-- Do things here if it starts with $GPGGA -->
    </if>
    <if start="$GPZDA">
        <!-- Do things here if it starts with $GPZDA -->
    </if>
</path>
```

If this is unwanted behaviour, the `return` node can be used to shortcircuit the path.
Which essentially creates an if/else (if) structure.

```xml

<path delimiter="," id="gps">
    <if start="$GPGGA">
        <!-- Do things here if it starts with $GPGGA -->
        <return/> <!-- The path doesn't go beyond this point -->
    </if>
    <if start="$GPZDA">
        <!-- Do things here if it starts with $GPZDA -->
    </if>
</path>
```
### Rules

Note: All rules don't have to end on 's'. If this seems more logical, it can be omitted.

#### Item count  
These split the data according to the given delimiter and compare the resulting item count.
* items : Checks if the count is within the given range.
* maxitems : Checks if the count is at most the given value.
* minitems : Checks if the count is at least the given value.

```xml
<path delimiter=",">
    <if maxitems="4"> <!-- Check if after splitting on , the result is at most 4 --> 
        <!-- Other steps if check succeeds -->
    </if>
    <filter minitems="3"/>
    <!-- Other steps if the check succeeds -->
</path>
```
#### Text matching
These check a certain part of the data for the occurence of specified text.
* starts : The data must start with the given text.
* nostarts or !starts" : The data can't start with the given text.
* ends : The data ends with the given text.
* contains or includes : The data contains the given text.
* !contains" : The data doesn't contain the given text.
* regex : The data must match the given regex.

#### Character matching 
If a character needs to be at specific position in the data, these can be used.

* c_start : At index x from the start, character y needs to be.
* c_end : At index x from the end, character y needs to be.

#### Data Length
* minlength : The minimum length of the data.
* maxlength : The maximum length of the data.

### Other
* nmea : Check if the data is a valid nmea string (verifies checksum).
* math : Does a simple math check.

## Customsrc

If you want to check if a forward is working as expected, it's possible to create the data stream locally.

````xml
<path>
    <plainsrc>Hello World!</plainsrc> <!-- Will send Hello World every second -->
    <plainsrc delay="100ms" interval="10s">Hello??
    </plainsrc> <!-- Will send hello?? every 10s with initial delay of 100ms -->
    <filesrc>todo</filesrc> <!-- will send the data from all the files in the map one line at a time at 1s interval -->
    <cmdsrc>st</cmdsrc> <!-- will send the result of the cmd -->
</path>
````