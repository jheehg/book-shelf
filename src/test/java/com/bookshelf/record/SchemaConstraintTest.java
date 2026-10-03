package com.bookshelf.record;

import com.bookshelf.record.domain.ReadingStatus;

import com.bookshelf.support.DbCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 첫 배포 직후 기록을 넣는 길은 수동 SQL(deploy/README.md)뿐이다. 도메인 코드를 거치지 않으므로
 * 스키마가 마지막 방어선이다 — 10자리 ISBN은 book과 조인되지 않아 영원히 "책 정보 준비 중"이 되고,
 * 소문자 'done'은 공개 서재의 ReadingStatus.valueOf를 깨 500이 된다.
 */
@SpringBootTest
class SchemaConstraintTest {

    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        jdbc.sql("INSERT INTO member (slug, display_name, created_at) VALUES ('juhee', '주희', now())").update();
    }

    @ParameterizedTest
    @CsvSource({
            "8936434128,    DONE, PUBLIC",        // 10자리 ISBN
            "97889364341,   DONE, PUBLIC",        // 자릿수 부족
            "9788936434120, done, PUBLIC",        // 소문자 상태
            "9788936434120, DONE, public",        // 소문자 공개 범위
    })
    void 도메인_규칙에_맞지_않는_기록은_DB가_거부한다(String isbn, String status, String visibility) {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO reading_record (member_id, isbn13, status, visibility, created_at, updated_at)
                        SELECT id, :isbn, :status, :visibility, now(), now() FROM member
                        """)
                .param("isbn", isbn).param("status", status).param("visibility", visibility)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
