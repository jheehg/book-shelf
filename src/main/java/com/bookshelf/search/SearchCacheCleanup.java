package com.bookshelf.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

/**
 * TTL(24시간)이 지난 캐시는 제공자 장애 때 "지난 결과라도" 보여주는 데 쓰인다(SearchService).
 * 그래서 TTL이 지나자마자 지우지 않고 일주일 둔다. 그보다 오래된 결과는 장애 때도 보여줄 가치가 없다.
 */
@Slf4j
@Component
public class SearchCacheCleanup {

    static final Duration RETENTION = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final Clock clock;

    public SearchCacheCleanup(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public int runOnce() {
        int deleted = jdbc.sql("DELETE FROM search_cache WHERE fetched_at < :cut")
                .param("cut", Timestamp.from(clock.instant().minus(RETENTION)))
                .update();
        log.info("검색 캐시 정리: {}행", deleted);
        return deleted;
    }
}
