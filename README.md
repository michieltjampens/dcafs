dcafs
=========
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)  

A tool that takes care of all the 'nitty-gritty' involved in getting sensor data into a database or plain text.
It also supports device interrogation and control.

It's lightweight and works headless, with control via Telnet, Matrix, or, if you have the patience, email.  
Once set up, projects like [Grafana](https://grafana.com/) can be used to display data, or you can keep it simple.

The 'Getting Started' guide, is available [here](https://github.com/michieltjampens/dcafs/blob/main/docs/Basics.md).

## Data Collection

Supports data collection from a variety of sources:

* **Local hardware:** Serial/TTY, I2C, and files.
* **Network-based:** TCP/UDP (both server and client), MODBUS(ascii), MQTT (client).
* **Internet:** Email and Matrix.

## Data Alteration & Filtering

Configure the path of the collected data through various modules or steps:

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

* **Memory:** Data is initially parsed and stored in memory.
* **Log files:** Data can be saved in timestamped .log files, providing a simple and accessible history of raw or
  altered data.
* **SQLite:** Stores data locally in a SQLite database, with automatic database and table creation.
* **Server-based databases:** Supports MariaDB, MySQL, PostgreSQL, and MSSQL, automatically creating and reading the
  table structure (but not querying data).
* **MQTT:** Data can be sent back to an MQTT broker, enabling real-time data forwarding and integration with other
  systems.
* **Email:** An email inbox is technically a storage...

## Scheduling

Handled by the Task Manager, provides flexibility through tasks consisting of:

* **Trigger:** Based on delay, interval, or time of day (on a specified weekday).
* **Action:** Send data to a source, send an email, or execute a user command.
* **Additional Conditions:** Requirements based on real-time data or repeat until conditions are met.

## Triggering

Automate everything a user could do or the tasks mentioned earlier:

* **Directly:** Trigger actions through Telnet, email, or Matrix commands.
* **Real-time data:** Trigger based on logical operations on real-time data, such as exceeding thresholds or meeting
  conditions.
* **Hardware events:** Respond to events such as a source disconnecting, being idle, (re)connecting, or even GPIO
  events.
* **Geofencing:** Trigger actions when entering or leaving a specified area, such as a four-corner zone or circular
  zone, with an optional bearing check for the circular zone.

These triggers allow for complex automation of tasks, enabling dcafs to respond to a wide range of conditions and
events, both from the software and the hardware side.

## Configuration via XML: Simple (Opinion) and Powerful

At the heart of dcafs is its command functionality, made possible by configuring everything seen so far through
flexible XML files. Although it might seem complex at first, this approach offers powerful control and easy automation,
making it adaptable to a wide range of use cases.

## Use Cases

### As a Tool

* **Device Control and Monitoring:** Schedule tasks to interact with devices or add hardware to control pumps,
  solenoids, or other equipment based on time, sensor data, or geofencing events.
* **Flexible Data Forwarding:** Put a serial device on the network or sniff its traffic to reverse engineer
  communication protocols, enabling seamless integration with other systems or remote monitoring.

### As a Logging Platform

* **At Home:** Start small—run everything on a Raspberry Pi, logging data from MQTT-connected sensors, all stored in a
  lightweight database. Perfect for local, simple setups.
* **In the Field:** Still on a Raspberry Pi (or similar small device), now you're collecting environmental data during
  trips or fieldwork, uploading it to a central server for analysis, without the need for a full-scale server setup.
* **On a Research Vessel:** Transition to something bigger—on a server, you handle more complex data streams from a
  range of sensors. The system tracks and analyzes real-time data, all while supporting remote access and continuous
  logging.
* **On a Buoy:** Back to low power—now you’ve got a system running on a buoy, autonomously collecting and transmitting
  data without the need for large servers, operating efficiently on minimal power in remote environments.
* **In Deep Space:** Vast distances—well a "tiny" nuclear cell might be necessary... but it is possible?

## Installation
* Make sure you have _at least_ java17 installed. If not, [download and install java 17](https://adoptium.net/)
* Either download the most recent (pre)release [here](https://github.com/michieltjampens/dcafs/releases)
  * Unpack to the working folder  
* Or clone the repo and build it with maven (mvn install) directly or through IDE.
    * copy the resulting dcafs*.jar and lib folder to a working dir

## Running it
### Windows
* If you have java17+ installed properly, just doubleclick the dcafs*.jar
  * If extra folders and a settings.xml appear, this worked
* If java 17+ isn't installed properly, check the installation step
   
### Linux
* In a terminal
  * Go to the folder containing the .jar
  * sudo java -jar dcafs-*.jar  (sudo is required to be able to open the telnet port)
  * To make this survive closing the terminal, use [tmux](https://linuxize.com/post/getting-started-with-tmux/) to start it or run it as a service
* As a service:
  * If going the repo route, first copy past the install_as_service.sh file to the same folder as the dcafs*.jar 
  * chmod +x install_as_service.sh file
  * ./install_as_service.sh
    * Restarting the service: sudo systemctl restart dcafs
    * Get the status: sudo systemctl status dcafs
    * Read the full log: sudo journalctl -u dcafs.service
    * Follow the console: sudo journalctl -u dcafs.service -f
   * Optional, add bash alias's for easier usage (apply with source ~/.bashrc)
     * echo "alias dcafs_restart='sudo systemctl restart dcafs'" >> ~/.bashrc
     * echo "alias dcafs_start='sudo systemctl start dcafs'" >> ~/.bashrc
     * echo "alias dcafs_stop='sudo systemctl stop dcafs'" >> ~/.bashrc
     * echo "alias dcafs_log='sudo journalctl -u dcafs.service'" >> ~/.bashrc
     * echo "alias dcafs_track='sudo journalctl -u dcafs.service -f'" >> ~/.bashrc
     * echo "alias dcafs='telnet localhost'" >> ~/.bashrc
  
## First steps

It's recommended to follow [this](https://github.com/michieltjampens/dcafs/blob/main/docs/Basics.md) guide if it's your first time using it.

Once running and after opening a telnet connection to it, you'll be greeted with the following screen.

<img src="https://user-images.githubusercontent.com/60646590/112713982-65630380-8ed8-11eb-8987-109a2a066b66.png" width="500" height="300">

In the background, a fresh settings.xml was generated.
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
* `ss:addserial,serialsensor,COM1:19200`  --> adds a serial connection to a sensor called serialsensor that runs at 19200 Baud
* `ss:addtcp,tcpsensor,localhost:4000`  --> adds a tcp connection to a sensor called tcpsensor with a locally hosted tcp server

Assuming the data has the default eol sequence, you'll receive the data in the window by typing
* `raw:serialsensor` --> for the serial sensor
* `raw:tcpsensor` --> for the tcp sensor

Meanwhile, in the background, the settings.xml was updated as follows:
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

Sending `help` in the telnet interface should provide a list of available commands and guidance on
the next recommended steps. For more in-depth and extensive information, check the docs folder in the repo.

Oh, and the command `sd` shuts it down.
