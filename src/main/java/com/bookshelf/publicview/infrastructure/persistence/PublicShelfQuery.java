package com.bookshelf.publicview.infrastructure.persistence;

import com.bookshelf.publicview.application.PublicEntry;
import com.bookshelf.publicview.application.PublicShelf;
import com.bookshelf.publicview.application.RatedEntry;
import com.bookshelf.publicview.application.ReviewedEntry;

import com.bookshelf.record.domain.ReadingStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 공개 서재 조회. 인증된 쪽의 리포지터리·서비스를 재사용하지 않는다 (PublicViewArchitectureTest가 강제).
 *
 * 공개 조건은 두 곳에만 있다:
 *   서재 — shelf_public = TRUE AND deleted_at IS NULL
 *   기록 — visibility IN ('PUBLIC', 'RATING_ONLY')
 * 한줄평은 visibility = 'PUBLIC'일 때만 SQL이 돌려준다. RATING_ONLY의 한줄평은 JVM에 올라오지도 않는다.
 */
@Component
public class PublicShelfQuery {

    private static final int MAX_ENTRIES = 200;

    private record Owner(long id, String slug, String displayName) {
    }

    private final JdbcClient jdbc;

    public PublicShelfQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PublicShelf> find(String slug) {
        return jdbc.sql("""
                        SELECT id, slug, display_name FROM member
                        WHERE slug = :slug AND shelf_public = TRUE AND deleted_at IS NULL
                        """)
                .param("slug", slug)
                .query((rs, n) -> new Owner(rs.getLong("id"), rs.getString("slug"), rs.getString("display_name")))
                .optional()
                .map(this::load);
    }

    private PublicShelf load(Owner owner) {
        int count = jdbc.sql("""
                        SELECT count(*) FROM reading_record
                        WHERE member_id = :id AND visibility IN ('PUBLIC', 'RATING_ONLY')
                        """)
                .param("id", owner.id())
                .query(Integer.class)
                .single();

        List<PublicEntry> entries = jdbc.sql("""
                        SELECT r.isbn13, b.title, b.author, b.cover_url, r.status, r.rating,
                               CASE WHEN r.visibility = 'PUBLIC' THEN r.review END AS review
                        FROM reading_record r
                        LEFT JOIN book b ON b.isbn13 = r.isbn13
                        WHERE r.member_id = :id AND r.visibility IN ('PUBLIC', 'RATING_ONLY')
                        ORDER BY r.created_at DESC, r.id DESC
                        LIMIT :limit
                        """)
                .param("id", owner.id())
                .param("limit", MAX_ENTRIES)
                .query((rs, n) -> {
                    String isbn = rs.getString("isbn13");
                    String title = rs.getString("title");
                    String author = rs.getString("author");
                    String cover = rs.getString("cover_url");
                    ReadingStatus status = ReadingStatus.valueOf(rs.getString("status"));
                    Integer rating = rs.getObject("rating", Integer.class);
                    String review = rs.getString("review");
                    // 타입을 명시한다 — 삼항 연산자는 두 record의 교집합 타입으로 추론돼 List<PublicEntry>에 담기지 않는다
                    PublicEntry entry = review == null
                            ? new RatedEntry(isbn, title, author, cover, status, rating)
                            : new ReviewedEntry(isbn, title, author, cover, status, rating, review);
                    return entry;
                })
                .list();

        return new PublicShelf(owner.slug(), owner.displayName(), count, entries);
    }
}
