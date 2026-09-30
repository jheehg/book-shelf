package com.bookshelf.search;

import com.bookshelf.book.application.BookView;
import com.bookshelf.book.domain.Book;
import com.bookshelf.book.infrastructure.BookRepository;
import com.bookshelf.catalog.BookCatalog;
import com.bookshelf.catalog.CatalogPage;
import com.bookshelf.common.BookshelfProperties;
import com.bookshelf.common.DomainException;
import com.bookshelf.provider.CallPurpose;
import com.bookshelf.provider.ProviderUnavailableException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 3층 방어의 2층. @Transactional 없음 — 외부 호출이 있다 */
@Service
public class SearchService {

    private static final int MAX_PAGE = 10;

    private final SearchCacheRepository cache;
    private final SearchWriter writer;
    private final BookRepository books;
    private final BookCatalog catalog;
    private final BookshelfProperties props;
    private final Clock clock;

    public SearchService(SearchCacheRepository cache, SearchWriter writer, BookRepository books,
                         BookCatalog catalog, BookshelfProperties props, Clock clock) {
        this.cache = cache;
        this.writer = writer;
        this.books = books;
        this.catalog = catalog;
        this.props = props;
        this.clock = clock;
    }

    public SearchResult search(String rawQuery, int page) {
        if (page < 1 || page > MAX_PAGE) {
            throw new DomainException("페이지는 1~" + MAX_PAGE + "다");
        }
        String key = QueryNormalizer.normalize(rawQuery);
        Instant now = clock.instant();

        Optional<SearchCacheRepository.Entry> cached = cache.find(key, page);
        if (cached.isPresent() && cached.get().fetchedAt().plus(props.searchCacheTtl()).isAfter(now)) {
            return fromCache(cached.get(), page);
        }
        try {
            CatalogPage fetched = catalog.search(key, page, CallPurpose.INTERACTIVE);
            writer.save(key, page, fetched, now);
            return fromCache(cache.find(key, page).orElseThrow(), page);
        } catch (ProviderUnavailableException e) {
            return cached.map(c -> fromCache(c, page))
                    .orElseThrow(() -> new SearchUnavailableException("검색을 잠시 쓸 수 없다", e));
        }
    }

    private SearchResult fromCache(SearchCacheRepository.Entry entry, int page) {
        Map<String, Book> byIsbn = books.findAllById(entry.isbns()).stream()
                .collect(Collectors.toMap(Book::getIsbn13, Function.identity()));
        List<BookView> ordered = entry.isbns().stream()
                .map(byIsbn::get)
                .filter(Objects::nonNull)
                .map(BookView::of)
                .toList();
        boolean isEnd = page * BookCatalog.PAGE_SIZE >= entry.total();
        return new SearchResult(ordered, entry.total(), isEnd, entry.fetchedAt());
    }
}
