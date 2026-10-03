package com.bookshelf.search;

import com.bookshelf.search.domain.QueryNormalizer;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class SearchApiTest {

    private static final String A = "9788936434120";
    private static final String Q = "소년이 온다";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("providers.kakao.base-url", KakaoStub::baseUrl);
        r.add("providers.user.daily-limit", () -> 2);
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

    private int userCalls() {
        return jdbc.sql("SELECT coalesce(sum(calls), 0) FROM user_call_counter").query(Integer.class).single();
    }

    @Test
    void 검색_결과에_새_책_판매_상태가_같이_온다() throws Exception {
        KakaoStub.returns(Q, response(1, true, doc(A, "소년이 온다", 15000, 13500, "정상판매")));

        mvc.perform(get("/api/search").param("q", Q).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].book.isbn13").value(A))
                .andExpect(jsonPath("$.items[0].sale.status").value("ON_SALE"))
                .andExpect(jsonPath("$.isEnd").value(true));
    }

    @Test
    void 캐시에_걸린_검색은_사용자_상한을_깎지_않는다() throws Exception {
        KakaoStub.returns(Q, response(1, true, doc(A, "소년이 온다", 15000, 13500, "정상판매")));

        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/search").param("q", Q).header("Authorization", auth)).andExpect(status().isOk());
        }

        assertThat(KakaoStub.calls()).isEqualTo(1);
        assertThat(userCalls()).isEqualTo(1);
    }

    @Test
    void 사용자_상한을_넘으면_429다() throws Exception {
        // 검색어는 2자 이상이다(QueryNormalizer.MIN). 서로 다른 검색어라 캐시에 걸리지 않는다
        KakaoStub.returns("가나", response(0, true));
        KakaoStub.returns("나다", response(0, true));
        KakaoStub.returns("다라", response(0, true));

        mvc.perform(get("/api/search").param("q", "가나").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(get("/api/search").param("q", "나다").header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(get("/api/search").param("q", "다라").header("Authorization", auth))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    @Test
    void 빈_검색어와_범위_밖_페이지는_400이다() throws Exception {
        mvc.perform(get("/api/search").param("q", "  ").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/search").param("q", Q).param("page", "11").header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/search").param("q", Q).param("page", "abc").header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 제공자가_죽었고_캐시도_없으면_503이다() throws Exception {
        KakaoStub.failsWith(503);

        mvc.perform(get("/api/search").param("q", Q).header("Authorization", auth))
                .andExpect(status().isServiceUnavailable());
    }
}
