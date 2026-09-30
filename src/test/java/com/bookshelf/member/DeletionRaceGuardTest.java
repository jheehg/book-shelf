package com.bookshelf.member;

import com.bookshelf.auth.UnauthorizedException;
import com.bookshelf.common.Isbn13;
import com.bookshelf.library.application.MyLibrariesWriter;
import com.bookshelf.member.domain.Member;
import com.bookshelf.record.application.RecordWriter;
import com.bookshelf.record.domain.ReadingStatus;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서비스의 active() 검사를 통과한 요청이 탈퇴 트랜잭션과 겹치는 경우를 흉내 낸다 (리뷰 I2).
 * 쓰기 빈은 회원 행을 잠그고 다시 확인하므로, 탈퇴가 먼저 끝났으면 아무것도 남기지 않는다.
 */
@SpringBootTest
@Import(Fixtures.class)
class DeletionRaceGuardTest {

    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired RecordWriter records;
    @Autowired MyLibrariesWriter libraries;

    private Member gone;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        gone = fx.member("leaving", false);
        jdbc.sql("INSERT INTO library (lib_code, name, region_code, synced_at) VALUES ('L1', '도서관', 11, now())").update();
        fx.withdraw(gone);   // 서비스가 active()를 본 직후 탈퇴가 커밋됐다
    }

    @Test
    void 탈퇴가_먼저_끝났으면_기록을_만들지_않는다() {
        assertThatThrownBy(() -> records.create(gone, Isbn13.parse("9788936434120"), ReadingStatus.DONE, Instant.now()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM reading_record").query(Integer.class).single()).isZero();
    }

    @Test
    void 탈퇴가_먼저_끝났으면_도서관을_등록하지_않는다() {
        assertThatThrownBy(() -> libraries.replace(gone.getId(), List.of("L1"), LocalDate.now()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM member_library").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM member_library_change").query(Integer.class).single()).isZero();
    }
}
