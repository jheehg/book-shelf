package com.bookshelf.publicview;

import com.bookshelf.book.domain.SaleSnapshot;
import com.bookshelf.book.domain.SaleStatus;
import com.bookshelf.book.infrastructure.RefreshQueue;
import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
import com.bookshelf.common.Isbn13;
import com.bookshelf.member.domain.Member;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import com.bookshelf.support.KakaoStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static com.bookshelf.record.domain.Visibility.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class PublicPagesTest {

    private static final String A = "9788936434120";
    private static final String B = "9788954651134";
    private static final String NO_BOOK = "9780804429573";

    @DynamicPropertySource
    static void kakao(DynamicPropertyRegistry r) {
        r.add("providers.kakao.base-url", KakaoStub::baseUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired SaleSnapshotRepository sales;
    @Autowired RefreshQueue queue;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        KakaoStub.reset();
        KakaoStub.failsWith(503);          // 기본은 "카카오가 죽어 있다" — 공개 페이지는 그래도 떠야 한다
        fx.book(A, "소년이 온다");
        Member open = fx.member("open", true);
        fx.record(open, A, PUBLIC, 5, "<script>alert(1)</script>");
        fx.record(open, NO_BOOK, PUBLIC, null, null);
        fx.member("closed", false);
    }

    private String body(String url) throws Exception {
        return mvc.perform(get(url)).andReturn().getResponse().getContentAsString();
    }

    // ── 공개 서재 ──────────────────────────────────────────

    @Test
    void 공개_서재는_OG_태그가_들어간_완성된_HTML이다() throws Exception {
        mvc.perform(get("/u/open"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("property=\"og:title\" content=\"open의 이름의 서재\"")))
                .andExpect(content().string(containsString("property=\"og:url\" content=\"http://localhost:8080/u/open\"")))
                .andExpect(content().string(containsString("property=\"og:image\" content=\"https://image.example/cover.jpg\"")))
                .andExpect(content().string(containsString("공개한 책 2권")))
                .andExpect(content().string(containsString("소년이 온다")));
    }

    @Test
    void 닫힌_서재와_없는_서재는_같은_404다() throws Exception {
        mvc.perform(get("/u/closed")).andExpect(status().isNotFound());
        mvc.perform(get("/u/nobody")).andExpect(status().isNotFound());

        assertThat(body("/u/closed")).isEqualTo(body("/u/nobody"));
    }

    @Test
    void 한줄평의_HTML은_글자로_보인다() throws Exception {
        mvc.perform(get("/u/open"))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(1)"))));
    }

    @Test
    void 표시_이름의_HTML도_글자로_보인다() throws Exception {
        jdbc.sql("UPDATE member SET display_name = '<b>굵게</b>' WHERE slug = 'open'").update();

        mvc.perform(get("/u/open"))
                .andExpect(content().string(not(containsString("<b>굵게</b>"))))
                .andExpect(content().string(containsString("&lt;b&gt;굵게&lt;/b&gt;")));
    }

    @Test
    void 책_정보가_없는_기록은_ISBN으로_보이고_책_페이지_링크가_없다() throws Exception {
        mvc.perform(get("/u/open"))
                .andExpect(content().string(containsString(NO_BOOK)))
                .andExpect(content().string(not(containsString("href=\"/book/" + NO_BOOK + "\""))))
                .andExpect(content().string(containsString("href=\"/book/" + A + "\"")));
    }

    @Test
    void 서재_페이지는_카카오를_부르지_않는다() throws Exception {
        mvc.perform(get("/u/open")).andExpect(status().isOk());

        assertThat(KakaoStub.calls()).isZero();
    }

    // ── 책 페이지 ──────────────────────────────────────────

    @Test
    void 책_페이지는_판매_상태와_가격과_기준_시각을_보여준다() throws Exception {
        sales.save(SaleSnapshot.of(Isbn13.parse(A), 15000, 13500, SaleStatus.ON_SALE,
                Instant.now().minus(Duration.ofMinutes(190))));

        mvc.perform(get("/book/" + A))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("판매 중")))
                .andExpect(content().string(containsString("13,500원")))
                .andExpect(content().string(containsString("15,000원")))
                .andExpect(content().string(containsString("3시간 전 기준")))
                .andExpect(content().string(containsString("property=\"og:title\" content=\"소년이 온다\"")));
    }

    @Test
    void 판매_기록이_없으면_확인_불가이고_카카오를_부르지_않고_큐에_넣는다() throws Exception {
        mvc.perform(get("/book/" + A))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("판매 상태 확인 불가")))
                .andExpect(content().string(containsString("소년이 온다")));

        assertThat(KakaoStub.calls()).isZero();
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    void 판매_정보_없음은_숫자를_쓰지_않는다() throws Exception {
        sales.save(SaleSnapshot.of(Isbn13.parse(A), null, null, SaleStatus.UNKNOWN, Instant.now()));

        String html = body("/book/" + A);

        assertThat(html).contains("판매 정보 없음");
        assertThat(html).doesNotContain("-1원").doesNotContain("0원");
    }

    @Test
    void 책_페이지는_모든_구매처로_가는_링크를_보여준다() throws Exception {
        String html = body("/book/" + A);

        assertThat(html).contains("https://search.kyobobook.co.kr/search?keyword=" + A);          // 새 책 — ISBN
        assertThat(html).contains("SearchTarget=Used&amp;SearchWord=" + A);                        // 중고 — ISBN
        assertThat(html).contains("https://ridibooks.com/search?q=%EC%86%8C%EB%85%84%EC%9D%B4");   // 전자책 — 제목
        assertThat(html).contains("에서 검색");                                                     // "판다"고 말하지 않는다
    }

    @Test
    void 빌리기는_앱으로_안내한다() throws Exception {
        assertThat(body("/book/" + A)).contains("동네 도서관 대출 여부는 앱에서");
    }

    @Test
    void 책_페이지는_열린_서재의_한줄평을_이스케이프해서_싣는다() throws Exception {
        mvc.perform(get("/book/" + A))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("href=\"/u/open\"")));
    }

    @Test
    void 책_페이지는_출처를_밝힌다() throws Exception {
        assertThat(body("/book/" + A)).contains("도서 정보 제공");
    }

    @Test
    void DB에_없는_ISBN은_카카오를_부르지_않고_404다() throws Exception {
        mvc.perform(get("/book/" + B)).andExpect(status().isNotFound());

        assertThat(KakaoStub.calls()).isZero();
        assertThat(queue.size()).isZero();
    }

    @Test
    void 잘못된_ISBN도_같은_404다() throws Exception {
        mvc.perform(get("/book/12345")).andExpect(status().isNotFound());

        assertThat(body("/book/12345")).isEqualTo(body("/u/nobody"));
    }

    // ── 검색 유입 ──────────────────────────────────────────

    @Test
    void 사이트맵은_열린_서재와_책만_담는다() throws Exception {
        mvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andExpect(content().string(containsString("<loc>http://localhost:8080/u/open</loc>")))
                .andExpect(content().string(not(containsString("/u/closed"))))
                .andExpect(content().string(containsString("<loc>http://localhost:8080/book/" + A + "</loc>")));
    }

    @Test
    void robots는_사이트맵을_가리킨다() throws Exception {
        mvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sitemap: http://localhost:8080/sitemap.xml")));
    }
}
