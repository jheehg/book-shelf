package com.bookshelf.auth.apple;

import com.bookshelf.auth.infrastructure.provider.apple.AppleProperties;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplePropertiesTest {

    private static AppleProperties with(String clientId, String privateKey) {
        return new AppleProperties(clientId, "TEAM", "KEY", privateKey, "https://appleid.apple.com",
                "https://appleid.apple.com/auth/keys", Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "${APPLE_CLIENT_ID}"})
    void 시크릿이_없거나_치환되지_않았으면_기동이_실패한다(String value) {
        assertThatThrownBy(() -> with(value, "k")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> with("com.bookshelf", value)).isInstanceOf(IllegalStateException.class);
    }

    @org.junit.jupiter.api.Test
    void 개인키의_구분자를_줄바꿈으로_되돌린다() {
        assertThat(with("com.bookshelf", "-----BEGIN PRIVATE KEY-----|abc|-----END PRIVATE KEY-----").privateKeyPem())
                .isEqualTo("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----");
    }
}
