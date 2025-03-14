# Store

Complete guide to the 'store' functionality.

## Introduction

Store acts as the gateway between raw data and rtvals. It's main purpose is creating those rtvals according to 
user setup. So it's best to read the rtvals doc before this one. 

At the moment there are three slightly different uses of store
- Inside a stream node, to process simple stream data
- Inside a path node, to process stream data after filter/math/edit operations
- As the rtvals node, to create rtvals that aren't directly linked to stream/path data

As usual, an overview of all available commands can be obtained using `store:?`.

## Inside a stream node

### Simple direct data
Suppose there's stream that continuously outputs the humidity and nothing more.  
```xml
<stream id="meteo" type="tcp">
    <eol>crlf</eol>
    <address>localhost:4000</address>
</stream>
```
```
45
44
43
43
```
To create the store: `store:meteo,addint,humidity`. 
```xml
<stream id="meteo" type="tcp">
    <eol>crlf</eol>
    <address>localhost:4000</address>
    <store>
        <int unit="">humidity</int> <!-- unit isn't actually needed -->
    </store>
</stream>
```
This would result in the humidity being stored in an int with id `meteo_humidity`.

But the above would have actually been read like this:
```xml
<stream id="meteo" type="tcp">
    <eol>crlf</eol>
    <address>localhost:4000</address>
    <store group="meteo" map="false" db="" delimiter=","> <!-- group is taken from the id of the stream, ',' is the default delimiter -->
        <int index="0" unit="">humidity</int> <!-- Index is the position after split on the delimiter, 'i' is also ok -->
    </store>
</stream>
```
* Index is not required, as long as there's no doubt.  
If there are two or more elements after the split and the store contains the same amount of val's, no indexes need to be provided.
Whether to include indexes in that case depends on user preference.
* map will be explained in the section 'Multiline key:value pair data'
* db is for writing to databases and will be covered in 'extras'

### Delimited data

If instead the data would look like this  
hum:48  
The store would be created with (check `store:?` for syntax):

* To add an integer: `store:meteo,addint,humidity,1`  
* To alter the delimiter to : `store:meteo,delimiter,:`
* Set the unit: `store:meteo,alterval,humidity,unit,%`

```xml
<stream id="meteo" type="tcp">
    <eol>crlf</eol>
    <address>localhost:4000</address>
    <store group="meteo" delimiter=":"> <!-- group is taken from the id of the stream, ',' is the default delimiter -->
        <int i="1" unit="%">humidity</int> <!-- Index is the position after split on the delimiter -->
    </store>
</stream>
```
Or if not using index.
```xml
    <store group="meteo" delimiter=":">
        <ignore/> <!-- ignore the item at 0 -->
  <int unit="%">humidity</int> <!-- Use the item at index 1 for humidity -->
    </store>
```
### Multiline key:value pair data

If the data is multiline `key:value` pairs for example:  
hum:45
temp:22.5
winddir:320

> Note: The delimiter doesn't have to be ':', it can be any character sequence. But no regex (for now).

> Tip: This is probably a good time to use the 'repeat' function of the telnet interface. After using `store:meteo,!!`
> that will be used as prefix for any following command until `!!` is sent.

For this the `map` feature is used.
* Switch to map mode: `store:meteo,map,yes` (or true,1,high)
* Alter the delimiter: `store:meteo,delimiter,:`
* Add the humidity: `store:meteo,addint,humidity,hum`
* Set the unit: `store:meteo,alterval,humidity,unit,%`
* Add the temperature `store:meteo,addreal,temperature,temp`
* Set the unit: `store:meteo,alterval,temperature,unit,°C`
* Add the winddirection: `store:meteo,addint,winddir,winddir`
* Set the unit: `store:meteo,alterval,winddir,unit,°`

```xml
<stream id="meteo" type="tcp">
    <eol>crlf</eol>
    <address>localhost:4000</address>
    <store map="true" group="meteo" delimiter=":"> <!-- Pairs are separated with ':' -->
        <int key="hum" unit="%">humidity</int>  <!-- if the key of the pair is hum, store the value in humidity -->
        <real key="temp" unit="°C">temperature</real>
        <int key="winddir" unit="°">winddir</int>
    </store>
</stream>
```
The order of the val's doesn't have to match the sequence in which they arrive.

### Extras

**Writing to a database**

To write values to a database the `db` attribute is used.  
The format is db="dbid:dbtable" multiple dbid's can be provided separated with a ','.

What this does, is trigger an insert into the specified table(s) when the last val in the store is updated.  
This is why it might be important for key:pair data that the last pair received is also the last in the store.  
Otherwise old and new data might get mixed.

To give a simple example:
```xml
<dcafs>
    <!-- doesn't include the telnet node -->
    <databases>
        <sqlite id="meteo" path="db\meteodata.sqlite">
          <table name="meteo"> <!-- creates a table named meteo -->
            <utcnow>timestamp</utcnow>
            <integer>humidity</integer>
            <integer>winddir</integer>
            <real>temperature</real>
          </table>
        </sqlite>
    </databases>
    <streams>
        <stream id="meteo" type="tcp">
            <eol>crlf</eol>
            <address>localhost:4000</address>
            <store map="true" db="meteo:meteo" delimiter=":"> 
                <int key="hum" unit="%">humidity</int> 
                <real key="temp" unit="°C">temperature</real>
                <int key="winddir" unit="°">winddir</int> <!-- Receiving this key will trigger the table insert -->
            </store>
        </stream>
    </streams>
</dcafs>
```
**Resetting values**

There are multiple ways to deal with rtvals if a stream is no longer sending data.
- Check the timestamp to see if it's still relevant
- Reset to default when the stream is idle

For the second one, the `idlereset` attribute is added. Setting this to `idlereset="true"` will cause the store to
update the vals to their default value, if those are set...

```xml

<stream id="meteo" type="tcp">
  <eol>crlf</eol>
  <ttl>1m</ttl> <!-- TTL needs to be set so that it is known when the stream is idle -->
  <address>localhost:4000</address>
  <store idlereset="true" map="true" group="meteo" delimiter=":"> <!-- idlereset to true -->
    <int key="hum" def="-1" unit="%">humidity</int>  <!-- def value filled in -->
    <real key="temp" def="-999" unit="°C">temperature</real>
    <int key="winddir" def="-1" unit="°">winddir</int>
  </store>
</stream>
```

**Calculations**

It's possible to do calculations with the values in a store.
```xml
<store>
      <real group="o1" i="1" unit="V">voltage</real>
      <real group="o1" i="2" unit="mA">current</real>
      <!-- calculate the product of the voltage and current to get the power in watt -->
      <!-- Instead of 'i' (for input) the letter 'o' is used -->  
      <real group="o1" o="(o1_voltage*o1_current)/1000" unit="W">power</real> 
</store>
```

## Inside a path node

The functionality is almost the same as inside a path node, the difference is mainly in the commands.
To refer to a store inside a path: `pf:pathid,store,cmd,value(s)`.  

**Restrictions**
- Because a store doesn't have an id, you can't refer to a specific one.
- All commands will be applied to the last store in the path. If the last node isn't a store, a new one will be created.
- The `idlereset` attribute can't be used because there's no idle check for a path (yet). Workaround is adding 
  `rtvals:resetgroup,id` as a cmd in a stream to execute if it's idle. 