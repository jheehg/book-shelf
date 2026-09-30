package com.bookshelf.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/**
 * 지금 요청의 회원 id. 토큰의 sub에서만 온다 — 요청 본문으로 주체를 받지 않는다.
 * 백그라운드 작업에는 인증이 없으므로 empty다.
 */
public final class CurrentMember {

    private CurrentMember() {
    }

    public static Optional<Long> id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.of(Long.parseLong(jwt.getToken().getSubject()));
        }
        return Optional.empty();
    }

    public static Long require() {
        return id().orElseThrow(() -> new IllegalStateException("인증되지 않은 요청에서 회원을 찾았다"));
    }
}
