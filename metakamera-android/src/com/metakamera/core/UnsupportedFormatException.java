package com.metakamera.core;

import java.io.IOException;

/** Thrown when an input file is not a supported (or is a corrupt) container. Message is user-facing Turkish. */
public class UnsupportedFormatException extends IOException {
    public UnsupportedFormatException(String message) {
        super(message);
    }
}
