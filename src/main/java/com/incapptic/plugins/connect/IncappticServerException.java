package com.incapptic.plugins.connect;

import java.io.IOException;

public class IncappticServerException extends IOException {
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
