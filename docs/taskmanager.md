# Taskmanager

This module is used for automating and scheduling (long running) tasks.
It has the same capabilities as the user.

These tasks can be standalone or collected inside a taskset.

## Commandline

To get a list of all commands related, use the cmd `tm:?`. These are mainly for managing them.
For now, the way to create them is in a text editor suited for xml.

## Basics

The start is alwats the same, `tm:add,id` creates a new taskmanager script with the given id (which is
also used as filename). This will add a node to the `settings.xml` that looks like this inside the
settings node.

```xml

<taskmanager id="test">tmscripts\test.xml</taskmanager>
```

The `test.xml` will look like this. It comes with an example task and tasket.
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<dcafs>
  <tasklist>
    <!-- Any id is case insensitive -->
    <!-- Reload the script using tm:reload,test -->
    <!-- If something is considered default, it can be omitted -->
    <!-- There's no hard limit to the amount of tasks or tasksets -->
    <!-- Task debug info has a separate log file, check logs/taskmanager.log -->
    <!-- Tasksets are sets of tasks -->
    <tasksets>
      <!-- Below is an example taskset -->
      <taskset id="example" info="Example taskset that says hey and bye" run="oneshot">
        <task output="telnet:info">Hello World from test</task>
        <task delay="2s" output="telnet:error">Goodbye :(</task>
      </taskset>
      <!-- run can be either oneshot (start all at once) or step (one by one), default is oneshot -->
      <!-- id is how the taskset is referenced and info is a some info on what the taskset does, -->
      <!-- this will be shown when using test:list -->
    </tasksets>
    <!-- Tasks are single commands to execute -->
    <tasks>
      <!-- Below is an example task, this will be called on startup or if the script is reloaded -->
      <task delay="1s" output="system">tm:test,run,example</task>
      <!-- This task will wait a second and then start the example taskset -->
      <!-- A task doesn't need an id but it's allowed to have one -->
      <!-- Possible outputs: stream:id , system (default), log:info, email:ref, manager, telnet:info/warn/error -->
      <!-- Possible triggers: delay, interval, while, ... -->
      <!-- For more extensive info, check Taskmanager in the docs -->
    </tasks>
  </tasklist>
</dcafs>
```

The basic building block is a task. These can consist of three types of attributes.

### Output

This indicates what the content of the node means, this has a couple options:

- `stream:id` The content is the data that will be send to the referenced stream.
- `email:to` The content indicates the subject and content of the email send to `to`.
- `telnet:level` This will broadcast the content to all telnet session, options for level are
  info,warn and error. This mainly determines the color of the message (green,orange,red).
- `log:level` The content will be written to the logs with the given level (info,warn,error).
- `file:fcid` The content will be handed to the given filecollector.
- `system` The content will be executed as if it's a command.
- `manager` Similar to system, but restricted to the taskmanager this file belongs to.

### Trigger

This set of attributes determine the trigger that starts the task.

- `delay` The task will start after the given delay e.g. `delay="10s" `
- `interval` Schedules a recurring task with the chosen interval e.g. `interval="10m"`
  - By default, the first run will be on 'clean' time based on the interval. Meaning that
    if the interval is 15min, this will be executed at 15,30,45 or 0 minutes past the hour. This
    behaviour can be altered by specifying an initial delay e.g. `interval="5m,10m`. So five
    minutes after the task is started the output will be done. From then on every ten minutes.
- `time` or `localtime` The task is triggered on the chosen time e.g. `time="16:25"`.
  - `time` means UTC and `localtime` is for localtime
  - It's possible to define the days on which the task should run, the default is always.
    - Combined options are: weekend,weekday,all/always
    - To specify given days, concatenate the first two letter: mowe means monday & wednesday.
    - E.g. `localtime="16:25,mowe` or `time="09:25:10,weekday`

### Req

This is a requirement that needs to be met in order for the task to run.

- These refer to rtvals and it's possible to use more than one at once.
  - Examples:
    - `req="flag:cooler_enabled"` checks if the given flag is true
    - `req="{i:gas_temp} below 20"` checks if the value is below 20
      - Other comparisons are: (not) above, equals, at least, from x to y
    - `req="flag:cooler_enabled and {i:gas_temp} below 20"` combined, 'or' is also possible.

## Loops

There are currently two loops possible: retry and while.

### Retry

Test a condition and on failure retry after a given delay up to a specified retries. When
the condition is met, the next task will be started. If retries are spend, the set this belongs to
stops.

**Single line**
```xml
<!--
Every 5 seconds check if the gas_temp is above 20, if not try again up to 6 times.
If retries is -1, there's no limit to the amount of retries
-->
<retry interval="5s" retries="6">{i:gas_temp} above 20</retry>
```

**With child nodes**
Below is a retry node that send a message to a stream and checks if a value changes because of it.

```xml

<retry retries="10" onfail="stop"> <!-- Retry up to 10 times, on failure quit the set -->
  <stream to="raw:gas">Cool down</stream> <!-- Send 'Cool down' to 'raw:gas' -->
  <delay>20s</delay> <!-- Wait for twenty seconds -->
  <req>{i:gas_temp} below 10</req> <!-- Check if gas_temp is below 20, if not go back to first node -->
  <!-- If req succeeds, leave the node and go to the next task -->
</retry>
```

The order of the nodes can be chosen freely. In the above example, the 'Cool down please' will be send
every iteration. In the example below, it will only be send once and if the condition is met.
In short, below waits for the temperature to reach above 30 before asking to cool it down.

```xml

<tasket id="checktemp" info="Asks to cool down if temp reaches 30°C">
  <retry retries="-1"> <!-- Retry up to 10 times, on failure quit the set -->
    <delay>20s</delay> <!-- Wait for twenty seconds -->
    <req>{i:gas_temp} above 30</req>
    <stream to="raw:gas">Cool down please</stream> <!-- Send 'Cool down' to 'raw:gas' -->
    <delay>5s</delay> <!-- Wait another 5s before going to the next task -->
  </retry>
  <task delay="2s" output="telnet:info">To hot, asked to cool down</task>
</tasket>
```

### While

Test a condition and redo after a given delay up to a specified maxruns. When
the condition isn't met, the next task will be started. If maxruns are spend, this will also start the
next task.

**Single line**

```xml
<!--
Every 5 seconds check if the gas_temp is above 20, if so try again up to 6 times.
If maxruns is -1, there's no limit to the amount of retries. When maxruns becomes 0, go to the next
task.
-->
<while interval="5s" maxruns="6">{i:gas_temp} above 20</while>
```

**With child nodes**
Below is a while node that send a message to a stream and checks if a value changes because of it.

```xml

<while maxruns="10"> <!-- Redo up to 10 times, on failure go to next task -->
  <stream to="raw:gas">Cool down</stream> <!-- Send 'Cool down' to 'raw:gas' -->
  <delay>20s</delay> <!-- Wait for twenty seconds -->
  <req>{i:gas_temp} above 20</req> <!-- Check if gas_temp is above 20. If not, leave the node -->
  <!-- If req succeeds, go back to the first node -->
</while>
```

Just like for retry, the order of the nodes can be chosen freely.