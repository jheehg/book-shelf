package com.bookshelf.publicview;

import com.bookshelf.common.Isbn13;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 공개 책 페이지. book 행이 없으면 없음이다 — 제공자를 부르지 않는다.
 * 부르면 ISBN을 하나씩 올려가며 요청하는 것만으로 우리 예산이 바닥난다 (설계 문서 7-2).
 * book 행이 있어도 공개된 기록이 없으면 없음이다 (PublicSql.BOOK_IS_LISTED).
 */
@Component
public class PublicBookQuery {

    private static final int MAX_REVIEWS = 50;

    private final JdbcClient jdbc;

    public PublicBookQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PublicBook> find(Isbn13 isbn) {
        return jdbc.sql("""
                        SELECT b.isbn13, b.title, b.author, b.publisher, b.pub_date, b.cover_url, b.page_count
                        FROM book b
                        WHERE b.isbn13 = :isbn
                        """ + " AND " + PublicSql.BOOK_IS_LISTED)   // 텍스트 블록은 줄 끝 공백을 지운다 — 연결 공백은 밖에 둔다
                .param("isbn", isbn.value())
                .query((rs, n) -> new PublicBook(
                        rs.getString("isbn13"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getString("publisher"),
                        rs.getObject("pub_date", LocalDate.class),
                        rs.getString("cover_url"),
                        rs.getObject("page_count", Integer.class),
                        reviews(isbn)))
                .optional();
    }

    /**
     * 서재가 열려 있고(탈퇴 안 함) 기록이 PUBLIC이고 한줄평이 있는 것만.
     * RATING_ONLY는 이름·별점도 싣지 않는다 (설계 문서 7-3) — WHERE가 PUBLIC만 고른다.
     */
    private List<PublicReview> reviews(Isbn13 isbn) {
        return jdbc.sql("""
                        SELECT m.slug, m.display_name, r.rating, r.review
                        FROM reading_record r
                        JOIN member m ON m.id = r.member_id
                        WHERE r.isbn13 = :isbn
                          AND r.visibility = 'PUBLIC'
                          AND r.review IS NOT NULL
                          AND m.shelf_public = TRUE
                          AND m.deleted_at IS NULL
                        ORDER BY r.updated_at DESC, r.id DESC
                        LIMIT :limit
                        """)
                .param("isbn", isbn.value())
                .param("limit", MAX_REVIEWS)
                .query((rs, n) -> new PublicReview(
                        rs.getString("slug"),
                        rs.getString("display_name"),
                        rs.getObject("rating", Integer.class),
                        rs.getString("review")))
                .list();
    }
}
