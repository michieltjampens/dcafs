## Pump

The first simulation is a 'simple' pump.  
Settings file & tm script can be found [here](examples/Pump.zip) extract this to the folder that contains the .jar.
The properties:
- Warms up 1°C/s while active and keeps heating up till it breaks (at a measly 50°C)
- Prefers to stay between about 10°C and 25°C
- Has a good cooler that cools 2°C/s but lacks any safety feature
- When idle the pump slowly heats up/cools down to ambient temperature
- Ambient will slowly change towards the pump temperature (and faster if far off)
- Ambient will try to get to 20°C
- Data format: pump:active/idle/broken,cooler:active/idle/broken,temp:xx.x,ambient:xx.x

Below is the settings file to start from (included in the earlier linked zip).
````xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<dcafs>
  <settings>
    <mode>normal</mode>
    <!-- Settings related to the telnet server -->
    <telnet port="23" title="DCAFS_pump_scenario"/>
    <!-- The simulation scripts, don't alter or look at it -->
    <taskmanager id="pump">tmscripts\pump.xml</taskmanager>
  </settings>
  <!-- This is part of the dummy, don't change it -->
  <streams>
    <!-- connection to the pump -->
    <stream id="pump" type="local">
    </stream>
  </streams>
</dcafs>
````
**The goals:**  
a. Process the data  
b. Make sure the cooler is activated and stopped on time  
c. Keep track of the times the cooler is activated and for how long

### Process the data

Open up two sessions again, one will be used to show incoming data and the other to run commands in.  
Let's look at the data using `raw:pump`, should be something like this:

> pump:idle,cooler:idle,temp:20.0,ambient:20.0

So the three states are idle,active,broken.

Next up is processing it, add a datapath with the id proc that has:
* editor node to alter the received states to numbers, and : to , for easier generic use
* generic to write those numbers to realvals
  * 2x integer (pump_state and pump_cooler)
  * 2x real (pump_temp,pump_ambient)

This can be partially done with the command `pf:add,pump,raw:pump,eeeeiirr` in which:
* pf:add is the command
* proc is the id
* raw:pump is the src
* eeeeiirr => Editor with 4 edits, generic with integer+integer+real+real

The result:
```xml
<path delimiter="," id="pump" src="raw:pump"> <!-- Note, these are always ordered -->
  <editor>
    <edit type="">.</edit>
    <edit type="">.</edit>
    <edit type="">.</edit>
    <edit type="">.</edit>
  </editor>
  <generic id="pump">
    <int index="1">pump_</int>
    <int index="2">pump_</int>
    <real index="3">pump_</real>
    <real index="4">pump_</real>
  </generic>
</path>
```
So now to fill it in:
````xml
<paths>
  <path delimiter="," id="pump" src="raw:pump" >
    <editor>
      <edit find=":" type="replace">,</edit> <!-- First replace the : with , -->
      <edit find="active" type="replace">1</edit> <!-- then replace the word active with 1 -->
      <edit find="idle" type="replace">0</edit>   <!-- then replace the word idle with 0 -->
      <edit find="broken" type="replace">-1</edit> <!-- then replace the word broken with -1 -->
    </editor>
    <generic id="pump">
      <int index="1">pump_state</int>
      <int index="3">pump_cooler</int>
      <real index="5">pump_temp</real>
      <real index="7">pump_ambient</real>
    </generic>
  </path>
</paths>
````