package io.stream.serialport;

import com.fazecast.jSerialComm.*;
import das.Core;
import io.Writable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.stream.BaseStream;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.LookAndFeel;
import util.tools.Tools;
import util.xml.XMLdigger;
import worker.Datagram;

import java.time.Instant;

/**
 * Variant of the StreamHandler class that is
 */
public class SerialStream extends BaseStream implements Writable {

    protected SerialPort serialPort;
    String port ="";
    boolean flush=false;
    int eolFound=0;
    byte[] eolBytes;
    ByteBuf buffer;
    public SerialStream(Element stream) {
        super(stream);
    }

    protected String getType(){
        return "serial";
    }
    public boolean setPort(String port) {
        try{
            this.port=port;
            serialPort = SerialPort.getCommPort(port);
        }catch( SerialPortInvalidPortException e ){
            Logger.error("No such serial port: " + port);
            Logger.error(e);
            return false;
        }
        return true;
    }

    public String getInfo() {
        String info = "No connection to "+port;
        if( serialPort!=null){
            info = serialPort.getSystemPortName();
            if( info.equalsIgnoreCase("0"))
                info=port;
            info += " | "+ getSerialSettings();
        }
        return "SERIAL [" + id + "] " + info;
    }

    public boolean connect() {
        return this.doConnect(eol);
    }

    private boolean doConnect(String delimiter) {
        eol = delimiter;
        connectionAttempts++;

        if (serialPort == null) {
            return false;
        }

        if (serialPort.openPort()) {
            serialPort.flushIOBuffers(); // Make sure we don't process stuff that was in there before startup
            addListener();
            Logger.info("Connected to serial port " + serialPort.getSystemPortName());
            listeners.forEach( l -> l.notifyOpened(id) );
        } else {
            if(LookAndFeel.isNthAttempt(connectionAttempts))
                Logger.info("FAILED connection to serial port " + serialPort.getSystemPortName());
            return false;
        }
        return true;
    }

    private void addListener() {
        if (serialPort == null)
            return;

        if (eol.isEmpty() || flush ) {
            if( flush ) {
                buffer = Unpooled.buffer(128,512);
                eolBytes = eol.getBytes();
            }
            serialPort.addDataListener(new SerialPortDataListener() {
                @Override
                public int getListeningEvents() {
                    return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
                }

                @Override
                public void serialEvent(SerialPortEvent event) {
                    processListenerEvent( event.getReceivedData() );
                }
            });
        } else {
            serialPort.addDataListener(new MessageListener(eol));
        }
    }
    protected void flagIdle(){}
    private final class MessageListener implements SerialPortMessageListenerWithExceptions {

        byte[] deli;

        public MessageListener(String delimiter) {
            deli = delimiter.getBytes();
        }

        @Override
        public int getListeningEvents() {
            return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
        }

        @Override
        public byte[] getMessageDelimiter() {
            return deli;
        }

        @Override
        public boolean delimiterIndicatesEndOfMessage() {
            return true;
        }

        @Override
        public void serialEvent(SerialPortEvent event) {
            processMessageEvent( event.getReceivedData() );
        }

        @Override
        public void catchException(Exception e) {
            Logger.error(e);
        }
    }
    public void flushBuffer(){
        if( buffer==null || buffer.readableBytes()==0 )
            return;
        var rec = new byte[buffer.readableBytes()];
        buffer.readBytes(rec);
        forwardData(new String(rec));
    }
    protected void processListenerEvent( byte[] data ){
        Logger.debug(id+ " <-- "+Tools.fromBytesToHexString(data));

        if( readerIdle ){
            listeners.forEach( l -> l.notifyActive(id));
            readerIdle=false;
        }
        if( flush ){
            int used = buffer.readableBytes();
            Logger.info("Used space:"+used);

            for ( byte datum : data) {
                buffer.writeByte(datum);
                if (datum == eolBytes[eolFound]) {
                    eolFound++;
                    if (eolFound == eolBytes.length) { // Got whole eol
                        var rec = new byte[buffer.readableBytes()-eolFound];
                        buffer.readBytes(rec); // Read the bytes, but omit the eol
                        buffer.clear(); // ignore the eol
                        Logger.tag("RAW").warn(id() + "\t" + Tools.fromBytesToHexString(rec));
                        forwardData(new String(rec));
                        eolFound = 0;
                    }
                } else {
                    eolFound = 0;
                }
            }
        }else {
            Logger.tag("RAW").warn(id() + "\t" + Tools.fromBytesToHexString(data));
            forwardData(data);
        }
    }

    protected void processMessageEvent(byte[] data){
        String msg = new String(data).replace(eol, ""); // replace actually needed?
        if( readerIdle ){
            listeners.forEach( l -> l.notifyActive(id));
            readerIdle=false;
        }
        // Log anything and everything (except empty strings)
        if( !msg.isBlank() && log ) {        // If the message isn't an empty string and logging is enabled, store the data with logback
            Logger.tag("RAW").warn( id + "\t" + msg);
        }

        // Implement the use of labels
        if( !label.isEmpty() ) { // No use adding to queue without label
            Core.addToQueue( Datagram.build(msg).label(label).priority(priority).writable(this) );
        }

        forwardData(msg);

        long p = Instant.now().toEpochMilli() - timestamp; // Calculate the time between 'now' and when the previous
        // message was received
        if (p > 0) { // If this time is valid
            passed = p; // Store it
        }
    }
    protected void forwardData( String message){
        forwardData(message.getBytes());
    }

