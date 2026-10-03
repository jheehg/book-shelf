package com.bookshelf.book.application;

import com.bookshelf.book.infrastructure.persistence.BookLookupFailureRepository;
import com.bookshelf.book.infrastructure.persistence.BookRepository;
import com.bookshelf.member.domain.Member;
import com.bookshelf.record.domain.Visibility;
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

@SpringBootTest
@Import(Fixtures.class)
class BookBackfillTest {

    private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");
    private static final String A = "9788936434120";
    private static final String B = "9788954651134";
    private static final String UNKNOWN = "9791100000007";

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
    @Autowired BookBackfill backfill;
    @Autowired BookRepository books;
    @Autowired BookLookupFailureRepository failures;
    @Autowired Fixtures fx;
    @Autowired MutableClock clock;

    private Member m;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        KakaoStub.reset();
        clock.set(T0);
        m = fx.member("juhee", false);
    }

    @Test
    void 책_정보가_없는_기록의_책을_채운다() {
        fx.record(m, A, Visibility.PRIVATE, null, null);
        KakaoStub.returns(A, response(1, true, doc(A, "소년이 온다", 15000, 13500, "정상판매")));

        assertThat(backfill.runOnce()).isEqualTo(1);
        assertThat(books.findById(A)).isPresent();
    }

    @Test
    void 이미_책이_있으면_묻지_않는다() {
        fx.book(A, "소년이 온다");
        fx.record(m, A, Visibility.PRIVATE, null, null);

        backfill.runOnce();

        assertThat(KakaoStub.calls()).isZero();
    }

    @Test
    void 모르는_ISBN은_미루고_다음_회차에_묻지_않는다() {
        fx.record(m, UNKNOWN, Visibility.PRIVATE, null, null);
        KakaoStub.returns(UNKNOWN, response(0, true));

        backfill.runOnce();
        backfill.runOnce();

        assertThat(KakaoStub.calls()).isEqualTo(1);
        assertThat(failures.findById(UNKNOWN).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void 미룬_시간이_지나면_다시_묻고_더_오래_미룬다() {
        fx.record(m, UNKNOWN, Visibility.PRIVATE, null, null);
        KakaoStub.returns(UNKNOWN, response(0, true));
        backfill.runOnce();

        clock.advance(Duration.ofMinutes(61));
        backfill.runOnce();

        assertThat(KakaoStub.calls()).isEqualTo(2);
        assertThat(failures.findById(UNKNOWN).orElseThrow().getAttempts()).isEqualTo(2);
    }

    @Test
    void 제공자가_죽으면_그_회차를_멈춘다() {
        fx.record(m, A, Visibility.PRIVATE, null, null);
        fx.record(m, B, Visibility.PRIVATE, null, null);
        fx.record(m, UNKNOWN, Visibility.PRIVATE, null, null);
        KakaoStub.failsWith(503);

        backfill.runOnce();

        assertThat(KakaoStub.calls()).isEqualTo(1);
    }

    /** 체크섬은 DB CHECK가 못 본다. 수동 SQL로 들어온 틀린 ISBN 한 줄이 회차 전체를 멈추면 안 된다 */
    @Test
    void 체크섬이_틀린_ISBN_기록이_있어도_다른_책은_채운다() {
        jdbc.sql("""
                INSERT INTO reading_record (member_id, isbn13, status, visibility, created_at, updated_at)
                VALUES (:m, '9780000000001', 'DONE', 'PRIVATE', now(), now())
                """).param("m", m.getId()).update();
        fx.record(m, A, Visibility.PRIVATE, null, null);
        KakaoStub.returns(A, response(1, true, doc(A, "소년이 온다", 15000, 13500, "정상판매")));

        assertThat(backfill.runOnce()).isEqualTo(1);
        assertThat(books.findById(A)).isPresent();
    }

    @Test
    void 예산이_바닥나면_실패로_적지_않고_멈춘다() {
        fx.record(m, A, Visibility.PRIVATE, null, null);
        LocalDate today = LocalDate.ofInstant(T0, ZoneId.of("Asia/Seoul"));
        jdbc.sql("INSERT INTO provider_call_counter (provider, day, calls) VALUES ('KAKAO', :d, 18000)")
                .param("d", today).update();

        backfill.runOnce();

        assertThat(KakaoStub.calls()).isZero();
        assertThat(failures.count()).isZero();   // 책 탓이 아니다
    }
}
