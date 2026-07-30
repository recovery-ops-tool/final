package com.recoverpro.server.exception;

public class SttUnavailableException extends RuntimeException {
    public SttUnavailableException(String message) { super(message); }
    public SttUnavailableException(String message, Throwable cause) { super(message, cause); }
}
