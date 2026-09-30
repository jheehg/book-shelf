package com.bookshelf.search;

import com.bookshelf.common.DomainException;

public class InvalidQueryException extends DomainException {
    public InvalidQueryException(String message) {
        super(message);
    }
}
