# Taskmanager blocks

## Glossary

- **Block:** A shape in the diagram representing a unit of functionality, defined by its properties (`dcafstype`,
  `dcafsid`, etc.).
- **Block Type:** The content of the `dcafstype` property.
- **Functional Group:** A set of related blocks that can freely connect within the group. Cross-group connections are
  only valid if supported by the receiving block's logic.
- `Command:` Often abbreviated to `cmd`, text that is part of the Command Pipeline to control dcafs but can also be use
  by the various modules
  within the system itself.
- `Route`: Visually represented as an arrow between blocks in the diagram. A route defines a directional connection and
  may
  represent either the flow of execution (e.g., `next`, `fail`) or structural relationships (e.g., `target`, `source`).
  The meaning
  of a route depends on its label and the block types involved.

## General rules

- **Style doesn't matter**: The visual style of shapes (excluding container shapes) and arrows is ignored. Use whatever
  styling you prefer.
- **Text on the shapes**: The visible text on shapes is not parsed or required. It’s just a suggestion or visual aid.
- **Shape Properties and Arrow Label Are Key:** All logic depends on:
    - The properties (mainly `dcafstype`) of the shapes.
    - The label and direction of the arrows.
- **Output Limits:** The number of outputs a block can have is limited and depends on the block's type. The types of
  blocks
  it can connect to may also be restricted by its own logic.
- **Input Limits:** A single input can serve any number of outputs. So the only limit is a practical one.
  in the setup. For no, inputs don't receive data, they are just a trigger for the block to start.
- **Arrow Label Syntax:** Arrow labels may include a pipe (`|`). Everything after the pipe is treated as a comment and
  ignored by the parser.
- **Comment Arrows (Experimental):** Arrows without direction (i.e. plain lines) and with a ? label are considered
  visual comments. These are not yet parsed, just a test to see if the method works.
- **Free Connection Within Functional Groups:** Blocks within the same functional group (e.g., task blocks) can connect
  freely without restriction. Logical sense is not enforced.
- **Hint** As you might notice when clicking on a shape. The text contains words enclosed in `%`, those are properties.
  Meaning these are placeholders that get replaced by the property if it exists.

## Current blocks (as of 3.0.0)

### Origin block

- **Purpose:** Serves as entry point to linked blocks and provides an ID that allows it to be triggered via a `command`.
- **Outputs**: A single output labeled `next`, leading to the next block in the sequence.
- **Inputs**: Receives a trigger that restarts the task in sequence. Unlike when triggered by a `command` or
  `Controlblock`,
  this behavior is sequential rather than threaded.
- **Required properties:**
    - `dcafstype`: Must be `originblock` for it to be processed as an Origin Block.
    - `dcafsid`: The unique ID used to reference the link. It needs to be unique within file.
- **Optional Properties:**
    - `autostart`: Defaults to `no/false`. Determines whether the sequence starts on startup/reload or only when
      triggered by a command or other block.

### Delay block

- **Purpose:** Pauses **execution** here until the delay time has passed._
- **Outputs**: A single output labeled `next`, leading to the next block in the sequence.
- **Inputs**: Receives a trigger that starts the delay countdown. Successive triggers are ignored until the countdown
  has passed.
- **Required properties:**
    - `dcafstype`: Must be `delayblock` for it to be processed as a Delay Block.
    - `delay`: Defines the period to wait. The format is abbreviated time period (i.e. `5s`,`10m`,`3h10m2s`)
- **Optional Properties:**
    - None for now, below is an untested addition.
    - `retrigger`: Defaults to `no`. When this block is triggered while a wait is active, the count-down **does not**
      restart.

### Interval block

- **Purpose:** Triggers **execution** at a set interval, indefinitely or fur the number of times specified by `repeats`.
- **Outputs**:
    - A route labeled `next`, leading to the next block in the sequence.
    - A route labeled `repeats==0`, diverting flow when `repeats` reaches 0.
- **Inputs**: Receives a trigger that starts the interval. Successive triggers are ignored by default.
- **Required properties:**
    - `dcafstype`: Must be `intervalblock` for it to be processed as an Interval Block.
    - `interval`: Defines the interval. The format is abbreviated time period (i.e. `5s`,`10m`,`3h10m2s`)
