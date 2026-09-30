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

    /** 다른 libSrch 스텁에 걸리지 않은 요청의 응답(우선순위가 낮다) */
    public static void librariesDefault(String body) {
        SERVER.stubFor(get(urlPathEqualTo("/api/libSrch")).atPriority(10).willReturn(okJson(body)));
    }

    /** 도서관 n곳짜리 libSrch 응답. 코드는 prefix + 번호 */
    public static String librariesBody(String prefix, int n) {
        StringBuilder libs = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                libs.append(',');
            }
            libs.append("{\"lib\":{\"libCode\":\"").append(prefix).append(i)
                    .append("\",\"libName\":\"").append(prefix).append(" 도서관 ").append(i)
                    .append("\",\"address\":\"주소 ").append(i)
                    .append("\",\"tel\":\"02-000-0000\",\"latitude\":\"37.5\",\"longitude\":\"127.0\",\"homepage\":\"\"}}");
        }
        return "{\"response\":{\"numFound\":" + n + ",\"resultNum\":" + n + ",\"libs\":[" + libs + "]}}";
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
