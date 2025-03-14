dcafs
=========
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)  

A tool that takes care of all the 'nitty-gritty' involved in getting sensor data into a database or plain text file.
It also supports device interrogation/querying and control.

It's lightweight and works headless, with control via Telnet, [Matrix][Matrix], or -if you have the patience- email.  
Once set up, viewing data is possible by:

* using a project like [Grafana](https://grafana.com/) to display data in real time, 
* generating and sending automated reports to email or Matrix
* logging in through telnet and query the data
* working with the data output (database or plain text files)

The 'Getting Started' guide is available [here](https://github.com/michieltjampens/dcafs/blob/main/docs/Basics.md).

## Data Collection

Dcafs supports data collection from a variety of sources:

* **Local instrumentation:** Serial/TTY, I2C, SPI and files (can be used as passthrough from other logging systems).
* **Network-based (LAN):** TCP/UDP (both server and client), MODBUS(ascii), MQTT (client).
* **Internet (WAN):** Email and [Matrix][Matrix].

## Data in-place processing & Filtering

Configure the [path](docs/paths.md) of the collected data through various modules or steps:

* **Line filtering:** Exclude unwanted data or lines based on specific criteria.
* **String operations:** Modify or format data using regex or custom string manipulation.
* **Mathematical operations:** Apply complex calculations to numeric data.

## Data Forwarding

Flexible routing of data, including:

* **Return to origin:** Send (altered) data back to its original source.
* **Protocol conversion:** Or over any other link, such as serial to TCP.
* **Multi-destination support:** Why limit to a single destination?

## Data Storage

Store (processed) data in various formats:

* **Memory:** Data is initially parsed and stored [in memory](docs/rtvals.md).
* **Log files:** Data can be saved in timestamped .log files, providing a simple and accessible history of raw or
  altered data.
* **SQLite:** Stores data locally in a SQLite database, with automatic database and table creation.
* **Server-based databases:** Supports MariaDB, MySQL, PostgreSQL, and MSSQL, automatically creating and reading the
  table structure (but not querying data).
* **MQTT:** Data can be sent back to an MQTT broker, enabling real-time data forwarding and integration with other
  systems.
* **Email:** An email inbox is technically a storage...
* **FTP:** Supports FTP, SFTP and ...

## Scheduling

Handled by the [Task Manager](docs/taskmanager.md), provides flexibility through tasks consisting of:

* **Trigger:** Based on delay, interval, or time of day (on a specified weekday).
* **Action:** Send data to a source, send an email, or execute a user command.
* **Additional Conditions:** Requirements based on real-time data or repeat until conditions are met.

## Triggering

Automate everything a user could do or the tasks mentioned earlier:

* **Directly:** Trigger actions through Telnet, email, or Matrix commands.
* **On predetermined real-time data conditions:** Trigger based on logical operations on real-time data, such as exceeding thresholds or meeting
  conditions.
* **Hardware events:** Respond to events such as a source disconnecting, being idle, (re)connecting, or even GPIO
  events.
* **Geofencing:** Trigger actions when entering or leaving a specified area, such as a four-corner zone or circular
  zone, with an optional bearing check for the circular zone option.

These triggers allow for complex automation of tasks, enabling dcafs to respond to a wide range of conditions and
events, both from the software and the hardware side.

## Configuration via XML: Simple (Opinion) and Powerful

At the heart of dcafs is its command functionality, made possible by configuring everything seen so far through
flexible XML files. Although it might seem complex at first, this approach offers powerful control and easy automation,
making it adaptable to a wide range of use cases.

## Use Cases

### As a Tool

* **Device Control and Monitoring:** Schedule tasks to interact with devices or add hardware to control pumps,
  solenoids or other equipment based on time, sensor data, or geofencing events.
* **Flexible Data Forwarding:** Put a serial device on the network or sniff its traffic to reverse engineer
  communication protocols, enabling seamless integration with other systems or remote monitoring.

### As a logging platform

* **At Home:** Start small: run everything on a Raspberry Pi, logging data from MQTT-connected sensors, all stored in a
  lightweight database. Perfect for local, simple setups.
* **In the Field:** Still on a Raspberry Pi (or similar small device), collect environmental data during
  trips or fieldwork, uploading it to a central server for analysis without the need for a full-scale server setup.
* **On a Research Vessel:** Transition to something bigger on a server: handle more complex data streams from a
  range of sensors. The system tracks and analyzes real-time data, all while supporting remote access and continuous
  logging.
* **On a Buoy:** Back to low power: now you’ve got a system running on a buoy, autonomously collecting and transmitting
  data without the need for large servers, operating efficiently on minimal power in remote environments.
* **In Deep Space:** Over vast distances: a "tiny" nuclear cell (RTG)  might be necessary... but it is possible!

## Installation
* Make sure you have _at least_ java17 installed. If not, [download and install java 17](https://adoptium.net/)
* Either 
  * Download the most recent (pre)release [here](https://github.com/michieltjampens/dcafs/releases) and unpack to a working folder  
  * Or clone the repo and build it with Maven (`mvn install`) directly or by using an IDE. Then copy the resulting `dcafs*.jar` and `/lib` folder to a working directory

## Running it
### Windows
* If you have java17+ installed properly, just doubleclick the `dcafs*.jar`
  * You'll see extra folders and a settings.xml appear in your working folder, confirming a succesful startup.
* If java 17+ isn't installed properly, check the installation step above
   
### Linux
* In a terminal
  * Go to the folder containing the .jar
  * Run `sudo java -jar dcafs-*.jar`  (sudo is required to be able to open the telnet port)
  * To make this survive closing the terminal, use [tmux](https://linuxize.com/post/getting-started-with-tmux/) to start it or run it as a service (see below)
* As a service:
  * If going the repo route, first copy-paste the `install_as_service.sh` file to the same folder as the dcafs*.jar 
  * Run `chmod +x install_as_service.sh file`
  * `./install_as_service.sh`
    * Restarting the service: `sudo systemctl restart dcafs`
    * Get the status: `sudo systemctl status dcafs`
    * Read the full log: `sudo journalctl -u dcafs.service`
    * Follow the console: `sudo journalctl -u dcafs.service -f`
   * Optionally add bash alias's for easier usage (apply with `. ~/.bashrc)`
     * ```echo "alias dcafs_restart='sudo systemctl restart dcafs'" >> ~/.bashrc```
     * ```echo "alias dcafs_start='sudo systemctl start dcafs'" >> ~/.bashrc```
     * ```echo "alias dcafs_stop='sudo systemctl stop dcafs'" >> ~/.bashrc```
     * ```echo "alias dcafs_log='sudo journalctl -u dcafs.service'" >> ~/.bashrc```
     * ```echo "alias dcafs_track='sudo journalctl -u dcafs.service -f'" >> ~/.bashrc```
     * ```echo "alias dcafs='telnet localhost'" >> ~/.bashrc```
  
## First steps

It is recommended to follow [this](https://github.com/michieltjampens/dcafs/blob/main/docs/Basics.md) guide if it's your first time using it.

Once running and after opening a telnet connection to it (default: port 23), you'll be greeted with the following screen:

<img src="https://user-images.githubusercontent.com/60646590/112713982-65630380-8ed8-11eb-8987-109a2a066b66.png" width="500" height="300">

In the background, a fresh settings.xml was generated in your working directory:
````xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<dcafs>
    <settings>
        <!-- Settings related to the telnet server -->
        <telnet port="23" title="DCAFS">
            <textcolor>lightgray</textcolor>
        </telnet>
    </settings>
    <streams>
        <!-- Defining the various streams that need to be read -->
    </streams>
</dcafs>
````
Back in the telnet client, add a data source:
* `ss:addserial,serialsensor,COM1:19200`  --> adds a serial connection to a sensor called "serialsensor" that runs at 19200 Baud
* `ss:addtcp,tcpsensor,localhost:4000`  --> adds a tcp connection to a sensor called "tcpsensor" with a locally hosted tcp server

Assuming the data has the default eol (end-of-line) sequence of `<CR><LF>`, you'll receive the data in the open terminal by typing:
* `raw:serialsensor` --> for the serial sensor
* `raw:tcpsensor` --> for the tcp sensor

Meanwhile, in the background, the `settings.xml` was updated as follows:
````xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<dcafs>
  <settings>
    <!-- Settings related to the telnet server -->
      <telnet port="23" title="DCAFS">
          <textcolor>lightgray</textcolor>
      </telnet>
  </settings>
  <streams>
    <!-- Defining the various streams that need to be read -->
    <stream id="serialsensor" type="serial">
      <eol>crlf</eol>
      <serialsettings>19200,8,1,none</serialsettings>
      <port>COM1</port>
    </stream>
    <stream id="tcpsensor" type="tcp">
      <eol>crlf</eol>
      <address>localhost:4000</address>
    </stream>
  </streams>
</dcafs>
````

Sending `help` in the telnet interface will provide a list of available commands and guidance on
the next recommended steps. For more in-depth and extensive information, check:
* The docs folder in the repo.
* [The tutorial][The tutorial]

Oh, and the command `sd` shuts it down.

[Matrix]:matrix.org
[The tutorial]:docs/README.md
