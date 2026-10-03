package com.bookshelf.auth.infrastructure.provider.apple;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("apple")
public record AppleProperties(String clientId, String teamId, String keyId, String privateKey,
                              String authBaseUrl, String jwksUri, Duration connectTimeout, Duration readTimeout) {

    public AppleProperties {
        require(clientId, "APPLE_CLIENT_ID");
        require(teamId, "APPLE_TEAM_ID");
        require(keyId, "APPLE_KEY_ID");
        require(privateKey, "APPLE_PRIVATE_KEY");
    }

    /** 환경변수 한 줄에 담으려고 줄바꿈을 |로 바꿔 넣었다 */
    public String privateKeyPem() {
        return privateKey.replace("|", "\n");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank() || value.startsWith("${")) {
            throw new IllegalStateException(name + "가 없다 — 기본값 없이 환경변수로 주입해야 한다");
        }
    }
}
