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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

    private static final String NONCE = "raw-nonce-1234";
    private static final String SUB = "001234.apple-user";

    @DynamicPropertySource
    static void apple(DynamicPropertyRegistry r) {
        r.add("apple.jwks-uri", AppleStub::jwksUri);
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

    private MvcResult login(String sub, String displayName) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "identityToken", AppleStub.identityToken(sub, NONCE), "nonce", NONCE,
                "displayName", displayName == null ? "" : displayName));
        return mvc.perform(post("/api/auth/apple").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}")).andReturn();
    }

    @Test
    void 처음_로그인하면_회원을_만들고_토큰을_준다() throws Exception {
        MvcResult r = login(SUB, "주희");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("newMember").asBoolean()).isTrue();
        assertThat(b.get("accessToken").asString()).isNotBlank();
        assertThat(b.get("refreshToken").asString()).isNotBlank();
        assertThat(jdbc.sql("SELECT slug FROM member WHERE apple_sub = :s").param("s", SUB).query(String.class).single())
                .matches("reader-[a-z0-9]{6}");
        assertThat(jdbc.sql("SELECT display_name FROM member").query(String.class).single()).isEqualTo("주희");
    }

    @Test
    void 이름을_주지_않으면_기본_이름으로_만든다() throws Exception {
        login(SUB, null);

        assertThat(jdbc.sql("SELECT display_name FROM member").query(String.class).single()).isEqualTo("독자");
    }

    @Test
    void 같은_Apple_계정으로_다시_로그인하면_같은_회원이다() throws Exception {
        login(SUB, "주희");
        MvcResult again = login(SUB, "다른 이름");

        assertThat(body(again).get("newMember").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM member").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT display_name FROM member").query(String.class).single()).isEqualTo("주희");
    }

    @Test
    void 흉내_낸_Apple_토큰은_같은_401이다() throws Exception {
        String forged = json.writeValueAsString(java.util.Map.of(
                "identityToken", AppleStub.tokenSignedByStranger(SUB, AppleStub.CLIENT_ID, NONCE), "nonce", NONCE));
        MvcResult r = mvc.perform(post("/api/auth/apple").contentType(MediaType.APPLICATION_JSON).content(forged)).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getContentAsString())
                .isEqualTo(mvc.perform(get("/api/me")).andReturn().getResponse().getContentAsString());
        assertThat(jdbc.sql("SELECT count(*) FROM member").query(Integer.class).single()).isZero();
    }

    @Test
    void 발급한_access_토큰으로_API를_통과한다() throws Exception {
        String access = body(login(SUB, "주희")).get("accessToken").asString();

        int status = mvc.perform(get("/api/me").header("Authorization", "Bearer " + access))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401);
    }

    @Test
    void refresh하면_새_토큰_쌍을_주고_옛_refresh는_다시_못_쓴다() throws Exception {
        String r1 = body(login(SUB, "주희")).get("refreshToken").asString();

        MvcResult rotated = refresh(r1);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String r2 = body(rotated).get("refreshToken").asString();
        assertThat(r2).isNotEqualTo(r1);

        assertThat(refresh(r1).getResponse().getStatus()).isEqualTo(401);
    }

    /** Review Focus 2: 이미 쓴 토큰이 다시 오면 도난으로 보고 그 회원의 모든 refresh를 폐기한다 */
    @Test
    void 이미_쓴_refresh가_다시_오면_그_회원의_모든_refresh를_폐기한다() throws Exception {
        String phone = body(login(SUB, "주희")).get("refreshToken").asString();
        String tablet = body(login(SUB, "주희")).get("refreshToken").asString();
        String phone2 = body(refresh(phone)).get("refreshToken").asString();

        assertThat(refresh(phone).getResponse().getStatus()).isEqualTo(401);    // 재사용 → 전부 폐기

        assertThat(refresh(phone2).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(tablet).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 만료된_refresh는_401이다() throws Exception {
        String r1 = body(login(SUB, "주희")).get("refreshToken").asString();
        jdbc.sql("UPDATE refresh_token SET expires_at = now() - interval '1 minute'").update();

        assertThat(refresh(r1).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 로그아웃한_refresh는_401이다() throws Exception {
        String r1 = body(login(SUB, "주희")).get("refreshToken").asString();
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + r1 + "\"}")).andExpect(status().isNoContent());

        assertThat(refresh(r1).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void DB에는_refresh_원문이_없다() throws Exception {
        String r1 = body(login(SUB, "주희")).get("refreshToken").asString();

        assertThat(jdbc.sql("SELECT count(*) FROM refresh_token WHERE token_hash = :r").param("r", r1)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM refresh_token WHERE token_hash = :h")
                .param("h", AppleStub.sha256Hex(r1)).query(Integer.class).single()).isEqualTo(1);
    }
}
