package com.bookshelf.auth;

import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import com.bookshelf.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static com.bookshelf.record.domain.Visibility.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class SecurityBoundaryTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired JwtEncoder encoder;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        fx.book("9788936434120", "소년이 온다");
        fx.record(fx.member("open", true), "9788936434120", PUBLIC, 5, null);
    }

    /** 보안을 붙였더니 공개 페이지가 막히는 일이 없어야 한다 (Review Focus 4) */
    @ParameterizedTest
    @ValueSource(strings = {"/u/open", "/book/9788936434120", "/sitemap.xml", "/robots.txt", "/css/site.css", "/actuator/health"})
    void 공개_경로는_토큰_없이_열린다(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void API는_토큰이_없으면_401이다() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void 없음_만료_위조가_모두_같은_401_본문이다() throws Exception {
        String none = mvc.perform(get("/api/me")).andReturn().getResponse().getContentAsString();
        String garbage = mvc.perform(get("/api/me").header("Authorization", "Bearer not-a-jwt"))
                .andReturn().getResponse().getContentAsString();
        String forged = mvc.perform(get("/api/me").header("Authorization",
                        "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwiaXNzIjoiYm9vay1zaGVsZiJ9.AAAA"))
                .andReturn().getResponse().getContentAsString();

        assertThat(none).isEqualTo(garbage).isEqualTo(forged).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void 우리가_발급한_토큰이면_API를_통과한다() throws Exception {
        long id = fx.member("token-owner", false).getId();

        mvc.perform(get("/api/me").header("Authorization", TestJwt.bearer(encoder, id))).andExpect(status().isOk());
    }
}
