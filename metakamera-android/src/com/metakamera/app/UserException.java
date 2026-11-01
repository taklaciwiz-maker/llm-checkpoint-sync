package com.metakamera.app;

import java.io.IOException;

/** IO failure whose message is already user-facing Turkish text. */
final class UserException extends IOException {
    UserException(String message) {
        super(message);
    }

    UserException(String message, Throwable cause) {
        super(message, cause);
    }
}
