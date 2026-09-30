package com.bookshelf.support;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

/** 서비스 테스트에서 "이 회원의 요청"을 흉내 낸다. HTTP를 거치지 않을 때 쓴다 */
public final class AsMember {

    private AsMember() {
    }

    public static void run(long memberId, Runnable body) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject(Long.toString(memberId))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            body.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