- **Optional Properties:**
    - `repeats` Defaults to -1 (infinite). Defines how many times the interval is executed. A value of 0 reduces it to a
      `delayblock`.
    - (Untested) `retrigger`: Defines how the block responds to a trigger while already active., Options are:
        - `stop`– stops the current interval cycle;
        - `continue`– ignores the new trigger (default);
        - `restart`– restarts the current interval countdown but doesn't affect `repeats`.

### Time Block

- **Purpose:** Triggers **execution** at a set time of day, on specified days of the week.
- **Outputs**: A single output labeled `next`, leading to the next block in the sequence.
- **Inputs**: Receives a trigger that starts the timer. Successive triggers are ignored by default.
- **Required properties:**
    - `dcafstype`: Must be `timeblock` for it to be processed as a Time Block.
    - `time`: Defines the time of day in **UTC** (24-hour format, e.g., 14:30).
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

### Reader Block

- **Purpose:** Pauses **execution** at this block until either the specified data is received from the source or the
  timeout expires.
- **Outputs**:
    - A route labeled `received`, leading to the next block in the sequence.
    - A route labeled `timeout`, diverting flow when `timeout` reaches 0.
- **Inputs**:
    - A route from another task block, triggers to subscribe to the source and wait for data.
    - A route labeled `source`, determines which source to subscribe to.
- **Required properties:**
    - `dcafstype`: Must be `readerblock` for it to be processed as a Reader Block.
    - `wants`: Defines the data to wait for. The check is case-insensitive.
    - `timeout`: Default is **no** timeout. Maximum time to wait before triggering the `timeout` route. The format is
      abbreviated time period (i.e. `5s`,`10m`,`3h10m2s`)
- **Optional Properties:**
    - `source`: Defines the source of the data, if no block is connected with an arrow that has the `source` label.

### Writer Block

- **Purpose:** Writes the specified data to the `target`.
- **Outputs**:
    - A route labeled `next`, leading to the next block in the sequence.
    - A route labeled `fail`, diverting flow when writing failed.
- **Inputs**:
    - A route from another task block, triggers to resolve the target and write the data. Successive triggers repeat the
      writing.
    - A route labeled`target`. Defines the target of the data, which could be a stream, file,
      or another writable reference.
- **Required properties:**
    - `dcafstype`: Must be `writerblock` for it to be processed as a Writer Block.
    - `message`: Defines the data to send. Expected end-of-line sequences are automatically added.
- **Optional Properties:**
    - `target`: Same as the arrow. Used if no arrow connected valid target is found.

### Counter Block

- **Purpose:** Counts down every time it is triggered, optionally diverts the flow to the alternate route on reaching
  zero.
- **Outputs**:
    - A route labeled `counter>0`, leading to the next block in the sequence, taken while counter is above 0.
    - A route labeled `counter==0`, diverting flow once the counter reached zero.
- **Inputs**: Receives a trigger that decrements the counter and acts according to the new value.
- **Required properties:**
    - `dcafstype`: Must be `originblock` for it to be processed as a Counter Block.
    - `counter`: The amount of times this block can be triggered before it optionally diverts flow.
- **Optional Properties:**
    - `onzero`: Defaults to `alt_pass`. Determines what happens on reaching zero, options:
        - `alt_pass`: Follow the alternative route without marking the task as failed.
        - `alt_fail`: Follow the alternative route but mark the task as failed.
        - `stop`: Don't continue.
    - `altcount`: Defaults to `once`. How often the alternative route is taken, options are `once` or `infinite`. Note
      that
      a failed task will be logged on every use of that route and result will be logged even if no block is connected.

### Condition Block

- **Purpose:** Checks a condition and diverts flow on fail.
- **Outputs**:
    - A route labeled `pass`, leading to the next block in the sequence on pass.
    - A route labeled `fail`, diverting flow when result is false. Marks the task as failed
- **Inputs**: Receives a trigger that initiates the comparison. Successive triggers repeat this.
- **Required properties:**
    - `dcafstype`: Must be `conditionblock` for it to be processed as a Condition Block.
    - `expression`: Defines the condition, brackets and references to `rtvals` are allowed, nesting not (yet).
- **Optional Properties:**
  