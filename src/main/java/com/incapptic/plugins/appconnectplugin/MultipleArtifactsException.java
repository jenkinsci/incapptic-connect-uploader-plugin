package com.incapptic.plugins.appconnectplugin;

/**
 * Created by tjurkiewicz on 23/02/2017.
 */
public class MultipleArtifactsException extends Exception {
    public MultipleArtifactsException() {
    }

    public MultipleArtifactsException(String message) {
        super(message);
    }

    public MultipleArtifactsException(String message, Throwable cause) {
        super(message, cause);
    }

    public MultipleArtifactsException(Throwable cause) {
        super(cause);
    }
}
