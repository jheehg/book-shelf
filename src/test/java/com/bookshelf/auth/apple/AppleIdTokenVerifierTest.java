package com.bookshelf.auth.apple;

import com.bookshelf.auth.UnauthorizedException;
import com.bookshelf.support.AppleStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Review Focus 3: 흉내 낸 토큰은 모두 같은 401 */
@SpringBootTest
class AppleIdTokenVerifierTest {

    private static final String NONCE = "raw-nonce-1234";

    @DynamicPropertySource
    static void apple(DynamicPropertyRegistry r) {
        r.add("apple.jwks-uri", AppleStub::jwksUri);
        r.add("apple.auth-base-url", AppleStub::baseUrl);
    }

    @Autowired AppleIdTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        AppleStub.reset();
    }

    @Test
    void 올바른_토큰이면_Apple_사용자_식별자를_준다() {
        AppleIdentity id = verifier.verify(AppleStub.identityToken("001234.abcd", NONCE), NONCE);

        assertThat(id.sub()).isEqualTo("001234.abcd");
    }

    @Test
    void 다른_앱용_토큰은_거부한다() {
        String token = AppleStub.identityToken("001234.abcd", "com.other.app", NONCE, Instant.now().plusSeconds(600));

        assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 만료된_토큰은_거부한다() {
        String token = AppleStub.identityToken("001234.abcd", AppleStub.CLIENT_ID, NONCE, Instant.now().minusSeconds(600));

        assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void Apple_키가_아닌_키로_서명한_토큰은_거부한다() {
        String token = AppleStub.tokenSignedByStranger("001234.abcd", AppleStub.CLIENT_ID, NONCE);

        assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 발급자가_Apple이_아니면_거부한다() {
        String token = AppleStub.tokenFromIssuer("https://evil.example", "001234.abcd", NONCE);

        assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void nonce가_다르면_거부한다() {
        String token = AppleStub.identityToken("001234.abcd", NONCE);

        assertThatThrownBy(() -> verifier.verify(token, "another-nonce")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 토큰_모양이_아니면_거부한다() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt", NONCE)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> verifier.verify(null, NONCE)).isInstanceOf(UnauthorizedException.class);
    }
}
