package com.incapptic.plugins.connect;

public class IncappticServerException extends Exception {
    public IncappticServerException() {
    }

    public IncappticServerException(String message) {
        super(message);
    }

    public IncappticServerException(String message, Throwable cause) {
        super(message, cause);
    }

    public IncappticServerException(Throwable cause) {
        super(cause);
    }
}
