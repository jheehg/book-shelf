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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class MyLibrariesApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired JwtEncoder encoder;

    private long me;
    private String auth;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        me = fx.member("mine", false).getId();
        auth = TestJwt.bearer(encoder, me);
        for (String code : new String[]{"L1", "L2", "L3", "L4"}) {
            jdbc.sql("INSERT INTO library (lib_code, name, region_code, synced_at) VALUES (:c, :n, 11, now())")
                    .param("c", code).param("n", code + " 도서관").update();
        }
    }

    private ResultActions replace(String codes) throws Exception {
        return mvc.perform(put("/api/me/libraries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"libCodes\":[" + codes + "]}"));
    }

    @Test
    void 도서관을_등록하면_보낸_순서대로_준다() throws Exception {
        replace("\"L3\",\"L1\"").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].libCode").value("L3"))
                .andExpect(jsonPath("$[1].libCode").value("L1"));

        mvc.perform(get("/api/me/libraries").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("L3 도서관"));
    }

    @Test
    void 네_곳은_400이다() throws Exception {
        replace("\"L1\",\"L2\",\"L3\",\"L4\"").andExpect(status().isBadRequest());
    }

    @Test
    void 모르는_도서관과_같은_도서관_두_번은_400이다() throws Exception {
        replace("\"L1\",\"NOPE\"").andExpect(status().isBadRequest());
        replace("\"L1\",\"L1\"").andExpect(status().isBadRequest());
        replace("null").andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM member_library").query(Integer.class).single()).isZero();
    }

    /** Review Focus 5: 등록을 바꿔 끼우며 훑지 못하게 */
    @Test
    void 하루_네_번째_변경은_429이고_다음_날엔_된다() throws Exception {
        replace("\"L1\"").andExpect(status().isOk());
        replace("\"L2\"").andExpect(status().isOk());
        replace("\"L3\"").andExpect(status().isOk());
        replace("\"L4\"").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_CHANGES"));

        mvc.perform(get("/api/me/libraries").header("Authorization", auth))
                .andExpect(jsonPath("$[0].libCode").value("L3"));   // 거부된 변경은 반영되지 않았다

        jdbc.sql("UPDATE member_library_change SET day = day - 1").update();   // 하루가 지났다
        replace("\"L4\"").andExpect(status().isOk());
    }

    @Test
    void 같은_목록을_다시_보내면_변경으로_세지_않는다() throws Exception {
        replace("\"L1\",\"L2\"").andExpect(status().isOk());
        for (int i = 0; i < 5; i++) {
            replace("\"L1\",\"L2\"").andExpect(status().isOk());
        }

        assertThat(jdbc.sql("SELECT changes FROM member_library_change").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void 빈_목록이면_모두_해제한다() throws Exception {
        replace("\"L1\"").andExpect(status().isOk());
        replace("").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 남의_등록은_보이지_않는다() throws Exception {
        long other = fx.member("other", false).getId();
        jdbc.sql("INSERT INTO member_library (member_id, lib_code, position) VALUES (:m, 'L4', 1)").param("m", other).update();

        mvc.perform(get("/api/me/libraries").header("Authorization", auth)).andExpect(jsonPath("$.length()").value(0));
    }
}
