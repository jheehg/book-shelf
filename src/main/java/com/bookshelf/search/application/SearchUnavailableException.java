package com.bookshelf.search.application;

/** 캐시에도 없고 제공자에게도 물을 수 없다. Phase 2에서 "잠시 후 다시"로 번역한다 */
public class SearchUnavailableException extends RuntimeException {
    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
