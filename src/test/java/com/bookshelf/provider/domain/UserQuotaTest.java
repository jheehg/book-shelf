package com.bookshelf.provider.domain;

import com.bookshelf.provider.infrastructure.persistence.CallBudget;
import com.bookshelf.provider.infrastructure.UserQuotaProperties;

import com.bookshelf.support.AsMember;
import com.bookshelf.support.DbCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static com.bookshelf.provider.domain.CallPurpose.BACKGROUND;
import static com.bookshelf.provider.domain.CallPurpose.INTERACTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class UserQuotaTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final BudgetPolicy KAKAO = new BudgetPolicy(Provider.KAKAO, 100, 100, SEOUL);

    @Autowired JdbcClient jdbc;

    private CallBudget budget;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        budget = new CallBudget(jdbc, Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"), SEOUL), new UserQuotaProperties(2));
    }

    private int providerCalls() {
        return budget.usedToday(KAKAO);
    }

    @Test
    void 사람의_요청은_사용자별_상한에서_멈춘다() {
        AsMember.run(7, () -> {
            budget.acquire(KAKAO, INTERACTIVE);
            budget.acquire(KAKAO, INTERACTIVE);
            assertThatThrownBy(() -> budget.acquire(KAKAO, INTERACTIVE)).isInstanceOf(UserQuotaExceededException.class);
        });
    }

    @Test
    void 사용자_상한에_걸린_요청은_제공자_예산을_깎지_않는다() {
        AsMember.run(7, () -> {
            budget.acquire(KAKAO, INTERACTIVE);
            budget.acquire(KAKAO, INTERACTIVE);
            assertThatThrownBy(() -> budget.acquire(KAKAO, INTERACTIVE)).isInstanceOf(UserQuotaExceededException.class);
        });

        assertThat(providerCalls()).isEqualTo(2);
    }

    @Test
    void 사용자끼리_섞이지_않는다() {
        AsMember.run(7, () -> {
            budget.acquire(KAKAO, INTERACTIVE);
            budget.acquire(KAKAO, INTERACTIVE);
        });
        AsMember.run(8, () -> budget.acquire(KAKAO, INTERACTIVE));   // 예외 없음
    }

    @Test
    void 백그라운드_호출은_사용자_상한을_보지_않는다() {
        AsMember.run(7, () -> {
            for (int i = 0; i < 5; i++) {
                budget.acquire(KAKAO, BACKGROUND);
            }
        });
        assertThat(jdbc.sql("SELECT count(*) FROM user_call_counter").query(Integer.class).single()).isZero();
    }

    @Test
    void 인증이_없는_요청은_사용자_상한을_보지_않는다() {
        for (int i = 0; i < 5; i++) {
            budget.acquire(KAKAO, INTERACTIVE);   // 백그라운드 작업처럼 SecurityContext가 비어 있다
        }
        assertThat(providerCalls()).isEqualTo(5);
    }

    @Test
    void 제공자_상한은_그대로_지킨다() {
        CallBudget tight = new CallBudget(jdbc, Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"), SEOUL),
                new UserQuotaProperties(100));
        BudgetPolicy one = new BudgetPolicy(Provider.KAKAO, 1, 1, SEOUL);
        AsMember.run(7, () -> {
            tight.acquire(one, INTERACTIVE);
            assertThatThrownBy(() -> tight.acquire(one, INTERACTIVE)).isInstanceOf(BudgetExhaustedException.class);
        });
    }
}
