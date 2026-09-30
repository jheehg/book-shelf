package com.bookshelf.search;

import com.bookshelf.book.application.BookWriter;
import com.bookshelf.catalog.CatalogBook;
import com.bookshelf.catalog.CatalogPage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class SearchWriter {

    private final BookWriter books;
    private final SearchCacheRepository cache;

    public SearchWriter(BookWriter books, SearchCacheRepository cache) {
        this.books = books;
        this.cache = cache;
    }

    /**
     * 결과의 책과 판매 상태를 저장하고 캐시를 갱신한다. 한 트랜잭션.
     * 카카오 검색은 ISBN 조회와 같은 필드를 주므로 saveLookup을 그대로 쓴다 — 이미 아는 쪽수는 지킨다.
     */
    @Transactional
    public void save(String key, int page, CatalogPage result, Instant now) {
        for (CatalogBook b : result.books()) {
            books.saveLookup(b);
        }
        List<String> isbns = result.books().stream().map(b -> b.isbn().value()).toList();
        cache.upsert(key, page, isbns, result.total(), now);
    }
}
