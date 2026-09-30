package com.bookshelf.auth;

/** 응답에 refresh 원문이 담긴다 — record 기본 toString이 로그에 원문을 남기지 않게 재정의한다 */
public record AuthTokens(String accessToken, String refreshToken, long expiresIn, boolean newMember) {
    @Override
    public String toString() {
        return "AuthTokens[expiresIn=" + expiresIn + ", newMember=" + newMember + "]";
    }
}
