# Rtvals

Everything there is to know about rtvals.

At the moment there are four vals
- real -> for real/double/fractional numbers
- int -> for integer numbers
- text -> for general (ascii) sequences
- flag -> for binary true/false states

## Real & Int

These are meant to store numbers.  
Because these two have significant overlap, this is combined.

### Basics attributes
The most basic node, this is the absolute minimum required:
````xml
<real>name</real>
<int>name</int>
<!-- or -->
<real name="name">
    <!-- add subnodes -->
</real>
<int name="name">
</int>
````
For the following examples an outdoor temperature reading will be used.  
Adding to this, the most often used:

````xml
<real group="outdoor" scale="2" unit="°C">temperature</real>
````
The added attributes:
- `group` The group the val belongs to, this is a way to combine different parameters from the same sensor/location etc
- `unit` The unit to append to the end on listings, default is none 
- `def` or `default` The default value to use if something went wrong or to start with
- `scale` The amount of digits (only applicable for real), default is 8

So in short this creates a real with the id `outdoor_temperature` with unit`°C` and rounded (half up) to two digits.

### Advanced attributes

At the moment there's only one `options`.
This attribute can be a list of other options to enable.  
These are:
- `time` Store the timestamp of the last update
- `minmax` Keep track of the minimum and maximum value (since startup)
- `history:x` Keep the last x values in memory, nothing is done with this for now except stdev calculations
- `stdev` Calculate the stdev based on the history
- `abs` Always store the absolute value instead
- `order:x` Just the position this val should get in the listing of the group

**Example:**  
````xml
<real group="outdoor" scale="2" unit="°C" options="minmax,history:5">temperature</real>
<!-- minmax will be calculated/stored and the last 5 values kept in memory -->
````

### Triggered Actions
It's possible to issue commands if a condition is met, this is the same for int and real.
````xml
<real group="outdoor" name="temperature" unit="°C">
    <!-- Always do this on an update -->
    <cmd when="always">cmd:arg1,$</cmd> <!-- $ will be replaced with the new value -->
    <cmd>command_to_execute</cmd>
    <!-- Only do this if the value changed -->
    <cmd when="changed">command_to_execute</cmd>
    <!-- Based on a comparison -->
    <cmd when="above 10">command_to_execute</cmd>
    <cmd when="below 5">command_to_execute</cmd>
    <!-- Other options are
        between 4.2 and 5.6
        5 through 10
        not below/above 6.6
        equals 10 
    -->
    <!-- Based on stdev comparison, requires history to be used -->
    <cmd when="stdev below 0.1">dosomething</cmd>
</real>
````
>Note: The reason this works with 'above' instead of '>' is because this violates XML syntax...

### Parsing

By default, if the val is used inside a store, the string is just converted to int/real. But this behaviour can be overridden.
````xml
<real group="outdoor" name="temperature" unit="°C">
    <!-- Instead of storing the value received, first add one -->
    <op>i0=i0+1</op>
</real>
````

## Text

### Basics attributes
The most basic node, this is the absolute minimum required:
````xml
<text>name</text>
<!-- or -->
<text name="name">
    <!-- add subnodes -->
</text>
````
````xml
<text group="outdoor" >temperature</text>
````
The added attributes:
- `group` The group the val belongs to, this is a way to combine different parameters from the same sensor/location etc
- `def` or `default` The default value to use if something went wrong or to start with

### Advanced attributes

At the moment there's only one `options`.
This attribute can be a list of other options to enable.  

These are:
- `time` Store the timestamp of the last update
- `history:x` Keep the last x values in memory, nothing is done with this for now except stdev calculations
- `order:x` Just the position this val should get in the listing of the group

### Triggered Actions

It's possible to issue commands if a condition is met, this is the same for int and real.
````xml
<text group="outdoor" name="temperature" >
    <!-- Always do this on an update -->
    <cmd when="always">cmd:arg1,$</cmd> <!-- $ will be replaced with the new value --> 
    <cmd>command_to_execute</cmd>
    <!-- Only do this if the value changed -->
    <cmd when="changed">command_to_execute</cmd>
</text>
````

### Parsing

By default, if the val is used inside a store, the string is just stored. But this behaviour can be overridden.
````xml
<text group="outdoor" name="temperature" >
    <parser key="012">No valid data</parser> <!-- So if new value is '012', the stored value will actually be 'No valid data' -->
    <parser key="013">No known sample</parser>
    <!-- To cover all other values because otherwise an error is given -->
    <keep>.*</keep>    <!-- Keep means that all values that don't match the earlier keys, will be stored untouched -->
    <keep regex=".*"/> <!-- Same as previous line -->
    <keep/>            <!-- Same as previous line -->
</text>
````