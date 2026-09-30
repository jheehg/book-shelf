package com.bookshelf.book.infrastructure;

import com.bookshelf.common.Isbn13;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

/**
 * 판매 상태 갱신 요청. ISBN당 한 줄 — 같은 책을 백 번 요청해도 한 줄이다.
 * 공개 페이지(요청 스레드)가 넣고, RefreshWorker(백그라운드)가 뺀다.
 */
@Repository
public class RefreshQueue {

    private final JdbcClient jdbc;
    private final Clock clock;

    public RefreshQueue(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void request(Isbn13 isbn) {
        jdbc.sql("INSERT INTO refresh_request (isbn13, requested_at) VALUES (:isbn, :at) ON CONFLICT (isbn13) DO NOTHING")
                .param("isbn", isbn.value())
                .param("at", Timestamp.from(clock.instant()))
                .update();
    }

    public List<Isbn13> take(int limit) {
        return jdbc.sql("SELECT isbn13 FROM refresh_request ORDER BY requested_at, isbn13 LIMIT :n")
                .param("n", limit)
                .query(String.class)
                .list()
                .stream()
                .map(Isbn13::parse)
                .toList();
    }

    public void done(Isbn13 isbn) {
        jdbc.sql("DELETE FROM refresh_request WHERE isbn13 = :isbn").param("isbn", isbn.value()).update();
    }

    public int size() {
        return jdbc.sql("SELECT count(*) FROM refresh_request").query(Integer.class).single();
    }
}
