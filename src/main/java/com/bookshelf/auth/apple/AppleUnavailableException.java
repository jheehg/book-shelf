package com.bookshelf.auth.apple;

public class AppleUnavailableException extends RuntimeException {
    public AppleUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
