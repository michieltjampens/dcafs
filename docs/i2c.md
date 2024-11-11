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

This operation is used to read data from a specific register.

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

### Alter operation

This operation reads the content of a register and then applies logic operations on the content before returning
this altered content.

**Supported operands**
* `and` 0101 & 0011 = 0001 -> Bits remain the same if and with 1 or reset if 0.
* `or`  0101 | 0011 = 0111 -> If either bit is 1, the result is 1.
* `xor` 0101 | 0011 = 0110 -> If only one bit is 1, the result is 1.

**Examples**
Single operation can be by a single node without childnodes
```xml
	<i2cop id="enable_v0" info="Enable Vout0, internal Ref">
        <!-- Read 16bits or two byte from 0x1F and apply an 'and' -->
        <alter reg="0x1F" bits="16" operand="and">1111 000 111111111</alter>  
	</i2cop>
```

If you want to do multiple operations on the same register content before writing, this is done with multiple childnodes.
```xml
	<i2cop id="enable_v0" info="Enable Vout0, internal Ref">
		<alter reg="0x1F" bits="16"> <!-- Read 16 bits from 0x1F -->
			<and>1111 000 111111111</and> <!-- Apply an and with this value on the read register value -->
            <!-- Note, you can add spaces wherever is better readable -->
			 <or>0000 001 000000000</or> <!-- Apply or -->
		</alter>
	</i2cop>
```
Or this does the same thing, if you prefer to work with hexes.
```xml
	<i2cop id="enable_v0" info="Enable Vout0, internal Ref">
		<alter reg="0x1F" bits="16"> <!-- Read 16 bits from 0x1F -->
			<and>0xF1 0xFF</and> <!-- Apply an and with this value on the read register value -->
            <!-- Note, for now it needs to be split in bytes -->
			 <or>02h 00h</or> <!-- Apply or -->
            <!-- Note, both 0x?? and ??h are valid -->
		</alter>
	</i2cop>
```
> **Note:** If the content read from the register and the value after altering is the same, the writing won't be done.

### Write operation

This operation writes to a register.

```xml
    <!-- Used with i2c:deviceid,vout,millivolts,register -->
    <!-- Example: i2c:dac,vout0,2200,0x1C or i2c:dac,vout0,2200,28-->
	<i2cop id="vout0" info="Output 0 to 0-3300mV (iRef), give millivolts and register as param">
		<math>
			<op scale="0">i0=i0*(4095/3636)-4</op><!-- minus 4 because of offset -->
			<!-- Previous had scale = 0 so result is integer -->
			<op>i0=i0*16</op> <!-- Need to fill 12bit from MSB -->
		</math>
        <!-- i1 is assumed to be one byte -->
		<write reg="i1" bits="16">i0</write> <!-- DAC-0-DATA, i0 needs to know it's 2bytes -->
		<write reg="0x1F">0x12 0x01</write> <!-- Common config -->
		<write reg="0x15">0x10 0x00</write>
	</i2cop>
```