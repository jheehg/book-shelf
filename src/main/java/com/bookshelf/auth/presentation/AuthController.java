package com.bookshelf.auth.presentation;

import com.bookshelf.auth.application.AuthTokens;

import com.bookshelf.auth.application.AuthService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** displayName은 Apple이 처음 로그인 때만 준다. 없으면 기본 이름 */
    public record AppleLoginRequest(String identityToken, String nonce, String displayName) {
    }

    public record RefreshRequest(String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=***]";   // 로그에 원문이 남지 않게
        }
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/apple")
    public AuthTokens apple(@RequestBody AppleLoginRequest req) {
        return auth.loginWithApple(req.identityToken(), req.nonce(), req.displayName());
    }

    @PostMapping("/refresh")
    public AuthTokens refresh(@RequestBody RefreshRequest req) {
        return auth.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
