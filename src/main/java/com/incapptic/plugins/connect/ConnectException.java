package com.incapptic.plugins.connect;

import java.io.IOException;

/**
 * Created by tjurkiewicz on 16/02/2017.
 */
public class ConnectException extends IOException {
    public ConnectException() {
    }

    public ConnectException(String message) {
        super(message);
    }

    public ConnectException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConnectException(Throwable cause) {
        super(cause);
    }
}
