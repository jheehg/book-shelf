package com.bookshelf.record;

import com.bookshelf.member.domain.Member;
import com.bookshelf.record.domain.Visibility;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.bookshelf.support.KakaoStub.doc;
import static com.bookshelf.support.KakaoStub.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class RecordApiTest {

    private static final String A = "9788936434120";

    @DynamicPropertySource
    static void kakao(DynamicPropertyRegistry r) {
        r.add("providers.kakao.base-url", KakaoStub::baseUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired JwtEncoder encoder;

    private Member me;
    private Member other;
    private String auth;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        KakaoStub.reset();
        me = fx.member("mine", false);
        other = fx.member("other", false);
        auth = TestJwt.bearer(encoder, me.getId());
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/records").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int count(String where) {
        return jdbc.sql("SELECT count(*) FROM reading_record WHERE " + where).query(Integer.class).single();
    }

    @Test
    void 스캔한_ISBN으로_기록을_만들면_책_정보도_채운다() throws Exception {
        KakaoStub.returns(A, response(1, true, doc(A, "소년이 온다", 15000, 13500, "정상판매")));

        create("{\"isbn\":\"" + A + "\",\"status\":\"READING\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isbn13").value(A))
                .andExpect(jsonPath("$.status").value("READING"))
                .andExpect(jsonPath("$.book.title").value("소년이 온다"));
    }

    @Test
    void 제공자가_죽어도_기록은_만들어지고_책은_비어_있다() throws Exception {
        KakaoStub.failsWith(503);

        create("{\"isbn\":\"" + A + "\",\"status\":\"DONE\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.book").value(nullValue()));
        assertThat(count("isbn13 = '" + A + "'")).isEqualTo(1);
    }

    @Test
    void ISBN10으로_만들어도_13자리로_저장된다() throws Exception {
        KakaoStub.returns(A, response(0, true));

        create("{\"isbn\":\"8936434128\",\"status\":\"DONE\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.isbn13").value(A));
    }

    @Test
    void 새_기록의_공개_범위는_회원_기본값이다() throws Exception {
        KakaoStub.returns(A, response(0, true));

        create("{\"isbn\":\"" + A + "\",\"status\":\"DONE\"}").andExpect(jsonPath("$.visibility").value("PRIVATE"));
    }

    @Test
    void 같은_책을_두_번_기록하면_409다() throws Exception {
        fx.record(me, A, Visibility.PRIVATE, null, null);

        create("{\"isbn\":\"" + A + "\",\"status\":\"DONE\"}").andExpect(status().isConflict());
    }

    @Test
    void 잘못된_ISBN과_잘못된_상태는_400이다() throws Exception {
        create("{\"isbn\":\"9788936434121\",\"status\":\"DONE\"}").andExpect(status().isBadRequest());
        create("{\"isbn\":\"" + A + "\",\"status\":\"FINISHED\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        create("{\"isbn\":\"" + A + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void 요청_본문의_회원_id는_무시하고_토큰의_회원으로_만든다() throws Exception {
        KakaoStub.returns(A, response(0, true));

        create("{\"isbn\":\"" + A + "\",\"status\":\"DONE\",\"memberId\":" + other.getId() + "}").andExpect(status().isCreated());

        assertThat(count("member_id = " + me.getId())).isEqualTo(1);
        assertThat(count("member_id = " + other.getId())).isZero();
    }

    @Test
    void 내_기록만_최신순으로_준다() throws Exception {
        fx.book(A, "소년이 온다");
        fx.record(me, A, Visibility.PRIVATE, 5, "좋았다");
        fx.record(me, "9791100000007", Visibility.PRIVATE, null, null);
        fx.record(other, A, Visibility.PUBLIC, 1, "남의 기록");

        mvc.perform(get("/api/records").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].book.title").value("소년이 온다"))
                .andExpect(jsonPath("$[0].book").value(nullValue()));
    }

    @Test
    void 기록을_고친다() throws Exception {
        long id = fx.record(me, A, Visibility.PRIVATE, null, null).getId();

        mvc.perform(put("/api/records/" + id).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DONE","rating":4,"review":"  남는다 ","visibility":"RATING_ONLY",
                                 "startedAt":"2026-09-01","finishedAt":"2026-09-20"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(4))
                .andExpect(jsonPath("$.review").value("남는다"))
                .andExpect(jsonPath("$.visibility").value("RATING_ONLY"))
                .andExpect(jsonPath("$.finishedAt").value("2026-09-20"));
    }

    @Test
    void 완료일이_시작일보다_앞이면_400이다() throws Exception {
        long id = fx.record(me, A, Visibility.PRIVATE, null, null).getId();

        mvc.perform(put("/api/records/" + id).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"visibility\":\"PRIVATE\",\"startedAt\":\"2026-09-20\",\"finishedAt\":\"2026-09-01\"}"))
                .andExpect(status().isBadRequest());
    }

    /** Review Focus 1: 남의 기록은 조회·수정·삭제 모두 404이고 아무것도 바뀌지 않는다 */
    @Test
    void 남의_기록은_404이고_바뀌지_않는다() throws Exception {
        long theirs = fx.record(other, A, Visibility.PRIVATE, 3, "남의 한줄평").getId();

        mvc.perform(get("/api/records/" + theirs).header("Authorization", auth)).andExpect(status().isNotFound());
        mvc.perform(put("/api/records/" + theirs).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DONE\",\"rating\":1,\"visibility\":\"PUBLIC\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/records/" + theirs).header("Authorization", auth)).andExpect(status().isNotFound());

        assertThat(jdbc.sql("SELECT visibility FROM reading_record WHERE id = :id").param("id", theirs)
                .query(String.class).single()).isEqualTo("PRIVATE");
        assertThat(count("id = " + theirs)).isEqualTo(1);
    }

    @Test
    void 기록을_지운다() throws Exception {
        long id = fx.record(me, A, Visibility.PRIVATE, null, null).getId();

        mvc.perform(delete("/api/records/" + id).header("Authorization", auth)).andExpect(status().isNoContent());

        assertThat(count("id = " + id)).isZero();
    }
}
