package com.bookshelf.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 제공자별 일일 호출 예산.
 *
 * 조회 후 증가(SELECT → 비교 → UPDATE)로 짜면 동시 요청 두 개가 같은 값을 읽고 둘 다 통과한다.
 * 그래서 "상한 미만일 때만 올린다"를 UPDATE 한 문장의 WHERE에 넣는다. 올라갔으면 행이 돌아오고,
 * 상한이라 안 올라갔으면 아무 행도 돌아오지 않는다.
 *
 * @Transactional을 붙이지 않는다. 한 문장이라 자동 커밋으로 충분하고, 호출하는 쪽의 긴 트랜잭션에
 * 끌려 들어가면 카운터 행 락을 오래 쥔다.
 */
@Slf4j
@Component
public class CallBudget {

    private final JdbcClient jdbc;
    private final Clock clock;

    public CallBudget(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean tryAcquire(BudgetPolicy policy, CallPurpose purpose) {
        int limit = policy.limitFor(purpose);
        List<Integer> after = jdbc.sql("""
                INSERT INTO provider_call_counter (provider, day, calls) VALUES (:provider, :day, 1)
                ON CONFLICT (provider, day) DO UPDATE SET calls = provider_call_counter.calls + 1
                WHERE provider_call_counter.calls < :limit
                RETURNING calls
                """)
                .param("provider", policy.provider().name())
                .param("day", today(policy))
                .param("limit", limit)
                .query(Integer.class)
                .list();

        if (after.isEmpty()) {
            log.warn("{} 예산 소진 purpose={} limit={}", policy.provider(), purpose, limit);
            return false;
        }
        int calls = after.getFirst();
        if (calls == policy.dailyLimit() * 8 / 10 || calls == policy.backgroundCeiling()) {
            log.warn("{} 호출 {}회 도달 (상한 {})", policy.provider(), calls, policy.dailyLimit());
        }
        return true;
    }

    public int usedToday(BudgetPolicy policy) {
        return jdbc.sql("SELECT calls FROM provider_call_counter WHERE provider = :provider AND day = :day")
                .param("provider", policy.provider().name())
                .param("day", today(policy))
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    private LocalDate today(BudgetPolicy policy) {
        return LocalDate.ofInstant(clock.instant(), policy.resetZone());
    }
}
