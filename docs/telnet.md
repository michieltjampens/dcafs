# Telnet

## Features with commands

### Adding prefixes to received data shown in Telnet

These can be combined.

* `>>>prefixid` Prepends the **id** (colored magenta) of the source of the data. Useful when showing multiple.
* `>>>es` Prepends the elapsed time since previous data.
* `>>>ts` Prepends the time the data was received.
* `>>>ds` Prepends the full timestamp the data was received.

## Debugging paths

* Consider a path called `test` with a source `raw:device`.

* Start with setting up the session to have the id of the data added `>>prefix`.
* Next, request the raw data with `raw:device`.
* Then, request the result of the path with `path:test`.