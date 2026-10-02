package com.bookshelf.auth;

import com.bookshelf.support.AppleStub;
import com.bookshelf.support.DbCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 로컬 전용 개발 로그인 — Apple 멤버십 없이 앱을 개발하기 위해 (켜졌을 때) */
@SpringBootTest(properties = "bookshelf.dev-login.enabled=true")
@AutoConfigureMockMvc
class DevLoginTest {

    @DynamicPropertySource
    static void apple(DynamicPropertyRegistry r) {
        r.add("apple.auth-base-url", AppleStub::baseUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        AppleStub.reset();
    }

    private ResultActions devLogin(String slug) throws Exception {
        return mvc.perform(post("/api/auth/dev").contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"" + slug + "\"}"));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void 처음이면_회원을_만들고_Apple_로그인과_같은_토큰을_준다() throws Exception {
        JsonNode b = body(devLogin("dev-juhee").andExpect(status().isOk()));

        assertThat(b.get("newMember").asBoolean()).isTrue();
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + b.get("accessToken").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("dev-juhee"));
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + b.get("refreshToken").asString() + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT apple_sub IS NULL FROM member").query(Boolean.class).single()).isTrue();   // Apple 연결 없음
    }

    @Test
    void 같은_slug로_다시_들어오면_같은_회원이다() throws Exception {
        devLogin("dev-juhee");

        assertThat(body(devLogin("dev-juhee")).get("newMember").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM member").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void 규칙에_맞지_않는_slug는_400이다() throws Exception {
        devLogin("Dev Juhee").andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/dev").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 탈퇴한_slug로는_들어오지_못한다() throws Exception {
        devLogin("dev-juhee");
        jdbc.sql("UPDATE member SET deleted_at = now()").update();

        devLogin("dev-juhee").andExpect(status().isUnauthorized());
    }

    /** Apple 연결이 없는 회원은 철회할 것이 없다 — 코드 없이 지우고 Apple을 부르지 않는다 */
    @Test
    void 개발_회원은_Apple_코드_없이_탈퇴한다() throws Exception {
        String access = body(devLogin("dev-juhee")).get("accessToken").asString();

        mvc.perform(delete("/api/me").header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent());

        assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM member").query(Boolean.class).single()).isTrue();
        assertThat(AppleStub.calls("/auth/token")).isZero();
    }
}
