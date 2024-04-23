## TaskManager

>Note: This uses the same 'dicer' as in the basics guide.

One of the main components of dcafs is the taskmanager functionality.

Reset the settings.xml back to this and restart dcafs:
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<dcafs>
  <settings>
    <mode>normal</mode>
    <!-- Settings related to the telnet server -->
    <telnet port="23" title="dcafs"/> <!-- The telnet server is available on port 23 and the title presented is DAS-->
  </settings>
  <streams>
    <!-- Defining the various streams that need to be read -->
    <stream id="dice" type="tcp">
      <eol>crlf</eol> <!-- Messages should end with \r\n or carriage return + line feed -->
      <address>localhost:4000</address> <!-- In the address, both ipv4 and hostname are accepted IPv6 is wip -->
    </stream>
  </streams>
</dcafs>
```

Back in the telnet interface, send `tm:addblank,dicetm` to create an empty taskmanager called dicetm.
> Tasks script created, use tm:reload,dicetm to run it.

In the settings.xml the following line has been added.
```xml
<taskmanager id="dicetm">tmscripts\dicetm.xml</taskmanager>
```
If you check the tmscripts' folder, a file dicetm.xml should be present with the following content which serves as both a
'blank' starting script and basic explanation.
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<tasklist>
  <!-- Any id is case insensitive -->
  <!-- Reload the script using tm:reload,dicetm -->
  <!-- If something is considered default, it can be omitted -->
  <!-- There's no hard limit to the amount of tasks or tasksets -->
  <!-- Task debug info has a separate log file, check logs/taskmanager.log -->
  <!-- Tasksets are sets of tasks -->
  <tasksets>
    <!-- Below is an example taskset -->
    <taskset id="example" info="Example taskset that says hey and bye" run="oneshot">
      <task output="telnet:info">Hello World from dicetm</task>
      <task output="telnet:error" delay="2s">Goodbye :(</task>
    </taskset>
    <!-- run can be either oneshot (start all at once) or step (one by one), default is oneshot -->
    <!-- id is how the taskset is referenced and info is a some info on what the taskset does, this will be 
         shown when using dicetm:list -->
  </tasksets>
  <!-- Tasks are single commands to execute -->
  <tasks>
    <!-- Below is an example task, this will be called on startup or if the script is reloaded -->
    <task output="system" delay="1s">taskset:example</task>
    <!-- This task will wait a second and then start the example taskset -->
    <!-- A task doesn't need an id, but it's allowed to have one -->
    <!-- Possible outputs: stream:id , system (default), log:info, email:ref, manager, telnet:info/warn/error -->
    <!-- Possible triggers: delay, interval, while, ... -->
    <!-- For more extensive info and examples, check Reference Guide - Taskmanager in the manual -->
  </tasks>
</tasklist>
```
To activate it use `tm:reload,dicetm`,  'Hello world from dicetm' should show in green and 'Goodbye :(' follows a
couple seconds later in red. (fyi telnet:warn is in something that should resemble orange)

A while back this was given as an option to get a d6 roll every 5 seconds.
````xml
    <stream id="dice" type="tcp">
  <address>localhost:4000</address>
  <ttl>5s</ttl> <!-- because of no longer receiving data this will expire -->
  <write when="wakeup">dicer:rolld6</write> <!-- and a d6 will be asked because of the ttl -->
  <!-- After receiving the d6 result, after  5s another ttl trigger etc... -->
</stream>
````
It was also stated that this wasn't the best way to solve it.
What should have been done is use the taskmanager for it, like below:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<tasklist>
  <tasks>
    <!-- This introduces a third output option called 'stream' which takes an id as secondary argument -->
    <!-- As the name implies, the value of the task (dicer:rolld6) will be send to the stream with id dice -->
    <task output="stream:dice" trigger="interval:5s">dicer:rolld6</task>
    <!-- By default the initial delay is taken the same as the interval, so the first run is 5 seconds after start -->
    <!-- Alternative is trigger="interval:1s,5s" etc to change the initial delay, main use would be spreading multiple -->
    <!-- interval tasks with the same interval -->
  </tasks>
