package com.incapptic.plugins.appconnectplugin;

import java.io.IOException;

/**
 * Created by tjurkiewicz on 16/02/2017.
 */
public class AppConnectException extends IOException {
    public AppConnectException() {
    }

    public AppConnectException(String message) {
        super(message);
    }

    public AppConnectException(String message, Throwable cause) {
        super(message, cause);
    }

    public AppConnectException(Throwable cause) {
        super(cause);
    }
}
