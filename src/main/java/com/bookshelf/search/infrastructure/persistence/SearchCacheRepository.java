package com.bookshelf.search.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** 복합 키 한 테이블이라 엔티티 대신 SQL로 둔다 */
@Repository
public class SearchCacheRepository {

    public record Entry(List<String> isbns, int total, Instant fetchedAt) {
    }

    private final JdbcClient jdbc;

    public SearchCacheRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(String key, int page) {
        return jdbc.sql("SELECT isbns, total, fetched_at FROM search_cache WHERE query_key = :k AND page = :p")
                .param("k", key)
                .param("p", page)
                .query((rs, n) -> new Entry(
                        rs.getString("isbns").isEmpty() ? List.of() : Arrays.asList(rs.getString("isbns").split(",")),
                        rs.getInt("total"),
                        rs.getObject("fetched_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    public void upsert(String key, int page, List<String> isbns, int total, Instant fetchedAt) {
        jdbc.sql("""
                INSERT INTO search_cache (query_key, page, isbns, total, fetched_at)
                VALUES (:k, :p, :isbns, :total, :at)
                ON CONFLICT (query_key, page)
                DO UPDATE SET isbns = EXCLUDED.isbns, total = EXCLUDED.total, fetched_at = EXCLUDED.fetched_at
                """)
                .param("k", key)
                .param("p", page)
                .param("isbns", String.join(",", isbns))
                .param("total", total)
                .param("at", Timestamp.from(fetchedAt))
                .update();
    }
}
