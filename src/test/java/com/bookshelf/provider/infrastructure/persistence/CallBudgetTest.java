package com.bookshelf.provider.infrastructure.persistence;

import com.bookshelf.provider.domain.BudgetPolicy;
import com.bookshelf.provider.domain.CallPurpose;
import com.bookshelf.provider.domain.Provider;
import com.bookshelf.provider.infrastructure.UserQuotaProperties;

import com.bookshelf.support.DbCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.bookshelf.provider.domain.CallPurpose.BACKGROUND;
import static com.bookshelf.provider.domain.CallPurpose.INTERACTIVE;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CallBudgetTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    /** 한국 시각 2026-10-01 23:59 (LA 시각 2026-10-01 07:59) */
    private static final Instant SEOUL_BEFORE_MIDNIGHT = Instant.parse("2026-10-01T14:59:00Z");
    /** 한국 시각 2026-10-02 00:01 (LA 시각 2026-10-01 08:01) */
    private static final Instant SEOUL_AFTER_MIDNIGHT = Instant.parse("2026-10-01T15:01:00Z");

    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
    }

    private CallBudget budgetAt(Instant now) {
        return new CallBudget(jdbc, Clock.fixed(now, SEOUL), new UserQuotaProperties(300));
    }

    private static BudgetPolicy kakao(int dailyLimit, int backgroundCeiling) {
        return new BudgetPolicy(Provider.KAKAO, dailyLimit, backgroundCeiling, SEOUL);
    }

    @Test
    void 상한까지_차감하고_넘으면_거부한다() {
        CallBudget b = budgetAt(SEOUL_BEFORE_MIDNIGHT);
        BudgetPolicy p = kakao(3, 3);
        assertThat(b.tryAcquire(p, INTERACTIVE)).isTrue();
        assertThat(b.tryAcquire(p, INTERACTIVE)).isTrue();
        assertThat(b.tryAcquire(p, INTERACTIVE)).isTrue();
        assertThat(b.tryAcquire(p, INTERACTIVE)).isFalse();
        assertThat(b.usedToday(p)).isEqualTo(3);   // 거부된 요청은 세지 않는다
    }

    @Test
    void 백그라운드는_낮은_상한에서_멈추고_사람의_몫은_남는다() {
        CallBudget b = budgetAt(SEOUL_BEFORE_MIDNIGHT);
        BudgetPolicy p = kakao(4, 2);
        assertThat(b.tryAcquire(p, BACKGROUND)).isTrue();
        assertThat(b.tryAcquire(p, BACKGROUND)).isTrue();
        assertThat(b.tryAcquire(p, BACKGROUND)).isFalse();
        assertThat(b.tryAcquire(p, INTERACTIVE)).isTrue();
        assertThat(b.tryAcquire(p, INTERACTIVE)).isTrue();
        assertThat(b.tryAcquire(p, INTERACTIVE)).isFalse();
    }

    @Test
    void 제공자끼리_카운터가_섞이지_않는다() {
        CallBudget b = budgetAt(SEOUL_BEFORE_MIDNIGHT);
        BudgetPolicy kakao = kakao(1, 1);
        BudgetPolicy library = new BudgetPolicy(Provider.LIBRARY, 1, 1, SEOUL);
        assertThat(b.tryAcquire(kakao, INTERACTIVE)).isTrue();
        assertThat(b.tryAcquire(kakao, INTERACTIVE)).isFalse();
        assertThat(b.tryAcquire(library, INTERACTIVE)).isTrue();   // 카카오가 바닥나도 정보나루는 멀쩡하다
    }

    @Test
    void 그_제공자의_시간대로_자정이_지나면_새로_센다() {
        BudgetPolicy p = kakao(1, 1);
        CallBudget before = budgetAt(SEOUL_BEFORE_MIDNIGHT);
        assertThat(before.tryAcquire(p, INTERACTIVE)).isTrue();
        assertThat(before.tryAcquire(p, INTERACTIVE)).isFalse();

        assertThat(budgetAt(SEOUL_AFTER_MIDNIGHT).tryAcquire(p, INTERACTIVE)).isTrue();
    }

    @Test
    void 초기화_시간대가_다르면_날짜_경계도_다르다() {
        // Google 할당량은 태평양 시간 자정에 초기화된다(가정). 한국 자정을 넘겨도 LA는 아직 같은 날이다
        BudgetPolicy google = new BudgetPolicy(Provider.GOOGLE_BOOKS, 1, 1, LA);
        assertThat(budgetAt(SEOUL_BEFORE_MIDNIGHT).tryAcquire(google, INTERACTIVE)).isTrue();
        assertThat(budgetAt(SEOUL_AFTER_MIDNIGHT).tryAcquire(google, INTERACTIVE)).isFalse();
    }

    @Test
    void 동시에_몰려도_상한을_넘지_않는다() throws Exception {
        CallBudget b = budgetAt(SEOUL_BEFORE_MIDNIGHT);
        BudgetPolicy p = kakao(30, 30);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> b.tryAcquire(p, INTERACTIVE));
        }
        int granted = 0;
        for (Future<Boolean> f : pool.invokeAll(tasks)) {
            if (f.get()) {
                granted++;
            }
        }
        pool.shutdown();

        assertThat(granted).isEqualTo(30);
        assertThat(b.usedToday(p)).isEqualTo(30);
    }
}
