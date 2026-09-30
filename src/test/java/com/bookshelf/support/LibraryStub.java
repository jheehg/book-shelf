package com.bookshelf.support;

import com.github.tomakehurst.wiremock.WireMockServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** 가짜 정보나루. 스프링 테스트는 @DynamicPropertySource로 providers.library.base-url을 이 주소로 바꾼다 */
public final class LibraryStub {

    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());

    static {
        SERVER.start();
    }

    private LibraryStub() {
    }

    public static String baseUrl() {
        return SERVER.baseUrl();
    }

    public static WireMockServer server() {
        return SERVER;
    }

    public static void reset() {
        SERVER.resetAll();
    }

    public static void holding(String libCode, String isbn13, String body) {
        SERVER.stubFor(get(urlPathEqualTo("/api/bookExist"))
                .withQueryParam("libCode", equalTo(libCode))
                .withQueryParam("isbn13", equalTo(isbn13))
                .willReturn(okJson(body)));
    }

    public static void libraries(int region, int pageNo, String body) {
        SERVER.stubFor(get(urlPathEqualTo("/api/libSrch"))
                .withQueryParam("region", equalTo(String.valueOf(region)))
                .withQueryParam("pageNo", equalTo(String.valueOf(pageNo)))
                .willReturn(okJson(body)));
    }

    public static void failsWith(int status) {
        SERVER.stubFor(any(urlPathMatching("/api/.*")).willReturn(aResponse().withStatus(status)));
    }

    public static String holdingBody(String hasBook, String loanAvailable) {
        return "{\"response\":{\"result\":{\"hasBook\":\"" + hasBook + "\",\"loanAvailable\":\"" + loanAvailable + "\"}}}";
    }

    /** 실측: 오류도 HTTP 200으로 온다 */
    public static String errorBody() {
        return "{\"response\":{\"errCode\":\"isbnMpngErr\",\"error\":\"ISBN에 해당하는 도서가 없습니다.\"}}";
    }

    public static String emptyLibraries() {
        return "{\"response\":{\"pageNo\":1,\"pageSize\":500,\"numFound\":0,\"resultNum\":0,\"libs\":[]}}";
    }

    public static int calls() {
        return SERVER.findAll(anyRequestedFor(urlPathMatching("/api/.*"))).size();
    }

    public static String fixture(String name) {
        try (InputStream in = LibraryStub.class.getResourceAsStream("/library/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture 없음: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
