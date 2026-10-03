package com.bookshelf.auth.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@ConfigurationProperties("jwt")
public record JwtProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {

    /** @ConfigurationProperties는 치환 못 한 ${...}를 문자 그대로 넣는다 — 여기서 기동을 막는다 */
    public JwtProperties {
        if (secret == null || secret.isBlank() || secret.startsWith("${")
                || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET이 없거나 32바이트보다 짧다 — openssl rand -base64 48 로 만든다");
        }
    }
}
