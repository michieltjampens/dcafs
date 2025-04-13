package io;

public interface Writable {
    /**
     * Write a line that won't have the eol appended
     * @param data The data to write
     * @return True if this was successful
     */
    default boolean writeString(String data) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Write a line that has the eol appended
     * @param data The data to write
     * @return True if this was successful
     */
    boolean writeLine(String origin, String data);
    /**
     * Write the given data bytes
     * @param data The bytes to write
     * @return True if successful
     */
    default boolean writeBytes(byte[] data) {
        throw new UnsupportedOperationException("Not implemented");
    }
    /**
     * Get the id of the object implementing Writable
     * @return The (preferably unique) id for the implementing object
     */
    String id();
    /**
     * Indicate if the connection is valid or not. Mainly used to know if the Writable should be removed or not
     * @return True if it's valid
     */
    boolean isConnectionValid();

    default Writable getWritable() {
        return this;
    }

    default void giveObject(String info, Object object) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
