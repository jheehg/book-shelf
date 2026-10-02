package com.bookshelf.auth;

import com.bookshelf.common.BookshelfProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Set;

/**
 * 로컬 전용 개발 로그인. Apple Developer 멤버십 없이 앱을 개발하려고 둔다 — 무료 계정은 Sign in with Apple을 켤 수 없다.
 * slug만으로 그 회원의 토큰을 주므로, 켜진 채 배포되면 누구나 아무 계정으로 들어온다. 그래서
 * 기본값은 꺼짐(빈 자체가 없어 404)이고, 켜더라도 site-url이 로컬 주소가 아니면 기동을 막는다.
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "bookshelf.dev-login.enabled", havingValue = "true")
public class DevLoginController {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");

    public record DevLoginRequest(String slug) {
    }

    private final AuthService auth;

    public DevLoginController(AuthService auth, BookshelfProperties props) {
        requireLocal(props.siteUrl());
        this.auth = auth;
        log.warn("개발 로그인이 켜져 있다 (POST /api/auth/dev) — 로컬 전용이다. 배포 환경에서는 DEV_LOGIN_ENABLED를 두지 않는다");
    }

    @PostMapping("/api/auth/dev")
    public AuthTokens login(@RequestBody DevLoginRequest req) {
        return auth.loginAsDev(req.slug());
    }

    static void requireLocal(String siteUrl) {
        String host = siteUrl == null ? null : URI.create(siteUrl).getHost();
        if (host == null || !LOCAL_HOSTS.contains(host)) {
            throw new IllegalStateException("개발 로그인은 로컬에서만 켤 수 있다 — SITE_URL=" + siteUrl);
        }
    }
}
