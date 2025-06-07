# Taskmanager blocks

## Glossary

- **Block:** A shape in the diagram representing a unit of functionality, defined by its properties (`dcafstype`,
  `dcafsid`, etc.).
- **Block Type:** The content of the `dcafstype` property.
- **Functional Group:** A set of related blocks that can freely connect within the group. Cross-group connections are
  only valid if supported by the receiving block's logic.
- `Command:` Often abbreviated to `cmd`, text that is part of the Command Pipeline to control dcafs but can also be use
  by the various modules within the system itself.
- `Route`: Visually represented as an arrow between blocks in the diagram. A route defines a directional connection and
  may represent either the flow of execution (e.g., `next`, `fail`) or structural relationships (e.g., `target`,
  `source`).
  The meaning of a route depends on its label and the block types involved.

## General rules

- **Style doesn't matter**: The visual style of shapes (excluding container shapes) and arrows is ignored. Use whatever
  styling you prefer.
- **Text on the shapes**: The visible text on shapes is not parsed or required. It’s just a suggestion or visual aid.
- **Shape Properties and Arrow Label Are Key:** All logic depends on:
    - The properties (mainly `dcafstype`) of the shapes.
  - The label and start and end of the arrows. Arrows without label are given the label `next`.
- **Outgoing arrow Limits:** The number a block can have is limited and depends on the block's type. The types of
  blocks it can connect to may also be restricted by its own logic.
- **Incoming arrow limits:** A single block can receive any number of incoming arrows. So the only limit is a practical
  one.
  For now, incoming arrows **in a pure taskblock diagram** don't transport data, they are just a trigger for the block
  to start.
- **Arrow Label Syntax:** Arrow labels may include a pipe (`|`). Everything after the pipe is treated as a comment and
  ignored by the parser.
- **Comment Arrows (Experimental):** Arrows without direction (i.e. plain lines) and with a ? label are considered
  visual comments. These are not yet parsed, just a test to see if the method works.
- **Free Connection Within Functional Groups:** Blocks within the same functional group (e.g., task blocks) can connect
  freely without restriction. Logical sense is not enforced.
- **Hint** As you might notice when clicking on a shape. The text contains words enclosed in `%`, those are properties.
  Meaning these are placeholders that get replaced by the property if it exists.

## Current blocks (as of 3.1.0)

### Basicval Block

- **Purpose:** Provides some simple operations to apply to a val for which Math Block is overkill.
- **Outgoing Arrow**: Labeled `next` (or `done`,`ok`), leading to the next block in the route.
- **Incoming Arrow**: From another task block, triggers the `action`.
- **Required properties:**
    - `dcafstype`: Must be `basicvalblock` for it to be processed as a Basicval Block.
    - `target`: The integerval or realval the `action` will affect.
    - `source`: Either a constant or another integerval/realval or empty/ignored if `action` is `reset`.
    - `action`: The action to perform on the target. Options:
        - `increment`: Adds the value of `source` to the value of `target`.
        - `decrement`: Subtracts the value of `source` to the value of `target`.
        - `reset`: Resets the value of `source` to its default value.
        - `copy`: Updates the value of `target` to match `source`.
    - **Note**: All actions (except `reset`) **update** the `target`, meaning any associated triggers might be fired.

### Clock Block

- **Purpose:** Triggers **execution** at a set time of day, on specified days of the week.
- **Outgoing Arrow**: Labeled `next` (or `pass`,`ok`), leading to the next block in the route.
- **Incoming Arrow**: From another task block, triggers the timer. Successive triggers are ignored by default.
- **Required properties:**
    - `dcafstype`: Must be `clockblock` for it to be processed as a Clock Block.
    - `time`: Defines the time of day in **UTC** (24-hour format HH:MM, e.g., 14:30).
    - `localtime`: Time of day in **system local time** (same format)
- **Optional Properties:**
    - `days`: Defaults to `always`. Specifies which days the block is active. Options include:
        - `always` - runs on every day;
        - `weekday` - only runs on a weekday;
        - `weekend` - only runs during the weekend;
        - Custom selections using either
            - Two-letter Abbreviations: `mo`,`tu`,`we`,`th`,`fr`,`sa`,`su`. Use a sequence for multiple days i.e. `motu`
                - For example Tuesday and Thursday -> tuth
            - Our full names: `monday`,`tuesday` and so on. Use  `,` to separate multiple days.

### Condition Block

