package com.bookshelf.library;

import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import com.bookshelf.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class LibraryApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired JwtEncoder encoder;

    private String auth;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        auth = TestJwt.bearer(encoder, fx.member("mine", false).getId());
        library("111456", "가락몰도서관", "서울특별시 송파구 양재대로 932", 11);
        library("111457", "송파어린이도서관", "서울특별시 송파구 가락로 71", 11);
        library("111458", "100% 작은도서관", "서울특별시 노원구", 11);
        library("211001", "부산시민도서관", "부산광역시 부산진구", 21);
    }

    private void library(String code, String name, String address, int region) {
        jdbc.sql("""
                INSERT INTO library (lib_code, name, address, region_code, synced_at)
                VALUES (:c, :n, :a, :r, now())
                """).param("c", code).param("n", name).param("a", address).param("r", region).update();
    }

    @Test
    void 지역_목록을_준다() throws Exception {
        mvc.perform(get("/api/libraries/regions").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(17))
                .andExpect(jsonPath("$[0].code").value(11))
                .andExpect(jsonPath("$[0].label").value("서울"));
    }

    @Test
    void 지역의_도서관을_이름순으로_준다() throws Exception {
        mvc.perform(get("/api/libraries").param("region", "11").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("100% 작은도서관"))
                .andExpect(jsonPath("$[1].name").value("가락몰도서관"));
    }

    @Test
    void 이름이나_주소로_좁힌다() throws Exception {
        mvc.perform(get("/api/libraries").param("region", "11").param("q", "송파").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/libraries").param("region", "11").param("q", "어린이").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].libCode").value("111457"));
    }

    /** 검색어의 %와 _는 글자 그대로다 — "%"로 모든 도서관을 긁어 가지 못한다 */
    @Test
    void 검색어의_와일드카드는_글자_그대로다() throws Exception {
        mvc.perform(get("/api/libraries").param("region", "11").param("q", "%").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].libCode").value("111458"));
        mvc.perform(get("/api/libraries").param("region", "11").param("q", "_").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 모르는_지역과_빠진_지역은_400이다() throws Exception {
        mvc.perform(get("/api/libraries").param("region", "27").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/libraries").header("Authorization", auth)).andExpect(status().isBadRequest());
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(get("/api/libraries").param("region", "11")).andExpect(status().isUnauthorized());
    }
}
