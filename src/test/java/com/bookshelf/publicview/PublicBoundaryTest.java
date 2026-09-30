package com.bookshelf.publicview;

import com.bookshelf.common.Isbn13;
import com.bookshelf.member.domain.Member;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.stream.Stream;

import static com.bookshelf.record.domain.Visibility.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계 문서 8장 테스트 우선순위 1번. "비공개 기록이 공개 페이지·책 페이지·집계 숫자 어디에도 나오지 않는지. 빠짐없이."
 * 비밀 문자열은 전부 SECRET_으로 시작한다 — 마지막 테스트가 모든 공개 출력에서 이 접두어를 찾는다.
 * Phase 4(W12)에서 이 클래스를 보강한다.
 */
@SpringBootTest
@Import(Fixtures.class)
class PublicBoundaryTest {

    private static final String A = "9788936434120";
    private static final String B = "9788954651134";
    private static final String C = "9791100000007";
    private static final String NO_BOOK = "9780804429573";   // book 행이 없는 ISBN
    private static final String CLOSED_ONLY = "9791161571188"; // 닫힌 서재에만 PUBLIC으로 있는 책
    private static final String GONE_ONLY = "9791168473690";   // 탈퇴한 회원에게만 있는 책

    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired PublicShelfQuery shelves;
    @Autowired PublicBookQuery bookPages;
    @Autowired SitemapQuery sitemap;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        fx.book(A, "소년이 온다");
        fx.book(B, "소년을 위로해줘");
        fx.book(C, "독립출판");
        // 백필은 공개 여부와 상관없이 모든 기록의 책을 채운다 — book 행이 있다는 것 자체가 기록의 흔적이다
        fx.book(CLOSED_ONLY, "닫힌 서재의 책");
        fx.book(GONE_ONLY, "탈퇴한 회원의 책");

        Member open = fx.member("open", true);
        fx.record(open, A, PUBLIC, 5, "공개한 한줄평");
        fx.record(open, B, RATING_ONLY, 4, "SECRET_별점만_공개한_기록의_한줄평");
        fx.record(open, C, PRIVATE, 1, "SECRET_비공개_기록의_한줄평");
        fx.record(open, NO_BOOK, PUBLIC, null, null);

        // RATING_ONLY 기록만 있는 회원 — 책 페이지에 이름조차 나오면 안 된다 (설계 문서 7-3)
        Member ratingOnly = fx.member("secret-rater", true);
        fx.record(ratingOnly, A, RATING_ONLY, 2, "SECRET_별점만_회원의_한줄평");

        Member closed = fx.member("secret-closed", false);
        fx.record(closed, A, PUBLIC, 3, "SECRET_닫힌_서재의_한줄평");
        fx.record(closed, CLOSED_ONLY, PUBLIC, 3, null);

