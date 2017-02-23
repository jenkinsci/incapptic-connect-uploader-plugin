package com.incapptic.plugins.appconnectplugin;

/**
 * Created by tjurkiewicz on 23/02/2017.
 */
public class ArtifactsNotFoundException extends Exception {
    public ArtifactsNotFoundException() {
    }

    public ArtifactsNotFoundException(String message) {
        super(message);
    }

    public ArtifactsNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public ArtifactsNotFoundException(Throwable cause) {
        super(cause);
    }
}