- **Purpose:** Checks a condition and diverts flow on fail.
- **Outgoing Arrow(s)**:
    - Labeled `pass`(or `next`,`ok`,`true`,`yes`), leading to the next block in the route on pass.
  - Labeled `fail` (or `no`,`false`,`failed`), diverting to alternative route when result is false. Marks the task as
    failed.
    - Labeled `update`, passes the result of the condition to the targeted FlagVal.
- **Incoming Arrow**: From another task block, triggers the comparison. Successive triggers repeat this.
- **Required properties:**
    - `dcafstype`: Must be `conditionblock` for it to be processed as a Condition Block.
    - `expression`: Defines the condition, brackets and references to `rtvals` are allowed, nesting not (yet).

### Control Block

- **Purpose:** Start or stop another block.
- **Outgoing Arrows**:
    - Labeled `next` (or `pass`, `ok`), leading to the next block in the route.
  - Labeled `stop`, the target is stopped and reset. This does not continue the route.
  - Labeled `trigger` or `start`, the target is triggered. Difference with the `next` arrow is that this starts a new
    route in a new thread from the targeted block onwards.
- **Incoming Arrow**: From another task block, triggers the start/stop. Successive triggers repeat this.
- **Required property:**
    - `dcafstype`: Must be `controlblock` for it to be processed as a Control Block.

### Counter Block

- **Purpose:** Counts down every time it is triggered, optionally diverts the flow to the alternate route on reaching
  zero.
- **Outgoing Arrows**:
    - Labeled `counter>0` (or `retries>0`,`retry`,`ok`,`pass`) , leading to the next block in the route,
      taken while counter is above 0.
  - Labeled `counter==0` (or `retries==0`, `no retries left`) , diverting to alternative route once the counter reached
    zero.
- **Incoming Arrow**: From another task block, triggers decrementing the counter and act according to the new value.
- **Required properties:**
    - `dcafstype`: Must be `counterblock` for it to be processed as a Counter Block.
    - `counter`: The amount of times this block can be triggered before it optionally diverts flow.
- **Optional Properties:**
    - `onzero`: Defaults to `alt_pass`. Determines what happens on reaching zero, options:
        - `alt_pass`: Follow the alternative route without marking the task as failed.
        - `alt_fail`: Follow the alternative route but mark the task as failed.
        - `alt_reset`: Follow the alternative route, don't mark as failed, reset counter.
            - `stop`: Don't continue.
  - `altcount`: Defaults to `once`. How often the alternative route is taken, options are `once` or `infinite`.

### Delay block

- **Purpose:** Pauses **execution** here until the delay time has passed.
- **Outgoing Arrow**: Labeled `next`(or `ok`,`done`), leading to the next block in the route.
- **Incoming Arrow**: From another task block, triggers the start of the delay countdown. Successive triggers restart
  the countdown.
- **Required properties:**
    - `dcafstype`: Must be `delayblock` for it to be processed as a Delay Block.
    - `delay`: Defines the period to wait. The format is abbreviated time period (i.e. `5s`,`10m`,`3h10m2s`)
- **Optional Properties:**
  - `retrigger`: What to do when a trigger is received after the first one, options (default: `restart`).
      - `ignore`: Ignore the trigger.
      - `cancel`: Stop current delay if active, don't do anything else.
      - `restart`: Stop current delay (if active), restart delay.

### Flag block

- **Purpose:** Alters the state of a FlagVal.
- **Outgoing Arrow**: Labeled `next` (or `pass`,`yes`,`ok`), leading to the next block in the route.
- **Incoming Arrow**: From another task block, triggers the execution of the update.
- **Required properties:**
    - `dcafstype`: Must be `flagblock` for it to be processed as a Flag Block.
    - `action`: The state change to execute (note: if 'raise' is used and it was high already, it just remains high)
        - `raise`/`set`: The state is changed to true.
        - `fall`/`clear`: The state is changed to false.
        - `toggle`: The state is negated.

### Interval block

- **Purpose:** Triggers **execution** at a set interval, indefinitely or fur the number of times specified by `repeats`.
- **Outgoing Arrows**:
    - Labeled `next`, leading to the next block in the route.
    - Labeled `stopped` (or `cancelled`), diverting to alternative route when something interrupts it.
- **Incoming Arrow**: From another task block, triggers the interval. Successive triggers are ignored by default.
- **Required properties:**
    - `dcafstype`: Must be `intervalblock` for it to be processed as an Interval Block.
    - `interval`: Defines the interval. The format is abbreviated time period (i.e. `5s`,`10m`,`3h10m2s`)
