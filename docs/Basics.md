## Introduction

>**Note: Document up to date for 2.5.0**

The purpose of this (probably in the end very long) page is to slowly introduce the different components in dcafs and how to use them.
The basis will be interacting with a dummy sensor that simulates rolling a d20 (a 20 sided die). This wil be simulated by another instance of dcafs running on the same system. Do note that practicality isn't the mean concern, showing what is (or isn't) possible is.

The dummy sensor is in fact just dcafs running a purpose made settings.xml and script.  
Nothing in the source code has been altered to make it possible, so it should be independent of the version used (unless because of new bugs).   
How the dummy works will be explained on this page (somewhere). The only thing that matters for now is that it is running a TCP server on port 4000.

>Note: This guide assumes [Java](https://adoptium.net/) (at least 17, install the latest LTS version) and a telnet client (such as [PuTTY](https://www.chiark.greenend.org.uk/~sgtatham/putty/latest.html)) have been installed already.
Dcafs doesn't have a GUI and uses a telnet server for interaction instead.

To start off with a glossary:

* `stream`: tcp/udp/serial connection that can receive/transmit data
* `source`: any possible source of (processed) data
* `command`: a readable instruction that can affect any part of the program, always abbreviated to cmd
* `forward`: an object that receives data from a source, does something with it and then gives it to a writable

And some important commands:
* For a general beginners aid, use `help`
* To get an overview/status of all the streams,databases and such, use `st`
* To shut down the instance of dcafs, `sd:reason` fe. `sd:updating to new version`

### Setup & startup
1. Download the latest release version of dcafs from the [dcafs releases page](https://github.com/michieltjampens/dcafs/releases). Pick the zip file that contains all files.
2. Extract it to a working folder, make a second copy of it and rename that one to dummy. Keep the original.
3. Download the [Diceroller]([examples/Diceroller.zip](https://github.com/michieltjampens/dcafs/raw/main/examples/simulations/diceroller/diceroller.zip)) package
4. Extract the content into the dummy folder (fe. settings.xml should be on the same level as the .jar)
5. From now on, the dcafs version that generates the dummy (diceroller) data will be called "dummy", the other one we'll call "regular"
6. Start both the regular and dummy dcafs (double-click on their respective .jar files). If your firewall (Windows Defender, ...) ask permissions you'll have to grant them.
> Note: If you get a JNI error message, this likely means an incorrect version of Java
7. `settings.xml` should be generated in the regular folder. 

> Note: Dummy can be accessed via telnet if needed, it is listening on port 24 instead of the standard 23. Dcafs refuses to start if there is a telnet active on port 23. This prevents duplicate instances running.

### Workspace layout

The following is the recommended workspace layout for setting up dcafs in general:
* Have two dcafs telnet instances open
  * Open PuTTY, select telnet as the protocol, use `localhost` as the ip, keep the default port.
     * Localhost means that dcafs is running on your PC/laptop 'locally'.
     * Type 'dcafs' in the 'saved session' box and click 'Save'.
     * Click 'Open', to open the connection.
  * Once open, right-click on the title bar and select 'Duplicate instance' to open the second instance.
    * You'll probably want to make this window larger (or adjust it accordingly later)
    * There's no real limit to the amount of instances.
  * The idea is to use one window for showing long term info (like help etc. or data updates) and the other to issue other commands
* Keep the settings.xml open and visible, [notepad++](https://notepad-plus-plus.org/downloads/v8.5/) is a suitable lightweight editor
  *  It's possible to have it auto-update on changes done by dcafs
     *  Settings -> Preferences -> MISC. -> Update silently (checkbox) -> (click close)
  *  Working with xml is made a lot easier by installing the XML tools plugin
     * Plugins -> Plugin Admin -> search for 'XML tools' (click next) -> click checkbox -> install
     * This will auto-close xml nodes (something that's easily forgotten)
  * In case you read the markdown docs locally, install the Markdown panel plugin
    * Plugins -> Plugin Admin -> search for 'Markdown panel' (click next) -> click checkbox -> install
    * To enable: 
      * Plugins -> NppMarkdownPanel -> Toggle Markdown Panel
      * Or find the small purple icon on the toolbar and click it (near the end about six from the eye)


## Interacting with Regular

The earlier workspace layout connected to dcafs. 

As a first step type `help`(in either screen), which would result in the screen below:

For the sake of consistency, we'll follow the 'recommend workflow' from the help.

#### Nothing happens or something goes wrong?

Congratulations, you found a bug! Or made a typo...  
If you suspect a bug, check the subfolder 'logs' it should contain a dated errorlog and a single info.log. Both might
give a hint on what went wrong.
If this doesn't help, create an [issue](https://github.com/michieltjampens/dcafs/issues) about it (and maybe attach those logs)
and I'll look into it.

## A. Let's take it slow

### 1. Connect to a data source
The data source (our dummy simulating a d20 dice) is a TCP server. Typing `ss:?` in the (second) telnet session will give a lot
of information, but the only line interesting now is:  
`ss:addtcp,id,ip:port` which connects to a tcp server.

For this example, this becomes `ss:addtcp,dice,localhost:4000`.   
You should see _Connected to dice, use `raw:dice` to see incoming data._ as the reply.
If you try that, hit `enter` to stop it.

Use `st` to see the current state of dcafs. This will give you a lot of info including a section about streams which
should look like this:

>Streams  
>TCP [dice] localhost/127.0.0.1:4000        870ms [-1s]

To explain the whole line:
* TCP : It's a TCP connection
* [dice] : The ID is 'dice'
* localhost/127.0.0.1:4000 : The hostname is localhost, IP is 127.0.0.1, and we connected to port 4000
* 870ms : Received last data 870ms ago
* [-1s] : The stream is never considered idle, the term used for this period is ttl or 'Time to live'

If there's something wrong there are three options:
* **NC** at the beginning of the line means Not Connected
* **!!** at the beginning of the line means no data was received in the chosen timeout window (in our case this is
  [-1s], so disabled)
* **No data yet** at the end, means that no data has been received since the connection was made

Some properties of the stream can be changed via telnet commands. To do this, the command is like `ss:id,ref,value`.  
Some examples:
* `ss:dice,ttl,3s` would change the ttl for the connection to 3 seconds, do note that 3000ms is also allowed
  (as well as 1m10s, 1h etc.)
* `ss:dice,eol,cr` would change the eol (end-of-line) character(s) to carriage return (\xD would also be accepted)
  from the default crlf (or \xD\xA)
> **Note:** If you try them, this will change the settings you'll see below.
> Make sure to correct the settings file to match the one below before proceeding any further.

There are more, but those are the most commonly used.
What we did so far (starting up dcafs and connecting to a stream) generated a `settings.xml` file that looks like this (without some comments):
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<dcafs>
  <settings>
    <!-- Settings related to the telnet server -->
    <telnet port="23" title="dcafs"> <!-- The telnet server is available on port 23 and the title presented is dcafs -->
        <textcolor>lightgray</textcolor> <!-- default color of standard text in telnet -->
    </telnet>
  </settings>
  <streams>
    <!-- Defining the various streams that need to be read -->
    <stream id="dice" type="tcp">
      <eol>crlf</eol> <!-- Messages should end with \r\n or carriage return + line feed -->
      <address>localhost:4000</address> <!-- In the address, both ipv4 and hostname are accepted, IPv6 is wip -->
    </stream>
  </streams>
</dcafs>
```
Some basic information about xml to start:
* Anything starting with <word> and closing with </word>, is called a node (the text in it a 'tag').
    * The first such node is called the rootnode, so <dcafs> ... </dcafs> (tag is dcafs)
    * A node inside another node is called a childnode, so settings is a childnode of dcafs and so on
* A node can have content, so like eol has crlf as content
    * A node without content and without childnodes can be closed with just a / fe. <stream id="hello" />
        * This is not mandatory, just makes it a bit shorter
* A node can have attributes, the telnet node has port and title as attributes

Everything in a stream node of the `settings.xml` file (except the ID) can be altered while running and will be applied
without restart. To reload a stream after changing something in the `streams` node use `ss:id,reload` or in our case `ss:dice,reload`.  
Or all at once with `ss:reload`.
>**Note:** To connect to other data sources (like serial or modbus), the command `ss:?` shows a list of options.

### 2. Look at the received data

To see the data as it's coming in, use the `raw:streamid` command, so `raw:dice`. Alternatively 
`stream:streamid` is also valid, just a bit longer.
>**Note:** You don't need to type the full id, just make sure it at least 'starts with' and is the only option.
> So `raw:dic` would have worked  or even `raw:d`. Because no other stream id's start with dic or d.

Result could be (highly unlikely, because its random):
```
d20:9
d20:3
d20:7
d20:19
d20:12
```
To stop this constant stream of data, send an empty command (press enter).

> Note: By default all data received (but not send) is stored in .log files that can be found in the /raw subfolder.

### 3. Store the last value in memory

For now, the only thing done is storing the data from the dummy sensor in the raw data log file, that's it.  
To actually keep the last value in memory there are multiple options. For now, we'll stick to the easiest one, 'store'.

Values in memory are referred to as 'rtvals', which stands for realtimevalues.    
Some basic info about rtvals:
* There are currently four types: real,int,text and flag
* Each rtval belongs to a group and has a name, the id of the rtval is group_name
    * A name can be repeated inside a group if the type is different, but it's better not to.

One way to create and set those values is called 'store', the name is from the optional node inside a stream.  
Store splits incoming data and assigns the values to the predefined rtvals.

So, to define the store,we need to analyse the format of the data.
* Given that the data looks like 'd20:xx' in which:
    * `d20` is the prefix
    * `:` is the delimiter
    * `xx` is the possible roll result (1-20)

The data needs to be split on ':' and then the second element (but counting from 0) is the integer we want.

An overview of all the commands available for store is given with the `store:?` command.  
(Issue that command in the window that had the ss:? result)

Because all the commands start with the same `store:streamid,` we'll let the interface prepend this with `store:dice,!!`.  
This tells dcafs that all the following things we'll send need to have everything in front of the '!!' appended.

> Note: This can be cancelled by sending just !!

Now defining the store:
* First we'll set the delimiter to ':' with `delimiter,:`
    * The default delimiter is ',', so if that was the case setting it can be skipped
* Next up is adding an integer `store:streamid,addint,id<,index>`
    * which we'll call 'rolled' and it's at index 1 so this becomes `addint,rolled,1` or `addi,rolled,1`

Now that the store is defined, use `!!` to remove the prefix.

The result of those commands can be seen in the settings.xml.
```xml
<stream id="dice" type="tcp">
    <eol>crlf</eol>
    <address>localhost:4000</address>
    <store delimiter=":">
        <int i="1" unit="">rolled</int> <!-- unit isn't used for now -->
    </store>
</stream>
```
Everytime a store command is issued, dcafs applies those changes.

To see if it actually worked, look at the values in memory with `rtvals`.
````
Status at 2023-03-04 00:35:39  

Group: dice  
   rolled : 20  
````
* Because a group wasn't specified, the id of the stream is used.
* If we had filled in a `unit` earlier, this would have been appended to the 20

**Extra:** you can get updates on specific rtvals using `type:id` so in this case `int:dice_rolled` or `integer:dice_rolled`.  
Once again, press return to cancel the request for updates.

Another option is`rtvals:name,rolled` which list those with the name rolled (once instead of streaming).   
Or if not so certain on the name `rtvals:name,rol*` or actually using regex `rtvals:name,rol.*`
(.* means any amount of any character)

### 4. Simple alterations

There are two ways to alter raw data before it's written to memory (rtvals). The main one is 'paths', which will be
explained later. But store itself also has a couple -limited- options.

As mentioned earlier, store takes care of parsing the string data to the relevant rtval. But this parsing can be changed.
The options are:
- real/int -> apply an operation to the value before storing it
- text -> replace one value with another
- flag -> use other values for true/false (instead of the default like 1,yes,true,high for true)

Altering the store to increase the roll with 5: (for now, there's no command to do this)
````xml
<store delimiter=":">
      <int i="1" name="rolled" unit=""> <!-- because the content will contain the op, the name becomes an attribute -->
        <op>i=i+5</op>
        <!-- 'i' is the input received -->
        <!-- After parsing to an integer, the value will get 5 added before being stored in the rtval -->
      </int> 
</store>
````
This can be applied with the cmd `ss:dice,reloadstore`.
Or if the roll was stored as a text instead. The node below replaces some bad rolls with better ones
````xml
<store delimiter=":">
      <text i="1" name="rolled" unit=""> 
        <parser key="1">11</parser>
        <parser key="2">12</parser>
        <parser key="3">13</parser>
        <parser key="4">14</parser>
        <parser key="5">15</parser>
        <parser key="6">16</parser>
        <keep regex=".*"/> <!-- Don't alter the other values -->
      </text> 
</store>
````
Or if you just want to keep track of a bad or good roll (apply with `ss:dice,reloadstore`).
````xml
<store delimiter=":">
      <flag index="1" name="rolled" unit="">
        <true delimiter=",">16,17,18,19,20</true>
        <false delimiter=",">1,2,3,4,5,6,7,8,9,10,11,12,13,14,15</false>
      </flag> 
</store>
````

### 5. Store the last value in a database

> Note: To look at a created sqlite database, install an SQLite viewer like [DB Browser for SQLite](https://sqlitebrowser.org/dl/)

The code doesn't differ much between SQLite or a database server, so we'll go with SQLite.  
For a full list of database related commands, use `dbm:?`
The one we are interested in now is `dbm:addsqlite,id(,filename)`, filename is optional, and the default is id.sqlite
inside the db subfolder.  
So `dbm:addsqlite,rolls`
>Created SQLite at db\rolls.sqlite and wrote to settings.xml

And indeed in the settings.xml the following section has been added (comments here added for information):
```xml
    <databases>
      <sqlite id="rolls" path="db\rolls.sqlite"> <!-- This will be an absolute path instead -->       
        <flush batchsize="30" age="30s"/>
        <idleclose>-1</idleclose> <!-- Do note that this means the file remains locked till dcafs closes -->
        <!-- batchsize means, store x queries before flushing to db -->
        <!-- age means, if the oldest query is older than this, flush to db -->
        <!-- idleclose means, if the connection hasn't been used for x period (eg. 10m), close it or never if -1 -->
      </sqlite>
    </databases>
```
Next the database needs a table to store the results `dbm:id,addtable,tablename(,format)`
There are two options to do this, but we'll just show the shortest:

- Create the table with:`dbm:rolls,addtable,dice`
- Use the prefix thing again with `dbm:rolls,addcol,dice,!!` (addcol is the cmd to add a column)
- Add the first column with: `utc:timestamp`
    - dice - refers to the table, could also be rolls:dice but given that there's only one dice this can be omitted
    - utc:timestamp - utc is short for columntype 'utcnow' and the name is timestamp (utcnow is auto-filled)
- Add the second column with: `i:rolled` (or `int:rolled`)
    - All the same except it's i for integer (there's also t/text,r/real,ldt=localdt now,dt=datetime)
- Stop the prefix with `!!`

This will have altered the sqlite node accordingly:
````xml
<sqlite id="rolls" path="db\rolls.sqlite"><!--will be absolute path -->
  <flush age="30s" batchsize="30"/>
  <idleclose>-1</idleclose>
  <table name="dice">
    <utcnow>timestamp</utcnow>
    <int>rolled</int>
  </table>
</sqlite>
````

To apply this `dbm:rolls,reload`, this will generate the table in the database.

To check if it actually worked: `dbm:rolls,tables`
>Info about rolls  
> Table 'dice'  
>\> timestamp TEXT (rtval=dice_timestamp)  
>\> rolled INTEGER (rtval=dice_rolled)

A column has a name and a rtval, the name is how it's called in the database while the rtval is the corresponding
rtval in dcafs. The default rtval used has id `tablename_columnname`.

By default, the rtval attribute is hidden but if this wasn't the case the node would actually look like this.
````xml
  <table name="dice">
    <utcnow rtval="dice_timestamp">timestamp</utcnow>
    <int rtval="dice_rolled">rolled</int>
  </table>
````

Do note that the columntype of timestamp is TEXT. This is because sqlite doesn't have a datetime equivalent and TEXT is
the recommended columntype alternative.

And `st` (for status) also got updated:
> ...  
> Databases  
> rolls : db\rolls.sqlite -> 0/30 (NC)

So now there's a database ready (go ahead and open the sqlite db in a viewer). But no connection is active (NC).  
It is, however, still empty as no data gets written to it... yet.

In order to actually get data in it, the store must know about where the data needs to go to.  
Issue `store:id,db,dbid:table` which becomes `store:dice,db,rolls:dice`.

The result:
```xml
    <store db="rolls:dice"  delimiter=":" >
        <int id="rolled" index="1" unit=""/>
    </store>
    <!-- db can contain multiple id's separated with "," but the table structure must match between databases.
               As such, it's easy to have a sqlite database as backup for a server without any additional code -->
```

Not sure if this is going to explain it or make it harder to understand.  
The db attribute doesn't mean that the store is the one writing to the database.  
If you check `dbm:?`, under the title 'Working with tables' there's `dbm:dbid,store,tableid`.  
So what the store does is execute that cmd with the data from the attribute. In other words, there isn't
any link between the store and the table(s) (on a source code level).

To check if something is actually happening, check `st` again, you'll see that it's no longer 0/30 (NC) and the
sqlite is slowly getting bigger. If not, check the logs.

### 5. What if...

This section will make small changes to the previous setup, mainly to show small variations and available options.

#### The name of the table doesn't match the group of the store?

There are two ways to fix this, either change what the table looks for or what the store uses.

**What the table looks for**

A lot of attributes are hidden if they contain the default value. Suppose this wasn't the case and the tablename
was actually 'd20s', then the sqlite node would have looked like this
````xml
<table name="d20s"> <!-- d20s instead of dice -->
  <utcnow>timestamp</utcnow>
  <int rtval="d20s_rolled">rolled</int> <!--rtval attribute is shown -->
</table>
````
So the easiest fix is to alter the rtval attribute in the xml to 'dice_rolled'.
````xml
<table name="d20s"> <!-- d20s instead of dice -->
  <utcnow>timestamp</utcnow>
  <int rtval="dice_rolled">rolled</int> <!-- d20s replaced with dice -->
</table>
````
Save the file and then do the `dbm:rolls,reload` command again to apply it. Then `dbm:rolls,tables` can be used to verify.

**What the store uses**

By default the store determines the group to which the rtvals belong. But if the store itself doesn't define a store, the 
id of the stream is used. Below is how the store is actually read by dcafs
````xml
<store delimiter=":" group="dice"> <!-- group with the id of the stream is used -->
    <int index="1" unit="" >rolled</int>
</store>
````
There's a general rule that if a node (here 'int') expects a certain attribute that is defined by the parent node (here 'store') but
not in the node itself, it will use the value of the parent node.
So because int doesn't have a group node, it takes the value from the store node and becomes 'dice'.

Because all the rtval can belong to the other group, just using `store:dice,group,d20s` makes the changes.
````xml
<store delimiter=":" group="d20s"> <!-- change the group for the whole store -->
    <int index="1" unit="">rolled</int>
</store>
````
Another option would have been:
````xml
<store delimiter=":">
    <int group="d20s" index="1" unit="">rolled</int>
</store>
````

#### Periodic SQLite?

It's called rollover (db rolls over to the next), the command for it `dbm:id,addrollover,count,unit,pattern`.
* id is the SQLite id
* count is the amount of unit to rollover on
* unit is the time period of the count, options are minute, hour, day, week, month, year
* pattern is the text that is added in front of .sqlite of the filename, and allows for datetime patterns.
  * Alternative position can be given be adding `{rollover}` in the filename, this will be replaced 

So as an example a monthly rollover: `dbm:rolls,addrollover,5,min,_HHmm`. 
Other options for unit are: hour, day, month, year.
To apply it: `dbm:rolls,reload` 
```xml
    <databases>
      <sqlite id="rolls" path="db\rolls.sqlite"> 
        <rollover count="5" unit="minutes">_HHmm</rollover>
        <flush age="30s" batchsize="30"/>
        <idleclose>-1</idleclose>
        <table name="dice">
          <utcnow>timestmap</utcnow> 
          <int>rolled</int>  
        </table>
      </sqlite>
    </databases>
```
Suppose this is active at 10:12:15, the filename will be rolls_1012.sqlite.
Dcafs will make the next filename be a 'cleaner' division, so it will be named rolls_1015.sqlite and so on. 

#### Using a database server?

> Note: this just serves to show how to add a server to dcafs, this won't install said database server

Going back to the `dbm:?` command, its shown that database servers are also an option.  
Let's take MariaDB as an example: `dbm:addmariadb,id,db name,ip:port,user:pass`

Suppose:
* give the id diceserver
* it's running on the same machine and using default port (then you don't need to specify it)
* It has a database with the same name as the sqlite one made earlier
* Security isn't great and user is admin and pass stays pass

The command becomes`dbm:addmariadb,diceserver,rolls,localhost,admin:pass`  
Which in turn fills in the xml.
> Note: Attributes are sorted, that's why it's first pass and then user...
````xml
<databases>
    <server id="diceserver" type="mariadb">
        <db pass="pass" user="admin">rolls</db>
        <flush age="30s" batchsize="30"/>
        <idleclose>-1</idleclose>
        <address>localhost</address>
    </server>
</databases>
````
All the rest is the same as the SQLite. (meaning adding the table and using the generic with the new db attribute)

### 6. Summary
This should serve as a broad, toplevel overview of what happens and what goes where or has which function.

* Create a `stream`. The ID will be what you use to refer to it afterward.
    * Some references that can be altered: `ttl`, `eol`.
* A `store` will process the data by splitting it at the delimiter.
    * Each "field" that results from the split can be given a name and type (int, text ...)
    * One of the attributes of a `store` is the group. This allows you to group incoming data (shown using `rtvals`).

## B. Altering the raw data

Restore the settings.xml to restart from a clean slate.
````xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<dcafs>
  <settings>
    <mode>normal</mode>
    <!-- Settings related to the telnet server -->
    <telnet port="23" title="dcafs"/> <!-- The telnet server is available on port 23 and the title presented is dcafs -->
  </settings>
  <databases>
     <sqlite id="rolls" path="db\rolls.sqlite"> <!-- fe. db\rolls_2021_05.sqlite -->        
        <flush age="30s" batchsize="30"/>
        <idleclose>-1</idleclose>
        <table name="dice">
            <utcnow>timestmap</utcnow>
            <integer>rolled</integer>
        </table>
     </sqlite>
  </databases>
  <streams>
    <!-- Defining the various streams that need to be read -->
    <stream id="dice" type="tcp">
        <address>localhost:4000</address>
    </stream>
  </streams>
</dcafs>
````

Then use the `sd` command to shut down dcafs and start a new instance (launch the `.jar`).

### 1. Creating a path

As the name implies, a path defines how the path the data travels in dcafs.

There are two options for the path nodes, either inside the settings.xml or in their own file.
For small projects, inside the settings.xml is fine.

But when working with multiple (longer) paths it's recommended to give each path their own file.
This also makes it easier to copy-paste them between projects.

For this example, the path will be added inside the settings.xml.
Paths have their own cmd's, which are listed with the `pf:?` cmd (pf=pathforward).

So create an empty path with: `pf:dice,new,raw:dice`.
* dice is the id of the path, we'll use it to cheat the dice rolls with it
* raw:dice is the source of the data

The result is an extra node added:
````xml
  <paths>
    <path delimiter="," id="dice" src="raw:dice"/>
  </paths>
````
For now the path is empty, but 'steps' will be added (as childnodes in the xml).
These are the 'steps' the data takes while being travelling along the path.

A path doesn't need a delimiter, but the steps might. That's why a default delimiter can be set
that will be used by a step (if none is specified by the step).

Because we'll use the same data as before, the delimiter can be altered to ':': `pf:dice,delim,:`.

### 1. Filter
There's very little data to filter... unless you want to cheat! (in a much too obvious way)   
Filtering is based on rules, the list of options at `help:filter` might contain something to cheat with.  
Given how the data looks, choices are limited...
> minlength : the minimum length the message should be

Let us use that to claim we never roll below 10, the command to add a filter with a single rule `pf:pathid,addfilter/addf,rule:value`.  
Filled in: `pf:dice,addf,minlength:6`

Now use `path:dice` to see the result. If you don't see anything, it either doesn't work, or the rolls are really unlucky.  
You could open a second telnet session and use `raw:dice` to see the unfiltered data.

Below is what was added to the settings.xml.
````xml
<paths>
    <path delimiter=":" id="dice" src="raw:dice">
        <filter type="minlength">6</filter>
    </path>
</paths>
````
To write this to the database, a store node needs to be added. Because it's inside a path, the cmds are different.  
All the cmds start with `pf:pathid,store,`, using !! again `pf:dice,store,!!`
* Because a store takes group from the parent id (dice), this doesn't need to be set
* To add an int to a store: `addint,name<,index>`, so `addint,rolled,1`.
    * If a store is the last step in the path, the int will be added to that store
    * If no store is at the end, a new one will be created for it.
* Then finally, to set the db reference, `db,rolls:dice`
* Next `!!`, to go back to normal entry

````xml
<store db="rolls:dice">
    <int i="1" unit="">rolled</int>
</store>
````
Now when you check the rtvals or the database, results below 10 shouldn't appear anymore. But in the database, it might
be obvious that there are gaps... this could be fixed but that's for another section.

> Note: For now, a message much comply with all filters. The only exception to that rule is startswith, you can add multiple
> of those in a single filter, they will be or'd instead.

This is a really short intro into FilterForward, check the dedicated markdown page for more.

### 2. Math

So we managed to cheat, but it's way too easy to spot, so we'll make it bit harder...
* Start over by clearing the path, `pf:dice,clear`
* To use the prefix'ing again `pf:dice,!!`
* Then we want a filter that removes values below 10 `addf,maxlength:5`
* Then add a math operation, `addm,i1=i1+5`
    * Like earlier with the store, math also splits the data and those i's refer to that (and start at 0)
    * The operation applied is i1=i1+5, so the number at index 1 will be increased with 5
      * In contrast to the store op, the index number must be specified because the math gets everything 
    * After this is done, the data is reconstructed fe. d20:5 -> d20:10
* Then just copy-paste the earlier made store underneath it or...
    * `store,addi,rolled,1`, `store,db,rolls:dice`
* Back to normal input with `!!`

Below is how it will look in xml.
````xml
  <paths>
    <path delimiter=":" id="cheat2" src="raw:dice">
        <filter type="maxlength">5</filter>
        <math>i1=i1+5</math>
        <store db="rolls:dice" group="dice">
            <int i="1" unit="">rolled</int>
        </store>
    </path>
</paths>
````

So now if we use `int:dice_rolled` we'll see rolled as it's updated. Add `raw:dice` to see the raw that is
used.
````
d20:16
dice_rolled:16
d20:1
dice_rolled:6
d20:11
dice_rolled:11
d20:7
dice_rolled:12
````
As a (last) reminder, press enter to stop the data from appearing.

Some extra info on maths:
* There's no limit to the amount of op's
* The op's are executed in order, so if the first one alters index 1 the next one will use the updated value
* Besides +, other supported ones are -,/,*,^ and %
* Brackets are allowed but not mandatory because it will follow the priority rules with the minor exception that % has
  lower priority than / and * (who share priority). So 5+2*4 will be 13 and not 28.
* Both Scientific notation (15E2) and hexadecimal (0xFF) are allowed in both data received and op's.

There's no function (yet) for logical operations in MathForward nor FilterForward, so that's it for cheating...
It still might be obvious that 1 to 5 never appear but there's little that can be done about that (for now), besides
its random, so we might just be lucky.

This was a really short intro into MathForward, check the dedicated markdown page for more.

### 3. Editor

The third forward is capable of altering the data with string operations.  
There are to many edit options to list here, so use `help:editor` to get an overview including example xml syntax.

The most commonly used ones:
- replace - replace one string with another one
- remove - remove a certain string from the data
- resplit - reorder the data and alter as needed

Usage is pretty much the same as filters, this editor will pretend that we actually wanted a d10 instead of a d20 if the
result was less than 10.

Start of with resetting the path to `pf:dice,clear` and `pf:dice,addf,maxlength:5`
````xml
<path delimiter=":" id="dice" src="raw:dice">
    <filter type="maxlength">5</filter>
</path>
````

To add an editor that does a replace, check the earlier `help:editor` for the correct sequence.
> replace -> Replace 'find' with the replacement
> cmd pf:pathid,adde,replace:find|replacement

So this becomes: `pf:dice,adde,replace,d2|d1`
````xml
<path delimiter=":" id="dice" src="raw:dice">
    <filter type="maxlength">5</filter>
    <editor>
        <!-- Replace d2 with d1 -->
        <replace find="d2">d1</replace>
    </editor>
</path>
````
You can compare the result wih the raw data using `raw:dice` and `path:dice`.
We'll try a different edit, so remove the last node with `pf:dice,delete,last`.

The resplit one is a bit more complex so to give an example of yet another cheat method.  
According to `help:editor`, the cmd is `pf:pathid,adde,resplit,delimiter|append/remove|format`.  
The reason for using '|' instead of ',' is because it's highly likely that delimiter and format contain ','.
* The delimiter is the same as before, so `:`.
* The append/remove refers to what is done with the items that aren't mentioned in the format. If it's split in four but
  the format only refers to two, append will have those appended at the end (using delimiter) and remove will discard.
* Format will be `i0:1i1` so just add a 1 in front of the second item. Because the filter only allows rolls below 10 to
  go through, this results in those rolls becoming much better.

Based on that the cmd becomes: `pf:dice,adde,resplit,:|append|i0:1i1`
````xml
<path delimiter=":" id="dice" src="raw:dice">
    <filter type="maxlength">5</filter>
    <editor>
        <!-- Split on : then combine according to i0:1i1 -->
        <resplit delimiter=":" leftover="append">i0:1i1</resplit>
    </editor>
</path>
````

Check the result with `path:dice`.

### 4. Multiple filters in a single path

So far we made a path for each filter, but it's actually the same when combining.
````xml
<path delimiter=":" id="dice" src="raw:dice">
    <filter type="minlength">6</filter>
    <store db="rolls:dice">
        <int i="1" unit="">rolled</int>
    </store>
    <!-- because the previous step was a 'store', dcafs assumes you're done with the filtered data -->
    <!-- So the next step will get the discarded data -->
    <filter type="maxlength">5</filter>
    <math>i1=i1+5</math>
    <store db="rolls:dice">
        <int i="1" unit="">rolled</int>
    </store>
</path>
````
Note that the output of the `path:dice` will be the result of the second filter because that's what reaches the end of
the path.  
If this is unwanted behaviour, it's possible to override it.
````xml
<paths>
    <path delimiter=":" id="dice" src="raw:dice">
        <filter id="f1" type="minlength">6</filter> <!--  the filter was given an id -->
        <store db="rolls:dice">
            <int i="1" unit="">rolled</int>
        </store>
        <!-- because the previous step was a 'store', dcafs assumes you're done with the filtered data -->
        <!-- So the next step will get the discarded data -->
        <math src="filter:f1">i1=i1+5</math> <!-- refer to filter with id f1 as the source -->
    </path>
</paths>
````

### 5. Summary

#### Repeating the glossary

* `stream` tcp/udp/serial connection that can receive/transmit data
* `source` any possible source of (processed) data
* `command` a readable instruction that can affect any part of the program
* `path` a series of steps that the data passes through to get processed

#### About dataflow

A major aspect of dcafs is the concept of source and writable. Source means 'it can provide data' while writable means
'it can accept data'. Most components are both, when the raw data was provided to the filter, the stream was the source,
and the filter the writable (which in turn became a source).  
Everytime you want to see data in telnet, that interface acts as the writable.   
Not that it matters for the user, but for example a filter will only request data from its source if it has a writable/target for the
filtered data.

#### About commands
* For a general beginners aid, use `help`
* To get an overview of all the streams,databases and such, use `st`
* If you want to collect data from TCP,UDP and serial, `ss:?`
    * Once you have this data, get it with `raw:id`
* If you have the data, but it needs filtering, `help:filter`
* If (even after filtering) the data still needs some maths, `help:math`
* If (even after filtering) the data still needs some edits, `help:editor`
* If you are happy with the data, store it in memory, `store:?`
    * The resulting data can be seen with `rtvals`
* For persistent storage in a database, `dbm:?`
    * The easiest way to keep track of this is by checking `st` and seeing the */30 go up
* To shut down the instance of dcafs, `sd:reason` fe. `sd:updating to new version` or just `sd`

For consistency's sake, a lot of subcommands are repeated.
* `cmd:?` gives info on the command
* `cmd:list` gives a list of all the elements with some info (fe. `ff:list` will list all the filters)
* `cmd:add` will be the start of creation of a new/blank element
* `cmd:reload` will reload all the elements of the component
* `cmd:reload,id` will reload the element with that id


Bonus!
* The up/down arrow can be used to go through history of send commands.

## C. Let's kick it up a notch

Now that we got the basics looked at, we'll expand on it.

### 1. Interact with a stream

This previously was just connecting to a TCP server and getting the data from it. The actual absolute minimum:
````xml
    <stream id="dice" type="tcp">   
      <address>localhost:4000</address>
    </stream>
````
So a lot can be omitted and then dcafs will just assume the default values (which are hard coded).  
It's up to the user to add these or not (if using the defaults).
````xml
<streams>
    <stream id="dice" type="tcp">
        <log>yes</log> <!-- by default, data is written to the raw logs, note that true/yes/1 are the same-->
        <ttl>-1</ttl>   <!-- by default, no ttl/idle is active --> 
        <eol>crlf</eol> <!-- default end of line sequence is carriage return + linefeed -->
        <echo>false</echo> <!-- by default, data isn't looped back to the sender, note that false/no/0 are the same -->
    </stream>
    <!-- For a serial stream -->
    <stream id="dice" type="serial">
        <!-- All of the above and... -->
        <serialsettings>19200,8,1,,none</serialsettings> <!-- by default, baudrate is 19200 with 8 databits,one stopbit and no parity -->
    </stream>
</streams>
````
This also means that the evidence of the earlier cheating is present in the log files...

So far we were only on the receiving end from a stream, no talking back yet.  
Sending data isn't anything special and certainly not 'kicking it up a notch', but the bit afterwards will be...

Send `streams` (or `ss`) to get a list of available streams, this will return a list with currently only **S1:dice**  
* `S1:important` to send important to the dice stream  
* `ss:send,dice,unimportant` to send unimportant to the dice stream

As always, this has a couple of extras...
* by default, the earlier defined eol is appended (so the first command actually sends important\r\n)
* hexadecimal escape characters are allowed, so `S1:hello?` and `S1:hello\x3F` send the same thing
    * alternatively, `S1:\h(0x68,0x65,0x6c,0x6c,0x6f,0x3f,0xd,0xa)` would do the same thing, note that the eol sequence needs to be added manually
* ending with \0 will signal to dcafs to omit the eol sequence so `S1:hello?\0` won't get crlf appended.
    * Since 1.0.9, it's also possible to use CTRL+s to omit sending eol sequence
* If you plan on transmitting multiple lines, you should start with `S1:!!` from then on, everything send via that
  telnet session will have S1: prepended and thus be transmitted to dice. Sending `!!` ends this.   
  This feature can also be used to repeat a certain command over and over, because then it will just send the prepended part.

So now you know pretty much everything there is to know about manually sending data.

Let's put it to some use.  
Have two telnet sessions open:
* `raw:dice` running in one to see the rolls come in
* send `S1:dicer:stopd20s` in the other one, this should stop the d20 rolls to arrive in the first one

The dicer accepts more commands, to test them out first send `S1:dicer:!!` so that is prepended.
* `rolld6` rolls a single 6 sided die
* `rolld20` rolls a single 20 sided die
* `rolld100` rolls a 100 sided die
* `stopd20s` stops the continuous d20 stream
* `rolld20s` starts the continuous d20 stream

(send `!!` to go back to normal)

Next up will introduce triggered actions, which are also the final nodes for the stream.
````xml
<stream>
    <cmd when="">cmd</cmd>
    <!-- or -->
    <write when="">data</write>
</stream>
````
A stream can have multiple of these cmd/write nodes and there are a couple 'when' options.
* To write data:
    * `hello` to send something upon (re)connecting
    * `wakeup` to send something when the connection is idle

Suppose we don't actually want to receive the d20s, but want a d6 every 5 seconds...
The command to add a 'write' is `ss:addwrite,id,when:data` so:
- `ss:addwrite,dice,hello:dicer:stopd20s` to send the stop
- `ss:addwrite,dice,wakeup:dicer:rolld6` to request a d6 on idle
- `ss:alter,dice,ttl:5s` to trigger idle after 5s of not receiving data
````xml
    <stream id="dice" type="tcp">   
        <address>localhost:4000</address>   
        <write when="hello">dicer:stopd20s</write> <!-- send the text to stop getting the d20s upon connecting -->
        <ttl>5s</ttl> <!-- because of no longer receiving data this will expire -->
        <write when="wakeup">dicer:rolld6</write> <!-- and a d6 will be asked because of the ttl -->
        <!-- After receiving the d6 result, after  5s another ttl trigger etc... -->
    </stream>
````
Then `ss:reload,dice` and d6 results should appear every 5 seconds.  
Just to clarify, this is not the proper way to handle this situation and just serves to show the functionality.  
This should actually be done using the TaskManager, but that's for a later section. Do note that dicer is actually
a taskmanager running on dummy...

The cmd node has four 'when' options, but those are for issuing (local) commands instead of writing data.
* `open` executed on a (re)connection
* `idle` executed when the ttl is passed and thus idle
* `!idle` executed when idle is resolved
* `close` executed when it's closed

These can be added with the command `ss:addcmd,id,when:data`

So the main difference is that hello and wakeup send data to somewhere, while open,idle and close are local commands.

For the next example, we'll shut down both instances. Shutting down the dummy can be done by sending `S1:sd` to it.  
Then use `sd` to close the regular one.
> Note: The full command is sd:reason, this allows the user to give a reason for the shutdown that will be logged.

We'll automate this.
1. First restart the dummy, don't start the regular one yet
1. Open a telnet connection to the dummy on port 24
1. Send `raw:dummy` (in the dummy session) to later notice the updates stopping
1. Alter the stream node in the xml for the regular one
````xml
    <stream id="dice" type="tcp">   
        <address>localhost:4000</address>   
        <write when="hello">dicer:stopd20s</write> <!-- send the text to stop getting the d20s upon connecting -->
        <ttl>6s</ttl> <!-- because of no longer receiving data this will expire -->
        <write when="wakeup">sd</write> <!-- and a shutdown will be asked because of the ttl -->
        <cmd when="close">sd</cmd> <!-- which in turn will trigger a 'close' that will shutdown this instance -->
    </stream>
````
5. Start the regular one and open a telnet connection on port 23
1. Shortly after no more updates will appear in the dummy session
1. After about 6 seconds it will close and shortly after the regular one

Again this is purely to show the functionality, every command that can be issued through telnet can be used this way.  
For example,the two nodes below have exactly the same end result:
````xml
<stream>
    <cmd when="open">ss:send,dicer,hello!</cmd> <!-- execute ss:send,dicer,hello! on connectino established -->
    <write when="hello">hello!</write> <!-- send hello! on connection established-->
</stream>
````
Problem is that giving actual useful examples is hard because it involves components not seen yet...
* command a taskmanager to do something on opening or closing a connection (and something is an understatement)
* email someone on connection loss or gain
* send data to one device if another one is idle
* ...

But, I assume that it's clear what it can be used for...?

### 2. Store and databases revisited

What was shown so far was when dcafs needs to figure out the relationship between a table and a store.  
Which has the advantage of being easy to explain, but it's not the most performant option.

Next a couple of alternative scenario's will be shown.

#### What if there are multiple tables?

We are also interested in the results of rolling a d6 (6 sided dice) and want to store that in another table.  
So first close dcafs and dummy, delete the rolls.sqlite file and overwrite the xml to match the one below.  
Anything new/added will be explained in the comments.

````xml
<dcafs>
    <settings>
        <mode>normal</mode>
        <!-- Settings related to the telnet server -->
        <telnet port="23" title="dcafs"/>
        <databases>
            <sqlite id="rolls" path="db\rolls.sqlite">
                <setup batchsize="10" flushtime="10s" idletime="-1"/>
                <table name="d20s">
                    <utcnow>timestamp</utcnow>
                    <integer>rolld20</integer> <!-- column added to store the d20 -->
                </table>
                <table name="d6s"> <!-- Add an extra table for d6s -->
                    <utcnow>timestamp</utcnow>
                    <int>rolld6</int>
                </table>
            </sqlite>
        </databases>
    </settings>
    <stream id="dice" type="tcp">
        <address>localhost:4000</address>
        <write when="hello">dicer:rolld6s</write> <!-- We also want to receive d6 results -->
    </stream>
    <paths>
        <path id="sort" src="raw:dice" delimiter=":">
            <filter type="start">d20</filter> <!-- redirect the d20's -->
            <store db="rolls:d20s" group="d20s">
                <int i="1">rolld20</int>
            </store>
            <!-- From this point, the data discarded by the previous filter is given -->
            <filter type="start">d6</filter> <!-- redirect the d6's -->
            <store db="rolls:d6s" group="d6s">
                <int i="1">rolld6</int>
            </store>
        </path>
    </paths>
</dcafs>  
 ````
Start both dummy and then dcafs again.

What will happen:
* The dummy will be asked to also send d6 results over the same connection
* The path will process the data so both stores are used when needed.
    * If a d20 is received it will pass the first filter and thus be stored by the first store
    * If a d6 is received it will be discarded by the first filter but passed on to the second one etc.

So the above shouldn't be anything new, next changes will be made.

#### What if there's only a single table?

At the moment each store triggers an insert into its own table, but now those need to be combined.

Simplest option would be to provide the earlier mentioned rtval attribute:
````xml
    <table name="rolls">
        <utcnow>timestamp</utcnow>
        <int rtval="d20s_rolld20">rolld20</int>
        <int rtval="d6s_rolld6">rolld6</int> <!-- column added to store the d6 -->
    </table>
````
This tells dcafs to look for d20s_rolld20 instead of rolls_rolld20 etc.

But suppose the table actually looked like this.
````xml
    <table name="rolls">
        <utcnow>timestamp</utcnow>
        <int>rolld20</int>
        <int>rolld6</int> <!-- column added to store the d6 -->
    </table>
````

Then the groups of the stores need to be altered.
````xml
<path id="sort" src="raw:dice" delimiter=":">
    <filter type="start">d20</filter> <!-- redirect the d20's -->
    <store db="rolls:rolls" group="rolls">
        <int i="1">rolld20</int>
    </store>
    <!-- From this point, the data discarded by the previous filter is given -->
    <filter type="start">d6</filter> <!-- redirect the d6's -->
    <store group="rolls">
        <int i="1">rolld6</int>
    </store>
</path>
````
If the d6 store should be the trigger, just move the db attribute to it. If both stores have the db attribute, both will
trigger a database insert.  
This means that the rolls written to the database will be the ones that are currently in the rtvals collection and the
timestamp will refer to the roll that initiated the trigger!   
So if triggered by the d20 roll, the timestamp in the database is the timestamp of the d20 roll, the d6
entry will just be whatever is in rtvals at that time.

There's an alternative way to accomplish the same but slightly simpler. A stream can contain a store but can't filter.  
But if the data format is easy, mapping can be used instead. The requirement is that the data consists of a single key and value  
pair.

````xml
<stream id="dice" type="tcp">
  <address>localhost:4000</address>
  <write when="hello">dicer:rolld6s</write> <!-- We also want to receive d6 results -->
  <store group="rolls" delimiter=":" db="dbresults:rolls" map="true"> 
        <int key="d20">rolld20</int> <!-- First element of the split should be d20 and second element is the value -->
        <int key="d6">rolld6</int>
  </store>
</stream>
````
When working mapped, the database insert is triggered on processing the last key (so after a d6:x line is received). 

#### What if the column needs a value that isn't always present?

That is done with the def attribute in the column node.  
If the corresponding rtval isn't found def will be used instead (if no def is defined, this will be null).
````xml
<table name="rolls">
    <timestamp>timestamp</timestamp>
    <int>rolld20</int>
    <int def="6">rolld6</int> <!-- column added to store the d6 -->
</table>
````

This pretty much covers it (for now).

#### Alternate src

All the above was based on using a src to get data into a path, but a path could also create the data.  
The most basic example would be:
````xml
<paths>
    <path id="custom">
          <plainsrc>Hello World?</plainsrc>
    </path>
</paths>
````
Calling `path:custom` will print 'Hello World?' every second (1s is the default interval).

On the other hand, with a different interval:
````xml
<paths>
    <path id="advexample">
        <plainsrc interval="3s">Hello World?</plainsrc>
    </path>
</paths>
````

Another alternative is to use a cmd as the src...
````xml
<paths>
    <path id="stupdate">
        <cmdsrc interval="10s">st</cmdsrc>
    </path>
</paths>
````
When using `path:stupdate` in a telnet session, the result of `st` will be shown every 10s.

Besides those two, there are also:
- filesrc -> to read the content of a file or files in a folder
- sqlitesrc -> to process the result of a query
- rtvalssrc -> same as plain, but can hold references to rtvals

### 3. How the data is stored in memory, rtvals

Up till now the only rtval used are the integers. But there's also real, text and flag(boolean).
So far to get data stored in memory, the only option was to go through store.  
It's possible to create a 'global' store of rtvals using the same commands as earlier.

#### Commands

Same as before, the listing is shown with `store:?`. The only difference now is that 'global' is used instead of
the streamid to refer to the 'global' store.

In addition, each rtval has a single command to update the value:
* realval: `rv:id,update,expression`, expression means operations and values are allowed (and references to other reals)
* intval: `iv:id,update,expression`, same as real
* flagval: `fv:id,update,state`, state is anything that can be parsed to a valid bool (0/1,yes/no,true/false)
* textval: `tv:id,update,value`, value is pretty much anything

These can be used in telnet etc.

### Reading rtvals

We saw how to write to the rtvals but not how to read them:
* real: {r:id} or {real:id}
* int: {i:id} or {int:id}
* text: {t:id} or {text:id}
* flag: {f:id} or {flag:id}

This is possible in various places:
* cmd: Allows both r and f `rv:offsettemp,update,{r:temp}+5*{f:withoffset}` do note that offsettemp **must** already exist.
* forwards:
````xml
<examples>
    <math>{r:offsettemp},i0={r:temp}+5*{f:withoffset}</math>
    <!-- Only one ref can be given on the left, if not needed the i0 can be omitted on the left -->
    <!-- Editor can also use them in the resplit command -->
    <editor type="resplit">i0,i1,{r:temp}</editor>
    <!-- These WILL FAIL if a real doesn't exist -->
</examples>
````
>Note: Although the above math can be used to update the val, it's probably better to use a store for that.

#### XML

Rtvals also have their own xml structure to prepare them in advance and add some metadata.
````xml
<rtvals>
    <group id="temps"> <!-- Multiple rtvals can belong to a single group -->
        <real>temp</real>  <!-- Without metadata -->      
        <real unit="°C" default="-999">offsettemp</real> <!-- With the unit and start value as metadata-->       
    </group>
    <group id="info">
      <text default="outdoor">location</text> 
      <flag default="no">serviced</flag> 
    </group>
</rtvals>
````
***Other metadata***
- reals: fractiondigits or scale attribute: will round the value to that amount of digits
- reals/integers have a history node : keep x values of history in memory, no use through xml yet

````xml
<rtvals>
  <group id="">
        <real options="scale:1,history:10" >humidity</real>  
  </group>
</rtvals>
````

#### Triggers

As usual these (except text) allow for triggered commands. So combining the earlier seen things...
Just like with math, '$' will be replaced with the updated value.
````xml
<rtvals>
    <group id="temps">
        <real unit="°C" default="-999">offsettemp</real>  
        <real name="temp">  <!-- Because there are childnodes, the name of the real is now an attribute -->
            <cmd>rv:temps_offsettemp,update,$+5</cmd> <!-- When temp is updated, offsettemp will be calculated -->
            <!-- $ is replaced with the new value of temp -->
            <cmd>rv:temps_offsettemp,update,$+{r:offset}</cmd> <!-- Is also allowed if offset exists -->
        </real> 
        <int name="otherval" unit="°C"/> <!-- It's also allowed to use name attribute when there are no childnodes -->
    </group>  
</rtvals>
````
So a command can be issued everytime a value is set, that is the default trigger...  
All options:
````xml
<real>
    <cmd when="always">rv:temps_offsettemp,update,$+5</cmd> <!-- default is always execute -->
    <cmd when="changed">rv:temps_offsettemp,update,$+5</cmd> <!-- only run if the new value is different from the previous one -->
    <cmd when="below x">rv:temps_offsettemp,update,$+5</cmd> <!-- runs once if the value drops below x, reset when going above -->
</real>
````
Besides 'below', the other options are:
- not below
- (not) above
- x through y
- (not) between x and y
- (not) equals x

> Notes:   
> (1) It's really easy to create an endless loop with this, the user is responsible for not causing it!  
> (2) I'd rather have used logical symbols like <,>,>= etc but some of them aren't allowed in xml

````xml
<rtvals>
    <group id="temps">
        <real name="temp">  
            <cmd>rv:update,temp,$+5</cmd> <!-- Endlessly increase temp with 5 -->
        </real>  
    </group>   
</rtvals>
````
#### Other cmds
* `rtvals:group,groupid` will return a list of **all** rtvals belonging to requested group, fe. the earlier temps  
  <b>Group: temps</b>  
  &nbsp;&nbsp;&nbsp;&nbsp;temp : -999°C  
  &nbsp;&nbsp;&nbsp;&nbsp;offsettemp : -999°C  
  &nbsp;&nbsp;&nbsp;&nbsp;location : outdoor  
  &nbsp;&nbsp;&nbsp;&nbsp;serviced: false
