package com.bookshelf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BookShelfApplicationTests {

    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    @Test
    void Flyway가_테스트_DB에_이력_테이블을_만든다() {
        Integer applied = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                .query(Integer.class).single();
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 시계는_서울_기준이다() {
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }
}
