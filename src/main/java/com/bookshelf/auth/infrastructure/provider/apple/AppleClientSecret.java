package com.bookshelf.auth.infrastructure.provider.apple;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Apple 토큰 API의 client_secret. Apple이 준 .p8(ES256) 키로 우리가 서명하는 짧은 JWT다.
 * 개인키는 기동 때 한 번 읽는다 — 키가 깨졌으면 탈퇴 요청 때가 아니라 기동 때 알아야 한다.
 */
@Component
public class AppleClientSecret {

    static final String AUDIENCE = "https://appleid.apple.com";
    static final Duration LIFETIME = Duration.ofMinutes(5);   // Apple 상한은 6개월. 요청마다 새로 만드니 짧게

    private final AppleProperties props;
    private final ECPrivateKey key;

    public AppleClientSecret(AppleProperties props) {
        this.props = props;
        this.key = parse(props.privateKeyPem());
    }

    public String create(Instant now) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(props.teamId())
                .subject(props.clientId())
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(props.keyId()).build(), claims);
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("Apple client secret 서명 실패", e);
        }
        return jwt.serialize();
    }

    private static ECPrivateKey parse(String pem) {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            // 키 내용은 메시지에 넣지 않는다
            throw new IllegalStateException("APPLE_PRIVATE_KEY를 EC 개인키(PKCS#8)로 읽지 못했다", e);
        }
    }
}
