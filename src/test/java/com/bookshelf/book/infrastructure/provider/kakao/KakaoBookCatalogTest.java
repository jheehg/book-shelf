package com.bookshelf.book.infrastructure.provider.kakao;

import com.bookshelf.provider.domain.CallPurpose;
import com.bookshelf.provider.infrastructure.UserQuotaProperties;

import com.bookshelf.book.domain.SaleStatus;
import com.bookshelf.book.application.catalog.CatalogBook;
import com.bookshelf.book.application.catalog.CatalogPage;
import com.bookshelf.common.Isbn13;
import com.bookshelf.provider.domain.BudgetExhaustedException;
import com.bookshelf.provider.infrastructure.persistence.CallBudget;
import com.bookshelf.provider.domain.ProviderUnavailableException;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.KakaoStub;
import com.bookshelf.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static com.bookshelf.provider.domain.CallPurpose.BACKGROUND;
import static com.bookshelf.provider.domain.CallPurpose.INTERACTIVE;
import static com.bookshelf.support.KakaoStub.doc;
import static com.bookshelf.support.KakaoStub.response;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스프링 컨텍스트는 JdbcClient(예산 카운터)를 얻는 데만 쓴다.
 * 클라이언트는 테스트마다 직접 조립한다 — 시계·타임아웃·예산·쿨다운을 테스트가 정해야 하기 때문이다.
 */
@SpringBootTest
class KakaoBookCatalogTest {

    private static final Isbn13 ISBN = Isbn13.parse("9788936434120");
    private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");

