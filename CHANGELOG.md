## Changelog
Note: Version numbering: x.y.z 
  -> x goes up with major breaking api changes
  -> Y goes up with major addition/changes
  -> z goes up for minor additions and bugfixes

### To do/fix
- back up path for sqlite db etc? for when sd is missing/full...

## 3.1.0 (wip)

### Drawio (Still experimental)
- Added flagval
    - Active: Flagblock that allows set/clear/toggle of a flagval
    - Reactive: Flagval that allows arrows that depend on the effect the update had (h->h,l->l,l->h,h->l)
        - Can connect a conditionblock to a flagval using 'update', this adds a reference to the flagval in that
          conditionblock that triggers the update with the result of the condition.
        - Made it so a conditionblock that is connected to a flagval encapsulates it and takes over its 'next'
- Added GPIO, pretending to be flagval. Input can trigger on rising and falling edge, just like regular flag.
  - This involved adding extra classes, xml version still uses 'old' ones.
- Controlblock can now be used for 'any' taskblock instead of only origin block and allows `start` as alternative to
  `trigger`.
- Conditionblock received a new arrow with label 'update', when connected to a flagval, it will update the state
  according to the result.
- If no label is given to an arrow connecting two shapes, `next` is given as default.
- Fixed, if controlblock trigger target isn't created by a task, meaning it's just an alternative path, the id was
  taken from the next target. Now it get [T]|0 and so on appended to controlblock id.
- The method that updates the dcafsid's now first checks to see if the node already has that id. No write is done if
  nothing is changed.
- Added support for the property `dcafslabel` on arrows to allow visual styles without visible label.
- Added `retrigger` property to `delayblock`, options are restart,cancel,ignore.
- Allow the target/source in writer/reader block to be split in two properties target+targettype and source+sourcetype.
This is done to allow only showing id on the block.
- Intervalblock and delay block now use the alternative route when they get cancelled/stopped.
- Added referring to numericalvals (real,integer,boolean) in log blocks {group_name}

### Database Manager
- Added option to refer to a flag to determine if inserts are allow in a table or not.
  If the flagval isn't defined anywhere yet, it will created and added to the global pool.
```xml
      <table allowinsert="sensor_doinserts" name="data">
        <real>temp</real>
        <real>other</real>
      </table>
```

### Math parser
- Can now process `a+1<b` allows more advanced expressions in conditionblock.

### Fixes
- Datagram always added a : even without arguments, changed that. This was reason for exit no longer working in telnet.
- Taskmanagerpool watcher gave an error on linux because it wasn't using an absolute path.

## 3.0.0 (15/05/2025)

