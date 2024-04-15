# I²C

## Introduction
Purpose of this document is to explain how to interact with I²C devices.  
Like all modules in dcafs, you can get a list of available commands using ?, in this case `i2c:?`.
Usage will be explained using the aht20 device as an example.  
Note that there's as much overlap with paths as possible, which is an attempt to make it more intuitive
for those that have used paths before...

## Add a device

1. Start with checking if the device is actually present, `i2c:detect,x` where x is the number
of the bus the device is supposed to be on.
2. If found, use `i2c:adddevice,id,bus,address,scriptid` to add it to the settings.xml. Address is
in hex and 7bit. Script is the name of the xml script that holds the operations. So for example,
for the aht20, this could be `i2c:adddevice,aht10,1,0x38,aht20`. If there's no script yet with that
name, a starting one will be generated inside the i2cscripts subfolder.

## Building a script

After using the adddevice command or `i2c:addblank,aht20`, the following will be created.
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<i2cscript id="aht20">
    <opset id="cmdname" info="what it does"/>
</i2cscript>
```

For now there aren't any commands to fill in the script, this needs to be done manually.

At the moment there are five possible nodes to be used.
- **read** Read a given amount of bytes from a certain register
- **write** Writes bytes to the device
- **alter** Read a register alter the content based on a bitwise operation and value
- **math** Same as in paths, math operations on results of earlier read
- **store** Same as in paths, store read/math results 
- **wait** Delay the next step, can also be an attribute in the step to delay

Because math is explained in paths and store has a dedicated doc, these won't be explained here.

### General operation set

```xml
<opset id="status" info="Read the status register" bits="8"/>
```
To explain all the attributes:
- **id** The id of this set of operations
- **info** Short explanation of what the set does
- **bits** The default amount of bits to group when reading from the device

### Read operation

As an example, to read the status register from the aht20.
```xml
<opset id="status" info="Read the status register" bits="8">
	<read delay="0ms" reg="0x71" return="1"/> <!-- If it returns 0x18 then device is ready -->
</opset>
```
To explain the attributes of the read node:
- **delay** Delay before the read is executed, default is 0ms (can be omitted)
- **reg** The address of the register to read
- **return** The amount of bits groups to read, in this case 8 bits once or one byte.

Regarding bits/return, it's also possible to set the bits attribute inside the read node instead.
When it's in the opset node, it's considered the default for all read ops.
```xml
<opset id="status" info="Read the status register" >
	<read reg="0x71" bits="8" return="1"/> <!-- If it returns 0x18 then device is ready -->
</opset>
```

There's also another option for the bits/return, called 'bitsplit'.
```xml
<read delay="100ms" reg="0x71" bitsplit="8,20,20,8"/> 
```
Now dcafs determines the 'return' based on the total amount of bits. Read bytes will be split to
bitwise to match those numbers.
The above will result in 7 bytes being read but 4 integers being given for following operations. 