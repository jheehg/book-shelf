package com.bookshelf.library.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Component
public class MyLibrariesWriter {

    static final int MAX_CHANGES_PER_DAY = 3;

    private final JdbcClient jdbc;

    public MyLibrariesWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> current(long memberId) {
        return jdbc.sql("SELECT lib_code FROM member_library WHERE member_id = :m ORDER BY position")
                .param("m", memberId).query(String.class).list();
    }

    /**
     * 같은 목록이면 아무것도 하지 않는다(세지 않는다). 다르면 횟수를 먼저 올리고(원자적, 상한이면 빈 결과)
     * 목록을 통째로 바꾼다. 한 트랜잭션이라 상한에 걸리면 목록도 그대로다.
     */
    @Transactional
    public void replace(long memberId, List<String> libCodes, LocalDate today) {
        if (current(memberId).equals(libCodes)) {
            return;
        }
        List<Integer> after = jdbc.sql("""
                INSERT INTO member_library_change (member_id, day, changes) VALUES (:m, :day, 1)
                ON CONFLICT (member_id, day) DO UPDATE SET changes = member_library_change.changes + 1
                WHERE member_library_change.changes < :limit
                RETURNING changes
                """)
                .param("m", memberId).param("day", today).param("limit", MAX_CHANGES_PER_DAY)
                .query(Integer.class).list();
        if (after.isEmpty()) {
            throw new LibraryChangeLimitException(memberId);
        }
        jdbc.sql("DELETE FROM member_library WHERE member_id = :m").param("m", memberId).update();
        for (int i = 0; i < libCodes.size(); i++) {
            jdbc.sql("INSERT INTO member_library (member_id, lib_code, position) VALUES (:m, :c, :p)")
                    .param("m", memberId).param("c", libCodes.get(i)).param("p", i + 1).update();
        }
    }
}
