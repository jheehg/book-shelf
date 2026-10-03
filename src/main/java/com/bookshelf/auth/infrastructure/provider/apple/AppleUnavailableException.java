package com.bookshelf.auth.infrastructure.provider.apple;

public class AppleUnavailableException extends RuntimeException {
    public AppleUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
