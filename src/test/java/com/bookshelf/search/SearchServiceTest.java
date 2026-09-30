package com.bookshelf.search;

import com.bookshelf.book.application.BookView;
import com.bookshelf.book.infrastructure.BookRepository;
import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
import com.bookshelf.common.DomainException;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import com.bookshelf.support.KakaoStub;
import com.bookshelf.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static com.bookshelf.support.KakaoStub.doc;
import static com.bookshelf.support.KakaoStub.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(Fixtures.class)
class SearchServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");
    private static final String A = "9788936434120";
    private static final String B = "9788954651134";

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @DynamicPropertySource
    static void kakao(DynamicPropertyRegistry r) {
        r.add("providers.kakao.base-url", KakaoStub::baseUrl);
    }

    @Autowired JdbcClient jdbc;
    @Autowired SearchService service;
    @Autowired BookRepository books;
    @Autowired SaleSnapshotRepository sales;
    @Autowired Fixtures fx;
    @Autowired MutableClock clock;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        KakaoStub.reset();
        clock.set(T0);
        KakaoStub.returns("소년이", response(2, true,
                doc("8936434128 " + A, "소년이 온다", 15000, 13500, "정상판매"),
                doc(B, "소년을 위로해줘", 16000, 14400, "주문판매")));
    }

    @Test
    void 결과를_순서대로_주고_책과_판매_상태를_로컬에_저장한다() {
        SearchResult r = service.search("소년이", 1);

        assertThat(r.books()).extracting(BookView::isbn13).containsExactly(A, B);
        assertThat(r.total()).isEqualTo(2);
        assertThat(r.isEnd()).isTrue();
        assertThat(books.count()).isEqualTo(2);
        assertThat(sales.findById(A).orElseThrow().getSalePrice()).isEqualTo(13500);
    }

    @Test
    void 표기만_다른_같은_검색어는_제공자에_한_번만_간다() {
        service.search("소년이", 1);
        service.search("  소년이 ", 1);
        service.search("소년이", 1);

        assertThat(KakaoStub.calls()).isEqualTo(1);
    }

    @Test
    void 이미_있는_책의_쪽수를_검색_결과가_지우지_않는다() {
        fx.book(A, "소년이 온다");          // Fixtures는 쪽수 216으로 만든다

        service.search("소년이", 1);

        assertThat(books.findById(A).orElseThrow().getPageCount()).isEqualTo(216);
    }

    @Test
    void TTL이_지나면_다시_묻는다() {
        service.search("소년이", 1);
        clock.advance(Duration.ofHours(25));
        service.search("소년이", 1);

        assertThat(KakaoStub.calls()).isEqualTo(2);
    }

    @Test
    void 제공자가_죽으면_지난_캐시라도_준다() {
        service.search("소년이", 1);
        clock.advance(Duration.ofHours(25));
        KakaoStub.reset();
        KakaoStub.failsWith(503);

        SearchResult r = service.search("소년이", 1);

        assertThat(r.books()).hasSize(2);
        assertThat(r.fetchedAt()).isEqualTo(T0);
    }

    @Test
    void 캐시도_없고_제공자도_죽었으면_잠시_후_다시다() {
        KakaoStub.reset();
        KakaoStub.failsWith(503);

        assertThatThrownBy(() -> service.search("소년이", 1)).isInstanceOf(SearchUnavailableException.class);
    }

    @Test
    void 예산이_바닥나면_잠시_후_다시다() {
        LocalDate today = LocalDate.ofInstant(T0, ZoneId.of("Asia/Seoul"));
        jdbc.sql("INSERT INTO provider_call_counter (provider, day, calls) VALUES ('KAKAO', :d, 27000)")
                .param("d", today).update();

        assertThatThrownBy(() -> service.search("소년이", 1)).isInstanceOf(SearchUnavailableException.class);
        assertThat(KakaoStub.calls()).isZero();
    }

    @Test
    void 페이지는_1부터_10까지다() {
        assertThatThrownBy(() -> service.search("소년이", 0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.search("소년이", 11)).isInstanceOf(DomainException.class);
    }
}
