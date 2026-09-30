package com.bookshelf.book.application;

import com.bookshelf.book.domain.SaleSnapshot;
import com.bookshelf.book.domain.SaleStatus;
import com.bookshelf.book.infrastructure.RefreshQueue;
import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
import com.bookshelf.common.Isbn13;
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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(Fixtures.class)
class SaleRefreshTest {

    private static final Isbn13 A = Isbn13.parse("9788936434120");
    private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");

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
    @Autowired SaleService sales;
    @Autowired RefreshWorker worker;
    @Autowired RefreshQueue queue;
    @Autowired SaleSnapshotRepository snapshots;
    @Autowired Fixtures fx;
    @Autowired MutableClock clock;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        KakaoStub.reset();
        clock.set(T0);
        fx.book(A.value(), "소년이 온다");
    }

    private void snapshotAt(Isbn13 isbn, int salePrice, Instant at) {
        snapshots.save(SaleSnapshot.of(isbn, 15000, salePrice, SaleStatus.ON_SALE, at));
    }

    // ── 요청 스레드: 캐시만 본다 ────────────────────────────

    @Test
    void 신선하면_그대로_주고_큐에_넣지_않는다() {
        snapshotAt(A, 13500, T0);
        clock.advance(Duration.ofHours(5));

        SaleView v = sales.current(A);

        assertThat(v.salePrice()).isEqualTo(13500);
        assertThat(v.fetchedAt()).isEqualTo(T0);
        assertThat(queue.size()).isZero();
        assertThat(KakaoStub.calls()).isZero();
    }

    @Test
    void 오래되면_옛_값을_기준_시각과_함께_주고_큐에_넣는다() {
        snapshotAt(A, 13500, T0);
        clock.advance(Duration.ofHours(7));

        SaleView v = sales.current(A);

        assertThat(v.salePrice()).isEqualTo(13500);
        assertThat(v.fetchedAt()).isEqualTo(T0);
        assertThat(queue.size()).isEqualTo(1);
        assertThat(KakaoStub.calls()).isZero();   // 요청 스레드는 카카오를 부르지 않는다
    }

    @Test
    void 없으면_확인_불가이고_큐에_넣는다() {
        SaleView v = sales.current(A);

        assertThat(v.available()).isFalse();
        assertThat(queue.size()).isEqualTo(1);
        assertThat(KakaoStub.calls()).isZero();
    }

    @Test
    void 같은_책을_백_번_열어도_큐는_한_줄이다() {
        for (int i = 0; i < 100; i++) {
            sales.current(A);
        }

        assertThat(queue.size()).isEqualTo(1);
    }

    // ── 백그라운드: 큐를 비운다 ────────────────────────────

    @Test
    void 큐를_비우며_갱신한다() {
        sales.current(A);
        KakaoStub.returns(A.value(), response(1, true, doc(A.value(), "소년이 온다", 15000, 12000, "정상판매")));
        clock.advance(Duration.ofMinutes(1));

        assertThat(worker.runOnce()).isEqualTo(1);

        SaleView v = sales.current(A);
        assertThat(v.salePrice()).isEqualTo(12000);
        assertThat(v.fetchedAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
        assertThat(queue.size()).isZero();
    }

    @Test
    void 카카오가_모르는_책은_판매_정보_없음을_남기고_다시_큐에_들어가지_않는다() {
        sales.current(A);
        KakaoStub.returns(A.value(), response(0, true));

        worker.runOnce();
        SaleView v = sales.current(A);

        assertThat(v.available()).isTrue();
        assertThat(v.status()).isEqualTo(SaleStatus.UNKNOWN);
        assertThat(v.salePrice()).isNull();
        assertThat(queue.size()).isZero();         // 6시간 동안 봇이 열어도 큐에 다시 넣지 않는다
    }

    @Test
    void 그_사이_신선해진_책은_부르지_않고_뺀다() {
        sales.current(A);                          // 없어서 큐에 들어감
        snapshotAt(A, 13500, T0);                  // 그 사이 앱 사용자의 스캔이 갱신했다

        worker.runOnce();

        assertThat(KakaoStub.calls()).isZero();
        assertThat(queue.size()).isZero();
    }

    @Test
    void 카카오가_죽으면_그_회차를_멈추고_큐는_남는다() {
        sales.current(A);
        KakaoStub.failsWith(503);

        worker.runOnce();

        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    void 백그라운드_상한이면_부르지_않고_큐는_남는다() {
        sales.current(A);
        LocalDate today = LocalDate.ofInstant(T0, ZoneId.of("Asia/Seoul"));
        jdbc.sql("INSERT INTO provider_call_counter (provider, day, calls) VALUES ('KAKAO', :d, 18000)")
                .param("d", today).update();

        worker.runOnce();

        assertThat(KakaoStub.calls()).isZero();
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    void 큐에_틀린_ISBN이_섞여도_나머지를_처리하고_그_줄은_버린다() {
        jdbc.sql("INSERT INTO refresh_request (isbn13, requested_at) VALUES ('9780000000001', now() - interval '1 hour')").update();
        sales.current(A);
        KakaoStub.returns(A.value(), response(1, true, doc(A.value(), "소년이 온다", 15000, 12000, "정상판매")));

        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(queue.size()).isZero();
    }

    @Test
    void 한_회차에_정해진_수만_처리한다() {
        // 앞 12자리 9788900000nn + 체크 숫자 — 서로 다른 ISBN 25개
        for (int n = 0; n < 25; n++) {
            String body = "9788900000%02d".formatted(n);
            Isbn13 isbn = Isbn13.parse(body + check(body));
            fx.book(isbn.value(), "책 " + n);
            sales.current(isbn);
        }
        // 어떤 ISBN이든 빈 응답 — 20개가 "판매 정보 없음"으로 처리되고 5개가 남는다
        KakaoStub.server().stubFor(get(urlPathEqualTo("/v3/search/book")).willReturn(okJson(response(0, true))));

        assertThat(worker.runOnce()).isEqualTo(20);   // application.yaml refresh-batch-size
        assertThat(queue.size()).isEqualTo(5);
    }

    private static int check(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = first12.charAt(i) - '0';
            sum += (i % 2 == 0) ? d : d * 3;
        }
        return (10 - sum % 10) % 10;
    }
}