    @Autowired JdbcClient jdbc;

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        KakaoStub.reset();
        clock = new MutableClock(T0);
    }

    private KakaoBookCatalog catalog(int dailyLimit, Duration readTimeout) {
        KakaoProperties props = new KakaoProperties("test-key", KakaoStub.baseUrl(), Duration.ofSeconds(1),
                readTimeout, dailyLimit, dailyLimit, ZoneId.of("Asia/Seoul"), Duration.ofSeconds(60));
        RestClient rest = new KakaoRestClientConfig().kakaoRestClient(props, RestClient.builder());
        return new KakaoBookCatalog(rest, new CallBudget(jdbc, clock, new com.bookshelf.provider.infrastructure.UserQuotaProperties(300)), props, clock);
    }

    private KakaoBookCatalog catalog() {
        return catalog(100, Duration.ofSeconds(2));
    }

    @Test
    void 실제_ISBN_응답을_옮긴다() {
        KakaoStub.returns(ISBN.value(), KakaoStub.fixture("isbn-9788936434120.json"));

        CatalogBook b = catalog().lookup(ISBN, INTERACTIVE).orElseThrow();

        assertThat(b.isbn()).isEqualTo(ISBN);
        assertThat(b.title()).isEqualTo("소년이 온다");
        assertThat(b.author()).isEqualTo("한강");
        assertThat(b.publisher()).isEqualTo("창비");
        assertThat(b.pubDate()).isEqualTo(LocalDate.of(2014, 5, 19));
        assertThat(b.listPrice()).isPositive();
        assertThat(b.salePrice()).isPositive();
        assertThat(b.saleStatus()).isEqualTo(SaleStatus.ON_SALE);
        assertThat(b.coverUrl()).startsWith("https://");
        assertThat(b.pageCount()).isNull();          // 카카오에는 쪽수가 없다
    }

    @Test
    void 요청에_인증_헤더와_ISBN_검색_조건을_싣는다() {
        KakaoStub.returns(ISBN.value(), KakaoStub.fixture("isbn-9788936434120.json"));

        catalog().lookup(ISBN, INTERACTIVE);

        KakaoStub.server().verify(getRequestedFor(urlPathEqualTo("/v3/search/book"))
                .withHeader("Authorization", equalTo("KakaoAK test-key"))
                .withQueryParam("target", equalTo("isbn"))
                .withQueryParam("query", equalTo(ISBN.value())));
    }

    @Test
    void 모르는_책이면_empty다() {
        Isbn13 unknown = Isbn13.parse("9791100000007");
        KakaoStub.returns(unknown.value(), KakaoStub.fixture("isbn-9791100000007.json"));

        assertThat(catalog().lookup(unknown, INTERACTIVE)).isEmpty();
    }

    @Test
    void ISBN_조회_결과에_다른_책만_오면_empty다() {
        KakaoStub.returns(ISBN.value(), response(1, true, doc("9791168473690", "다른 책", 1000, 900, "정상판매")));

        assertThat(catalog().lookup(ISBN, INTERACTIVE)).isEmpty();
    }

    @Test
    void 실제_검색_응답의_가격_없음을_null로_옮긴다() {
        KakaoStub.returns("세이노의 가르침", KakaoStub.fixture("search-세이노의_가르침.json"));

        CatalogPage page = catalog().search("세이노의 가르침", 1, INTERACTIVE);

        assertThat(page.books()).isNotEmpty();
        assertThat(page.books()).allSatisfy(b -> {
            assertThat(b.listPrice()).satisfiesAnyOf(p -> assertThat(p).isNull(), p -> assertThat(p).isPositive());
            assertThat(b.salePrice()).satisfiesAnyOf(p -> assertThat(p).isNull(), p -> assertThat(p).isPositive());
        });
    }

    @Test
    void ISBN_필드의_세_가지_모양을_모두_옮긴다() {
        KakaoStub.returns("모양", response(3, true,
                doc("8936434128 9788936434120", "10과 13", 1000, 900, "정상판매"),
                doc("9791168473690", "13만", 1000, 900, "정상판매"),
                doc(" 9791168478688", "앞 공백", 1000, 900, "정상판매")));

        CatalogPage page = catalog().search("모양", 1, INTERACTIVE);

        assertThat(page.books()).extracting(b -> b.isbn().value())
                .containsExactly("9788936434120", "9791168473690", "9791168478688");
    }

    @Test
    void ISBN이_없거나_틀린_항목과_제목이_없는_항목만_건너뛴다() {
        KakaoStub.returns("건너뛰기", response(4, true,
                doc("", "ISBN 없음", 1000, 900, "정상판매"),
                doc("9788936434121", "체크섬 틀림", 1000, 900, "정상판매"),
                doc("9791168473690", "", 1000, 900, "정상판매"),
                doc("9788936434120", "소년이 온다", 15000, 13500, "정상판매")));

        assertThat(catalog().search("건너뛰기", 1, INTERACTIVE).books())
                .extracting(CatalogBook::title).containsExactly("소년이 온다");
    }

    /** 컬럼 길이를 넘는 값이 오면 저장에서 터지고, 그 행이 큐 맨 앞에서 매분 예산을 태운다 */
    @Test
    void 컬럼보다_긴_값은_잘라서_옮기고_너무_긴_표지_주소는_버린다() {
        String longTitle = "가".repeat(600);
        String longCover = "https://search1.kakaocdn.net/" + "a".repeat(1100);
        KakaoStub.returns(ISBN.value(), response(1, true, """
                {"title":"%s","isbn":"%s","authors":["%s","%s"],"publisher":"%s",
                 "datetime":"2014-05-19T00:00:00.000+09:00","price":1000,"sale_price":900,"status":"정상판매",
                 "thumbnail":"%s"}
                """.formatted(longTitle, ISBN.value(), "저".repeat(300), "자".repeat(300), "출".repeat(250), longCover)));

        CatalogBook b = catalog().lookup(ISBN, INTERACTIVE).orElseThrow();

        assertThat(b.title().codePointCount(0, b.title().length())).isEqualTo(500);
        assertThat(b.author().codePointCount(0, b.author().length())).isEqualTo(500);
        assertThat(b.publisher().codePointCount(0, b.publisher().length())).isEqualTo(200);
        assertThat(b.coverUrl()).isNull();
    }

    @Test
    void 가격이_0이나_음수면_null이다() {
        KakaoStub.returns(ISBN.value(), response(1, true, doc(ISBN.value(), "별책부록", 0, -1, "정상판매")));

        CatalogBook b = catalog().lookup(ISBN, INTERACTIVE).orElseThrow();

        assertThat(b.listPrice()).isNull();
        assertThat(b.salePrice()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"정상판매|ON_SALE", "주문판매|ORDER", "|UNKNOWN", "품절|UNKNOWN"})
    void 판매_상태를_옮기고_모르는_값은_판매_정보_없음이다(String kakaoStatus, SaleStatus expected) {
        String status = kakaoStatus == null ? "" : kakaoStatus;
        KakaoStub.returns(ISBN.value(), response(1, true, doc(ISBN.value(), "책", 1000, 900, status)));

        assertThat(catalog().lookup(ISBN, INTERACTIVE).orElseThrow().saleStatus()).isEqualTo(expected);
    }

    @Test
    void 검색_결과_안의_같은_책은_하나만_남긴다() {
        KakaoStub.returns("중복", response(2, true,
                doc("8936434128 9788936434120", "소년이 온다", 15000, 13500, "정상판매"),
                doc("8936434128", "소년이 온다(다른 항목)", 15000, 13500, "정상판매")));

        assertThat(catalog().search("중복", 1, INTERACTIVE).books()).hasSize(1);
    }

    @Test
    void 검색은_10권씩_요청하고_페이지를_넘긴다() {
        KakaoStub.returns("소년", response(0, true));

        catalog().search("소년", 3, INTERACTIVE);

        KakaoStub.server().verify(getRequestedFor(urlPathEqualTo("/v3/search/book"))
                .withQueryParam("size", equalTo("10"))
                .withQueryParam("page", equalTo("3")));
    }

    @Test
    void 검색어의_특수문자를_그대로_전달한다() {
        String query = "C++ & {자바} 100%";
        KakaoStub.returns(query, response(0, true));

        catalog().search(query, 1, INTERACTIVE);

        assertThat(KakaoStub.calls()).isEqualTo(1);   // query 파라미터가 정확히 일치해야 스텁에 걸린다
    }

    @Test
    void 서버_오류는_장애로_번역한다() {
        KakaoStub.failsWith(503);

        assertThatThrownBy(() -> catalog().lookup(ISBN, INTERACTIVE))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void 응답이_늦으면_읽기_타임아웃으로_끊는다() {
        KakaoStub.delays(1500, response(0, true));
        long start = System.nanoTime();

        assertThatThrownBy(() -> catalog(100, Duration.ofMillis(300)).lookup(ISBN, INTERACTIVE))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1200));
    }

    @Test
    void 예산이_없으면_요청을_보내지_않는다() {
        KakaoStub.returns(ISBN.value(), response(0, true));
        KakaoBookCatalog c = catalog(1, Duration.ofSeconds(2));
        c.lookup(ISBN, INTERACTIVE);

        assertThatThrownBy(() -> c.lookup(ISBN, INTERACTIVE)).isInstanceOf(BudgetExhaustedException.class);
        assertThat(KakaoStub.calls()).isEqualTo(1);
    }

    @Test
    void 실패_후_쿨다운_동안_백그라운드_호출은_보내지_않는다() {
        KakaoBookCatalog c = catalog();
        KakaoStub.failsWith(503);
        assertThatThrownBy(() -> c.lookup(ISBN, BACKGROUND)).isInstanceOf(ProviderUnavailableException.class);
        assertThat(KakaoStub.calls()).isEqualTo(1);

        // 쿨다운 중: 백그라운드는 즉시 포기한다 (요청도, 예산 차감도 없다)
        assertThatThrownBy(() -> c.lookup(ISBN, BACKGROUND)).isInstanceOf(ProviderUnavailableException.class);
        assertThat(KakaoStub.calls()).isEqualTo(1);

        // 사람이 기다리는 호출은 한 번 시도한다
        assertThatThrownBy(() -> c.lookup(ISBN, INTERACTIVE)).isInstanceOf(ProviderUnavailableException.class);
        assertThat(KakaoStub.calls()).isEqualTo(2);

        // 쿨다운이 지나면 백그라운드도 다시 보낸다
        clock.advance(Duration.ofSeconds(61));
        KakaoStub.reset();
        KakaoStub.returns(ISBN.value(), response(0, true));
        assertThat(c.lookup(ISBN, BACKGROUND)).isEmpty();
        assertThat(KakaoStub.calls()).isEqualTo(1);
    }
}
