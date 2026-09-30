package com.bookshelf.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * 가짜 Apple: 공개키 목록(JWKS), 토큰 교환, 토큰 철회. JVM 하나에 서버 하나.
 * 스프링 테스트는 @DynamicPropertySource로 apple.jwks-uri, apple.auth-base-url을 바꾼다.
 */
public final class AppleStub {

    public static final String ISSUER = "https://appleid.apple.com";
    public static final String CLIENT_ID = "com.bookshelf.test";   // build.gradle.kts의 APPLE_CLIENT_ID와 같다

    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());
    private static final RSAKey APPLE_KEY = rsa("apple-test-key");
    private static final RSAKey STRANGER_KEY = rsa("apple-test-key");   // 같은 kid, 다른 키 — 위조

    static {
        SERVER.start();
        reset();
    }

    private AppleStub() {
    }

    public static String baseUrl() {
        return SERVER.baseUrl();
    }

    public static String jwksUri() {
        return SERVER.baseUrl() + "/auth/keys";
    }

    public static WireMockServer server() {
        return SERVER;
    }

    /** JWKS 스텁은 항상 다시 건다 — 다른 스텁을 지워도 공개키는 남아야 한다 */
    public static void reset() {
        SERVER.resetAll();
        SERVER.stubFor(get(urlPathEqualTo("/auth/keys"))
                .willReturn(okJson(new JWKSet(APPLE_KEY.toPublicJWK()).toString())));
    }

    public static String identityToken(String sub, String aud, String rawNonce, Instant expiresAt) {
        return sign(APPLE_KEY, ISSUER, sub, aud, rawNonce, expiresAt);
    }

    public static String identityToken(String sub, String rawNonce) {
        return identityToken(sub, CLIENT_ID, rawNonce, Instant.now().plusSeconds(600));
    }

    public static String tokenSignedByStranger(String sub, String aud, String rawNonce) {
        return sign(STRANGER_KEY, ISSUER, sub, aud, rawNonce, Instant.now().plusSeconds(600));
    }

    public static String tokenFromIssuer(String issuer, String sub, String rawNonce) {
        return sign(APPLE_KEY, issuer, sub, CLIENT_ID, rawNonce, Instant.now().plusSeconds(600));
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 이 코드로 교환하면 이 refresh 토큰을 준다 */
    public static void exchangeReturns(String code, String refreshToken) {
        SERVER.stubFor(post(urlPathEqualTo("/auth/token")).withFormParam("code", equalTo(code))
                .willReturn(okJson("{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
                        + "\"refresh_token\":\"" + refreshToken + "\",\"id_token\":\"x\"}")));
    }

    /** 만료됐거나 이미 쓴 코드 — Apple은 400 invalid_grant를 준다 */
    public static void exchangeRejects(String code) {
        SERVER.stubFor(post(urlPathEqualTo("/auth/token")).withFormParam("code", equalTo(code))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_grant\"}")));
    }

    /** 우리 설정이 틀렸을 때(키·팀·클라이언트) Apple이 주는 오류 */
    public static void exchangeFailsWith(String code, String error) {
        SERVER.stubFor(post(urlPathEqualTo("/auth/token")).withFormParam("code", equalTo(code))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"" + error + "\"}")));
    }

    public static void revokeOk() {
        SERVER.stubFor(post(urlPathEqualTo("/auth/revoke")).willReturn(ok()));
    }

    public static void revokeFails(int status) {
        SERVER.stubFor(post(urlPathEqualTo("/auth/revoke")).willReturn(aResponse().withStatus(status)));
    }

    public static void appleDown() {
        SERVER.stubFor(post(urlPathEqualTo("/auth/token")).willReturn(aResponse().withStatus(503)));
        SERVER.stubFor(post(urlPathEqualTo("/auth/revoke")).willReturn(aResponse().withStatus(503)));
    }

    public static int calls(String path) {
        return SERVER.findAll(postRequestedFor(urlPathEqualTo(path))).size();
    }

    /** 그 경로로 온 마지막 요청의 form 본문 */
    public static Map<String, String> formOf(String path) {
        List<LoggedRequest> all = SERVER.findAll(postRequestedFor(urlPathEqualTo(path)));
        if (all.isEmpty()) {
            throw new AssertionError(path + "로 온 요청이 없다");
        }
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : all.getLast().getBodyAsString().split("&")) {
            String[] kv = pair.split("=", 2);
            form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return form;
    }

    private static String sign(RSAKey key, String issuer, String sub, String aud, String rawNonce, Instant expiresAt) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(sub)
                .audience(List.of(aud))
                .issuedAt(expiresAt.minusSeconds(600))
                .expiresAt(expiresAt)
                .claim("nonce", sha256Hex(rawNonce))
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(), claims)).getTokenValue();
    }

    private static RSAKey rsa(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
