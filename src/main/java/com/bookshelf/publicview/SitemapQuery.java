package com.bookshelf.publicview;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/** 사이트맵도 공개 조회다. 서재 조건은 PublicShelfQuery와 같다 */
@Component
public class SitemapQuery {

    /** 사이트맵 한 파일의 상한은 50,000 URL. 넘으면 사이트맵 인덱스로 나눈다(그때 가서) */
    private static final int MAX_SHELVES = 10_000;
    private static final int MAX_BOOKS = 40_000;

    private final JdbcClient jdbc;

    public SitemapQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> publicSlugs() {
        return jdbc.sql("""
                        SELECT slug FROM member
                        WHERE shelf_public = TRUE AND deleted_at IS NULL
                        ORDER BY id LIMIT :n
                        """)
                .param("n", MAX_SHELVES)
                .query(String.class)
                .list();
    }

    public List<String> bookIsbns() {
        return jdbc.sql("SELECT isbn13 FROM book ORDER BY isbn13 LIMIT :n")
                .param("n", MAX_BOOKS)
                .query(String.class)
                .list();
    }
}
