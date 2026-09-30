package com.bookshelf.member;

import com.bookshelf.member.domain.Member;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import com.bookshelf.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class MeApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired JwtEncoder encoder;

    private Member me;
    private String auth;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        me = fx.member("reader-abc123", false);
        fx.member("taken", false);
        auth = TestJwt.bearer(encoder, me.getId());
    }

    @Test
    void 내_정보를_준다() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("reader-abc123"))
                .andExpect(jsonPath("$.shelfPublic").value(false))
                .andExpect(jsonPath("$.defaultVisibility").value("PRIVATE"));
    }

    @Test
    void slug와_이름을_바꾼다() throws Exception {
        mvc.perform(patch("/api/me").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"juhee\",\"displayName\":\"주희\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("juhee"))
                .andExpect(jsonPath("$.displayName").value("주희"));
    }

    @Test
    void 이미_있는_slug는_409다() throws Exception {
        mvc.perform(patch("/api/me").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"taken\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void 규칙에_맞지_않는_slug는_400이다() throws Exception {
        mvc.perform(patch("/api/me").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"Juhee Hong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void 탈퇴한_회원의_access_토큰은_401이다() throws Exception {
        fx.withdraw(me);

        mvc.perform(get("/api/me").header("Authorization", auth)).andExpect(status().isUnauthorized());
    }
}
