package com.bookshelf.common;

/** 없거나, 남의 것이다 — 둘을 구분하지 않는다(403은 존재를 알려준다) */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
