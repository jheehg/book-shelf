package com.bookshelf.book;

import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import com.bookshelf.support.KakaoStub;
import com.bookshelf.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.bookshelf.support.KakaoStub.doc;
import static com.bookshelf.support.KakaoStub.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class BookApiTest {

    private static final String A = "9788936434120";

    @DynamicPropertySource
    static void kakao(DynamicPropertyRegistry r) {
        r.add("providers.kakao.base-url", KakaoStub::baseUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired JwtEncoder encoder;

    private String auth;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        KakaoStub.reset();
        auth = TestJwt.bearer(encoder, fx.member("mine", false).getId());
    }

    @Test
    void 아는_책은_제공자를_부르지_않고_준다() throws Exception {
        fx.book(A, "소년이 온다");

        mvc.perform(get("/api/books/" + A).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.book.title").value("소년이 온다"))
                .andExpect(jsonPath("$.sale.fetchedAt").value(nullValue()));   // 판매 상태는 아직 모른다 — 갱신 큐에 들어간다
        assertThat(KakaoStub.calls()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM refresh_request").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void 모르는_책은_제공자에서_가져온다() throws Exception {
        KakaoStub.returns(A, response(1, true, doc(A, "소년이 온다", 15000, 13500, "정상판매")));

        mvc.perform(get("/api/books/" + A).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.book.title").value("소년이 온다"))
                .andExpect(jsonPath("$.sale.status").value("ON_SALE"));
    }

    @Test
    void 제공자도_모르는_책은_404다() throws Exception {
        KakaoStub.returns(A, response(0, true));

        mvc.perform(get("/api/books/" + A).header("Authorization", auth)).andExpect(status().isNotFound());
    }

    @Test
    void 제공자가_죽었으면_503이다() throws Exception {
        KakaoStub.failsWith(503);

        mvc.perform(get("/api/books/" + A).header("Authorization", auth))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRY_LATER"));
    }

    @Test
    void 잘못된_ISBN은_400이다() throws Exception {
        mvc.perform(get("/api/books/9788936434121").header("Authorization", auth)).andExpect(status().isBadRequest());
    }

    @Test
    void 서점_링크를_칸별로_준다() throws Exception {
        fx.book(A, "소년이 온다");

        mvc.perform(get("/api/books/" + A + "/links").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.NEW[0].url").value(containsString(A)))
                .andExpect(jsonPath("$.USED").isArray())
                .andExpect(jsonPath("$.EBOOK[0].url").value(containsString("%EC%86%8C%EB%85%84")));
        assertThat(KakaoStub.calls()).isZero();
    }

    @Test
    void 모르는_책의_링크는_404다() throws Exception {
        mvc.perform(get("/api/books/" + A + "/links").header("Authorization", auth)).andExpect(status().isNotFound());
        assertThat(KakaoStub.calls()).isZero();   // 링크 때문에 제공자를 부르지 않는다
    }
}
