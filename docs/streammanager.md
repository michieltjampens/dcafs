# Stream Manager

This module is responsible for managing the various streams.

* Attempt to reconnect when the connection is lost.
* Provide commands to add, remove, alter streams or request data from them.

## Added telnet features

* `streamid:data` Send the data to the stream with the default eol appended.
* `Sx:data` or `sx:data` Send data to the stream with index x (check with `ss`), with default eol appended.

> **Note:** When using CTRL+S instead of enter, no eol will be appended!

* `Hx:hexdata` Send binary data to the stream with index x (check with `ss`), no eol appended.
  * `H1:0x24` Will send $ to the stream.
  * `H1:0x24 0x24` or `H1:0x24,0x24` will send $$ to the stream.
* It's possible to send binary and ascii data in a single command:
  * `S1:\h(0x24)=dollarsign`

## Commands overview

**Add new streams**

* `ss:addtcp,id,ip:port` Add a TCP stream to xml and try to connect
* `ss:addtcpserver,id,port` Add a TCP server stream to xml and start it
* `ss:addudp,id,ip:port` Add a UDP stream to xml and try to connect
* `ss:addserial,id,port,baudrate` Add a serial stream to xml and try to connect
* `ss:addlocal,id,source` Add a internal stream that handles internal data

**Info about all streams**

* `ss` Get a list of all streams with indexes for sending data
* `ss:buffers` Get confirm buffers.
* `ss:status` Get streamlist.
* `ss:requests` Get an overview of all the data requests held by the streams

**Alter the stream settings**

* `ss:id,ttl,value` Alter the ttl
* `ss:id,eol,value` Alter the eol string
* `ss:id,baudrate,value` Alter the baudrate of a serial/modbus stream
* `ss:id,addwrite,when,data` Add a triggered write, possible when are hello (stream opened) and wakeup (stream idle)
* `ss:id,addcmd,when,data` Add a triggered cmd, options for 'when' are open,idle,!idle,close
* `ss:id,echo,on/off` Sets if the data received on this stream will be returned to sender

**Route data from or to a stream**

* `ss:streamid,request,requestcmd` Requestcmd is the cmd you'd use in telnet to request the data, do this in name of the
  stream.
* `ss:id1,tunnel,id2` Data is interchanged between the streams with the given id's
* `ss:id,send,data(,reply)` Send the given data to the id with optional reply

## XML

The absolute minimum:

```xml

<streams>
    <!-- TCP client -->
    <stream id="sensor" type="tcp"> <!-- or tcpclient, udp/udpclient, modbus  -->
        <address>localhost:4004</address>
    </stream>
    <!-- TCP server -->
    <stream id="sensor" type="tcpserver"> <!-- or udpserver  -->
        <port>4004</port>
    </stream>
    <!-- Serial port -->
    <stream id="sensor" type="serial"> <!-- Or modbus -->
        <port>ttymxc0</port>
    </stream>
</streams>
```

That minimum actually uses the default for a lot of settings.

```xml

<streams>
    <!-- TCP client -->
    <stream id="sensor" type="tcp">
        <address>localhost:4004</address>
        <eol>crlf</eol> <!-- The eol of a message being carriage return and line feed -->
        <echo>no</echo> <!-- Don't echo the data back to the source -->
        <log>true</log> <!-- Log all the raw data -->
        <prefixorigin>no</prefixorigin> <!-- Prepend the id of the stream in front of raw data -->
        <ttl>-1</ttl> <!-- No ttl specified, normal format for example 5m (for 5 minutes) or 10s etc -->
        <label>none</label> <!-- pretty much legacy at this point, can be changed to 'system' to process commands -->
    </stream>
    <!-- TCP client -->
    <stream id="sensor" type="serial">
        <port>ttymxc0</port>
        <!-- all of the above -->
        <serialsetings>19200,8,1,none
        </serialsetings> <!-- Baudrate,databits(5-8),stopbits(1-2),parity(even,odd,stick) -->
    </stream>
</streams>
```

Beyond that, there are some extras:

* Execute cmds when the stream ic connected,disconnected,idle or no longer idle.
* Write something to the stream to say hello or wake it up if idle.
* Store received data after parsing if further alterations aren't needed.

```xml

<stream id="sensor" type="tcp">
  <triggered>  <!-- It's not mandatory to use this parent node, cmd and write can be below stream directly -->
    <cmd when="open">raw:sensor2</cmd>   <!-- Execute a request for data from sensor 2 when this connects -->
    <!-- Other options for when:
            * close - if the connection is closed
            * idle  - if the time passed since last data is longer than ttl
            * !idle - if the stream is no longer idle 
    -->
    <write when="hello">Welcome?</write> <!-- Write Welcome? to the stream on connection -->
    <!-- Other option of when is 'wakeup' for when the stream is idle -->
  </triggered>
    <store group="outside">
        <!-- Check the store docs for info, it's the same -->
    </store>
</stream>
```