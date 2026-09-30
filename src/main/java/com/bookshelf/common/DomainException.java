package com.bookshelf.common;

/** 도메인 규칙 위반. Phase 2에서 API가 생기면 400으로 번역한다. */
public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }
}
