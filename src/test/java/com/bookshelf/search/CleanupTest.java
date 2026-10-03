package com.bookshelf.search;

import com.bookshelf.search.infrastructure.persistence.SearchCacheCleanup;

import com.bookshelf.auth.infrastructure.persistence.RefreshTokenStore;
import com.bookshelf.support.DbCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CleanupTest {

    @Autowired JdbcClient jdbc;
    @Autowired SearchCacheCleanup searchCleanup;
    @Autowired RefreshTokenStore tokens;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
    }

    private void cache(String key, Duration age) {
        jdbc.sql("INSERT INTO search_cache (query_key, page, isbns, total, fetched_at) VALUES (:k, 1, '', 0, :at)")
                .param("k", key).param("at", Timestamp.from(Instant.now().minus(age))).update();
    }

    @Test
    void 일주일_넘은_검색_캐시만_지운다() {
        cache("old", Duration.ofDays(8));
        cache("recent", Duration.ofDays(6));

        assertThat(searchCleanup.runOnce()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT query_key FROM search_cache").query(String.class).list()).containsExactly("recent");
    }

    @Test
    void 만료된_refresh_토큰만_지운다() {
        Instant now = Instant.now();
        tokens.issue(1L, now.minus(Duration.ofDays(15)));   // TTL 14일(jwt.refresh-token-ttl) → 이미 만료
        tokens.issue(1L, now);

        assertThat(tokens.purgeExpired(now)).isEqualTo(1);

        assertThat(jdbc.sql("SELECT count(*) FROM refresh_token").query(Integer.class).single()).isEqualTo(1);
    }
}
