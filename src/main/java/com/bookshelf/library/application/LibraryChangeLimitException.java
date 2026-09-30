package com.bookshelf.library.application;

public class LibraryChangeLimitException extends RuntimeException {
    public LibraryChangeLimitException(long memberId) {
        super("회원 " + memberId + "의 오늘 도서관 변경 횟수 초과");
    }
}
