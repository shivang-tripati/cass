package com.shivang.obd.audio;

/** Storage failure inside the {@link AudioStorage} implementation. */
public class AudioStorageException extends RuntimeException {

    public AudioStorageException(String message) {
        super(message);
    }

    public AudioStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
