package io.telnet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.Optional;

public class CommandLineInterface {
    ByteBuf buffer = Unpooled.buffer(9,512);       // Buffer that holds the received data
    private ArrayList<String> cmdHistory = new ArrayList<>(); // Buffer that holds the processed commands
    private int cmdHistoryIndex =-1; // Pointer to the last send historical cmd

    Channel channel;

    public CommandLineInterface( Channel channel ){
        this.channel=channel;

        if( channel !=null ) {
            channel.writeAndFlush(TelnetCodes.WILL_SGA); // Enable sending individual characters
            channel.writeAndFlush(TelnetCodes.WILL_ECHO);
        }
    }

    /**
     * Receive data to process
     * @param data The data received
     * @return And optional response
     */
    public Optional<byte[]> receiveData(byte[] data ){

        byte[] rec=null;
        for( int a=0;a<data.length;a++ ){
            byte b = data[a];
            switch (data[a]) {
                case TelnetCodes.IAC -> Logger.debug(TelnetCodes.toReadableIAC(data[a++]) + " - "
                        + TelnetCodes.toReadableIAC(data[a++]) + " - "
                        + TelnetCodes.toReadableIAC(data[a]));
                case 27 -> {
                    if (data.length == 1) {
                        rec = doCarriageReturn(b);
                        continue;
                    }
                    a++;
                    Logger.debug("Received: " + (char) b + " or " + Integer.toString(b) + " " + Integer.toString(data[a]) + Integer.toString(data[a + 1]));
                    if (data[a] == 91) {
                        a++;
                        switch (data[a]) {
                            case 65 -> sendHistory(-1);// Arrow Up
                            case 66 -> sendHistory(1);// Arrow Down
                            case 67 -> { // Arrow Right
                                // Only move to the right if current space is used
                                if (buffer.getByte(buffer.writerIndex()) != 0) {
                                    buffer.setIndex(buffer.readerIndex(), buffer.writerIndex() + 1);
                                    writeString(TelnetCodes.CURSOR_RIGHT);
                                }
                            }
                            case 68 -> { // Arrow Left
                                if (buffer.writerIndex() != 0) {
                                    writeString(TelnetCodes.CURSOR_LEFT);
                                    buffer.setIndex(buffer.readerIndex(), buffer.writerIndex() - 1);
                                }
                            }
                        }
                    }
                }
                case '\n' -> writeByte(b);
                case '\r', 19 -> rec = doCarriageReturn(b);
                case 126 -> doDeleteCmd();
                case 8, 127 -> doBackspaceCmd();
                default -> {
                    if (data[a] != 0)
                        insertByte(b);
                }
            }
        }
        return Optional.ofNullable(rec);
    }
    private void doDeleteCmd(){
        writeString(TelnetCodes.CURSOR_RIGHT);
        if( buffer.getByte(buffer.writerIndex()+1)!=0x00){
            buffer.setIndex( buffer.readerIndex(),buffer.writerIndex()+1);
            shiftLeft();
        }else{
            writeByte((byte)127);// do backspace
            buffer.setByte(buffer.writerIndex(),0x00); // delete current value in buffer
        }
    }
    private void doBackspaceCmd(){
        if( buffer.getByte(buffer.writerIndex())!=0x00){
            shiftLeft();
        }else{
            writeByte((byte)8);
            writeByte((byte)' ');
            writeByte((byte)8);
            buffer.setByte(buffer.writerIndex()-1,0x00);
            buffer.setIndex( buffer.readerIndex(),buffer.writerIndex()-1);
        }
    }
    private byte[] doCarriageReturn(byte b){
        writeByte((byte)13); // echo CR
        if(b==27) // 27 = ESC
            insertByte((byte)27);
        if( b==19 || b==27 ) // If CTRL+S or ESC, insert a 0
            insertByte((byte)0);

        // When in the middle of altering text, the writer index isn't pointing to the last
        // char, so find the index of the actual end
        int wi = buffer.writerIndex();
        if( buffer.capacity() > wi ) { // If writer index is at the end of the buffer, no use searching
            while (buffer.getByte(wi) != 0)
                wi++;
        }
        return readBuffer(wi);
    }
    /**
     * Read the cmd from the buffer and add it to the cmd history
     * @param wi Index in the buffer of last useful data
     * @return The read bytes
     */
    private byte[] readBuffer(int wi){
        buffer.setIndex(0,wi); // Make sure it doesn't read more than 1 null?
        var rec = new byte[buffer.readableBytes()];
        buffer.readBytes(rec); // Copies the content to the array

        // Reset the buffer
        buffer.setZero(0,wi); // Clear everything that was read
        buffer.clear(); // Clears reader and writer index

        // Store the read cmd in the history and alter index
        var r = new String(rec);
        cmdHistory.remove(r); // Don't repeat cmds
        cmdHistory.add(r);
        if( cmdHistory.size()>20) // Limit it
            cmdHistory.remove(0);
        cmdHistoryIndex = cmdHistory.size(); // point to last item again

        return rec;
    }
    /**
     * Shift the content of the buffer left starting with the current writerindex
     */
    private void shiftLeft(){
        int start = buffer.writerIndex(); // Position to start from
        buffer.writerIndex(start-1 ); // New position will be one to the left

        int max = buffer.capacity()-1; // Highest allowed index

        writeString( TelnetCodes.cursorLeft(1) ); // Move to position that will be removed
        int moves = 1; // To count how many chars we need to shift back after wards
        for( int a=start;a<=max;a++){ // Go through all chars in the buffer from current position
            var moved = buffer.getByte(a);
            buffer.setByte(a-1,moved);
            if( moved == 0) // Nu use moving unused buffer, stop here
                break;
            writeByte(moved);
            moves++;
        }
        writeByte((byte)' '); // clear the last character
        writeString( TelnetCodes.cursorLeft(moves) ); // Move back to where we came from
    }
    /**
     * Insert a byte at the current writerindex, shifting everything to the right of it ... to the right
     * @param b The byte to insert
     */
    private void insertByte( byte b ){

        if (buffer.writerIndex() > 2 && b == 0 && buffer.getByte(buffer.writerIndex() - 1) == 0)
            return;

        int start = buffer.writerIndex(); // Position to start from
        int max = buffer.capacity()-1; // Highest allowed index
        int end = 0;

        // Figure out where the data stops
        while( buffer.getByte(end)!=0 && end < max )
            end++;

        // If the end is lower than the start, this is just appending
        if( end <= start ) {
            buffer.writeByte(b);
            writeByte(b);
            return;
        }
        // If we get here, it means stuff needs to be shifted to the right
        if( end == max ){ // Meaning if we shift things we go out of bonds, so add to it
            buffer.writerIndex(end+1); // Need to add it to the end
            buffer.writeByte(buffer.getByte(end)); // Creates space for last char
            buffer.writerIndex(start); // Restore it
            end++;
        }

        // Move the rest
        for( int a=end; a > start;a--){
            var moved = buffer.getByte(a-1);
            buffer.setByte( a,moved );
        }
        // Now do the insert
        buffer.writeByte(b);

        // Finally fix what the user sees
        for( int a=start;a<=end;a++){
            writeByte( buffer.getByte(a) );
        }
        writeString( TelnetCodes.cursorLeft(end-start) );
    }
    /**
     * Send the historical command referenced to by the current histindex value and alter this value
     * @param adj The alteration to be applied to histIndex
     */
    private void sendHistory(int adj){

        // Return when the history buffer is empty
        if( cmdHistory.isEmpty() )
            return;

        cmdHistoryIndex += adj; // Alter the pointer

        if( cmdHistoryIndex <0) // Can't go lower than 0
            cmdHistoryIndex =0;

        if (cmdHistoryIndex == cmdHistory.size() ) // Shouldn't go out of bounds
            cmdHistoryIndex = cmdHistory.size() - 1;

        writeString("\r>" + cmdHistory.get(cmdHistoryIndex));//Move cursor and send history
        writeString(TelnetCodes.CLEAR_LINE_END); // clear the rest of the line
        buffer.clear(); // reset the reader and writer index
        buffer.writeBytes(cmdHistory.get(cmdHistoryIndex).getBytes()); // fill the buffer with history
        try {
            buffer.setZero(buffer.writerIndex(), buffer.writableBytes() ); // clear the rest of the buffer
        }catch(Exception e){
            Logger.error("Zerosetting failed: "+e.getMessage());
        }
    }
    public void setHistory( ArrayList<String> cmds){
        cmdHistory=cmds;
    }
    public void clearHistory(){
        cmdHistory.clear();
    }
    /**
     * Write a single byte to the channel this CLI is using
     *
     * @param data The byte of data to send
     */
    public synchronized void writeByte(byte data ){
        if( channel != null && channel.isActive()){
            channel.writeAndFlush( new byte[]{data});
        }
    }

    /**
     * Write a string message to the channel this CLI is using
     * @param message The message to send
     * @return True if the channel was active
     */
    public synchronized boolean writeString( String message ){
        if( channel != null && channel.isActive()){
            channel.writeAndFlush(message.getBytes());
            return true;
        }
        return false;
    }
}


