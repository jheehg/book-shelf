package com.bookshelf.auth.infrastructure.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** access 토큰. payload에는 회원 id뿐이다 — Base64는 암호화가 아니라 누구나 읽는다 */
@Component
public class TokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties props;

    public TokenIssuer(JwtEncoder encoder, JwtProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public String access(long memberId, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(Long.toString(memberId))
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public long accessTtlSeconds() {
        return props.accessTokenTtl().toSeconds();
    }
}
