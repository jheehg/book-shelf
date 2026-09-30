package com.bookshelf.support;

import com.github.tomakehurst.wiremock.WireMockServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * 가짜 카카오. JVM 하나에 서버 하나를 띄워 모든 통합 테스트가 같이 쓴다.
 * 스프링 테스트는 @DynamicPropertySource로 providers.kakao.base-url을 baseUrl()로 바꾼다.
 */
public final class KakaoStub {

    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());
    private static final String PATH = "/v3/search/book";

    static {
        SERVER.start();
    }

    private KakaoStub() {
    }

    public static String baseUrl() {
        return SERVER.baseUrl();
    }

    /** 이 클래스에 없는 스텁·검증이 필요할 때 */
    public static WireMockServer server() {
        return SERVER;
    }

    public static void reset() {
        SERVER.resetAll();
    }

    /** query 파라미터가 정확히 이 값일 때 */
    public static void returns(String query, String body) {
        SERVER.stubFor(get(urlPathEqualTo(PATH)).withQueryParam("query", equalTo(query))
                .willReturn(okJson(body)));
    }

    public static void failsWith(int status) {
        SERVER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withBody("{\"errorType\":\"InternalError\",\"message\":\"stub\"}")));
    }

    public static void delays(int millis, String body) {
        SERVER.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(body).withFixedDelay(millis)));
    }

    public static int calls() {
        return SERVER.findAll(getRequestedFor(urlPathEqualTo(PATH))).size();
    }

    /** 카카오 documents 항목 하나. isbnField는 카카오 원형 그대로("10자리 13자리", "13자리", " 13자리" …) */
    public static String doc(String isbnField, String title, int price, int salePrice, String status) {
        return """
                {"title":"%s","isbn":"%s","authors":["저자"],"translators":[],"publisher":"출판사",
                 "datetime":"2014-05-19T00:00:00.000+09:00","price":%d,"sale_price":%d,"status":"%s",
                 "thumbnail":"https://search1.kakaocdn.net/thumb/cover.jpg","contents":"","url":"https://search.daum.net/x"}
                """.formatted(title, isbnField, price, salePrice, status);
    }

    public static String response(int total, boolean isEnd, String... docs) {
        return """
                {"meta":{"total_count":%d,"pageable_count":%d,"is_end":%b},"documents":[%s]}
                """.formatted(total, total, isEnd, String.join(",", docs));
    }

    /** src/test/resources/kakao/ 의 실측 원본 */
    public static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/kakao", name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
