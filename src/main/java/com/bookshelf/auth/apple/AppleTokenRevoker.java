package com.bookshelf.auth.apple;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;

/**
 * 탈퇴 때 Apple 연결을 끊는다: 앱이 방금 받은 authorizationCode → Apple refresh 토큰 → 철회.
 * 우리는 Apple 토큰을 저장하지 않는다 — 그래서 탈퇴마다 새 코드가 필요하다.
 */
@Slf4j
@Component
public class AppleTokenRevoker {

    /** Apple 응답의 나머지 필드(access_token, id_token 등)는 쓰지 않는다 */
    record TokenResponse(@JsonProperty("refresh_token") String refreshToken,
                         @JsonProperty("access_token") String accessToken) {
    }

    private final RestClient rest;
    private final AppleProperties props;
    private final AppleClientSecret secret;
    private final Clock clock;

    public AppleTokenRevoker(RestClient appleRestClient, AppleProperties props, AppleClientSecret secret, Clock clock) {
        this.rest = appleRestClient;
        this.props = props;
        this.secret = secret;
        this.clock = clock;
    }

    public void revokeWithCode(String authorizationCode) {
        String clientSecret = secret.create(clock.instant());
        TokenResponse tokens = exchange(authorizationCode, clientSecret);
        boolean hasRefresh = tokens.refreshToken() != null && !tokens.refreshToken().isBlank();
        revoke(hasRefresh ? tokens.refreshToken() : tokens.accessToken(),
                hasRefresh ? "refresh_token" : "access_token", clientSecret);
    }

    private TokenResponse exchange(String code, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", props.clientId());
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("grant_type", "authorization_code");
        try {
            TokenResponse r = rest.post().uri("/auth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(TokenResponse.class);
            if (r == null || (r.refreshToken() == null && r.accessToken() == null)) {
                throw new AppleUnavailableException("Apple 토큰 응답에 토큰이 없다", null);
            }
            return r;
        } catch (HttpClientErrorException e) {
            // 응답 본문(error 코드)만 남긴다. 코드와 secret은 로그에 남기지 않는다
            log.info("Apple이 탈퇴 코드를 거부: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new AppleRejectedException("Apple이 코드를 거부했다: " + e.getStatusCode());
        } catch (RestClientException e) {
            throw new AppleUnavailableException("Apple 토큰 교환 실패", e);
        }
    }

    private void revoke(String token, String hint, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", props.clientId());
        form.add("client_secret", clientSecret);
        form.add("token", token);
        form.add("token_type_hint", hint);
        try {
            HttpStatusCode status = rest.post().uri("/auth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().toBodilessEntity().getStatusCode();
            log.info("Apple 토큰 철회: {}", status);
        } catch (RestClientException e) {
            // 교환은 됐는데 철회가 안 됐다 — 연결이 남은 채 회원을 지우지 않는다
            throw new AppleUnavailableException("Apple 토큰 철회 실패", e);
        }
    }
}
