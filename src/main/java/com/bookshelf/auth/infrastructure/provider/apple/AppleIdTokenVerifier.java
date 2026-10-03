package com.bookshelf.auth.infrastructure.provider.apple;

import com.bookshelf.auth.application.UnauthorizedException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 앱이 보낸 Apple identity token을 서버가 직접 검증한다 (설계 문서 7-1).
 * 서명(Apple JWKS, RS256) · 발급자 · 대상(우리 앱) · 만료 · nonce. 하나라도 틀리면 같은 401.
 * 네이티브 앱에는 리다이렉트가 없어서 스프링이 대신 해 주던 이 검증을 우리가 한다.
 */
@Component
public class AppleIdTokenVerifier {

    static final String ISSUER = "https://appleid.apple.com";

    private final NimbusJwtDecoder decoder;

    public AppleIdTokenVerifier(AppleProperties props) {
        this.decoder = NimbusJwtDecoder.withJwkSetUri(props.jwksUri()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(props.clientId()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), audience));
    }

    public AppleIdentity verify(String identityToken, String rawNonce) {
        if (identityToken == null || rawNonce == null) {
            throw new UnauthorizedException("Apple 토큰 또는 nonce 없음");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(identityToken);
        } catch (JwtException e) {
            throw new UnauthorizedException("Apple 토큰 검증 실패: " + e.getMessage());
        }
        // 앱은 raw nonce의 SHA-256을 Apple에 보냈다 — 토큰에는 그 해시가 들어 있다
        if (!sha256Hex(rawNonce).equals(jwt.getClaimAsString("nonce"))) {
            throw new UnauthorizedException("Apple 토큰 nonce 불일치");
        }
        return new AppleIdentity(jwt.getSubject());
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
