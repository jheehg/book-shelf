package com.bookshelf.member;

import com.bookshelf.member.domain.Member;
import com.bookshelf.record.domain.Visibility;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class ShelfApiTest {

    private static final String A = "9788936434120";
    private static final String B = "9788954651134";
    private static final String C = "9791100000007";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired JwtEncoder encoder;

    private Member me;
    private String auth;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        me = fx.member("mine", false);
        auth = TestJwt.bearer(encoder, me.getId());
        fx.book(A, "소년이 온다");
        fx.record(me, A, Visibility.PRIVATE, 5, "다 읽은 책의 한줄평");
        fx.record(me, B, Visibility.PRIVATE, 4, null);
        jdbc.sql("""
                INSERT INTO reading_record (member_id, isbn13, status, visibility, created_at, updated_at)
                VALUES (:m, :isbn, 'WANT', 'PRIVATE', now(), now())
                """).param("m", me.getId()).param("isbn", C).update();
    }

    private ResultActions publish(String mode) throws Exception {
        return mvc.perform(post("/api/me/shelf/publish").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"" + mode + "\"}"));
    }

    private String visibilityOf(String isbn) {
        return jdbc.sql("SELECT visibility FROM reading_record WHERE member_id = :m AND isbn13 = :i")
                .param("m", me.getId()).param("i", isbn).query(String.class).single();
    }

    @Test
    void 서재가_닫혔는지와_공유_주소와_읽은_책_수를_준다() throws Exception {
        mvc.perform(get("/api/me/shelf").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shelfPublic").value(false))
                .andExpect(jsonPath("$.url").value("http://localhost:8080/u/mine"))
                .andExpect(jsonPath("$.doneCount").value(2))
                .andExpect(jsonPath("$.visibleCount").value(0));
    }

    @Test
    void 모두_공개하면_읽음_기록만_바뀌고_서재가_열린다() throws Exception {
        publish("PUBLIC").andExpect(status().isOk())
                .andExpect(jsonPath("$.shelfPublic").value(true))
                .andExpect(jsonPath("$.visibleCount").value(2));

        assertThat(visibilityOf(A)).isEqualTo("PUBLIC");
        assertThat(visibilityOf(B)).isEqualTo("PUBLIC");
        assertThat(visibilityOf(C)).isEqualTo("PRIVATE");
        mvc.perform(get("/api/me").header("Authorization", auth))
                .andExpect(jsonPath("$.defaultVisibility").value("PRIVATE"));
        mvc.perform(get("/u/mine")).andExpect(status().isOk())
                .andExpect(content().string(containsString("다 읽은 책의 한줄평")));
    }

    @Test
    void 별점만_공개하면_한줄평은_공개_서재에_나오지_않는다() throws Exception {
        publish("RATING_ONLY").andExpect(status().isOk());

        assertThat(visibilityOf(A)).isEqualTo("RATING_ONLY");
        mvc.perform(get("/u/mine")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("다 읽은 책의 한줄평"))));
    }

    @Test
    void 비공개나_모르는_방식은_400이고_아무것도_바꾸지_않는다() throws Exception {
        publish("PRIVATE").andExpect(status().isBadRequest());
        publish("EVERYONE").andExpect(status().isBadRequest());
        mvc.perform(post("/api/me/shelf/publish").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(visibilityOf(A)).isEqualTo("PRIVATE");
        mvc.perform(get("/u/mine")).andExpect(status().isNotFound());
    }

    @Test
    void 남의_기록은_바뀌지_않는다() throws Exception {
        Member other = fx.member("other", false);
        fx.record(other, A, Visibility.PRIVATE, 1, null);

        publish("PUBLIC");

        assertThat(jdbc.sql("SELECT visibility FROM reading_record WHERE member_id = :m").param("m", other.getId())
                .query(String.class).single()).isEqualTo("PRIVATE");
    }

    @Test
    void 서재를_닫으면_공개_서재가_404이고_기록의_공개_범위는_남는다() throws Exception {
        publish("PUBLIC");

        mvc.perform(post("/api/me/shelf/close").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shelfPublic").value(false));

        mvc.perform(get("/u/mine")).andExpect(status().isNotFound());
        assertThat(visibilityOf(A)).isEqualTo("PUBLIC");
    }

    @Test
    void 기본_공개_범위를_바꾼다() throws Exception {
        mvc.perform(patch("/api/me").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultVisibility\":\"RATING_ONLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultVisibility").value("RATING_ONLY"))
                .andExpect(jsonPath("$.slug").value("mine"));
    }
}