- **Optional Properties:**
    - `repeats` Defaults to -1 (infinite). Defines how many times the interval is executed, and thus the next block is
      triggered.
      A value of 0 reduces it to a `delayblock`.
  - `retrigger`: Defines how the block responds to a trigger while already active., Options are:
        - `stop`/`cancel`– stops the current interval cycle;
        - `continue`– ignores the new trigger (default);
        - `restart`– restarts the current interval and resets `repeats`.

### Log Block

- **Purpose:** Writes a message to a global log.
- **Outgoing Arrow**: Labeled `next` (or `pass`,`ok`), leading to the next block in the route.
- **Incoming Arrow**: Receives a trigger writes the message. Successive triggers repeat this.
- **Required properties:**
    - `dcafstype`: Must be `logblock` for it to be processed as a Log Block.
    - `message`: Defines the message.
    - `level`: Determines which level the message has, `info`,`warn` or `error`.

### Math Block

- **Purpose:** Execute an `expression` that likely alters a rtval.
- **Outgoing Arrow**: Labeled `next` (or `pass`, `yes`, `ok`), leading to the next block in the route.
- **Incoming Arrow**: From another task block, trigger that evaluates the `expression`.
- **Required properties:**
    - `dcafstype`: Must be `mathblock` for it to be processed as a Math Block.
  - `expression`: The expression to evaluate, check `logic/MathParser.md` for background.

### Origin block

- **Purpose:** Serves as entry point to linked blocks and provides an ID that allows it to be triggered via a `command`.
- **Outgoing Arrow**: Labeled `next`, leading to the next block in the route.
- **Incoming Arrow**: From another task block, triggers restarting the task using the same thread as the block
  triggering.
  Unlike when triggered by a `command` or `Controlblock`, this behavior is thus sequential rather than threaded.
- **Required properties:**
    - `dcafstype`: Must be `originblock` for it to be processed as an Origin Block.
    - `dcafsid`: An unique ID used to reference the task/sequence. It needs to be unique within the file.
- **Optional Properties:**
    - `autostart`: Defaults to `no`/`false`. Determines whether the sequence starts on startup/reload or only when
      triggered by a command or other block.
    - `shutdownhook`: Defaults to `no`/`false`. Determines if the task is run during the shutdown process of dcafs.
      If `yes`, keep these tasks short!

### Reader Block

- **Purpose:** Pauses **execution** at this block until either the specified data is received from the source or the
  timeout expires.
- **Outgoing Arrows**:
    - Labeled `received` (or `next`,`ok`), leading to the next block in the route.
  - Labeled `timeout`, diverting to alternative route when `timeout` reaches 0.
- **Incoming Arrows**:
    - From another task block, triggers to subscribe to the source and wait for data.
    - Labeled `source`, determines which source to subscribe to. (not implemented yet)
- **Required properties:**
    - `dcafstype`: Must be `readerblock` for it to be processed as a Reader Block.
    - `wants`: Defines the data to wait for. The check is case-insensitive.
    - `timeout`: Default is **no** timeout. Maximum time to wait before triggering the `timeout` route. The format is
      abbreviated time period (i.e. `5s`,`10m`,`3h10m2s`)
  - `source`: Defines the source of the data fe. raw:sensor.
- **Optional Properties:**
    - `sourcetype`: If you wish to use just the id as text on the shape, this property can be used to store type.
        - For example: source=sensor, sourcetype=raw

### Writer Block

- **Purpose:** Writes the specified data to the `target`.
- **Outgoing Arrows**:
    - Labeled `next` (or `pass`,`ok`,`send`), leading to the next block in the route.
  - Labeled `failed` (or `fail`,`failure`,`timeout`,`not connected`,`failure`), diverting to alternative route when
    writing
      failed.
    - Labeled `target`, points to the target of the data, which could be a stream, file, or another writable reference.
      (not implemented yet)
- **Incoming Arrow**:
    - From another task block, triggers to resolve the target and write the data. Successive triggers repeat the
      writing.
- **Required properties:**
    - `dcafstype`: Must be `writerblock` for it to be processed as a Writer Block.
    - `message`: Defines the data to send. Expected end-of-line sequences are automatically added.
  - `target`: Defines the target of the data fe. stream:sensor.
- **Optional Properties:**
    - `targettype`: If you wish to use just the id as text on the shape, this property can be used to store type.
        - For example: target=sensor, targettype=stream