    private void forwardData(byte[] data) {
        timestamp = Instant.now().toEpochMilli(); // Store the timestamp of the received message
        Logger.debug("Received: " + new String(data));
        if (!targets.isEmpty()) {
            try {
                targets.forEach(dt -> eventLoopGroup.submit(() -> {
                    try {
                        if (eol.isEmpty()) {
                            dt.writeBytes(data);
                        } else {
                            dt.writeLine(id, new String(data));
                        }
                    } catch (Exception e) {
                        Logger.error(id + " -> Something bad while writeLine to " + dt.id(), e);
                    }
                }));
                targets.removeIf(wr -> !wr.isConnectionValid()); // Clear inactive
            } catch (Exception e) {
                Logger.error(id + " -> Something bad in serial port: ", e);
            }
        }
    }
    public void alterSerialSettings(String settings) {
        if (serialPort == null) {
            return;
        }

        String[] split = settings.split(",");
        int stopbits;
        int parity = SerialPort.NO_PARITY;

        if (split.length == 1)
            split = settings.split(";");

        stopbits = switch (split[2]) {
            case "1.5" -> SerialPort.ONE_POINT_FIVE_STOP_BITS;
            case "2" -> SerialPort.TWO_STOP_BITS;
            default -> SerialPort.ONE_STOP_BIT;
        };
        if (split.length > 3) {
            parity = switch (split[3]) {
                case "even" -> SerialPort.EVEN_PARITY;
                case "odd" -> SerialPort.ODD_PARITY;
                case "mark" -> SerialPort.MARK_PARITY;
                case "space" -> SerialPort.SPACE_PARITY;
                default -> SerialPort.NO_PARITY;
            };
        }

        serialPort.setBaudRate(Tools.parseInt(split[0], 19200));
        serialPort.setNumDataBits(Tools.parseInt(split[1], 8));
        serialPort.setNumStopBits(stopbits);
        serialPort.setParity(parity);
    }

    public String getSerialSettings() {
        return serialPort.getBaudRate() + "," + serialPort.getNumDataBits() + "," + getStopbits() + "," + getParity();
    }

    private String getParity() {
        return switch (serialPort.getParity()) {
            case SerialPort.EVEN_PARITY -> "even";
            case SerialPort.ODD_PARITY -> "odd";
            case SerialPort.MARK_PARITY -> "mark";
            case SerialPort.SPACE_PARITY -> "space";
            default -> "none";
        };
    }

    private String getStopbits() {
        return switch (serialPort.getNumStopBits()) {
            case SerialPort.ONE_POINT_FIVE_STOP_BITS -> "1.5";
            case SerialPort.TWO_STOP_BITS -> "2";
            default -> "1";
        };
    }

    public void setBaudrate(int baudrate) {
        serialPort.setBaudRate(baudrate);
    }

    /* ************************************** W R I T I N G ************************************************************/
    /**
     * Sending data that will be appended by the default newline string.
     * 
     * @param message The data to send.
     * @return True If nothing was wrong with the connection
     */
    @Override
    public synchronized boolean writeLine(String message) {
        return writeString(message + eol);
    }
    @Override
    public synchronized boolean writeLine(String origin, String message) {
        if( addDataOrigin )
            return writeString(origin+":"+message + eol);
        return writeString(message + eol);
    }
    /**
     * Sending data that won't be appended with anything
     * 
     * @param message The data to send.
     * @return True If nothing was wrong with the connection
     */
    @Override
    public synchronized boolean writeString(String message) {
        return write(message.getBytes());
    }

    /**
     * Sending raw data
     * @param data The bytes to write
     * @return True if succeeded
     */
    @Override
    public synchronized boolean writeBytes(byte[] data) {
         return write(data);
    }

    /**
     * Sending data that won't be appended with anything
     * 
     * @param data The data to send.
     * @return True If nothing was wrong with the connection
     */
    public synchronized boolean write(byte[] data) {
        Logger.debug(id+" --> "+Tools.fromBytesToHexString(data));
        if (serialPort != null && serialPort.isOpen() && serialPort.bytesAwaitingWrite()<8000) {
            var res=-1;
            try{
                res = serialPort.writeBytes(data, data.length);

            }catch(Exception e) {
                Logger.error(e);
            }
            if( res==-1){
                Logger.error(id+" -> Error writing to port "+serialPort.getSystemPortName());
            }else if( res != data.length ){
                Logger.error(id+" -> The amount of bytes written does not equal expected.");
            }
            return  res == data.length;
        }else if( serialPort==null){
            Logger.error(id+" -> No write done, serial port is null.");

            return false;
        }else if( !serialPort.isOpen()){
            Logger.error(id+" -> No write done, serial port is closed.");
        }
        if( serialPort.bytesAwaitingWrite()<8000 ){
            Logger.error("Data not being read from "+id);
        }
        return false;
    }

    public boolean disconnect() {
        if (serialPort != null && serialPort.isOpen())
            return serialPort.closePort();
        return false;
    }

    @Override
    public boolean isConnectionValid() {
        if (serialPort == null || serialPort.bytesAwaitingWrite()>8000)
            return false;
        return serialPort.isOpen();
    }

    @Override
    public Writable getWritable() {
        return this;
    }

    @Override
    public boolean giveObject(String info, Object object) {
        return false;
    }

    @Override
    protected boolean readExtraFromXML(Element stream) {
        var dig = XMLdigger.goIn(stream);
        if (!setPort( dig.peekAt("port").value(""))) {
            return false;
        }
        alterSerialSettings(dig.peekAt("serialsettings").value("19200,8,1,none"));
        if( dig.hasPeek("ttl") ){
            dig.usePeek();
            if( dig.hasAttr("flush") && dig.attr("flush",false))
                flush=true;
        }
        return true;
    }
    @Override
    public long getLastTimestamp() {
        return timestamp;
    }

}