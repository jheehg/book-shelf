package com.bookshelf.auth.application;

/** 인증 실패. 메시지는 로그용이고 응답에는 나가지 않는다 — 응답은 언제나 같은 401 본문이다 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String reasonForLog) {
        super(reasonForLog);
    }
}
