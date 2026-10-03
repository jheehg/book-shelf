package com.bookshelf.member.infrastructure.persistence;

import com.bookshelf.record.domain.Visibility;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** 공개 서재의 집계와 일괄 변경을 담당하는 JDBC 어댑터. */
@Repository
public class ShelfPersistence {

    public record Counts(int done, int visible) {
    }

    private final JdbcClient jdbc;

    public ShelfPersistence(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void publishCompletedRecords(long memberId, Visibility visibility, Instant now) {
        jdbc.sql("""
                        UPDATE reading_record SET visibility = :visibility, updated_at = :now
                        WHERE member_id = :memberId AND status = 'DONE'
                        """)
                .param("visibility", visibility.name())
                .param("now", Timestamp.from(now))
                .param("memberId", memberId)
                .update();
    }

    public Counts counts(long memberId) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'DONE') AS done,
                               count(*) FILTER (WHERE visibility IN ('PUBLIC', 'RATING_ONLY')) AS visible
                        FROM reading_record WHERE member_id = :memberId
                        """)
                .param("memberId", memberId)
                .query((rs, rowNum) -> new Counts(rs.getInt("done"), rs.getInt("visible")))
                .single();
    }
}
