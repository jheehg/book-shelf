package com.bookshelf.record;

import com.bookshelf.book.infrastructure.BookRepository;
import com.bookshelf.member.domain.Member;
import com.bookshelf.record.domain.Visibility;
import com.bookshelf.record.infrastructure.ReadingRecordRepository;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(Fixtures.class)
class RecordPersistenceTest {

    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired ReadingRecordRepository records;
    @Autowired BookRepository books;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
    }

    @Test
    void 제공자에_없는_책이어도_기록은_저장된다() {
        Member m = fx.member("juhee", false);
        fx.record(m, "9791100000007", Visibility.PRIVATE, 4, "독립출판");   // 카카오 실측: total_count 0

        assertThat(books.count()).isZero();
        assertThat(records.count()).isEqualTo(1);
    }

    @Test
    void 같은_회원이_같은_책을_두_번_기록할_수_없다() {
        Member m = fx.member("juhee", false);
        fx.record(m, "9788936434120", Visibility.PRIVATE, null, null);

        assertThatThrownBy(() -> fx.record(m, "8936434128", Visibility.PUBLIC, null, null))   // 같은 책의 ISBN-10
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 기본_공개범위는_DB에도_PRIVATE로_들어간다() {
        fx.member("juhee", false);
        String v = jdbc.sql("SELECT default_visibility FROM member").query(String.class).single();
        assertThat(v).isEqualTo("PRIVATE");
    }
}
