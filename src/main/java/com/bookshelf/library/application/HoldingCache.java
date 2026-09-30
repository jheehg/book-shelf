package com.bookshelf.library.application;

import com.bookshelf.common.ClockConfig;
import com.bookshelf.common.Isbn13;
import com.bookshelf.library.HoldingState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Component
public class HoldingCache {

    public record Cached(HoldingState state, Instant fetchedAt) {
    }

    private final JdbcClient jdbc;

    public HoldingCache(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Cached> find(String libCode, Isbn13 isbn, Instant now) {
        return jdbc.sql("""
                SELECT state, fetched_at FROM library_holding
                WHERE lib_code = :c AND isbn13 = :i AND expires_at > :now
                """)
                .param("c", libCode).param("i", isbn.value()).param("now", Timestamp.from(now))
                .query((rs, n) -> new Cached(HoldingState.valueOf(rs.getString("state")),
                        rs.getTimestamp("fetched_at").toInstant()))
                .optional();
    }

    /** 정보나루는 조회일 기준 전날 상태를 준다 — 오늘 안에는 다시 물어도 같다. 다음 날 00:00 KST까지 */
    public void save(String libCode, Isbn13 isbn, HoldingState state, Instant now) {
        if (state == HoldingState.UNKNOWN) {
            return;
        }
        Instant expires = LocalDate.ofInstant(now, ClockConfig.SEOUL).plusDays(1).atStartOfDay(ClockConfig.SEOUL).toInstant();
        jdbc.sql("""
                INSERT INTO library_holding (lib_code, isbn13, state, fetched_at, expires_at)
                VALUES (:c, :i, :s, :now, :exp)
                ON CONFLICT (lib_code, isbn13) DO UPDATE SET
                    state = EXCLUDED.state, fetched_at = EXCLUDED.fetched_at, expires_at = EXCLUDED.expires_at
                """)
                .param("c", libCode).param("i", isbn.value()).param("s", state.name())
                .param("now", Timestamp.from(now)).param("exp", Timestamp.from(expires))
                .update();
    }
}