- Added EXPERIMENTAL support for reading draw.io files for configuration as alternative to xml.
- Rewritten TaskManager and PathForward to move to a factory making small functional parts that link together.
- Rewritten RealtimeValues, to make them leaner by splitting of 'extras' and implement the new logic parser.
- Added rawworker for bulk processing of data using multithreading and staged processing.
- Math and logic parsing was all over the codebase, centralized in evalcore and streamlined the layout. Logic got a
  major rewrite to allow for 'lazy evaluation ' (aka shortcircuit'ing).
- A lot of QoL fixes/changes and the cleanup based on Codacy feedback.
- Result is a diff of "211 files changed, 20413 insertions(+), 20338 deletions(-)"
- Major version bump because a lot has changed under the hood and major bump was overdue anyway.

### General

- Fixed 210 issues found by [Codacy](https://app.codacy.com/gh/michieltjampens/dcafs/dashboard). (none actually
  critical)
- Refactored **a lot**
- Cleaned up the full code base.

### Draw io integration

- Parser converts shapes (object in xml) to Java Objects containing properties and links.
- Parser traverses tabs but doesn't retain info on where it came from.
- Added file watcher so it's possible to have dcafs auto reload (disabled for now).
- Started making it possible to configure certain parts using drawio.
  - Task manager blocks have a drawio equivalent, added some 'virtual' ones to simplify common
    actions. Can create a taskmanager using a drawio,
    - Origin blocks have two properties that determine auto start, both default to no.
      - `autostart` starts the task on bootup or reload (yes/no,true/false)
      - `shutdownhook` starts the task when the system is shutting down (useful for cleanup) (yes/no,true/false)
  - Rtvals is work in progress, realval is mostly done. Can represent and generate. Not all
    possible iterations tested (it's rather flexible).
- Initial steps on annotating the drawio file. After parsing, the shapes get their dcafs id
  added as a property. This makes annotating easier as it no longer requires a lookup table. This does
  create infinite loop with autoreload so turned it off for now.
  - Proof of concept involved having the origin block show the amount of runs, updated every 30s.

### Task Manager

- Complete rewrite
- Modular instead of the earlier monolith, that's the last legacy monolith torn down.
- Most functionality is retained.
- Added retry/while nodes to make those more flexible and potentially easier to understand.
  Difference is that retry is done till condition is met (up to retries) and while repeats till condition is no longer
  met (up to maxruns).
  Both have a short and long form.
```xml

<retry retries="5" onfail="stop"> <!-- onfail can be 'continue', then the rest of the task is done -->
  <stream to="Board">C2;C4</stream>
  <delay>20s</delay>
  <req>{pump_warmup} equals 1</req> <!-- if this fails, the block is retried -->
</retry>

<while interval="5s" maxruns="6">{gas_temp} above 20</while>
```

### Stream manager
- If a stream is disconnected messages are no longer logged every attempt
- Removed 'prefixorigin' option, the `>>prefixid` in telnet is probably the only use case and better.
- `Sx:data` or `streamid:data` cmd for sending data can now mix binary and ascii, so `S1:\h(0x24)=dollarsign` is now
  valid.
- `ss:buffers` command gave an error when no buffers were in use, replaced with reply that no buffers are in use.
- The triggered actions (write,cmd) can now be in the <triggered> node instead of directly in <stream> node. Purely
  a visual change. This is now the default when adding through commands.
- `ss:id1,tunnel,id2` This command is now persistent.
- `ss:id,link,source` changed to `ss:id;request,requestcmd` to make the function clearer, now also persistent.
- Fixed, adding tcpserver, wasn't added to list because of nullpointer.Also added it to the help.
- Command help now lists how to add the servers.
- Fixed, adding a udp server, wasn't added to xml nor the manager, just started.
- Changed status message of the udp client to show it's send only.
- TCP server port was in an address node instead of port
- Fixed, The time since last data received wasn't determined for serial with empty eol
- Fixed `ss:id,eol,` for clearing eol.
- Now gives an error when trying to connect to an ip:port or serial port already in use.

### Telnet

- It's now possible to use `>>es` and `>>ts` or `>>ds` at the same time.
- Spaces are added as padding to line up `>>es` result because it's variable length (up to 8).
- Changed color of `>>es` to cyan so the output doesn't look like ts/ds (which is in orange).
- `>>?` àdded for a list and info on the available commands.
- Added `>>prefixid` does the same as `>>prefix` does/did but made more sense to show it prefixed the id.
- Added `telnet:writable,id` allows a telnet session to request the writable of another session. Instead
  of the id, * is valid for the first session that isn't the current one.
- Added ` >alt` suffix for commands issued through telnet, this will use the writable of the other
  session in the datagram so the result is shown there instead.

### Store
- Breaking, Changed the order of the add command so that group is no longer optional.
- Calculations should be slightly more performant because of decreased parsing.
- Added command to alter idlereset of a store.
- Fixed, Rtvals that are calculated are now also reset if idlereset is true.
- Fixed, Store that use map can now also use calculated values.

### RealtimeValues

- Rewrote it to with the intention to make it leaner and more flexible.  
  I thought that the various val's had become to 'bloated', especially RealVal and IntegerVal. They all had
  the code to do a lot of things that weren't used in 99% of the cases. So I set out to make
  them 'simpler', not sure if I failed or not... On the surface they probably are
  (as in, the code is a lot shorter), but they now are much more a reactive component
  within dcafs and thus have outgrown their rule of 'short term memory'.

  * RealVal, IntegerVal (aka real and integer container)
    * Optional pipeline in update method (where new value replaces old)
      * Precheck the incoming data
        * Allow both simple logic or using references to other ones
        * Set `cmds` to execute on pass or fail, those can reference both new and old value
        * Propagate the result or always allow the next step
      * Math expression(s) applied
        * Alter received data before it's applied
        * Receives both old and new value
        * Refer to other vals if needed both is input and output (as in directly trigger update of another)
        * Not needed to actually use incoming data
        * Allowed to overwrite either memory slot, new value slot will be applied.
      * Post Check of the calculated data
        * Same as pre check but now it can additionally respond to calculated values. This means math could calculate
          offset between new and old and this can be checked.
    * No need to use the full pipeline, can just 'activate' the bits needed.
    * **Aggregator** variant of both classes that acts as a collection, 'update' insert in the collection and requesting
      the
      value returns the results of a builtin reducer function. (think average, variance and so on)
    * **Symbiote** variant to hide the complexity of derived values, this 'takes over' a realval or integerval and
      propagates
      any update that would be done on it to a collection of vals that are derived from it. Think min,max and so on.
  ```xml
   <group id="test">
      <flag def="true" name="flag"/>
      <real def="0" scale="3" name="pressure" unit="Bar">
        <derived def="0" reducer="variance" suffix="variance" window="50">
          <!-- i0 refers to the received value and i1 would be the 'old' one -->
          <derived def="0" math="i0/(50-1)" suffix="sample"/>
          <!-- final name: pressure_variance_sample -->
          <derived def="0" mainsuffix="population_variance" math="i0/50"/>
          <!-- final name: pressure_population_variance -->
          <derived builtin="sqrt" def="0" mainsuffix="stdev"/>
          <!-- final name: pressure_stdev -->
        </derived>
        <derived builtin="max" def="0" suffix="max"/>
        <!-- final name: pressure_max -->
        <derived builtin="min" def="0"/>
        <!-- final name: pressure_min because no suffix or name defined -->
      </real>
  </group>
  ```

* FlagVal (aka boolean container)
  * Can have both `cmd` as response as triggering update of other val.
  * Four distinct trigger options:
    * Raising edge (false->true)
    * Falling edge (true->false)
    * Stay high (true->true)
    * Stay low (false->false)
  * Meaning these can trigger an update of an IntegerVal that is set to 'counter' mode. (that just means it ignores the
    input and adds one to the current value);
  * No logic(besides discerning triggers) or math involved in this because didn't seem useful.
* TextVal (ake String container )
  * Nothing really changed to this, remains a simple container.


- Fixed, `ss:id,reloadstore` didn't properly reload if a map was used.
- Fixed, `*v:id,update,value` wasn't looking at * but to id instead

### GIS
- No longer possible to use duplicate id's for waypoints or geoquads.

### Database Manager
- Fixed, tables weren't read from sqlite db.
- MariaDB, can add a node <timeprecision> to set the amount of digits in the seconds default 0.
- Server connect is now threaded, 5 consecutive fails trigger a query dump to csv. Logging is also rated.
- Rollover now uses the same format as everywhere else.
- Rewrote how reloading of the database works, now the instance is retained instead of a 'clean slate'

### Paths

- Rewrote PathForward to be similar in logic as taskmanager.
- Targets are stored between reloads, so updates are 'live'.
- Editor, indexreplace used an inmutable list so didn't work...
- Editor, added some alternives to the types to maybe make it more straightforward rex -> regex and so on.
- Updated `help:editor` to use node tags instead of type attribute.
- Fixed, response to `p:id,new...` is how it should be again.
- Fixed, src was cleared when reading from xml and the path is in the settings.xml
- Added `pf:pathid,switchsrc,newsrc` to switch the src for a path at runtime, can be used to implement redundancy.
  This won't alter the xml.
- Reloading a path now triggers a check if the db tables that have missing rtvals now can find them.
- Filter now allows the use of 'or' and 'and' and ,like the editor, it's now possible to
  use the tag name instead of the type attr.

```xml

<filter>
  <rule type="contains">1000</rule>
  <or/> <!-- or and, if none is mentioned, defaults to 'and' -->
  <contains>-6</contains>
  <contains>1000 OR -6</contains> <!-- Does the same as the three lines above -->
</filter>
```

```xml
<if contains="1001 OR -4"> <!-- Is also possible now -->
</if>
```

- Added the `return` node, mainly useful in combination with the `if`
  - For now only one behavior will add others in the future
  - Turns two consecutive if's into an if/else if

```xml

<path>
  <if contains="1001">
    <!-- do stuff -->
    <return/> <!-- After arriving here, leave the path early -->
  </if>
  <if contains="-4"> <!-- Is also possible now -->
    <!-- Do other stuff -->
  </if>
</path>
``` 

### Evalcore (package containing Logic and Math parser)

- Moved all code related to both logic and math parser to their own package.
- Rewrote the layout of both parsers, now the code is no longer split over modules according to requirements
  but one single central one.
- Each has a 'fab', LogicFab and MathFab that fabricate evaluators MathEvaluator and LogicEvaluator.
- Now the chaining of Math expressions is part of the class instead of needing a separate one. But it's not like an
  Evaluator contain multiple expressions (for now).
- Logic parser was rewritten to allow more complicated expressions (brackets and negation) and the parsing
  now attempts 'lazy evaluation' or 'shortcircuit' when before it just evaluated everything. No nesting of brackets
  (yet) though. That lazy bit is well lazy, it splits the expression on and/or (accounting for brackets) and evaluates
  the one that might lead to a solution first. So e.g. (a<b && c>d)||e<=f ,it will do e<=f first.

### Raw worker

- Added worker to reprocess raw log file if it doesn't require realtime data  (except in store)
- How it works:
  - Reads data from a single day
  - Each line gets a counter prefix for sorting it later
  - Starts stage 1 processing (these are things that can be multithreaded)
  - Data is sorted to match the original sequence
  - Stage two is done with the sorted data (this is the store to DB step)
  - Read the next day

````xml

<rawworker filepath="todo">
  <stage1>
    <!-- Path forwards except store -->
  </stage1>
  <stage2>
    <!-- Path forwards including store -->
  </stage2>
</rawworker>
````

## 2.13.0 (20/02/2025)
### Updated dependencies
- Netty 4.1.112->4.1.118
- Apache commons 3.15.0 -> 3.17.0
- MSSQL 12.6.3.jre11 -> 12.8.1.jre11
- diozero 1.4.0 -> 1.4.1
- mail to jakarta mail 2.0.1

### Minor changes
- Will now warn when a store uses an index twice.
- Store, now error is logged if the table used by a store doesn't exists.
- Parsing to a boolean now considers all values except 0 as true.

### Minor fixes
- Sending data to a stream that contains a digit in id didn't work with id:data.
- Default false options for flag contained 1 instead of 0.

### GIS
- Added GeoQuads, way to monitor rectangular areas and trigger cmds on entering or leaving it.

### I2C
- Improved the alter node:
  - Can now work with multi-byte registers
  - Can use both hex and binary values
  - Can apply multiple operations in succession after a read
  - Example to show all the new stuff in one go.
```xml
	<i2cop id="enable_it" info="Enable Vout0, internal Ref">
		<alter reg="0x1F" bits="16"> <!-- We'll alter 0x1F that contains 16 bits -->
			<and>1111 000 111111111</and> <!-- Apply an and with this value on the read register value -->
            <!-- Note, you can add spaces wherever is better readable -->
			 <or>0000 001 000000000</or> <!-- Apply or -->
		</alter>
	</i2cop>
```
- Improved the write node, so now you can pass arguments to a set which can be hex.
```xml
    <!-- Used with i2c:deviceid,vout,millivolts,register -->
    <!-- Example: i2c:dac,vout,2200,0x1C-->
	<i2cop id="vout0" info="Output 0 to 0-3300mV (iRef), give millivolts as param">
		<math>
			<op scale="0">i0=i0*(4095/3636)-4</op><!-- minus 4 because of offset -->
			<!-- Previous had scale = 0 so result is integer -->
			<op>i0=i0*16</op> <!-- Need to fill 12bit from MSB -->
		</math>
		<write reg="i1" bits="16">i0</write> <!-- DAC-0-DATA, i0 needs to know it's 2bytes -->
		<write reg="0x1F">0x12 0x01</write> <!-- Common config -->
		<write reg="0x15">0x10 0x00</write>
	</i2cop>
```
- Updated the manual with the new functionality
- I2cUart can now alter the baudrate

## 2.12.0 (28/07/2024)

### Updated dependencies
- Netty 4.1.111->4.1.112
- Apache commons 3.14.0 -> 3.15.0
- MSSQL 12.6.2.jre11 -> 12.6.3.jre11

### Minor changes
- Telnet, rtvals cmd now has alternating color for the val listing
- admin:phypower now supports rtl chips and checks if root privilege first.
- Taskmanagers can now be under dcafs node instead of settings or taskmanagers
- Removed IssuePool, wasn't used anyway.

### Fixes
- i2c:id,xml didn't reload if the script already exists

### MQTT
- Increased retry interval to 25s because lower causes 'already in progress' error.
- Ttl can be set broker wide and the status message gives more info.
- Added `mqtt:brokerid,debug` and `mqtt:brokerid,debug,true/false` to read and set debugging. For now the only
difference with enabled is log messages if data is received or send.
- Result of `mqtt:brokerid,stores` is now sorted on topic.

### Paths
- Changed how 'if' nodes work to be more in line with regular programming. Successive nodes will be executing
  instead of previously more like switch case.
- Added 'case' node to mimic how if used to work.
- Rewrote how the steps are interconnecting.
- Added some filter/if options
  - minitems : minimum amount of items after split
  - maxitems : maximum amount of items after split
  - !contains: doesn't contain the given data
- Added aliases for some filters
  - !start alias for notstart
  - !contains alias for contains
  - includes alias for contains
  
### Rtvals
- Improved dynamic units, but breaking change:
  - Now allows for starting with any of the units instead of only base.
  - Can now go up or down a unit instead of only up.
  - Replaced attribute 'from' with max/till, should be a bit clearer (this is the breaking part).
  - Can now have a scale for each individual unit.
  - It's possible to repeat units to add different scale settings.
  - Div is set globally or per unit.
  - Attribute digits and scale are the same thing, use according to preference.
```xml
<unit base="Wh" div="1000"> <!-- default divider/multiplier is 1000 -->
  <level max="100"  scale="2" >mWh</level> <!-- Up to 100mWh use two digits, no div done to next step -->
  <level max="1000" digits="1" >mWh</level> <!-- From 100 till 1000mWh use one digit -->
  <level max="1500" scale="2" >Wh</level>
  <level            scale="3" >kWh</level>
</unit>
```
- Fixed, reloading a store first removed the rtvals defined in it from the general pool. However this was also done if
that rtval was defined in the general node. Now those aren't removed anymore.
- After a `rtvals:reload`, the paths and databases will also be reloaded.

### DBM
- When editing a sqlite db in another program, this causes the current connection to break (errorcode 8). Reconnecting resolves this, so
now dcafs will try this. If this fails, query content is dumped to a file tablename_dump.csv in the same folder as the
sqlite.
- Fixed, Mariadb doesn't like the use of ", replaced with `. Which also works for sqlite.
- Fixed, MariaDB returns a different error code on batch errors, now properly handled
- Fixed, Localnow column wasn't local when send to sql server.
- `dbm:prep` existed but wasn't documented. Gives total procesed queries count.
- Added `dbm:reloadall` to reload all databases in one go
- Added `dbm:clearerrors` to clear the error counters

## 2.11.0 (29/06/2024)
Earlier than planned because of a telnet issue. Should probably start working with branches...

- Will now log an error if a commandable with same id is added. For example, taskmanager and stream share an id.
- Fixed, editor just kept working with the data of the previous step if a step failed, now aborts.
- Fixed, tablename's can't start with a number if not surrounded by " in queries.

### Telnet
- Fixed, telnet start cmd was executed but result not requested nor worked well with default id.
- Added `id?` cmd to request current id.
- Added `es`, adds the elapsed time since last received data.
- Fixed, if the result is 25 lines or longer the output was colored by line. This shouldn't be done
if it contains telnet codes (fe. st reply).
- Fixed, if dcafs starts without settings.xml, path for tinylog remained null. Because of this, telnet clients
couldn't connect.

### MQTT
- Fixed, addbroker cmd didn't write to xml.
- Removed use of label.
- If a topic contains \ it will be replaced with /
- Added cmd mqtt:brokerid,generate,topic this will generate store entries for messages received
that match the topic (use wildcard!). Data received will determine data type: int,real or txt.
- Breaking, removed the use of defaulttopic. Added more possible confusion than actual use.
- Can use a group node in store node like global rtvals.
- Added `mqtt:brokerid,stores` to get more info the the sub to val links.

### Valstore
- db attribute now allows multiple tables and multiple id:table sets. Can be handy if a single store
needs to write to multiple tables.Mo
  - For example db="db1:table1,table2" or db="db1:table1;db2:table2"

### Rtvals
- Fixed, realval couldn't get an op via node.
- Vals now share the code that reads group/name and name can be attr,content or node.
- Unit node can now be used as default unit/scale setup for vals.
```xml
<rtvals>
    <!-- If the name of the val matches the nameregex, the unit/scale will be applied -->
    <!-- This won't overwrite unit/scale settings --> 
    <unit base="°C" nameregex="temp.*" scale="1" />
    <unit base="%"  nameregex="hum.*"  scale="1" />
</rtvals>
```
## 2.10.0 (14/06/24)

This release is mainly a rewrite (again) of the i²c code and expanding on the gpio code. Next major point is adding
more feedback in the status report for possible issues.

- (If) dcafs runs with root permissions files created are only writable by root. This is not needed,
so now new xml files are created with 'others write' permission. But owner remains.
- Changed default telnet port to 2323, because 23 requires root on linux.
- Happened that tinylog stopped writing for unknown reason, added the age of the daily raw 
file to the status report. Default is one hour, can be changed with maxrawage node in settings.
- Base path of tinylog can be set in xml using tinylog node in the settings node.
- By default, dcafs now runs a global check every hour to see if there are (possibly unreported issues). Interval can
be altered or set to 0s to disable it. Only works if email/matrix is set up, because no use checking if can't be reported.
 ```xml
  <settings>
    <!-- Settings related to the telnet server -->
    <telnet port="2323" title="DCAFS">
      <textcolor>lightgray</textcolor>
    </telnet>
    <maxrawage>10m</maxrawage> <!-- Max age of raw data to consider good -->
    <tinylog>/mnt/sd</tinylog> <!-- Store logs on sd card -->
    <statuscheck>
      <interval>1h</interval> <!-- time interval to run a general check -->
      <email>admin</email> <!-- id of email recipient -->
      <matrix>playground</matrix> <!-- id of the matrix room -->
    </statuscheck>
  </settings>
```
- Status report has been updated to include some more info and unified appearance.
- Fixed, matrix:roomid,txt/say required the room id to have matrix prepended.

### Telnet
- CTRL+s can be used to send things to streams without eol sequence.
- Using the ESC key will actually send and ESC.
- Fixed, rewrote part of the cli code to make it less error prone. Things tended to break
when the length of the text equals the size of the buffer and edits are done.
- Will show message on opening session if raw data is abnormally old.

### Interrupts
- Can now add gpio if the board isn't recognised by diozero.
- Added `admin:chexkpins` to check which gpio are recognized
- Breaking, changed the xml format to fit this extra info
```xml
<gpios chiplines="32">
  <!-- defining a gpio if board is unknown -->
  <gpio name="GPIO2_A3" chip="2" line="4">
    <physical header="X1" pin="13"/>
    <modes>digital_input</modes>
    <interrupt edge="falling">
      <cmd>info:hello?</cmd>
    </interrupt>
  </gpio>
  <!-- defining a gpio board is known -->
  <gpio name="GPIO2_A4"> <!-- name needs to match -->
    <interrupt edge="falling">
      <cmd>info:hello 2?</cmd>
    </interrupt>
  </gpio>
</gpios>
```
- Added more actions to do on an interrupt: 
  - counter: counts the amount of pulses detected
  - frequency: calculates the frequency of the pulses, based on moving average or single interval
  - period: measures the length of a pulse of minimum length of 500µs with 100µs accuracy.
```xml
    <gpio chip="2" line="3" name="GPIO2_A3">
      <physical header="X1" pin="13"/>
      <modes>digital_input</modes>
      <interrupt edge="rising">
	    <counter>aws_rain</counter> <!-- count the amount of pulses, stores it in a int rtval -->
		<frequency samples="5">aws_frequency</frequency> <!-- Calculates the frequency of the pulses with moving average -->
      </interrupt>
    </gpio>
```
- Because these update an rtval, the triggers on said rtval can be used.

### I2C
- Added attribute 'datatype' to the write op. Allows to change datatype of the content to dec or ascii instead
of the default hex.
- Added attribute 'addsize' to write node, default false. If true appends the amount of bytes to send
after the register.
- Debug state is now carried over on reload.
- When adding devices, now there's a check for duplicate address&bus.

### I2C Uart
- Basic implementation to test https://github.com/michieltjampens/i2c_uart_stm32
- For now, only sending and receiving data works. 
```xml
  <i2c>
    <bus controller="1">
      <uart id="uart1">        
        <address>0x1b</address>
		<eol>crlf</eol>
		<irq>GPIO2_A3</irq>
		<serialsettings>38400,8,1,none</serialsettings>
      </uart>
	  <uart id="uart2">        
        <address>0x2b</address>
		<eol>crlf</eol>
		<irq>GPIO2_A3</irq>
		<serialsettings>38400,8,1,none</serialsettings>
      </uart>
    </bus>
  </i2c>
```


### DBM
- Database can now have the node `maxinsertage` which determines what the max time since the last insert is. When beyond
this, the status will turn red in the `st` cmd result. 
- When the amount of queries buffered is higher than the max, this will also turn red.

## 2.9.1 (05/05/2024)

- Telnet normally echo's a backspace. But some term programs send 0x08 instead or don't apply backspace.
So instead of echo 0x08,0x20,0x08 is returned instead. This moves the cursor left prints a space and moves it left again.

### Tasks
- Task with output stream allows for waiting for reply. The window could be altered with replywindow. But this
changed attempts to one. Now the attribute allows for attempts to be specified replywindow="5s,3" meaning 3 attempts wih 5s window.
If no amount is given, it's set to 1.
- Fixed, args given when running a cmd included the command instead of actual first arg.
- Added {mint2s16b:operation} to calculate 2s complement of a 16b operation.

### Paths
- Fixed, pf:id,delete now works again. Was changed to pf:id,delete,all/last.
- Fixed, pf:id,reload and pf:id,list weren't altered properly from pf:reload,id
- Fixed, if as first step wasn't working properly
- Paths will now be reloaded after changing them (instead of having to do manually).
- pf:id,list now shows db the store's are writing to and textcolor is now default instead of all green.
- Fixed, delimiter in a edit node wasn't processed for potential chars like \t etc.
- Added cmd to request the data of a step in a path, (similar to debug) pf:pathid,stepid

## 2.9.0 (27/04/2024)

### Streams
- Fixed, changing the ttl of a stream no longer reloads it, applies it instead.
- Can now send data to a stream using the id instead of Sy. 
- Can now send ESC to a stream using the esc key (eol won't be appended)
- SerialStream, Added the attribute flush to ttl node. If true, this means that any data in the buffer will be forwarded
as if the eol was received if the ttl has passed. This can be useful for situations in which a device sends a prompt without eol.
Or if unsure about the eol but still want data to be more or less per message instead of as received.

### TaskManager
- Added {utc:format} as possible fill in value for a task value. This will get replaced with the current utc time
according to the format (if valid).
- Can now use {group_name} in the content of a task instead of having to specify type of val. It will just take
a bit longer because it has to search in more collections.

### Store
- Added `store:streamid,alterval,valname,op,operation` to add ops to a val.
- If no index is provided for the addi/addr/addf or addt, one will be generated:
  - If it's the first node, it will be 0.
  - If other nodes are already present, it will be the index of the last one +1.

### Database
- Replaced the use of the enum Rolloverunit with the java ChronoUnit.
- Fixed, filename of the sqlite with rollover in status overview didn't match the actual name on short periods.
- Breaking, combined rollover attributes to make it a bit more straightforward. Bot SQLite and FileCollector use this.
```xml
<!-- Previous -->
<rollover count="5" unit="minutes">_HHmm</rollover>
<!-- Now -->
<rollover period="5 minutes">_HHmm</rollover>
```

### Other
- Editor redate now supports epochmillis and epochseconds. Use 'epochmillis' or epochsec(onds) as inputformat.
- Fixed, flagval and textval update command was checking for id in old index so didn't work.
- RealVal and Intval can now be created with a command.
- Updated dependencies:
  - netty 4.1.109
  - jSerialcom 2.11.0
  - mssql 12.6.1.jre11
  - postgresql 42.7.3
  
## 2.8.1 (15/03/2024)
- Updated netty,activation and json dependency
- Can now request realtime updates of flags with `flag:id`.
- The !! function of the telnet interface now works additive. So if a prefix was active, it can be appended to. 
```
>dbm:rolls,!!

Prefix changed to 'dbm:rolls,'
dbm:rolls,>addcal,dice,!!

Prefix changed to 'dbm:rolls,addcal,dice,'
dbm:rolls,addcal,dice,>
```

### I2C
- Changed i2c code to using doubles to actually work with numbers of 32bits till 63bit.
- Changed i2c:reload to do a full reload including looking for devices on the bus
- Fixed, requesting data in telnet couldn't be stopped

## 2.8.0 (09/02/2024)
- Fixed, Matrix out of bounds when sending something without room url etc.
- IntVal, now accepts real for parsing if 'allowreal' attribute is set to true. Will round according to math rules. Will
now give an error if this or regular parsing fails.
- Rewrote I2C code

### Dependencies
- Netty 4.1.101 -> 4.1.106
- jSerialCom 2.10.3 -> 2.10.4
- commons-lang3 3.12.0 -> 3.14.0
- 
### I2C
- Rewrite in progress, mainly to make the code easier to understand and adapt. All the basics work.
  - Read/Write/alter registers, delay before executing read.
  - Still need to do the 2.7.0 introduced args
- Now ops are stored inside the device instead of globally, so each device has their own set.
Doesn't make a difference if only one device was using the script. But now multiple can use the same
script but with custom store's.
- Now allow for read data to be split according to bit list, fe. 8,20,20,8 means read 7 bytes and split those according 
to that sequence.
- Removed use of label.
- Can now use math and store node inside the scripts.
**Breaking changes**
- The return attribute in the read node, now is based on the set amount of bits. So return now refers to the amount of
  times that bits is returned.... So if bits is 16 and you read one of that, return used to be two and now it's one.
- The main node is renamed from commandset to i2cscript and script attribute to id.
- The subnodes are changed to 'i2cop' from 'command' because command is already in use for other things

### Admin cmds
- Added a cmd that uses phytool to power down or up a phy, could be used to save power. 
User must install the tool first.

### Vals
- Added 'Dynamic Units' so it's possible to alter the unit depending on the amount.
There are two options, either 'step' for integers or 'level' for real
```xml
 <!-- For example the unit is a time period in seconds -->
  <rtvals>
    <unit base="s"> <!-- the unit used  -->
      <step cnt="60">m</step> <!-- the next step up 60s  to 1m -->
      <step cnt="60">h</step> <!-- the next step up 60m to 1h -->
    </unit>
    <unit base="Hz">
      <level div="1000" from="1500">kHz</level> <!-- Use A if the value is higher than 1500 and use 1000 as divider -->
      <level div="1000">MHz</level> <!-- No 'from' so same as div, so kHz -->
      <level div="1000">GHz</level>
    </unit>
</rtvals>
<!--
So an input of 3962s will result in 1h6m2s shown instead.
Or an input of 1400Hz will result in 1400Hz 
    but 1840Hz will become 1.840kHz
    and 1245358Hz will become 1.245MHz, scale is taken from the realval 
-->
```

### Paths
- Changed `pf:list` so it actually gives a list of active paths instead of also listing steps in it
- Added `pf:id,list` to give all the steps in the path with the provided id

### TaskManager
- Reordered parameters in some commands, so it's id first if it's interacting with that id, so `tm:id,cmd`.
- Interval without an initial delay is now actually without a delay... (was 10% of interval)
- Made it possible to refer to a trigger with an attribute of the type instead of the trigger attr. Should
  be a bit more intuitive.
```xml
<tasklist>
  <!-- Both tasks do the same -->
  <task output="stream:powmon" trigger="interval:10m">ac1</task>
  <task output="stream:powmon" interval="10m">ac1</task>
</tasklist>
```

## 2.7.0 (03/12/23)

Biggest change is a rewrite of the code that reads the path xml. This now can have 'if' structures (but no 'else' yet).

- Fixed, TCP streams no longer had the label applied.
- Fixed, the reply to a cmd in telnet wrote on the same line as the given cmd
- Fixed, serialport buffers seem to be filled even if nothing is listening, all that data gets dumped on connection. So
flush the buffers on opening the port.
- Added `ss:id,port,newport` to change the port of the stream
- Added option to prefix received data lines with the id of their origin. Activated by adding the <prefixorigin> node in 
the stream with true as content. Can be handy if you get data from different src's at the same time.
- Telnet, Command history (up to 20) in telnet is now saved in ram on ip basis, can be cleared by sending >>clearhistory or >>clrh
- Telnet, When cmds are issued from non-telnet, the telnet escape codes are removed. Adding -r at the end has the same result.

### Matrix
- Room text updates can now be requested with the 'matrix:roomid' cmd.
- Changed cmd matrix:say,roomid,message to matrix:roomid,say,message.
- Allow for cmds to be executed on joining a room with `<cmd></cmd` node inside room node.

### I2C
- It's now possible to add extra arguments to a i2c cmd to set data send. Next step applying a
math operation to the argument first.
```xml
	<command id="init" info="Set default control, not enabled, no oc retry, retry on ov and uv">
		<write reg="0xD0">i0</write> <!-- i0 will get replaced with the first arg -->
		<write reg="0xD4">0x13</write>
	</command> 
```

### Path
- Rewrote the code that reads paths from xml
- Fixed, type nmea was ignored because it was expecting a value.
- It's now possible to add filter rules with attributes
- Added a new tag 'if', this is actually a filter but one that allows nesting, this should make it a bit more intuitive... right?
  - The only node that expects other forwards to be inside it. In other words, it allows nesting.
  - Given how to code works, it should be possible to add multiple levels...
  - An If doesn't pass data on to a next step, instead the step after the if, get the same data as the if
```xml
<if start="$GPVTG">
  <store>
    <real def="0" i="1" unit="°">cog</real>
    <real def="0" i="5" unit="kn">sogknots</real>
    <real def="0" i="7" unit="m/s">sogms</real>
  </store>
</if>
```
- Added new filter rule 'atx', check if value at index is a certain value
```xml
<if at1="hello"> <!--start at 0 -->
</if>
```
- Filter often has to send to multiple writables, so now uses concurrency to speed things up a bit. Which means that if
the path contains multiple
- Math can now use temp variable storage using t, these values are also made available to the store following the math.
Can refer to them with by adding the t index to the highest possible i index.
```xml
<math>
  <op>t0=i0+5</op>
  <op>t0=t0*2+{r:t_test}</op>
  <op>i0=t0+3</op>
</math>
```

### Store

It's now possible to add math operations with values that are in the store.
````xml
<store group="o1">
      <real i="1" unit="V">voltage</real>
      <real i="2" unit="mA">current</real>
     <!-- calculate the product of the voltage and current to get the power in watt -->
      <real o="(o1_voltage*o1_current)/1000" unit="W">power</real> 
</store>
````
### TaskManager
- Allow for tasks started from cmd to include arguments to replace i0, i1 etc. This includes the 
functionality to do math operations.
For example:
```xml
<!-- Part of the taskmanager 'io' -->
<!--
{mint:formula} -> calculate the formula and return an integer 
{math:formula} -> calculate the formula and return a real
-->
<task id="go" output="stream:test" >ovli0:{mint:i1*(65535/32)}</task>
```
When using the cmd `io:go,1,3.3` this will result in `ovl1:6758` being written to test. 


## 2.6.0 (29/10/2023)

### Fixes
- FlagVals weren't sending updates to targets.
- ss:id1,tunnel,id2 command was using wrong indexes.
- Mathforward didn't set all values in the store if they weren't used by it.
- I2C cmds weren't read correctly from script, kept using the write/read etc from the first cmd.
- Telnet text color was read from attribute instead of element.

### Updated deps
  - jSerialcom
  - Netty
  - SQLite

### Store implementation
- General code cleanup
- Removed 'store' from the BaseStream and replaced this with a new collector 'StoreCollector'. That way it's works the same
as any other data processing method (making it easier to understand and debug).
- A forward or storecollector can now request a link to a specific sqltable to trigger an insert.

### Database
- Rewrote the way the 'store' and 'database' interact. This was based on cmd but this gives issues when processing 
at high speed. Now an insert is triggered directly instead when using forwards and streams.

### MQTT
- Added cmds for the mqtt provide and store
- Changed subscribe/unsubscribe cmds to no longer need to save afterwards
- Removed cmd to save settings (old store)

## 2.5.2 (16/08/23)
- Fixed, taskmanager scripts had different node paths for storing and reading.

### MQTT
- Added option to have mqtt topic linked to rtvals, no cmds for it yet
````xml
<broker id="mosq">
      <store> <!-- to store the value of a topic in a rtval -->
        <int topic="dice/rolled" group="dice">rolled</int>
      </store>
      <provide> <!-- to provide a rtval when updated this will be send to mqtt broker -->
        <rtval>dice_rolled</rtval> <!-- uses group/name as topic so dice/rolled -->
        <rtval topic="dice/d6">dice_rolledd6</rtval> <!-- or alternative topic -->
      </provide>
</broker>
````

## 2.5.1 (02/08/23)
- Fixes related to recent xml changes
  - Filter no longer working if not using rules
  - paths in files were no longer recognized properly
  - after a peek, attr and value refer to the peek even if it failed, now refers back to last dig
- Other fixes
  - Telnet in linux adds a 0x00 after 0x0D, now this is filtered out 
  - rtvals in a store can now actually define a group instead of using global one
  - Serial connections that used 'label' didn't provide the writable to receive replies
  - ss:id,label,newlabel altered to parent node instead of the label node
 - Updated jserialcom dep

### MQTT
- Rewrote the xml related code and general cleanup
- Fixed, addbroker didn't set the id
- Fixed, mqtt wasn't initialized if not present in xml... but then cmds cant be issued to add it to xml

## 2.5.0 (17/07/23)

- Rewrote a lot of the code involving XML reading/writing, this kinda effects everything. So plenty of new possibilities
for bugs. It should however make the related source code easier to read.

### Realtimevalues
- `rtvals:name,regex` didn't seem to exist anymore... made it again
- `text:id` now works
- fixed, The op in a real or int now actually also works with i (instead of i0)

### Other fixes
- telnet, fixed issue with cmd history not being applied correctly due to error between length and index

## 2.4.2 (09/07/2023)
- Fixed, filecollector checked if file and parent folder are both new, this wasn't right (was a bad fix of a old bug)
- Fixed, if a tab is requested as a delimiter for a forward, this wasn't properly converted to an actual tab but stayed
as the sequence \t. Which is fine for a split, but causes issues if it's used to join afterwards.
- Fixed, an interval task that outputs to a stream but fails could put that in endless loop reload because it forces succesive
reloads. Now the streampool will inform the taskmanagers on the reconnected event.

### Forwards
- editor, added indexreplace to replace the value at an index after split with something else (like rtval)
  - Using an empty node will remove that index, naming the node 'removeindex' does the same 
- path
  - now an error is given if no src is specified
  - fixed, pf:id,xml,src now actually creates the xml file
  - added pf:id,store,astable,dbid this adds a table to the given db based on the last store in the path
- filter
  - fixed, adding minlength and maxlength with cmd didn't work because it expected a _ between the words
  
### Telnet
- Replaced the 'Mode changed to' to 'Prefix changed to' because that fits better
- The prefix is now displayed completely (used to be cut off at : to only show main cmd)
- Fixed, the prefix wasn't shown for the first cmd after setting it

### Other fixes
- XMLdigger peekAt("*"), now properly recognises this as the wildcard (so it's pretty much 'any sibling?')
- store, now gives an error in the logs if db write failed (fe missing database).

### Dependencies
- Updated Netty 4.1.93.Final -> 4.1.94.Final
- Updated Json 20230227 -> 20230618

## 2.4.1 (31/05/2023)

### Paths
- `pf:list` didn't show store attached to a filter
- fixed, rtval references in cmd needed type in {type:id}, not anymore
- fixed, store after cmd didn't work because the step before holds it which cmd didn't yet
- When parsing a cmd, the rtvals are stored so they aren't looked for on every call

### Rtvals
- fixed, store to string showed the value not the id
- fixed, store didn't use the default delimiter if none was specified

## 2.4.0 (29/05/2023)
- When double/real was read from a xml file, and it was using a ',' for decimal sign, this wasn't altered to '.'.

### Dependencies
- Updated Tinylog 2.6.1 -> 2.6.2
- Updated Netty 4.1.92.Final -> 4.1.93.Final
- Updated sqlite 3.40.1.0 -> 3.42.0.0
- Updated progresql 42.5.4 -> 42.6.0

### Databasemanager
- Reordered the cmds to be in line with the other ones (start with id, if one is needed)

### Paths
- `pf:reload` now shows error if parsing failed
- `pf:list` 
  - now shows if a parsing error occurred in a step
  - now shows the defines for a math
  - fixed, will now state no ops and no edits instead of no rules for math/edit
  - fixed, will no longer show ix = twice for an op
#### Cmd
- Added option to add executing a cmd inside a path by adding a <cmd> at any position
  - Can refer to received data using i's (like other forwards), default or determined delimiter
  - Can refer to other rtvals
  - Either single cmd in <cmd> or multiple <cmd> inside a <cmds>
#### Math  
  - no need to add the type of val when referencing them in ops
  - can now use a tagname for a def instead of the def tag
````xml
<!-- before 2.3.5 -->
<math>
    <def ref="A1">10</def>
    <op>i0=A1*(i1+{i:big_number}</op>  
</math>   
<!-- now, above still works, but below is also fine -->
<math>
    <A1>10</A1> <!-- tag name instead of ref attribute -->
    <op>i0=A1*(i1+{big_number}</op> <!-- i: no longer needed -->
</math>   
````
#### Edit
- now allows to use the edit type as node tag instead of edit with type attribute. This is also the new 
result of the cmds.
````xml
  <paths> <!-- both old and new are valid -->
    <path id="old" src="raw:dummy" delimiter=":">
        <editor>
            <edit type="resplit">si0s</edit>
        </editor>
    </path>
    <path id="new" src="raw:dummy" delimiter=":">
        <editor>
          <resplit>si0s</resplit>
        </editor>
    </path>
  </paths>
````
#### Customsrc
- Changed the node from using attribute type to nodes, some examples
````xml
<path>
  <plainsrc>Hello World!</plainsrc> <!-- Will send Hello World every second -->
  <plainsrc delay="100ms" interval="10s">Hello??</plainsrc> <!-- Will send hello?? every 10s with initial delay of 100ms -->
  <filesrc>todo</filesrc> <!-- will send the data from all the files in the map one line at a time at 1s interval -->
  <cmdsrc>st</cmdsrc> <!-- will send the result of the cmd -->
</path>
````

### RealtimeValues
- Fixed, int only accepted the 'default' attribute instead of 'def' and 'default' 
  
## 2.3.4 (07/05/23)

### RealtimeValues
- Fixed, vals weren't set to default on startup

### StreamManager
- Added 'tcpserver' as possible type, can be used to replace the transserver with a more purpose built alternative.
  - Use label 'system' to mimick most of transserver functionality 
````xml
    <!-- 
        This mimics most used functionality of the TransServer 
        - Sends a welcome message to new clients, do note that continuous data requests will send data to ALL clients
        - Sends the data from a certain src to all connected clients
        - 'open' trigger is fired when the first client connects
    --> 
    <stream id="trans" type="tcpserver">
      <label>system</label>
      <eol>crlf</eol>
      <address>localhost:1234</address>
      <cmd when="open">raw:sensor</cmd> <!-- All attached clients will receive data from sensor stream -->
      <cmd when="hello">Welcome to trans?</cmd> <!-- Send welcome message -->
    </stream>
````
### EmailWorker
- Up till now there wasn't a limit to emailsending. But it can happen that due to mistake in taskmanager etc an infinite loop
is created. To restrict that from spamming, there's now a delay of one second between emails and a default maximum of 5 emails per 5+2 seconds.
Any other emails to send in that period are ignored. This limits can be increased in xml with the <maxemails> node.

## 2.3.3 (20/04/23)

### Store
- Added cmd `store:streamid,alterval,valname,attribute,value` to alter the attributes of a val in a store.
- Error messages on cmds are now in line with those of other cmds and correspond to use for path or stream

### RealtimeValues
- Flag didn't support the 'options' attribute like the others do

### Telnet
- When starting a new settings file, the text color for telnet will be light gray by default instead of yellow.
Because this node wasn't created before, this doesn't affect existing uses.

### Forwards
- MathForward fix:
  - when referring to an int the corresponding val was retrieved but not replaced with 'i' yet. (only real and flag) 

## 2.3.2 (05/04/23)

### StreamManager
- General code cleanup (remove unused, add comments etc)
- Fixed idle state after disconnect/reconnect
  - Idle code now the same for serial and tcp (tcp used to be netty built in)  

### Store
- Added 'idlereset' attribute to a store inside a stream. This will cause the store vals to be reset
to default if the stream goes idle.
- Fixed auto reload not working, if/else was swapped...
- Fixed, reloading of a store in the tcp didn't replace the store in the handler

### Realtimevalus
- Added `rtvals:resetgroup,id` command that resets the vals in the group to their default value.
Use this with cmd option of a stream to reset groups on idle. 
`````xml
<stream>
  <cmd when="idle">rtvals:resetgroup,id</cmd>  
</stream>
`````
- TextVal's parse functionality required all possible cases to be given, to remove that the 'keep' node was added
````xml
<text index="1" name="test">
  <parser key="012">No valid data</parser> <!-- So if there's a '012' at index 1, test will become 'No valid data' -->
  <parser key="013">No known sample</parser>
  <!-- To cover all other values -->
  <keep>.*</keep> <!-- Keep all that match the regex text content-->
  <!-- or -->
  <keep regex=".*"/> <!-- Keep all that match the regex attribute -->
  <!-- or -->
  <keep/> <!-- wille be the same as the above, default is the .* regex -->
</text>
````
- FlagVal
  - parse functionality was additive instead of replacing, changed this.
  - delimiter attribute wasn't actually implemented...?
  - added option to use regex instead of fixed values, can't combine
  - a match is still a requirement, so all cases need to be covered

## 2.3.1 (01/04/23)

### StreamManager
- ss:add command didn't give correct element to next step

### RealtimeValues
- Added option to specify parsing of Flag,int and real
````xml
<stream id="dice" type="tcp">
  <address>localhost:4000</address>
  <store map="true" delimiter=":">
    <int key="d6" name="d6" unit="">
      <op>i0=i0+6</op> <!-- Value will be parsed to integer and then 6 added to it, instead of i0 just 'i' is also ok -->
    </int>
    <flag key="d20" name="d20" unit="">
        <true>20</true><!-- default is a single value per node -->
        <true>10</true>
        <false delimiter=",">1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19</false> <!-- but delimiter can be specified -->
      <!-- If no match is found, an error will be given -->
    </flag>
    <int key="d100" unit="">d100</int>
  </store>
</stream>
````
## 2.3.0 (31/03/23)
- Commands cleanup
  - tried to improve consistency
  - removed unused ones
  - Rewrote the StreamManager
  - Changed order to have id in front if the cmd affects a single id (aka breaking changes)
  - Might introduce bugs

### Things removed
- 'Configurator' attempt at alternative way of building the xml
- InfluxDB support, wasn't complete anyway and didn't fit in with the rest of the code

### Path
- Added a filter type 'items', this allows to filter on the amount of items after split on delimiter
  - `pf:id,addfilter,items:2` split with default delimiter should return two items
  - `pf:id,addfilter,items:2,5` split with default delimiter should return 2-5 items (so 2,3,4 or 5)
- `pf:id,addfilter,type:rule` now checks if the type actually exists

## 2.2.0 (26/03/23)

### Telnet
- Added the node 'textcolor' to change the default text color
- Added the command `>>color:colorname` fe. `color:lightgray` to change the default text color of the session

### TaskManager
- tmid:list now gives more info on the state of task with delay trigger

### LabelWorker
- Removed 'ValMap', replaced with store

### Store
- Added attribute 'map', that makes the store act as a ValMap instead of regular store. 
- Best is to start with `store:id,map,true`, and then continue as usual.
````xml
<!--
d6:4
d20:14
d100:63
-->
<stream id="dice" type="tcp">
  <address>localhost:4000</address>
  <store map="true" delimiter=":">
    <int key="d6" unit="">d6</int>
    <int key="d20" unit="">d20</int>
    <int key="d100" unit="">d100</int>
  </store>
</stream>
````
### RealtimeValues

- Added parsing options to TextVal, this is used in combination with store. Mainly for decoding error-codes etc.
````xml
<text index="1" name="test">
  <parser key="012">No valid data</parser> <!-- So if there's a '012' at index 1, test will become 'No valid data' -->
  <parser key="013">No known sample</parser>
  <!-- or regex -->
  <parser regex=".+12">No valid data</parser> <!-- regex matching, first match counts -->
</text>
````

## 2.1.0 (23/03/2023)

Started a new set of commands 'history', that allows to look into raw/log files. 

### POM
- Updated dependencies
  - Netty: 4.1.88.Final -> 4.1.90.Final
  - Tinylog: 2.5.0 -> 2.6.1
  - org.json: 20220924 -> 20230227
  - SQLite: 3.39.4.1 -> 3.40.1.0
 
### Logging/Logger
- Altered error and info log:
  - to also have the timstamp surrounded with [] like raw
  - a tab between timestamp and level instead of space
- Raw log no longer mention priority nor label

### History
- Added `history:raw,filter<,lines>` looks in the last raw file for lines containing 'filter' and returns the most recent 25 
lines or at maximum the amount specified. (or -1 for all). Other files are compressed...
- Added `history:error/info,age,period<,filter>` to look for errors/info younger than the period. This is limited to the last 200 lines.
- Added `history:error/info,today,<,filter>` to get errors/info of today, optionally must contain 'find'
- Added `history:error,day,yyMMdd<,filter>` to get errors of a specific day, optionally must contain 'find'

## 2.0.0 (20/03/23)

This should still be fairly backwards compatible, but too much changes to api to stay on 1.x.y.  

**Main changes:**
* Generics and rtvals overlapped too much, removed generics and store now handles both. 
* Removed a lot of 'labels' that are now handled with store and hidden the rest from the user
* Started changing commands to have the id first and the cmd secondary
* Started using childnodes in favour of id -> src references 
* Removed functionality that either wasn't used anyway or had multiple ways of doing
  * filters,math,editors had their own node and cmd's outside of paths, this is no longer the case  
* Added extra cmds to telnet to show prefixes to data received

The Getting started guide has been updated to use all these changes, making it about 10% shorter.

### Labels
- Changed default label to an empty string, so streams no longer pass it on to the labelworker. Because of store,
there wasn't a use for it anymore.
- Slowly removing labels from user view

### StoreVal
- Has a telnet interface through 'store:' commands. Differs from regular because this has the id
  as the first argument instead of the cmd. This allows for use of `store:id,!!` for faster creation.
- Support for int,real,text,flag and parsing happens inside this object
- Is used for paths instead of generics
  - Is stored inside the forwards and parsing happens there. 
  - If a math, then parsed values are given instead of parsing again
 
### StreamManager
- StoreVals are part of the stream node and processing happens inside the capture thread instead of the global worker.

### RealtimeValues
- Text now has its own object. Shouldn't make any difference in usage, besides adding option for
triggered commands.
- rtvals by default now use the node content for the name instead of attribute, unless childnodes are added.
But a rtvals node with name attribute and no childnodes will still be processed as before.

### Telnet 

- Added the `>>prefix` command, to toggle having telnet add the id of incoming data as prefix, default false.
  - The prefix will be shown in magenta
  - This is set for the session that issued the command, so not for other sessions/windows
  - If enabled and lines from different id's are received a dashed line is added at the end to show end of datablock 
- Changed it so errors/warnings returned are in orange (with a ! in front), just so it stands out
more if something went wrong
- Added three commands to have timestamping in front of data in orange:
  - `>>ts`: add HH:mm:ss.SSS (UTC time including microseconds)
  - `>>ts:format`: according to given format
  - `>>ds`: add yyyy-MM-dd HH:mm:ss.SSS in front
  - These are set for the session that issued the command, so not for other sessions/windows
  
### Waypoints
- The travel check thread was only checked for existing or not, not for still being active.
- Added extra hourly check to see if the travel check is still alive
- Added extra info to `wpts:list`, to inform on the state of both checks

### Paths
- Added `pf:id,debug` cmd to request all steps
  - If used in combination with the telnet prefix, that prefix will get trailing spaces to match length of longest one.
  - The source for the path will also be requested

## 1.2.1 (forgot to add)

### Fixes
- FileCollector: trying to create an existing directory structure throws an exception that 
wasn't handled properly on linux if that structure is made with a link.
- PathForward: Generic means the end of a filterblock, but working with id's didn't circumvent
this as it's supposed to
- XMLfab alterchild used orElse with optional that didn't work as expected (was used if the op
optional wasn't empty). This affects pretty much all Vals writing to xml
- XMLdigger: The isInvalid check was copy paste of isValid, without being inverted...
 
## 1.2.0 ( 23/02/2023 )
Main addition is probably that you now get feedback on loading of taskmanager scripts. 
Either when logging into telnet (issues during startup) or after reloading (as response to the command.

### Telnet interface
- Use `CTRL+s` to send something without eol (mainly used for Sx:y cmd)
- Use `\e` to send `\x1B` or 'escape', combine with `CTRL+s` to only send that

### Paths
- pf:reloadall now exists

### TaskManagers
- Fixed: tmid:? failed if no tasksets were present
- tm:reload/tm:reloadall now return an error if parsing failed
- If parsing fails during startup, this is added to the 'welcome' message of the telnet interface

### Other
- Updated to last minor release of dependencies
- Fixed workpath on linux (windows adds a / to many, linux doesn't)
- Added XML 'digger' to check contents of XML file (instead of abusing fab). This now handles most of the
XML reading.
- Fixed wrong optional check that made FileCollector fail
- Fixed rtvals:group, check was wrong
- Fixed tm:add,tmid for some reason this year's old code suddenly stopped working
- Fixed i2c raw streaming not stopping on a global clear request 

## 1.1.0 (07/12/22)

### SQLiteDB
- Fix: The path to the sqlite with rollover is determined at startup and after rollover. Thus if
the system clock suddenly changes (significantly) this wasn't applied to the sqlite file name.
  - Now the current clock is checked every minute and filename altered if an unexpected change occurred
  
### StreamPool
- Slowly removing the (visible) use of 'label'.
- Added the optional node 'store' to a stream, this allows for processing inside the acquire thread.
This is as an alternative to the real/int/text label and some of the generic uses
````xml
    <!-- Example -->
    <!-- Before -->
    <stream id="sbe38" type="tcp">
      <address>localhost:4001</address>
      <label>generic:sbe38</label>
    </stream>
    <rtvals>
        <group id="sbe38">
            <real name="temp" unit="°C"/> 
        </group>
    </rtvals>
    <generics>
      <generic id="sbe38" group="sbe38" delimiter=":">
          <real index="1">temp</real>
      </generic>
    </generics>
    <!-- Now -->
    <stream id="sbe38" type="tcp">
      <address>localhost:4001</address>
      <store delimiter=":"> <!-- Take the group id from the stream id, delimiter is : -->
        <ignore/> <!-- ignore the first element of the split, can be repeated -->
        <real id="temp" unit="°C"/> <!-- second element is a real -->
        <!-- don't care about any other elements -->
      </store>
    </stream>
    <!-- or -->
  <stream id="sbe38" type="tcp">
    <setup>
        <address>localhost:4001</address>  
    </setup>
    <store delimiter=":"> <!-- Take the group id from the stream id, delimiter is : -->
      <real index="1" id="temp" unit="°C"/> <!-- second element is a real, starts at 0 -->
    </store>
  </stream>
````
### PathForward
**Imported path**  
- If id, delimiter are set in both reference and import, import is used for delimiter but id from reference. If present in only one of the two, that is used.
- Now possible to add path node to a stream node, do note, this is checked when reading paths *not* when reading streams (for now)
````xml
    <stream id="test" type="tcp">
      <eol>crlf</eol>
      <address>localhost:4001</address>
      <!-- Option one -->
      <path>paths/rtk.xml</path>
      <!-- Option two -->
      <path import="paths/rtk.xml"/>
    </stream>
````
Either way, this node is altered to look like this. The id and delimiter are taken from the xml if not specified.
````xml
<path import="paths/rtk.xml" src="raw:test"/>
<!-- So id and delimiter are taken from the xml. But can be specified if none are in the xml -->
````
**Improved feedback**  
When reading an xml fails during startup, this is now shown when logging into telnet.  
For example, a bad character somewhere in a path xml
````
Welcome to DCAFS!
It is Sun Nov 27 19:06:58 CET 2022 now.
> Common Commands: [h]elp,[st]atus, rtvals, exit...

ERRORS DETECTED DURING STARTUP
PathForward: rtk.xml: line:10:69; The content of elements must consist of well-formed character data or markup.
````

## 1.0.6 (25/11/2022)

### RealtimeValues
- RealVal, IntVal now have the 'abs' option that makes it store the absolute value instead

### PathForward
- Rtval nodes can now be added to a separate path xml and will be processed first. 
If all the vals should belong to the default group (the one without name/id) then no group node
is required.
- Generics inside a path will create rtvals in the file the path is in
- Instead of the node 'generic', the node 'store' can be used inside a path. Thought that might
be more logical...
- Improved the feedback given by `pf:list` *a lot*
- If no (empty) group is given to a generic/store inside a path, the id of the path is used
- Fixed: When using a custom src from a file with a relative path this failed because the workpath was set after the xml
read.

### MathForward
- Added a couple more checks to the whole process, to catch bad data earlier
- Before this stopped trying after receiving corrupt data for 5 consecutive times.
Now it keeps trying but limits the error logging and reesets the content of any attached generics.
If a target of the mathforward is an editorforward, that one will reset its generics too.
- Fix: Checks the highest used index to not convert more than needed. This however didn't take the special calculations (fe. sound velocity) in account.

### EditorForward
- Added listreplace to allow to replace a digit at a certain position with another value.
````xml
<editor type="listreplace" index="1" delimiter="," first="1">cat,dog,horse</editor>
<!-- index is the position of the value in the input after split on delimiter
     first means that the first element of the list corresponds to that number, default is 0
     So if the input was 'cow,chicken,3,goat' this will become 'cow,chicken,horse,goat' because horst is
 --> 
````

### Generics
- Added flags/bool
- Removed filters
- Allow def attribute to set a default value incase the parsing fails (only for int/real)
- Add rtvals to the file the generic is found in (so settings.xml or a path xml)


## 1.0.5 (27/10/2022)

### Waypoints
- Now the waypoints code checks for occurred travel instead of external trigger
- Now cmd nodes inside travel nodes are actually implemented properly
- Changed format of bearing attribute to only accept x -> y fe 0->360 or 0 -> 360
- Cleanup up the commandable interface
- Store to xml now stores cmds

### Fixes
- EmailWorker, reload etc wasn't working because checking wrong variable
- PathForward with a cmd as customsrc now properly works in telnet

### Changes
- SQLtable won't complain anymore if a value isn't found if def is specified

## 1.0.4 (11/10/2022)
### Fixes
- TaskManager fix in 1.0.3 caused another issue, should be fixed
- FileCollector folder permissions were altered before the folder was known...
- PathForward stepsforward could still be null, prevent nullpointer
- TaskManager allowed for interval tasks with an interval of 0s
- Realtimevalues used the 'get' to check if a real already instead of the bool one

### Changes
- Rtvals node doesn't have to be inside settings
- waitfor task can now have the check as content instead of attribute
- task with interval of 0 are now ignored

## 1.0.3 (06/10/2022)
### MQTT
- Response to ? is now in line with the other commandables (coloring etc)
- mqtt:brokerid now works as alternative to mqtt:forward,brokerid

### TaskManager
- tm:reload now also reloads all

### Fixes
- MathForward still had a method that referred to d instead of r
- TaskManager didn't handle oneshot tasksets properly if a reply was asked from a stream.
This reply caused it to go to the next task in the set while this should only happen in step type.
- Generic now properly generates new rtvals

## 1.0.2 (26/09/2022)

### Filemonitor
- Can now respond to files created and read them as if they are a stream
  - XML code 
````xml
  <monitor>
    <file id="test" path="todo" read="true"/>
    <!-- Request the data with 'fm:test' as src -->
  </monitor>
````

### Fixes
- When trying to reload a sqlite database with an empty table, an exception was thrown. 
Instead it is no longer tried to create an empty table.
- Refactoring from double to real was not complete in MathForward. When using reference to a double/real
in an op it required {d and {r instead of twice the same...

## 1.0.1 (31/08/2022)

### Fixed
- Looking for the workpath threw a hierarchy error for the uri when using as a lib.
- Rtvals without group weren't loaded properly nor have id read.
- Waypoints xml format altered/fixed.

### LabelWorker
- Added an interface so the processing of datagrams can be expanded (mainly meant for hardcoded usage).

### FileCollector
- [linux only] dcafs runs as root so files/directories created aren't changeable by users, changed this
for FileCollects. Now those can be deleted etc. by 'others'. 

## 1.0.0 (released 17/08/2022)
- Updated dependencies
- Now using java 17 (new lts)
- DoubleVal is now RealVal, so real is used throughout instead of mix of double/real

### General
- Clean up, remove unused stuff etc
- Rewrote emailworker a bit
- Removed DigiWorker and other things related to sms
- Moved methods in CommandPool that can be static to Tools package
- Removed cyclic redundancy between CommandPool and Das
- Alias in database table is replaced with rtval, but alias will still be read (backwards compatible)
- Added edit to convert epoch millis to a formatted datetime

### Rtvals
- The response now starts with the current datetime
- IntegerVals now show up in the rtvals listing and are available for
the rtvals commands
- Group is now mandatory (empty group also exists)
- Removed the option to use {D:...} etc, rtvals need be be defined instead
- fixed: Empty ungrouped texts no longer show in list
- fixed: int/real mix in group no longer cause duplicate entries
- fixed: rtvals:name now works again with *
- fixed: reals were always followed by int's independent of the order

### Generics
- Can't add a generic with duplicate id through telnet
- When two generics are in xml with same id, only first is used.
  This is mentioned in the errorlog
- gens:addgen now actually uses the given group
- Generics inside a path now get the id from the path id instead of the file
- If a value wasn't found, the rtvals are updated with NaN if double/real or Max_integer for integer 

### Other fixes
- Here and there the relative paths weren't converted to correct absolute ones
- ModbusTCP didn't use the inherited timestamp field
- raw: stops again when issue'ing empty cmd
- Interval task with delay more than 48 hours now works properly  
- Forwards, now giving a label with xf:alter does request src

## 0.11.x
- Goals for this version series (removed when they are done)
  * Code cleanup
    * Bring javadoc up to date

## 0.11.10 (04/05/2022)

### File monitor 
- Simple class that watches files for modification and executes a cmd on event
- Purely xml for now, now telnet interface

### Fixes
- EmailWorker check thread got called multiple times if it failed, this amount kept increasing
- The command to send a string to a stream didn't allow a single ?, now info on the cmd is with ??
- Various small fixes in the tools package

## 0.11.9 (28/03/22)

Mainly added initial matrix & modbus tcp support.

### Matrix support
- Added basic support for joining a room and sending messages.
- TaskManager can send either text or the result of a command
- Can respond to message in the form of a math formula (1+1=?) and use earlier defined variables for it
- Can upload and download files

### Modbus TCP
- Added support for receiving 03 function response
  - Data received is converted to 16bit integer and formatted regx:val,regx:val 
- When sending data, the header is attached by dcafs (so everything in front of function)

### TaskManager
- Changed the interval trigger to start from the 'clean' interval if no start delay was given.
So 5m interval will start at the next x0 or x5 minutes (0 seconds etc).
  - For some reason this doesn't work as accurately under linux as windows (or sbc vs pc) 

### Commands
- Altered admin:reboot, attempts to reboot the linux system using bash or sh.
- admin:errors now checks the daily file instead of the link because that is only valid for non-removable media
- fc:reload added do reload all filecollectors at once

### Other Fixes
- pf:reload should no longer break file collectors (writables weren't kept)
- parseRTline that checks for references to vals was to broad, use regex now.
- Various small fixes to the modbus tcp code

## 0.11.8 (11/02/2022)

- Updated jSerialComm dependency to 2.9.0

### Settings.xml
- Moved databases node out of settings node , but still finds it in settings too
- Added admin:errors,x and admin:info,x to get the last x lines in the errors/info log, or 30 if x is omitted. That 
way it's possible to check both without having direct access to the files (if the issue isn't the telnet server).
- Altered startup so that a bad xml (missing > etc) still allows the telnet server to start so the user
gets feedback on the issue (last 15 lines of the errors.log).

### Cmd's
- <taskmanager id>:? should now show the time till next occurrence for interval tasks
- Fixed: dbm:store should now work again, for some reason declarations were moved
- Fixed: pf:list should now show the steps in the path again

### Other
- SQLitedb no longer uses workpath expects a proper path instead

### Fixes
- parseRTline (used for customsrc etc.) didn't process the i/I yet
- PathForward, import now uses correct relative path instead of depending on how dcafs was started


## 0.11.7 (02/02/2022)

- Updated PostgreSQL dependency because of severe vulnerability

### Breaking
- Rtvals now use 'real' just like db and generic always have
- Rtvals now has integers, so renamed the short reference for issues to is

### Forwards
- EditorForward Resplit now allows just a filler element
- PathForward: pf:debug,id,stepid is now also possible instead of the nr

### Other
- Added streams as alias for ss
- More TimeTools use english locale
- Trim is done when reading textvalue's from a node
- Generics now give an error if the conversion failed (fe. text instead of real)
- Added IntegerVal, same as DoubleVal but for Integers

## 0.11.6 (18/01/22)

- Updated dependencies.

### CommandLineInterface
 - For some reason a 0 is left after the first cmd but only on linux... trim() as workaround
 - Fixed history, buffer.clear only resets the indexes so data remains. So this combined with checking for data after the current
writeindex caused commands to be mashed together when using the history function. So now when history is used, the rest
of the buffer is overwritten with null.

### Bugfixes
- FileCollector, Changed the join to do the same if headers are added
- TimeTools, formatter was using locale to convert month/day in text (fe. May) to number so now fixed to english

## 0.11.5 (26/10/2021)

### CommandLineInterface
- Backspace should now work as intended
- Delete button works
- Typing in random spot inserts instead of replace
- discardreadbytes didn't work as expected

### Fixes
- SQLTable, didn't include the scenario of creating a table without columns

## 0.11.4 (22/10/2021)

Mainly improvements to the (till now fairly barebone) CLI.

### Command Line Interface
- Switched from client side to server side editing
  - Meaning everything you see in the console came from the server
  - The use of left/right arrows is now supported
- Arrow up/down cycles through command history (for now only of the current session), max 50 or so

### Configurator
- Initial steps for a wizard like configuration method (building the settings.xml), started with 'cfg', still work in 
progress and limited. Not in there yet for actual use, rather for testing.

### Other
- Allow for source to be set for a generic, in reality this still alters the labels
- '?' now works the same as 'help'
- Extra functionality in the XMLfab
- 
## 0.11.3 (15/10/21)

Quick release because of the mf:addop/addblank bug.

### Forwards
- A src is now checked for the presence of ':' because a valid src should always have it
- Math and filter now use ',' as default delimiter
- Replaced addblank with addmath,addfilter,addeditor

### Fixes
- Mathforward, wasn't updating references etc when adding blank or op only on reload
because of that the highestI was wrong and this determines with indexes are converted to
bigdecimal so that stayed at -1 meaning none were converted => nullpointer.
- MathFab, didn't process op's of the form i2=i2 well
- Generic wasn't handling not receiving a delimiter in the addblank (now addgen) well

## 0.11.2 (14/10/2021)

### I2C
- Added the option to use ix (fe. i0) to refer to an earlier read result in the
return attribute of a read.
- The format of the read bytes was in decimal, now it can be chosen (dec,bin,hex,char)

### GPIO
- Newly added, option to trigger cmd's on a hardware gpio interrupt

### Other
- Updated dependencies

### Fixes
- PathForward, node structure for the import wasn't correct
- DoubleVal, catch numberformat on toBigDecimal because NaN doesn't exist as BD
- MathUtils, simpleCalculation didn't handle to simple calculations well
- MathUtils, makeBDArray failed on decimal numbers starting with 0. NumberUtils.isCreatable returned false.
But NumberUtils.createBigDecimal did work... Added NumberUtils.isParsable as extra check.

## 0.11.1 (23/09/21)

This turned out to mainly fix/improve the I2C code.

### Breaking
- Refactored datapaths to paths in xml to be consistent with filters,editors,maths

### StreamManager
- stream:id has been added as alternative to raw:id

### I2C
- fixed, adddevice didn't create directories
- fixed, adddevice always used bus 0 instead of the one given in the command
- New nodes added
  - math  to apply operations on the result of a read
  - wait to have the execution wait for a given amount of time
  - repeat to allow sections to be repeated
  - discard to remove data from the buffer
- Changed default label to void
- Added it to status in new devices section, !! in front means probing failed
- Added data request with i2c:id (used to be i2c:forward,id), allows regex to request multiple
- Moved from thread and blockingqueue to executor and submitting work
- cleanup up the i2c:detect to make it prettier and return more info

### Rtvals
- scale is now both attribute and option for double
- readFromXml now takes a fab so that it can be called from other modules
- fixed, requesting doubles now works again, and it's possible to request using regex to request multiple 
at once. Now it returns id:value, might change or add other options in the future.

### Other
- LabelWorker, added label cmd as alternative to system because it might be more logical
- PathForward, pf:reload now reloads all paths and generics
- TaskManager, tmid:reload now reloads the taskmanager (in addition to tm:reload,tmid)

### Fixes
- Trans was always started instead of only if defined in xml
- PathForward, generic and valmap id's weren't generated inside the path
- PathForward, customsrc wasn't targeted by the first step (if any)
- XMLfab, hasRoot didn't init root so always failed
- SQLiteDB, should now always generate parent dir

## 0.11.0 (12/09/21)  

### Dependencies
- Removed Jaxb, not sure why it was in there in the first place...
- Removed tinylog impl, only using api package

### Breaking changes to generics
- They now have a group attribute instead of using the table attribute to set a prefix. Group is chosen
  because this matches the group part of a doubleval etc.
- The dbid and table attributes are now combined in a single db attribute `db="dbid:tablename` multiple
  dbid's are still allowed (',' delimited).
- To update settings.xml to use these changes:
  * replace table= with group=
  * replace dbid= with db= and append the content of table= to it with a : inbetween
    *  So dbid="datas,datalite" table="ctd" -> db="datas,datalite:ctd"  group="ctd"

### Workshop prep
- Added option to give an id to the current telnet session
- Allow sessions to send exchange messages
- Allow to 'spy' on a session, only one spy allowed. This wil send all the cmds and responses issued by the session
spied on to be send to the spy. Can be used to (remotely) follow what someone else is doing (wrong)

### Tinylog
- An issue is that uncaught exceptions also don't end up in the generated errorlog. Which makes it rather hard to
debug issues like that when using dcafs outside of an ide (it happens). Made a wrapper for the system.err that
also writes to the errorlog. This isn't active when working in an ide because it no longer allows tinylog to 
write to err (otherwise this would be a loop). Which only matters in an ide (coloring).

### SQLTable
- If trying to build an insert and a val is missing, the insert is aborted instead of substituting a null

### RealtimeValues
- The store command now stores all setup instead of just name/group/id/unit

## Math
- fixed, extractparts wasn't made for double character comparisons (>= etc) altered to check this
- Added support for cosr(adian),cosd(egrees),sinr,sind and abs
- fixed, missing global surrounding brackets should be better detected
- Added op type utm and gdc, converts the two references to either utm or gdc

## CheckBlock
- The word regex didn't check for brackets but did add if not found...
- fixes for standalone use (no parent nor sharedmem)
- Is now used in the FilterForward for the type 'math'

## FileCollector
- fixed, didn't make folders when the file is new and no headers needed
- fixed, sometimes empty lines were added when writing for the first time

## 0.10.13 (04/09/21)

Cleanup release, added comments/javadoc moved stuff around in the .java files etc etc etc

### Dependencies
- Replaced Netty-all with Netty-common & Netty-Handler, saving about 2MB!
- Removed MySQL connector, shouldn't be needed given that MariaDB connector is present, saves about 4MB

### util.data
- Made AbstractVal as base class for DoubleVal and FlagVal
- FlagVal now also allows triggered commands (on flag changes)
- Added reset method, because reload didn't reset
- Added javadocs and comments here and there
- RealtimeValues, changed formatting on the telnet interface (as in the dv:? etc commands)
 
### util.database
- Cleanup up (removed unused, added comments, added javadoc etc)
- Changed some methods that could return null to return optional instead
- Made som more use of lambda's

### util.math
- Cleanup up (removed unused, added comments, added javadoc etc)

### DoubleVal
- Added stdev based trigger, requires the history option to be active `<cmd when="stdev below 0.01">dosomething</cmd>`
and a full history buffer.
- fixed, defValue only overwrites current value (on reload) if current value is NaN

### Other changes
- StreamManager, `ss:reload,all` is now done with `ss:reload` 

### Other Fixes
- MathUtil, comparisons like 1--10 weren't handled properly, the second replacement altered it too
- SQLTable, buildInsert tried using DoubleVal instead of double, forgot the .value()
- getStatus, \r\n wasn't replaced with <br> for html 
- Task, prereq now appended with delimiting space

## 0.10.12 (28/08/21)

More fixes and some improvements to rtvals xml and doubleval functionality 

### DoubleVal
- Combined timekeep, history in a single attribute 'options'
  - Usage options="time,history:10" to keep the time of last value and a history of 10 values
- Added minmax to options to keep track of min/max values (options="minmax")
- Added order to options to allow specifying in which position it is listed in the group (options="order:1"), lower number
first, default -1. Equal order is sorted as usual.
- Altered rtvals to include min/max/avg/age info if available

### CommandPool
- fixed, Still had a method that referenced issuePool while all the other code was moved to Realtimevalues
this caused a nullpointer when doing a check for a taskmanager

### RealtimeValues
- `rtvals:store`,`dv:new` etc now stores in groups
- rtvals listings are now sorted
- added `rtvals:reload` to reload the rtvals
- fixed, "dv:new" always wrote to xml instead of only on new
- fixed, getoradddouble wasn't writing according to the newer group layout
- fixed, multiple underscore id's again
- fixed, isFlagDown was inverted when it shouldn't be

### Other Fixes
- Pathforward, for some reason initial src setup when using generics wasn't working anymore
- Generic, when using the datagram payload, it used the wrong index
- MathForward, the check to see it can be stopped didn't check the update flag
- IssuePool, message was read from a node instead of both node and attribute

## 0.10.11 (24/08/21)
Bugfixes!

### RealtimeValues
- Fixed, reading from xml caused writing to xml...

### FileCollector
- Fixed, path was recognized as absolute but workpath still got prepended
- Fixed, fc:list used , as delimiter but should be eol
- Fixed, on startup it checks if the headers are changed, but not if that file exists first
- Fixed, `fc:reload,id` checked for three arguments instead of two

### MathForward
- Fixed, now checks the size of the array after split to prevent out of bounds
- Fixed, now it actually cancels when too much bad data was received

### PathForward
- ID's and local src's are now possible, !id is allowed to get reversed/negated filter output
- Multiple customsrc are now possible
- pf:list should now give useful info

## 0.10.10 (22/08/21)

Note: Contains TaskBlocks code but without interface and standalone meaning, code is present but not usable nor 
affecting anything else.

### Breaking
- Changed `<cmd trigger="open"></cmd` to `<cmd when="open"></cmd>` in a stream node because all the other cmd node use when
- Changed the hello/wakeup cmd node in a stream from cmd to write node to limit possible confusion (cmd node didn't actually apply a cmd)

### TaskBlocks (name subject to change)
- Will replace TaskManager
- Current blocks
  - TriggerBlock - replaces the trigger attribute
  - CmdBlock - replaces the output=system attribute
  - MetaBlock - serves as the starting block of a link 
  - ControlBlock - replaces output=manager
  - CheckBlock - replaces req/check
  - LabelBlock - allows for data received from other blocks to be labeled
  - EmailBlock - send an email
  - WritableBlock - send (received) data to a writable with reply option
- Utility classes
  - BlockTree - helps settings up a link
  - BlockPool - manages the MetaBlocks
- Progress
  - TriggerBlock, CmdBlock, CheckBlock, MetaBlock, EmailBlock, WritableBlock functional
  - TriggerBlock and CheckBlock will not be added if duplicates on the link level, will link to the 'original' instead
  - CmdBlock aggregates commands if successive blocks are also CmdBlocks
  - EmailBlock prepends a CmdBlock to convert the email content to the result of a command (if any)
  - Can read the taskmanager script variant of the functional blocks
  - Implemented step/oneshot (taskset runtype)
  - Implemented state attribute (task attribute)
- Improvements compared to TaskManager
  - Checks and triggers are aggregated if possible
  - Checks can be a lot more complex, taskmanager had the limit of either an ands or ors while checkblock doesn't have
  such a limit and allows brackets and the math part is equivalent to the mathforward
  - Functionality split over multiple blocks instead of two classes, should make it clearer to work with
  - Has a base interface and an abstract class on top of that, make it easier to expand (adding blocks)

### CommandPool
- Removed used of reflection because the commandable interface replaces this

### TaskManager
- Tasksets now allow for the if attribute do to a check before starting the set
- Replaced 'state' logic with rtval text

### MathForward
- Fixed, Addop/addblank now works as intended again (instead of i1=i1=...)
- Addblank altered ? to show that it could also add an optional op `mf:addblank,id,src<,op>`
- Fixed an op with only an index as argument `<op cmd="doubles:new,temp,$" scale="1">i0</op>`
- Allows for creating doubles/flags by using uppercase {D:id} or {F:id} instead of lowercase
- Fixed op that just sets a double `<op scale="1">{D:temp}=i0</op>`

### DoubleVals
- Now writes changes to XML (new,alter,addcmd)
- Added `dv:addcmd,id,when:cmd` to add a cmd
- Added `dv:alter,id,unit:value` to change the unit

### Datapaths
- to remain consistent, changed the cmd to pf(pathforward) or paths, path:id is still used to request data
- Added the pf:addgen command to directly add a 'full' generic to a path (uses the same code as the gens:addblank)
- Added the pf:? cmd to get info on the possible cmds
- refactored the .java to PathForward.java

### XMLfab
- Refactored the 'parent' methods to make it clear when this is actually 'child becomes parent'
- Select methods can now return an Optional fab (so can be continued with ifPresent)

### Fixes
- rtval:id works again, wasn't moved to the new commandable format
- rtvals was missing the eol delimiter (mistake between delimiter and prefix)
- EditorForward, resplit fixed when using i's above 10

### Other
- Generics, improved the addblank cmd to also set indexes, names and reload afterwards
- Moved IssuePool and Waypoints into RealtimeValus
- Math,Added th ~ operand, which translates A~B to ABS(A-B)

## 0.10.9 (14/08/21)

### Other
- Datagram, now has an optional 'payload' (Object)

### MathForward
- Now only converts the part of the raw data that contains the used indexes
- Adds the converted (altered) data in the payload of the datagram so fe. generic doesn't parse it again
- Removed scratchpad functionality, recent addition of {d:doubleid} made it obsolete
- fixed, if no i's are in the expression settings highestI wasn't skipped so tried to check what was higher
  previous value or null (=not good)

### Telnet
- added broadcast command to broadcast a message to all telnet sessions
  - `telnet:broadcast,info,message` or`telnet:broadcast,message` will display it in green
  - `telnet:broadcast,warn,message` will display it in 'orange' (as close as it gets to the color...)
  - `telnet:broadcast,error,message` or`telnet:broadcast,!message` will display it in red
  - Sending `nb` stops the current session from receiving broadcasts 

### TaskManager
- Added 'load' command to load a script from file
- Added 'telnet' output to broadcast text to telnet instances
- Changed the blank taskmanager to use the new broadcast functionality
- Fixed, previous version of checks were predefined, current one aren't so nullpointer wasn't checked for.
  This caused all task without req/check to fail the test.
- fixed, fillin didn't use the new references {d: instead of {rtval: etc

### Fixes
- MathUtils
  - extractParts didn't remove _ (in doublevals) or : (in flags/issues)
  - break was missing after diff
  - comparing doubles should be done with Double.compare
- RtvalCheck, the '!' in front of a flag/issue wasn't processed correctly
- FilterForward, successive filters (so if no other steps are in between, generics aren't steps) will use data given by
that filter instead of the reverse
- DoubleVal/FlagVal, didn't take in account the use of _ in the name
- TCPserver, removeTarget didn't take in account that it uses arraylists in the map
- CommandPool, new shutdown prevention prevented shut down because of nullpointer...
- MathFab, ox start index depended on the scratchpad that was removed


## 0.10.8 (12/08/21)

### RealtimeValues
- Centralized all code in the class that was in Commandable.java and DAS.java
- Replaced the update and create commands with double:update etc
- Moved to other package
- Added FlagVal

### MathForward
- Now supports referring to double's in the operations {double:id} or {d:id}
- Now supports referring to flags in the operations {flag:id} or {f:id}
- Experimental way of working with DoubleVal/FlagVal, if positive this will be replicated.
- Allows for the part left of the = to be a {d:id} and/or index ( , delimited)

````xml
<!-- Before -->
<path id="example">
    <editor type="resplit">i0;{double:offset}</editor>
    <math cmd="double:update,temp,$">i0=i0+2*i1</math>
</path>
````
````xml
<!-- Now -->
<math id="example">{d:temp},i0=i0+2*{d:offset}</math>
````
### Other
- LabelWorker, removed method reference functionality

### Fixes
- ForwardPool, nettygroup should have been before the xml reading, not after.
- MathForward, op with scale attribute should give the cmd to the scale op instead

## 0.10.7 (09/08/21)

### RealtimeValues
- simpleRTval, now also checks the flags and replaces them with 0 or 1 and the splitter is more inclusive
  ( was simple split on space, now regex that looks for words that might contain a _ and end on a number)
- Added methods that give listings of the stored variables based on name or group
- The rtvals node now allows for group subnode to group vals

### ForwardPath
- Renamed 'predefine' to 'customsrc'
- Moved from innerclass to ForwardPath.java
- Now it's possible to reload these with path:reload,id targets are maintained
- No longer uses the central maps to store the forwards, so can't add target to individual steps for now

### MathForward
- Just like the short version of the math, an <op> node can now receive a 'i0=i0+5'  
  form of expression instead of defining the index attribute
````xml
<!-- Both these op's result in the same operation -->
<op index="0">i0+i2+25</op>
<op>i0=i0+i2+25.123</op>
````
- Added an extra attribute 'scale' so that you don't need to define an extra op just for that
````xml
<math id="scale">
  <op scale="2">i0=i0+i2+25</op>  
</math>
<!-- i0 will be scaled to two fractional digits (half up) after the opeation -->
<!-- Or shorter -->
<math id="scale" scale="2">i0=i0+i2+25</math>
````
### Other
- Added interface to allow components to prevent shutdown, can be skipped with sd:force
- Math, added extra checks and catches to the whole processing chain
- EditorForward, Expanded the UI and added option of global delimiter 

### Fixes
- Waypoints, latitude was set twice instead of lat and lon

## 0.10.6 (05/08/21)

### Updated dependencies minor versions
- DioZero
- PostgresSQL
- mysql-connector-java
- netty

### RealtimeValues
- Added flags (in addition to rtvals and rttexts)
- Added commands for it to the pool (flags:cmd)
- TaskManagers now use the global flags instead of local ones

### IssuePool
- Now allows to set a start & stop test to run.
- Tests allow for dual test split with  'and' or 'or', atm can only be used once
- Cmd can contain {message}, this will be replaced with the message of the issue

### RTvalcheck
- flag or issue no longer require equals 1 or 0, flag:state and !flag:state can be used instead
- Now the check can contain 'and ' or 'or' to divide two tests

### Other
- Labelworker, added label 'log' so you can have data written to the info/warn/error logs.
  label="log:info" etc
- rtvals, double added support for not between x and y 
- EmailWorker, added email:toadmin,subject,content for a short way to email admin
- datapaths, can generate their own src data based on rtvals 
- doCreate,doUpdate now accept complicated formulas (used to be single operand)
- DoubleVal, history now with defined pool and avg can be calculated
- DoubleVal, added fractionaldigits to apply rounding
- Mathforward, now has attribute suffix, for now only nmea to add calc nmea checksum

### Bugfixes
- DoubleVal, used old value instead of new for trigger apply
- ForwardPool, global path delimiter wasn't read...

## 0.10.5 (29/07/2021)

### Datapaths
- New functionality, allows for filter,math,editor, generic and valmap to be added in a single node
- path can be in a separate file instead of the main settings.xml
- less boilerplate because the path will assume steps are following each other (so step 1 is the src for
  step 2 etc.) and will generate id's
- delimiter can be set globally in a path

### Digiworker
- Implemented Commandable
- The sms:send command now also replaces {localtime} and {utctime}

### Other
- Waypoints, travel bearing is now default 0-360 (so can be omitted)
- StreamManager, the send command now allows optional expected reply `ss:send,id,data(,reply)`
- BaseStream, now has access to the Netty threadpool and uses it for threaded forwarding  
- EmailWorker, the email:send command now also replaces {localtime} and {utctime}
- FilterForward, added regex type to match on regex
- Valmap, now allows for multiple pairs to be in a single dataline with given delimiter
- SerialStream, better nullpointer catches 
- Waypoint, added fluid api to replace constructor

### TaskManager
- Task, added replywindow attribute to provide a reply window te replace the standard 3s wait and 3 retries
 after sending something to a stream that wants a reply, default retries will be 0.
- tm:addblank now checks if a script with the given name already exists before creating it
- Fixed run="step" to actually wait for a reply

### Bugfixes
- LabelWorker didn't give an error if a generic doesn't exist
- MathForward, cmd in an op didn't make the forward valid
- TaskManager, clock tasks weren't properly cancelled on a reload

## 0.10.4 (15/07/2021)

### DebugWorker
- Using the command `read:debugworker` has been altered to `read:debugworker,originid` to only request
data from a specific id or * for everything. If no originid is given, * is assumed.

### RealtimeValues
- Implements the DataProviding interface to provide rtval and rttext data 

### Forwards
- DataProviding is added to the abstract class
- EditorForward uses it to add realtime data (and timestamping) to a resplit action
- Usage example:
````xml
<editor id="pd_parsec" src="filter:vout">
    <!-- vout1;0;vout2;0;vout3;0;vout4;0;vout5;125;vout6;0;vout7:0;vout8;0 -->
    <edit type="resplit" delimiter=";" leftover="remove">"{utc}";i1;{rtval:pd_iout1};i3;{rttext:pd_error}</edit>
</editor>
````
### FileCollector
- If the header is changed, a new file will be started and the old one renamed to name.x.ext where x is the first available 
number.

### Bugfixes
- FileCollector, batchsize of -1 didn't mean ignore the batchsize
- FileCollector, batchsize of -1 didn't allow for timeout flush

## 0.10.3 (13/07/2021)

### Waypoints
- Reworked the waypoints code to include new code:
  - 'from x to y' translation to a function (added in 0.10.2)
  - DoubleVal to have a local reference to latitude, longitude and sog
  - Implement Commandable (still wpts)
- Now executes commands by itself instead of via taskmanager
```xml
    <waypoints latval="lat_rtval" lonval="lon_rtval" sogval="sog_rtval">
      <waypoint id="wp_id" lat="1" lon="1" range="50">
          <name>dock</name>
          <travel id="leave_harbor" dir="out" bearing="from 100 to 180">
            <cmd>dosomething</cmd>
          </travel>
      </waypoint>
    </waypoints>
```

### CommandPool
- Refactored the store command to update and added create. Difference being that update requires the rtvals to exist
already and create will create them if needed.
- Renamed the update command (update dcafs,scripts etc ) to upgrade.  

### Bugfixes
- Building insert statement failed because it took the doubleval instead of val
- setting an rtval for the first time (and create it) didn't actually set the value
- doubleval getvalue, was endless loop
- starting with dcafs in repo didn't work properly
- when sending emails, the refs weren't replaced with the emailaddress anymore

## 0.10.2 (01/07/21)

### RealtimeValues
- Added metadata class for double data called DoubleVal, allows for:
  - default value
  - unit
  - triggered cmds based on single (<50) or double (10<x<50) comparison in plain text
    - Examples:
      - <50 or 'below 50'
      - 10<x<50 or 'between 10 and 50'
      - 10<=x<=50 or '10 through 50' or 'not below 10, not below 50' or '10-50'
    - Only triggers once when reaching range until reset by leaving said range
  - optional timekeeping (last data timestamp)
  - optional historical data (up to 100 values)
````xml
    <rtvals>
      <double id="tof_range" unit="mm" keeptime="false" >
        <name>distance</name>
        <group>laser</group>
        <cmd when="above 150">issue:start,overrange</cmd>
        <cmd when="below 100">issue:stop,overrange</cmd>
      </double>
    </rtvals>
````  
- Replaced rtvals hashmap <String,Double> with <String,DoubleVal>

### IssueCollector
- Replaced by IssuePool (complete rewrite)
- In combination with DoubleVal, this allows for cmds to be started on one condition and the reset on another
````xml
<issues>
    <issue id="overrange">
      <message>Warning range over the limit</message>
      <cmd when="start">fix the overrange</cmd>
      <cmd when="start">email:admin,Overrange detected,rtvals</cmd>
      <cmd when="stop">email:admin,Overrange fixed,rtvals</cmd>
    </issue>
</issues>
````


## 0.10.1 (26/06/21)

### Refactoring
- StreamPool -> StreamManager for consistency, and some methods
- Moved forward,collector, Writable, Readable to a level higher out of stream
- Influx -> InfluxDB
- Renamed TaskList back to TaskManager and TaskManager to TaskManagerPool

### Task
- Replaced Verify with RtvalCheck, uses MathUtils and should be cleaner code
- Added option to add {rtval:xxx} and {rttext:xxx} in the value of a task node
- Fixed repeats, were executed once to many (counted down to zero)
- Added option to use an attribute for while/waitfor checks
- Added option to use an attribute stoponfail (boolean) to not stop a taskset on failure of the task

### CommandPool
- The store command now accepts simple formulas fe. `store:dp1,dp1+5` or `store:dp1,dp2/6` etc. This can be used in a 
task...
- Removed doEditor,doFilter,doMath were replaced by Commandable in 0.10.0

### Other
- Fixed ef:? command

## 0.10.0 (24/06/21)

Too much breaking stuff, so version bump and update guide.

## Changes

### BaseReq
- Renamed to CommandPool
- Added interface Commandable, these can be given to CommandPool to use as custom commands
- Removed option from dcafs to extend CommandPool, should now use the interface

### DatabaseManager
- BREAKING: renamed the setup node to flush and flushtime to age, move idle out of it
- Added interface for simple querying, used by realtimevalues

### Datagram
- Renamed DataWorker to LabelWorker, fits a bit better... i think
- Replaced the Datagram constructors with a fluid api, should make them easier to read
````java
// For example
var d = new Datagram( "message", 1, "system");
d.setWritable(this)
d.setOriginID(id);
// Became
var d = Datagram.build("message").label("system").priority(1).origin(id).writable(this);
// But priority is 1 by default, id is taken from the writable, so this does the same
var d = Datagram.build("message").label("system").writable(this);
//or even shorter, system is build with system label (default label is void)
var d = Datagram.system("message").writable(this);
````
### TaskManager
- BREAKING: Replaced @fillin with {fillin}
- Moved it out of the main and renamed taskmanager to tasklist, made taskmanager that holds the main code  
- Implemented Commandable to access it via CommandReq
- Added the trigger 'waitfor', this can be used in a taskset to wait for a check to be correct a number of
times with the given interval so trigger="waitfor:5s,5" will check 5 times with 5 seconds between each check (so 20s in total)
- Bugfix: Not sure why the while actually worked... because fixed it. Runs never got reset
- Added an alternative way to add while and waitfor:
````xml
<!-- This will wait till 5 check return ok, restarting the count on a failure -->
<task trigger="waitfor:5s,5" req="value below 10"/>
<!-- can now also be written as -->
<waitfor interval="5s" checks="5">value below 10</waitfor>
<!-- This will wait till 5 checks return ok, stopping the taskset on a failure -->
<while interval="5s" checks="5">value below 10</while>
````
- Added the attribute 'atstartup' for single tasks that shouldn't be started on startup, default true
- Verify to string didn't take math into account

### DigiWorker
- Added interface SMSSending to use instead of passing the queue

### MQTTWorker
- Added MqttPool that interfaces the mqttworkers, to move code out of das.java
- MqttPool implements the Commandable interface

### EmailWorker
- Renamed EmailWork to Email and added fluid api
```java
var email = new EmailWork(to,subject,content,attachment,deleteattachment);
//became
var email = Email.to(to).subject(subject).content(content).tempAttachment(attachment);
// Also possible
Email.to(to).from(from);
// Special case
var adminEmail = Email.toAdminAbout(subject);
```  
- Removed regular options in favor of the fluid api and applied it throughout

### Forwards
- Moved the code out of StreamPool and into ForwardsPool
- BREAKING Changed source node option to src in filterforward for consistency with mathforward & attribute  
- ForwardsPool implements Commandable
- No functionality change (normally), just code separation
- EditorForward: now has addblank command, no idea yet on how to do the edits...

### Other
- cmds command now supports regex, uses startswith by default (meaning appends .*)
- Changed `update:setup` to `update:settings` because it's the settings file
- Updated dependencies
- Triggered cmds now support having a command as content of `email:send,to,subject,content`

### Bugfixes
- update command still referred to scripts instead of tmscripts, now also looks up the path instead of assuming default 
so `update:scripts,scriptfilename` is now `update:tmscript,taskmanager id`
- Same for retrieve command
- `ff:addblank` and `ff:addshort` didn't notify on errors but claimed 'ok'
- TransServer: >>>label: wasn't working because of bad unaltered substring
- Regex that determines workpath was wrong

## 0.9.9 (11/06/21)

### Modbus
- Added support for being a src
- Improved writing support, will calculate and append the crc16 by default

### Streampool
- Added writeBytes to the Writable interface
- Writebytestostream no longer allows appending eol

### Fixes 
- FileCollector: didn't reset headers,cmds before reading xml (only relevant on a reload)
- Modbus: Set the eol to empty on default instead of crlf
- MathUtils: modbus crc append only appended the first byte
- TaskManager: when creating a blank the path given to the object was still the old scripts instead of tmscripts

## 0.9.8 (09/06/21)

### FileCollector
- Added rollover (like sqlite) but with possible zipping
- Added max size limit. When going over file gets renamed to oldname.x.ext where x is 1-1000, with option to zip  
- Added flush on shutdown
- Improved timeout write to take in account last write attempt
- Added possibility to execute commands on idle, rollover and max size reached.
- Added macro {path} for the commands that gets replaced with the path
- Added telnet/cmd interface see fc:?

### SQLiteDB
- Moved the rollover timestamp update code to TimeTools, so FileCollector can use it
- Added option to put {rollover} in the path to have this replaced with the timestamp

### Other
- Added command 'stop', wil cause all src to stop sending to the writable

## 0.9.7 (05/06/21)

### Streampool
- When connecting to TCP stream, the stream is only added if connected.
- Give feedback to user if a TCP connection was a success

### FileCollector
- Allows src to be written to a file with custom header and flush settings
- Still todo: telnet/command interface

### SQLite
- Fixed absolute versus relative path
- Fixed rollover again... wasn't used correctly when added through telnet

## 0.9.6 (31/05/2021)

### General
- Added check for \<tinylog> node in settings.xml to override the path to the logs

### FilterForward
- Added option to get the discarded data instead (could be used to chain filters mor efficiently)
used with filter:!id.
- Added ff:swaprawsrc,id,ori,new to swap from one raw source to another. Can be used in combination
with the idle trigger to swap to secondary device and back to primary on !idle
  
### EditorForward
- replace type now works with hex and escape chars

### BugFixes
- clearGenerics actually cleared another map (hurray for copy paste)
- Before a full reload of forwards, the current once are first set to invalid (because 
  clearing the map only removes those references not the ones in other objects) 
- SQLite got double extension if user provided it, then didn't work with rollover...

## 0.9.5 (15/05/2021)

### Breaking changes
- default path for i2c xml is moved from devices to i2cscripts
- default node of the xml changed to dcafs from das

### BaseWorker
- Added the labels rtval:x and rttext:x, to directly store as rtval/rtext without generic
the rtval part only works if the data = a number (so no delimiting etc)

### MathForward
- supports sqrt with ^0.5
- added def node \<def ref="name">value\</def> to use defaults in a formula (type=complex only), useful
for calibration coefficients that might otherwise be buried...
- mf:reload now reloads all of them from the xml
- Added type salinity to calculate salinity based on temp,cond and pressure
- Added type svc to calculate soundvelocity based on temp,salinity and pressure

### EditorForward
- Renamed from TextForward
- Started adding commands with ef:x
- Added charsplit to split a string based on charposition (fe. ABCDEF -> 2,4 => AB,CD,EF )
- Added trim, to remove leading an trailing spaces (instead of remove ' ')

### TaskManager
- Changed the default folder to tmscripts, this doesn't affect current installs

### Fixes
- dbm:store command was missing from dbm:?
- TransHandler: label containing a : wasn't processed properly
- FilterForward: without rules had issues writing to xml
- EditorForward: resplit now uses the chars in front of the first index (were forgotten)

## RELEASED
## 0.9.4 (11/05/2021)

### Streams
- Added the trigger !idle to run if idle condition is lifted

### SQLiteDB
- Fixed rollover so the error mentioned in 0.9.2 is no longer thrown

### Bugfixes
- Tools get ip failed when no network connectivity was active
- TransServer: For some reason the address was missing from the store command

## 0.9.3 (03/05/2021)
This seems like it's going to be a bugfix/prevent issues release...
ToDo: Sometimes a rollover doesn't properly generate the tables...

### SQLLiteDB
- If a 'no such table error' is thrown while processing an sqlite prep.
  
### BaseWorker
- Replaced Executioner with Threadpool for more runtime info
- Added selfcheck on 10min interval that for now only gives info in the logs about processing

### Other
- After email:addblank, loading is possible with email:reload

### Bugfixes
- DebugWorker: if the raw data contains tabs, this wasn't processed properly
- SQLiteDB: First change path to db and then disconnect, to be sure nothing else gets in between...


## 0.9.2 (30/04/2021)

### Email
- Added extra restrictions to commands via email
  * Emails not from someone in contactlist are considered spam
  * commands from someone in contactlist without permission is ignored
- Multiple instances can share an inbox, subject end with 'for ...' where
... is whatever is in front of the @ in the from email. Multiple can be seperated
  with ,
- No wildcard yet to send to all instances without the 'for' only the first check
will get it.

### Databases
- Added PostgreSQL support because of #16
  * Added support for timestamp/timestamptz
- Now writing the OffsetDateTime class to the database instead of converting to string when possible
- Added the columns datetime,localdtnow and utcdtnow last two are filled in by dcafs

### Generics
- Added filler localdt and utcdt to present the offsetdatetime object of 'now'

### Forwards
- Added attribute 'log' (default false) to indicate if the result should be written to the raw files

### Other
 - Added concept of Readable, with this an object can declare that it is willing
to accept writables. Applied it to debugworker to use and baseworker to interconnect.
 - jvm version is now added to the status page, after dcafs version

### Bugfixes
- IssueCollector had a missing bracket in a to string that changed formatting


## 0.9.1 (25/04/2021)
Early release because of the way Influxdb was badly handled on bad connections and that 
tinylog didn't write to workpath (on linux when using service) because of relative paths.

### Influx
- Added auto reconnect (state checker)
- Written points are buffered if no connection

### Other
- Added admin:gc, this forces the jvm to do garbage collection
- Added memory info to st report (used/total)

### Bugfixes
- TimeTools.formatLongNow didn't specify a zone
- Paths for tinylog are relative which is an issue if the jar isn't started directly, 
now the paths get set at the beginning.
  

## 0.9.0 (24/04/2021)
- Updated dependencies
- Rewrote BaseWorker to use multiple threads (reason for 0.8.x -> 0.9.x )

#### Emailworker
- Used to be domain restricted cmd request now it's ref restricted meaning that if the from isn't mentioned in 
the emailbook it can't issue cmd's
- only admin's can issue 'admin' (admin:, retrieve, update,sd,sleep) cmds by default, others need permission
- now uses XMLfab for the write to xml

#### Mathforward
- Added support for i0++ and i+=2 etc
- Added mf:addcomplex,id,src,op
- Added single line xml (like filter has) if one op & one src
- bugfix: mf:addop now actually writes to xml

#### TransServer
With all the changes the server is now fit to receive data from sensors (instead of only serving).
- Changed command to ts or transserver (fe. ts:list instead of trans:list)
- Transserver connections are now available as forward, use trans:id 
- Label is now an attribute for a default etc and can be altered
- History recording is now optional, default off
- Now supports !! like telnet
- Added >>>? to get a list of available commands

#### Others
- added selectOrCreate to XMLfab without a specified attribute
- removed the doDAS command, was not maintained anyway (and had become outdated)
- Updated the SQLite dependency, no changes in performance noticed
- Altered install service script to use wildcard
- Timetools using instant more (slightly faster than previous)
- Generic uses NumberUtils instead of own parseDouble
- Debugworker allows looping x times

#### BugFixes
- Influx wasn't mentioned in generic info if other dbid is present

### 0.8.3 (09/04/2021)

#### Taskmanager
- Removed the sqlite from the taskmanager, wasn't used anyway
- Added @rand6,@rand20 and @rand100 to task fill in

#### Streampool
- added rttext:x equivalent of rtval:x
- ss:addtcp and ss:addserial no longer require a label defined

#### Generics
- added gens:fromdb,dbid to generate all generics at once (if new)
- gens:addblank was missing info on how to add text (t)
- generics label is now case sensitive but accepts regex

#### Databases
- Added dbm:addrollover to add rollover to an sqlite
- Table no longer contains a default empty alias
- When tables are read from xml, table name and columnname are trimmed
- It's assumed that if there's an autofill timestamp column it's the first one
- Tables are no longer written to the xml if present in db on first connect
- Added command to write a table in memory to xml dbm:tablexml

#### Forwards  
- Added min length and max length filters to filterforward
- Added ff:alter command to alter the label (nothing else yet)
- Added nmea:yes/no as a possible filterforward rule, checks if it has a proper *hh
- TextForward resplit now actually does something with leftover

#### Other
- Updated dependencies
- Removed the sources inside devices, didn't belong  in the repo
- Trans now allows editing id during store command
- Removed the default sqlite from Issuecollector, need to actually assign one
- setRealtimevalue retains case
- Path to settings.xml etc no longer relative but based on the .jar location
  * takes in account if used as lib (if inside a folder .*[lib])
  
#### Bugfixes
- sleep command was always using rtc0, now it can be selected
- Influx.java was using the wrong dependency for String operations
- Influx should now appear in the list from st etc
- trans:store,x removed wrong nodes on adding history
- timestamp column is now really a timestamp column on database servers (used to be text by default)
- the gen:addblank format f made a text node instead of filler
- starting a task directly called the wrong method doTask instead of startTask
  in that case the interval etc tasks aren't run properly
- ff:addrule wasn't working as it should nor was the xmlread/write on for start type 
- tables read from a server weren't marked as such
- index of label read from commands wasn't correct
- removed default alarms taskmanager but didn't check if one was made later

### 0.8.2 (27/03/2021)
#### I2CWorker
 * Added command to add a device (and generate empty script)
 * Altered i2c:list to only show list of devices and commands
 * Added i2c:cmds to get full list of commands (eg. include the read/write)

#### Streampool
 * raw:id:sensorid now looks for a 'startswith' match if exact match is missing
 * Added calc:reqs and rtval:reqs to get a rough idea on the global requests made (reason, recent bug)
 * ff:reload now reloads all filters (previously only ff:reload,id was possible)

#### BaseReq
* dbm:reload,id now gives error feedback if any
* scriptid:list now gives a better listing
* added admin:gettasklog, this sends the taskmanager log
* added admin:getlastraw, this sends the latest raw file

#### Other
 * mf:addblank now supports source containing a ',' (eg. to i2c:forward,id)
 * Database tables are now also generated serverside
 * rtvals listing is now split according to prefix if any...
 
#### Bugfixes
- Email started BufferCollector now collects one minute of data again
- Multiple 'calc:' requests for the same thing across multiple telnet sessions weren't allowed anymore. Caused by a
previous change that should have fixed removing them.
- Database rollover screwed up in the beginning of a month due to weekly rollover
- Database no longer gets default 'remote' name when it already has a table
- script to command now allows numbers in the name/id

### 0.8.1  (14/03/2021)
####TaskManager  
 * Task(sets) with an id can now be started as a single command:
    * taskmanagerid:taskid/tasksetid => start the task/taskset (first tasksets are checked) same as tm:run,manid:taskid
    * taskmanagerid:? => get a list of all available tasks/tasksets
    * If a command exists that's the same as the taskmanager id, then the command is run
#### Other 
* Telnet
   * Pressing up arrow followed by enter, will redo the last command (but still show a bunch of symbols) 
* Databases
   * Added limited support for InfluxDB (can connect & write to it via generics) 
   * SQLiteDB now extends SQLDB instead of Database to allow Database to be more generic (for influxdb)
    
#### Bugfixes
* Sending empty string to telnet didn't work anymore (outofbounds)
* CalcRequests now have an entry for each request from the same writable (instead of overwriting...)
* Calc and rtval requests can no longer by duplicated (eg. asking twice the same won't give twice)

### 0.8.0 (14/01/2021)

### Summary
- Stream part has been revamped to allow easier expansion, introduction of writable etc
- the database part has been rewritten to use prepared statements instead
- Added collector and forward classes that either collect data from a source or forward it after altering
- Updated dependencies to latest version (except sqlite)

### Database_redo
- tables can store prepared statements (not the object)
- generics now use prepared statements
- generics that exactly match a table now directly write a query (instead of going through rtvals)
- each database has own query write thread

### Stream_Redo

**Braking changes**
- Default for addDatarequest is now title: instead of label, so raw:id will now become raw:id:streamid instead of raw:label:streamid

**Features**
- Tasksets that contain writing to a stream now fail if this writing failed or a reply was not given. Failure means tha failure task(set) is run
- rtval:x now actually updates in 'realtime' and no longer passes the transserver
- BaseStream allows for telnet commands to be executed on stream open, can be used to request data etc
- MQTT subscriptions can now be forwarded to a stream via mqtt:forward,brokerid (stream issueing the command will be used)
- Added LocalStream to use when you want to use the result of a FilterStream etc as a datasource

**Changes**
- StreamHandler was replaced with the abstract class BaseStream TCP,serial and telnet done, UDP and trans todo
- SerialHandler now extends BaseStream and is renamed to SerialStream
- Added the interface Writable, this allows a BaseStream etc to share its writing functionality
- Waiting for reply was moved out of the StreamHandler and in a seperate class ConfirmWritable, this has an listener te report succes (or not)
- Removed the use of StreamHandler in favor of BaseStream+Writable
- EmailWorker: Buffered respons of realtimedata now works with own class instead of through transserver
- TransServer: nearly complete rewrite, now acts purely as a tcp server for incoming connections (it used to provide the data to telnet etc)
- UDP converted to BaseStream
- PDM now using to writable+ConfirmWritable instead of StreamHandler
- TODO: CTD(shouldn't be in the core)
- StreamListener is now stored in arraylist in the stream to allow multiple listeners

### 0.7.2 (22/10/2020)

**Features**
- Improved command: rtval: now allows wildcards rtval:*temp -> rtvals that end with temp, rtval:temp* -> rtvals that start with temp, rtval:*temp* > rtvals that contain temp
- Improved command: rtvals now allows a second parameter that makes it get the same result als rtval but only once (not updated every second)
- XMLfab: added down() this allows to go down a level (aka child becomes parent) go back up with up()

**Changes**
- When a task checks a rtval and that rtval doesn't exist, the task and future executions (eg interval) are cancelled
- Changed command: gens:astable to gens:fromtable
- TransServer: rewrote the alterxml code to use XMLfab

**Bug Fixes**
- Issuecollector.Resolveds: getCount() was still size of the arraylist instead of the count
- StreamPool: When storing a stream to xml, the document wasn't reloaded so anything done to it since startup was lost
- SQLite rollover now actually happens 'cleanly' meaning that 1day is actually starts at midnight etc, cause was using the wrong object (original instead of temp)

### 0.7.1
**Features**
- Added command (gens:astable) that creates a generic based on a database table, existing one is updated
- Added command (dbm:tables,dbid) that give info on the stored tables
- Added command (tasks:addblank,id) that creates a blank tasks xml
- Added command (tasks:reload,id) that reloads a specific taskmanager

**Changes**
- Made database abstract and connect() and getcurrenttables() methods aswell
- gens:addblank now allows for delimiter to be given at the end (not mandatory)

**Bug Fixes**
- When adding a tcp stream with the streams:addtcp command, hostnames are now properly used.
- trans:index,request didn't process trans:0,raw:title:something correctly because split in : instead of substring
- Mathtools.calstdev tried running with empty array, caused out of bounds
- IssueCollector: the resolved instances were kept track of, this can get huge so limited it to the last 15

### 0.7.0 

**Summary**
- Database code revised
- TransServer operating revised
- Generics expanded
- XML syntax unified, added XMLfab to write XML files hiding boilerplate code from XMLtools

**Features**
- Generics can now:
  * write to database servers, this means that the database is interrogated regarding tables and the columns in order to know what to write. It also possible to write to multiple db's from a single generic ie. dbid="db1,db2"
  * Use received data to determine the rtval reference, so if logging multiple of the same sensor and the data contains an identifier this can be used with @macro (and <macro index="1"/> to define it).
    This can also used with a database writeRecord using the 'alias' functionity with @macro added.
  * Write to a MQTT broker (see manual for usage)
- Streams that use generics can be requested from the transserver, raw:generic:id or raw:generic:id1,id2,id3 etc
- SQLiteTable: Added the option to use a default value incase the rtval isn't found
- TransServer: Added trans:reqs to see the currently active requests (includes email)
- Added some commands to add blank xml elements for generics,tables etc

**Changes**
- QueryWorker: removed
- Database: replaced
- SQLDB,Database: result of rewriting database code so the usage of sqlite and sql server is the same for the end user
- All DAS related things are now in a single sqlite with the id 'das'
- SQLiteDB: moved all general stuff to Database.java and it extends this (SQLDB does this to)
- Status command: Now shows '!!!' if a database is not connected
- Created a class called XMLfab to write xml files with less boilerplate code
- XMLtools getChild... now returns ArrayList instead of array to be able to use lambda's
- das.getXMLdoc() now reads the file, use das.getXMLdoc(false) to use the one in memory

**Breaking changes** (or the reason for the version bump)
- Database tag replaced with server
- Databases->server tag in the xml now requires a id attribute from the second database onwards, first one gets 'main' if none given
- RealtimeValues: all query write methods have been renamed to reflect the database independence (eg. same method for sqlite and server)
- TaskSet: changed attribute from 'short' to 'id' and 'name' to 'info'
- Database in xml: renamed server tag to address
- StreamPool: changed methods etc to consistenly use stream instead of mixing stream and channel and tag title replaced with attribute id
- BaseReq: The methods now receive String[] request instead of String request, request[0]=title, request[1]=command
- Digiworker: password attribute changed to pass
- Telnet: title and port are now attributes in xml instead of elements/nodes

**Bugfixes**
- TaskManager: fillin with both @mac and @ipv4 present didn't work, looked for 'lastindexof' @ which is incorrect if both present
- IssueCollector: added newline to getIssues result if 'none yet'
- TransServer: 
  * rtval: and calc: were broken, no idea since when the default ending wasn't processed correctly
  * rtval/calc didn't process buffer full for email requests and now only the queue only processes nmea or emailrequest data so that full wasn't checked anymore
- SQLiteDB: Only giving the filename in the xml path resulted in a nullpointer because no actual parent, so now this is checked and absolute path is used if so.
- MQTTworker: only last subscribe was kept, the clear was in the for loop instead of before
- StreamHandler: notifyIdle was called twice instead of notifyIdle and notifyActive, so this causes a permanent idle
- DatabaseManager: having a threadpool of 2 causes issues if there are two databases rolling over at the same time... because both threads are used and none are left for queries, so it waits forever.
- Database: when not all tables are defined in the xml the rest wasn't retrieved from the db, now if it doesn't find a table it tries retrieving once

### 0.6.2
**Features**
- Added 2 macro's for the task system commands: @ipv4:interface and @mac:interface@ to retrieve the IP or MAC of a networkinterface (fe. @mac:wlan0@)
- Added system command to add a tcp stream: streams:addtcp,title,ip:port,label uses default delimiter (crlf), priority (1) and ttl (-1)
- Added system command to save the settings of a stream to the xml: streams:store,title

**Changes**
- StreamPool: 
  * now uses an interface/listener to get notifications from the StreamHandlers instead of going through the BaseWorker
  * Same listener is now used to get request from the TransServer. Instead of all data in the transqueue data the handler sends it through the channel directly. Not sure yet if that can cause concurrency issues... So 'old' code won't be removed yet (so commented).
  * I2C and email still follow the 'old' route

**Bugfixes**
- doConnection used the threadpool from netty, but netty doesn't like using the bootstrap concurrently, swapped it back to own scheduler
- Path to the settings.xml wasn't correctly found on linux (for some reason it worked fine on windows)

### 0.6.1
**Features**
- Added 'Generic' this allow for processing streams without writing BaseWorker code. This is limited to simple delimited data that doesn't require processing
  * All the setup is done in the settings.xml
  * Need to define delimiter and the table/database to write to, for now only sqlite is supported
  * For now only real and integer are supported, string not (yet)
  * Can be updated during runtime with generics:reload or gens:reload command
- SQLiteDB can now be defined in the settings.xml, rollover is supported but things like 'unique,not null,primary key' not (yet)

**Changes**

- TaskManager: 
  * Now has it's own file for logging, taskmanager.log
  * taskset have the interruptable attribute to prevent a reload while the set is active
  * Taskset 'short' is deprecated and replaced with id (will be removed in 0.7.x)
- RealtimeValues: added .getSQLiteInsert( String table ), gets the Insert for this table with the current values
- SQLiteDB: Added createDontDisconnect
- MQTT: Add MQTT to status info (telnet st command), decreased the amount of debug info
- TransServer: Add command to reload defaults (trans:reload)
- SQLiteDB: Timestamp and epoch are 'is not null' by default

**Bugfixes**
- SQLiteDB: When using monthly/weekly rollover that current filename was wrong because offset wasn't applied
- BaseReq: Commands to a TaskManager that include a ',' got cut off (.split(",") was applied)
- SQLiteManager: RunQueries assumes the connection is ok, but the code calling it wasn't. Now a method is called that first checks and connects if needed.
- Task: Interval trigger was wrongly interpreted (interval was used twice instead of delay,interval)

### 0.6.0
**Features**
- Easy to use API to work with (time limited) SQLite databases added, see DAS Core manual for usage

**Changes**
- SQLiteManager added (user won't interface directly to this), this takes care of managing all the SQLite databases in use, managing means:
  - Take care of query execution in a thread
  - Check if the age of the oldest query hasn't passed the threshold for writing the queries to the database
  - Take care of the 'roll over' from one file to the next (hourly,daily databases etc)
- SQLiteWorker, QueryWork removed as no longer used
- All the netty stuff (telnet,transserver,streampool) use the same worker pool instead of each their own

### 0.5.1
**Features**
- Added an Insert class that helps creating an insert statement but isn't required to use (hence only minor version update)

### 0.5.0
**Features**
- Added command (admin:sqlfile,yes/no) to dump queries processed with QueryWorker to file, uses tinylog for this with tag=SQL (and warn level).  
- Inbox check interval can now be alterd with a command  
- Emails can have an alias, but this is only useful in the case of admin notification if dasbot receives a command so now e-mails send from the admin alias don't trigger an admin notification.
- Added tasks:manager_id,run,tasksetid to run a certain taskset
- Added alternative way to construct the 'create table' statement for SQLITE

**Changes**
- Reworked xml syntax for EmailWorker (old one no longer works) and other related things
- Removed the check done by DAS.java for processed raw/query per second, this is now done by the class themselves instead.
- Uptime is now mentioned in status
- Changelog is now in md format
- Improved robustness of email checker
- Update the DAS Core manual to reflect recent changes
- Version numbering changed from 1.x.y to 0.x.y to reflect none fixed API
- toString of a task with output log now shows time till next execution if trigger is clock
- Updated the dependencies to the latest version (except SQLite because armhf issues)

**Bugfixes**
- Forgot to alter after copy paste of the starttaskset method causing 'no such manager' reply because manager id was swapped with taskset id
- Time format in the status email was wrong (MM vs mm)
- Normally emails to the emailbot from the admin should trigger a notification to the admin(s), but was the case if admin email and sent from email isn't the same (aliasses), added feature fixed this.
- Notify of thread dead was in the wrong place for digiworker and emailwoker causing fake-death messages, moved to correct spot
- The admin:setup command required another argument this was wrong (reason was admin:script,scriptname earlier) now it is happy with just one
- Email checker issues should now be resolved, probably a timeout that wasn't set which means infinite
- StreamHandler now ignores 'PortUnreachable' when used for UDP clients (was only an issue in rare cases).
- Double execution of timebased tasks might no longer occur... (hard to reproduce).
- QueryWorker reconnect wasn't closing open connections when reconnecting (con=null without checking) now it is (fixes SQLite usage)
- QueryWorker executed queries count fixed
- double execution of time based tasks fixed

### 0.4.2
- never released, skipped to 0.5.0

### 0.4.1
**Features**
- Added tasks:remove,x to remove a taskmanager
- Added sleep:<time> this only works on linux for now but puts the processor in sleep for <time> fe. sleep:5m => sleep 5 minutes
  After sleeping, tasks with the keyword "sleep:wokeup" are executed.

**Changes**
- Made static variables final
- Replaced last system.out with Logger.info/Logger.debug 

### 0.4.0 Database rework
- QueryWorker, SQLiteWorker and Database got reworked for better error handling and easier debug
- Replaced .equals("") with .isBlank()
- Added boolean get methods to the XMLTools (to get a boolean textnode or attribute)
- Reverted a lot of the \r\n replacments... should only be done for file interaction and not telnet...
- fixed the calculateseconds method, 24h wasn't done properly (1s off), added nanos to tasktime to fix it

### 0.3.0 JDK8 update
- Replaced some loops with foreach lambda
- Replaced most StringBuilder with StringJoiner
- Replaced \r\n with System.lineSeparator()
- Replaced System.currentTimeInMillis with Instant.now().toEpochMilli()
- Updated the TaskManager calculateseconds to the time classes

### 0.2.1 
- queue properly handed to RealtimeValues (was only to extended version)
- SQLite now checks for each query if the table exists before executing the queries (should fix the bottles issue)
  This also means that the only create table to pass is to one needed for the query.
- DAS used to check the threads every minute to see if still alive, this was replaced with a listener
  This now also raises an issue which can be acted upon.

### 1.2.0 
- I²C support improved a lot

### 0.1.0 
- MQTT support added 
- Fresh start, initial commit of the core





