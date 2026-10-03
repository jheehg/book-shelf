package com.bookshelf.auth.apple;

import com.bookshelf.auth.infrastructure.provider.apple.AppleClientSecret;
import com.bookshelf.auth.infrastructure.provider.apple.AppleProperties;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AppleClientSecretTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private static AppleProperties props() throws Exception {
        String pem = Files.readString(Path.of("src/test/resources/apple/test-client-key.p8")).replace("\n", "|");
        return new AppleProperties("com.bookshelf.test", "TESTTEAM00", "TESTKEY000", pem,
                "https://appleid.apple.com", "https://appleid.apple.com/auth/keys",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    @Test
    void 우리_키로_ES256_서명하고_Apple이_요구하는_클레임을_담는다() throws Exception {
        SignedJWT jwt = SignedJWT.parse(new AppleClientSecret(props()).create(NOW));

        // JDK만으로 읽는다(Nimbus의 PEM 파서는 BouncyCastle이 필요하다)
        String b64 = Files.readString(Path.of("src/test/resources/apple/test-client-key.pub.pem"))
                .replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        ECPublicKey pub = (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        assertThat(jwt.verify(new ECDSAVerifier(pub))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("TESTKEY000");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("TESTTEAM00");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("com.bookshelf.test");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("https://appleid.apple.com");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }
}
