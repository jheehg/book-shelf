package com.bookshelf.library;

import com.bookshelf.common.ClockConfig;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import com.bookshelf.support.LibraryStub;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class BorrowApiTest {

    private static final String A = "9788936434120";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("providers.library.base-url", LibraryStub::baseUrl);
        r.add("providers.user.daily-limit", () -> 5);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired JwtEncoder encoder;

    private long me;
    private String auth;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        LibraryStub.reset();
        me = fx.member("mine", false).getId();
        auth = TestJwt.bearer(encoder, me);
        for (String code : new String[]{"L1", "L2", "L3", "999999"}) {
            jdbc.sql("INSERT INTO library (lib_code, name, region_code, synced_at) VALUES (:c, :n, 11, now())")
                    .param("c", code).param("n", code + " 도서관").update();
        }
    }

    private void register(String... codes) {
        for (int i = 0; i < codes.length; i++) {
            jdbc.sql("INSERT INTO member_library (member_id, lib_code, position) VALUES (:m, :c, :p)")
                    .param("m", me).param("c", codes[i]).param("p", i + 1).update();
        }
    }

    private org.springframework.test.web.servlet.ResultActions borrow() throws Exception {
        return mvc.perform(get("/api/books/" + A + "/libraries").header("Authorization", auth));
    }

    @Test
    void 등록한_도서관마다_상태를_등록_순서대로_준다() throws Exception {
        register("L2", "L1", "L3");
        LibraryStub.holding("L1", A, LibraryStub.holdingBody("Y", "Y"));
        LibraryStub.holding("L2", A, LibraryStub.holdingBody("Y", "N"));
        LibraryStub.holding("L3", A, LibraryStub.holdingBody("N", "N"));

        borrow().andExpect(status().isOk())
                .andExpect(jsonPath("$[0].libCode").value("L2"))
                .andExpect(jsonPath("$[0].state").value("ON_LOAN"))
                .andExpect(jsonPath("$[1].state").value("AVAILABLE"))
                .andExpect(jsonPath("$[2].state").value("NOT_HELD"))
                .andExpect(jsonPath("$[2].name").value("L3 도서관"));
    }

    @Test
    void 그날_안에는_다시_부르지_않고_캐시는_다음_날_0시_KST까지다() throws Exception {
        register("L1");
        LibraryStub.holding("L1", A, LibraryStub.holdingBody("Y", "Y"));

        borrow().andExpect(status().isOk());
        borrow().andExpect(jsonPath("$[0].state").value("AVAILABLE"));

        assertThat(LibraryStub.calls()).isEqualTo(1);
        Instant expires = jdbc.sql("SELECT expires_at FROM library_holding").query(Timestamp.class).single().toInstant();
        Instant nextMidnight = LocalDate.now(ClockConfig.SEOUL).plusDays(1).atStartOfDay(ClockConfig.SEOUL).toInstant();
        assertThat(expires).isEqualTo(nextMidnight);
    }

    @Test
    void 캐시가_만료되면_다시_부른다() throws Exception {
        register("L1");
        LibraryStub.holding("L1", A, LibraryStub.holdingBody("Y", "Y"));
        borrow().andExpect(status().isOk());

        jdbc.sql("UPDATE library_holding SET expires_at = now() - interval '1 second'").update();
        LibraryStub.holding("L1", A, LibraryStub.holdingBody("Y", "N"));

        borrow().andExpect(jsonPath("$[0].state").value("ON_LOAN"));
        assertThat(LibraryStub.calls()).isEqualTo(2);
    }

    @Test
    void 한_도서관이_실패하면_그_도서관만_확인_불가이고_실패는_캐시하지_않는다() throws Exception {
        register("L1", "L2");
        LibraryStub.holding("L1", A, LibraryStub.errorBody());
        LibraryStub.holding("L2", A, LibraryStub.holdingBody("Y", "Y"));

        borrow().andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("UNKNOWN"))
                .andExpect(jsonPath("$[0].fetchedAt").value(nullValue()))
                .andExpect(jsonPath("$[1].state").value("AVAILABLE"));

        borrow();
        LibraryStub.server().verify(2, getRequestedFor(urlPathEqualTo("/api/bookExist"))
                .withQueryParam("libCode", equalTo("L1")));
    }

    /** Review Focus 5: 요청으로 도서관 코드를 받지 않는다 */
    @Test
    void 요청_파라미터의_도서관_코드는_쓰이지_않는다() throws Exception {
        register("L1");
        LibraryStub.holding("L1", A, LibraryStub.holdingBody("Y", "Y"));

        mvc.perform(get("/api/books/" + A + "/libraries").param("libCode", "999999").param("libCodes", "999999")
                        .header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].libCode").value("L1"));

        LibraryStub.server().verify(0, getRequestedFor(urlPathEqualTo("/api/bookExist"))
                .withQueryParam("libCode", equalTo("999999")));
    }

    @Test
    void 등록한_도서관이_없으면_빈_목록이고_부르지_않는다() throws Exception {
        borrow().andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(LibraryStub.calls()).isZero();
    }

    @Test
    void 사용자_상한에_걸리면_확인_불가이고_부르지_않는다() throws Exception {
        register("L1");
        jdbc.sql("INSERT INTO user_call_counter (member_id, day, calls) VALUES (:m, :d, 5)")
                .param("m", me).param("d", LocalDate.now(ClockConfig.SEOUL)).update();

        borrow().andExpect(status().isOk()).andExpect(jsonPath("$[0].state").value("UNKNOWN"));
        assertThat(LibraryStub.calls()).isZero();
    }

    @Test
    void 잘못된_ISBN은_400이다() throws Exception {
        mvc.perform(get("/api/books/9788936434121/libraries").header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }
}