        Member gone = fx.withdraw(fx.member("secret-gone", true));
        fx.record(gone, A, PUBLIC, 2, "SECRET_탈퇴한_회원의_한줄평");
        fx.record(gone, GONE_ONLY, PUBLIC, 2, null);
    }

    @Test
    void 공개_서재에는_PUBLIC과_RATING_ONLY만_나온다() {
        PublicShelf shelf = shelves.find("open").orElseThrow();

        assertThat(shelf.entries()).extracting(PublicEntry::isbn13).containsExactlyInAnyOrder(A, B, NO_BOOK);
    }

    @Test
    void RATING_ONLY_항목은_한줄평_필드가_없는_타입이다() {
        PublicEntry b = entry("open", B);

        assertThat(b).isInstanceOf(RatedEntry.class);
        assertThat(b.rating()).isEqualTo(4);
    }

    @Test
    void PUBLIC_항목은_한줄평을_싣는다() {
        assertThat(entry("open", A)).isInstanceOfSatisfying(ReviewedEntry.class,
                e -> assertThat(e.review()).isEqualTo("공개한 한줄평"));
    }

    @Test
    void 책_정보가_아직_없는_공개_기록도_서재에_나오되_제목이_없다() {
        PublicEntry e = entry("open", NO_BOOK);

        assertThat(e.title()).isNull();       // 템플릿은 이때 책 페이지 링크를 걸지 않는다 (shelf.html)
        assertThat(e).isInstanceOf(RatedEntry.class);
    }

    @Test
    void 집계_숫자는_공개_기록만_센다() {
        assertThat(shelves.find("open").orElseThrow().publicCount()).isEqualTo(3);   // PRIVATE C 제외
    }

    @Test
    void 닫힌_서재_없는_slug_탈퇴한_회원은_모두_없음이다() {
        assertThat(shelves.find("secret-closed")).isEmpty();
        assertThat(shelves.find("nobody")).isEmpty();
        assertThat(shelves.find("secret-gone")).isEmpty();
    }

    /**
     * 탈퇴(withdraw)는 서재 스위치도 끈다 — 그래서 deleted_at 조건만 빠뜨리면 다른 테스트가 잡지 못한다(조건을 하나씩 지워 보다 드러났다).
     * 스위치가 켜진 채 탈퇴한 행(버그·수동 수정)을 직접 만들어 deleted_at 조건 하나만으로도 막히는지 본다.
     */
    @Test
    void 탈퇴한_회원은_서재_스위치가_켜져_있어도_어디에도_나오지_않는다() {
        jdbc.sql("UPDATE member SET shelf_public = TRUE WHERE slug = 'secret-gone'").update();

        assertThat(shelves.find("secret-gone")).isEmpty();
        assertThat(bookPages.find(Isbn13.parse(A)).orElseThrow().reviews())
                .extracting(PublicReview::slug).doesNotContain("secret-gone");
    }

    @Test
    void 책_페이지_한줄평은_열린_서재의_PUBLIC만() {
        PublicBook page = bookPages.find(Isbn13.parse(A)).orElseThrow();

        assertThat(page.reviews()).extracting(PublicReview::review).containsExactly("공개한 한줄평");
        assertThat(page.reviews()).extracting(PublicReview::slug).containsExactly("open");
    }

    @Test
    void RATING_ONLY는_책_페이지가_있되_한줄평은_실리지_않는다() {
        assertThat(bookPages.find(Isbn13.parse(B)).orElseThrow().reviews()).isEmpty();
    }

    /**
     * 책 페이지와 사이트맵 항목이 있다는 것 자체가 "누군가 이 책을 기록했다"를 알린다.
     * 공개 서재와 비교하면 비공개로 둔 책이 무엇인지 드러난다(설계 문서 7-3: PRIVATE는 읽었다는 사실조차 숨긴다).
     */
    @Test
    void 공개된_기록이_없는_책은_책_페이지도_사이트맵_항목도_없다() {
        assertThat(bookPages.find(Isbn13.parse(C))).isEmpty();             // PRIVATE로만 기록됨
        assertThat(bookPages.find(Isbn13.parse(CLOSED_ONLY))).isEmpty();   // 닫힌 서재에만
        assertThat(bookPages.find(Isbn13.parse(GONE_ONLY))).isEmpty();     // 탈퇴한 회원에게만

        assertThat(sitemap.bookIsbns()).containsExactlyInAnyOrder(A, B);
        assertThat(sitemap.publicSlugs()).containsExactlyInAnyOrder("open", "secret-rater");
    }

    @Test
    void 책_행이_없으면_책_페이지도_없다() {
        assertThat(bookPages.find(Isbn13.parse(NO_BOOK))).isEmpty();
    }

    @Test
    void 어떤_공개_출력에도_비밀_문자열이_없다() {
        List<Object> everything = Stream.<Object>concat(
                Stream.of("open", "secret-rater", "secret-closed", "secret-gone", "nobody").map(shelves::find),
                Stream.of(A, B, C, NO_BOOK, CLOSED_ONLY, GONE_ONLY).map(Isbn13::parse).map(bookPages::find)
        ).toList();

        // record의 toString은 모든 필드를 출력한다 — 필드 하나라도 새면 여기 걸린다.
        // 비밀 회원의 slug도 "secret-"으로 시작한다 — 책 페이지에 이름·slug가 새도 걸린다
        String out = bookPagesOnly(everything);
        assertThat(everything.toString()).doesNotContain("SECRET_");
        assertThat(out).doesNotContain("secret-");
        assertThat(sitemap.bookIsbns()).doesNotContain(C, CLOSED_ONLY, GONE_ONLY);
    }

    /** 공개 조회 SQL이 등록 도서관·소장 캐시 테이블을 읽지 않는다. SQL은 모두 publicview 패키지의 소스에 있다 */
    @Test
    void 공개_조회_SQL은_도서관_테이블을_읽지_않는다() throws Exception {
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of("src/main/java/com/bookshelf/publicview"))) {
            for (var file : files.toList()) {
                String src = java.nio.file.Files.readString(file);
                assertThat(src).as(file.toString()).doesNotContain("member_library").doesNotContain("library_holding");
            }
        }
    }

    private static String bookPagesOnly(List<Object> everything) {
        return everything.subList(5, everything.size()).toString();
    }

    private PublicEntry entry(String slug, String isbn) {
        return shelves.find(slug).orElseThrow().entries().stream()
                .filter(e -> e.isbn13().equals(isbn)).findFirst().orElseThrow();
    }
}
