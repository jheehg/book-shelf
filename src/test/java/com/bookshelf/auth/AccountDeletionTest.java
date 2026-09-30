package com.bookshelf.auth;

import com.bookshelf.support.AppleStub;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class AccountDeletionTest {

    private static final String NONCE = "raw-nonce-1234";
    private static final String SUB = "001234.apple-user";
    private static final String CODE = "fresh-auth-code";
    private static final String A = "9788936434120";

    @DynamicPropertySource
    static void apple(DynamicPropertyRegistry r) {
        r.add("apple.jwks-uri", AppleStub::jwksUri);
        r.add("apple.auth-base-url", AppleStub::baseUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired Fixtures fx;
    @Autowired JwtEncoder encoder;

    private long memberId;
    private String access;
    private String refresh;

    @BeforeEach
    void setUp() throws Exception {
        DbCleaner.clean(jdbc);
        AppleStub.reset();
        JsonNode tokens = login();
        access = "Bearer " + tokens.get("accessToken").asString();
        refresh = tokens.get("refreshToken").asString();
        memberId = jdbc.sql("SELECT id FROM member WHERE apple_sub = :s").param("s", SUB).query(Long.class).single();
        // 공개 서재에 공개 기록 하나 — 탈퇴 뒤 공개 페이지에서 사라지는지 본다
        jdbc.sql("UPDATE member SET slug = 'leaving', shelf_public = TRUE WHERE id = :id").param("id", memberId).update();
        fx.book(A, "소년이 온다");
        jdbc.sql("""
                INSERT INTO reading_record (member_id, isbn13, status, visibility, rating, review, created_at, updated_at)
                VALUES (:m, :isbn, 'DONE', 'PUBLIC', 5, '떠나는 사람의 한줄평', now(), now())
                """).param("m", memberId).param("isbn", A).update();
        jdbc.sql("INSERT INTO user_call_counter (member_id, day, calls) VALUES (:m, current_date, 3)").param("m", memberId).update();
    }

    private JsonNode login() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "identityToken", AppleStub.identityToken(SUB, NONCE), "nonce", NONCE, "displayName", "주희"));
        String res = mvc.perform(post("/api/auth/apple").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private ResultActions deleteAccount(String body) throws Exception {
        return mvc.perform(delete("/api/me").header("Authorization", access)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE member_id = :m").param("m", memberId)
                .query(Integer.class).single();
    }

    @Test
    void 탈퇴하면_기록_토큰_사용량을_지우고_회원_행은_이름과_Apple_식별자를_지운_채_남긴다() throws Exception {
        AppleStub.exchangeReturns(CODE, "apple-refresh-1");
        AppleStub.revokeOk();

        deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isNoContent());

        assertThat(count("reading_record")).isZero();
        assertThat(count("refresh_token")).isZero();
        assertThat(count("user_call_counter")).isZero();
        Map<String, Object> row = jdbc.sql("SELECT slug, display_name, apple_sub, shelf_public, deleted_at FROM member WHERE id = :id")
                .param("id", memberId).query().singleRow();
        assertThat(row.get("slug")).isEqualTo("leaving");
        assertThat(row.get("display_name")).isEqualTo("탈퇴한 회원");
        assertThat(row.get("apple_sub")).isNull();
        assertThat(row.get("shelf_public")).isEqualTo(false);
        assertThat(row.get("deleted_at")).isNotNull();
    }

    @Test
    void Apple에서_받은_refresh_토큰을_철회한다() throws Exception {
        AppleStub.exchangeReturns(CODE, "apple-refresh-1");
        AppleStub.revokeOk();

        deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isNoContent());

        Map<String, String> exchange = AppleStub.formOf("/auth/token");
        assertThat(exchange).containsEntry("grant_type", "authorization_code")
                .containsEntry("code", CODE).containsEntry("client_id", AppleStub.CLIENT_ID).containsKey("client_secret");
        assertThat(AppleStub.formOf("/auth/revoke")).containsEntry("token", "apple-refresh-1")
                .containsEntry("token_type_hint", "refresh_token").containsEntry("client_id", AppleStub.CLIENT_ID);
    }

    @Test
    void 탈퇴한_서재는_404이고_그_slug는_다른_사람이_쓰지_못한다() throws Exception {
        AppleStub.exchangeReturns(CODE, "apple-refresh-1");
        AppleStub.revokeOk();
        deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isNoContent());

        mvc.perform(get("/u/leaving")).andExpect(status().isNotFound());
        mvc.perform(get("/book/" + A)).andExpect(status().isNotFound());   // 공개 기록이 사라져 책 페이지도 없다

        String other = TestJwt.bearer(encoder, fx.member("other", false).getId());
        mvc.perform(patch("/api/me").header("Authorization", other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"leaving\"}")).andExpect(status().isConflict());
    }

    @Test
    void 탈퇴_전에_받은_토큰은_모두_통하지_않는다() throws Exception {
        AppleStub.exchangeReturns(CODE, "apple-refresh-1");
        AppleStub.revokeOk();
        deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isNoContent());

        mvc.perform(get("/api/me").header("Authorization", access)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void 같은_Apple_계정으로_다시_가입하면_새_회원이다() throws Exception {
        AppleStub.exchangeReturns(CODE, "apple-refresh-1");
        AppleStub.revokeOk();
        deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isNoContent());

        login();

        long newId = jdbc.sql("SELECT id FROM member WHERE apple_sub = :s").param("s", SUB).query(Long.class).single();
        assertThat(newId).isNotEqualTo(memberId);
        assertThat(jdbc.sql("SELECT count(*) FROM reading_record WHERE member_id = :m").param("m", newId)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void Apple이_코드를_거부하면_400이고_아무것도_지우지_않는다() throws Exception {
        AppleStub.exchangeRejects(CODE);

        deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isBadRequest());

        assertThat(count("reading_record")).isEqualTo(1);
        assertThat(count("refresh_token")).isEqualTo(1);
        mvc.perform(get("/api/me").header("Authorization", access)).andExpect(status().isOk());
        assertThat(AppleStub.calls("/auth/revoke")).isZero();
    }

    @Test
    void Apple이_죽었으면_503이고_아무것도_지우지_않는다() throws Exception {
        AppleStub.appleDown();

        deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isServiceUnavailable());

        assertThat(count("reading_record")).isEqualTo(1);
        mvc.perform(get("/u/leaving")).andExpect(status().isOk());
    }

    @Test
    void 철회가_실패해도_아무것도_지우지_않는다() throws Exception {
        AppleStub.exchangeReturns(CODE, "apple-refresh-1");
        AppleStub.revokeFails(500);

        deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isServiceUnavailable());

        assertThat(count("reading_record")).isEqualTo(1);
    }

    @Test
    void 코드가_없으면_400이고_Apple을_부르지_않는다() throws Exception {
        deleteAccount("{}").andExpect(status().isBadRequest());
        deleteAccount("{\"authorizationCode\":\"  \"}").andExpect(status().isBadRequest());

        assertThat(AppleStub.calls("/auth/token")).isZero();
        assertThat(count("reading_record")).isEqualTo(1);
    }
}
