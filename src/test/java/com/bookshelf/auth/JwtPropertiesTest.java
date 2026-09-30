package com.bookshelf.auth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "${JWT_SECRET}", "too-short-secret"})
    void 비밀키가_없거나_짧으면_기동이_실패한다(String secret) {
        assertThatThrownBy(() -> new JwtProperties(secret, Duration.ofMinutes(30), Duration.ofDays(14)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }
}
