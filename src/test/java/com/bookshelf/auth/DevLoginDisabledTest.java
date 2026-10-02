package com.bookshelf.auth;

import com.bookshelf.support.DbCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 기본값(꺼짐)에서는 개발 로그인이 존재하지 않는다 — 켜진 채 배포되면 누구나 아무 계정으로 들어온다 */
@SpringBootTest
@AutoConfigureMockMvc
class DevLoginDisabledTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
    }

    @Test
    void 기본값이면_개발_로그인은_404이고_회원을_만들지_않는다() throws Exception {
        mvc.perform(post("/api/auth/dev").contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"dev-juhee\"}"))
                .andExpect(status().isNotFound());

        assertThat(jdbc.sql("SELECT count(*) FROM member").query(Integer.class).single()).isZero();
    }
}
