package com.bookshelf.book.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 백필 대상 ISBN을 읽는 JDBC 어댑터. */
@Repository
public class BookBackfillCandidates {

    private final JdbcClient jdbc;

    public BookBackfillCandidates(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> findPending(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT DISTINCT r.isbn13
                        FROM reading_record r
                        LEFT JOIN book b                ON b.isbn13 = r.isbn13
                        LEFT JOIN book_lookup_failure f ON f.isbn13 = r.isbn13
                        WHERE b.isbn13 IS NULL
                          AND (f.isbn13 IS NULL OR f.next_try_at <= :now)
                        LIMIT :n
                        """)
                .param("now", Timestamp.from(now))
                .param("n", limit)
                .query(String.class)
                .list();
    }
}
