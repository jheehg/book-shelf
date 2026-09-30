package com.bookshelf.support;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 테스트에서 앱이 보내는 Authorization 헤더를 만든다. 실제 발급기와 같은 인코더를 쓴다 */
public final class TestJwt {

    private TestJwt() {
    }

    public static String bearer(JwtEncoder encoder, long memberId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("book-shelf")
                .subject(Long.toString(memberId))
                .issuedAt(now)
                .expiresAt(now.plus(30, ChronoUnit.MINUTES))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return "Bearer " + token;
    }
}