</tasklist>
```

So enable this with `tm:reload,dicetm`.   
Next, open a second telnet session and send `raw:dice`. You should see a d6:x appear once every 5 or so d20:x. We'll use
this session to monitor the output and the first one to issue cmds.

If you ever forget the name of the taskmanager(s), use `tm:list` to get a list.
As usual `tm:?` gives a list of all the available taskmanager commands. Furthermore, any task/tasket with an id, can be
called from telnet using tmid:taskid/tasksetid.
To test this add the following task:
````xml
<!-- This task has no trigger (just like a taskset) so it will only run when called -->
<task id="getd100" output="stream:dice" >dicer:rolld100</task>
````
Again, reload with `tm:reload,dicetm` (the other session output will stop briefly).  
Now send `dicetm:getd100` to test if the addition causes a d100:x to appear in the other session or send `dicetm:list` to get
an overview of all the tasks/tasksets in the taskmanager.

If, for example, you want to request a d6,d20 and d100 with one command you'd make a taskset like this.
```xml
<taskset id="rollall" run="oneshot" info="Roll a d6, then a d20 and finally a d100">
  <task output="stream:dice" trigger="delay:0s">dicer:rolld6</task>
  <task output="stream:dice" trigger="delay:250ms">dicer:rolld20</task>
  <task output="stream:dice" trigger="delay:500ms">dicer:rolld100</task>
</taskset>
```
This uses the trigger 'delay' which just means 'wait the given time before execution'. A delay of 0s is
the default in tasksets, but for readability it's advised to add it anyway.

This above taskset could be called with `dicetm:rollall`.   
Or you could combine the two and have the above on an interval of 5s.
```xml
<task output="manager" trigger="interval:5s">task:rollall</task>
        <!-- Output manager is used to signify that the value is a command for the active taskmanager -->
````
And a last basic example:
````xml
<task output="email:admin" trigger="delay:5s">DCAFS booted;Nothing else to say...</task>
        <!-- This will send an email to admin 5s after startup, subject of the email is DCAFS booted and the content 'Nothing...' -->
        <!-- It's also possible to use a command as content, the result of that command (fe.st) will become the email content -->
````

Going back to:
````xml
    <stream id="dice" type="tcp">
  <address>localhost:4000</address>
  <write when="wakeup">dicer:rolld6</write>
</stream>
````
Dummy has a taskmanager called 'dicer' and 'rolld6' is a task from it..
````xml
<task id="rolld6" output="stream:dummy" >d6:{rand6}</task>
````

This is the minimum knowledge you need to use the taskmanager, but this is just brushing the surface...
In later chapters examples will introduce the rest of the functionality.
But to get a full overview of all the capabilities now, check the [reference guide](taskmanager).

#### Summary
This introduced:
* In general, a taskmanager has tasks and tasksets
    * tasksets
        * are always executed on a command
        * are either one-shot (all tasks are triggered at the same time) or step (one-by-one)
        * an id is required
    * tasks
        * without a trigger are executed with a command, otherwise as response to the trigger
        * an id is optional
* trigger options:
    * interval Task is executed at a set interval after an initial delay
    * delay Task is executed after the delay has passed
* output options:
    * stream The value of the task is sent to a stream/raw
    * system The value of the task is a command or reference to another task(set)
    * email The value of the task is subject;content of the email

Other parts of it will be introduced later.

#### Extra

Earlier in the database & generics chapter the way of triggering a database write was using a generic.  
If more updates are given than you'd want to store in the database, it's also possible to limit this with the taskmanager.  
The earlier shown cmd `dbm:store,dbId,tableid` can be called with the system output.
````xml
<task output="system" trigger="interval:10s">dbm:store,diceresults,rolls</task>
````
So if the rolls come in every second, this will cause a single result to be stored every 10 seconds instead.  
Given you didn't forget to remove the db attribute from the generic...