package com.bookshelf.book.application;

import com.bookshelf.book.domain.Book;
import com.bookshelf.book.domain.BookLookupFailure;
import com.bookshelf.book.domain.SaleSnapshot;
import com.bookshelf.book.domain.SaleStatus;
import com.bookshelf.book.infrastructure.BookLookupFailureRepository;
import com.bookshelf.book.infrastructure.BookRepository;
import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
import com.bookshelf.catalog.CatalogBook;
import com.bookshelf.common.Isbn13;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 제공자에서 가져온 것을 DB에 적는다. 외부 호출은 여기서 하지 않는다.
 * 호출하는 서비스와 다른 빈이어야 @Transactional이 프록시를 거친다.
 */
@Component
public class BookWriter {

    private final BookRepository books;
    private final SaleSnapshotRepository sales;
    private final BookLookupFailureRepository failures;
    private final Clock clock;

    public BookWriter(BookRepository books, SaleSnapshotRepository sales,
                      BookLookupFailureRepository failures, Clock clock) {
        this.books = books;
        this.sales = sales;
        this.failures = failures;
        this.clock = clock;
    }

    /** ISBN 조회 결과. 책 정보를 갱신하되 이미 아는 쪽수는 지우지 않는다(Book#refreshFrom) */
    @Transactional
    public BookView saveLookup(CatalogBook c) {
        Instant now = clock.instant();
        Book fetched = toBook(c, now);
        Book book = books.findById(c.isbn().value())
                .map(existing -> {
                    existing.refreshFrom(fetched);
                    return existing;
                })
                .orElseGet(() -> books.save(fetched));

        upsertSale(SaleSnapshot.of(c.isbn(), c.listPrice(), c.salePrice(), c.saleStatus(), now));
        failures.deleteById(c.isbn().value());
        return BookView.of(book);
    }

    /**
     * 제공자가 모르는 책의 판매 칸. "판매 정보 없음"도 기준 시각이 있는 스냅샷이다 —
     * 없으면 봇이 그 책 페이지를 열 때마다 갱신 큐에 다시 들어간다 (Review Focus 1).
     */
    @Transactional
    public void saveUnknownSale(Isbn13 isbn) {
        upsertSale(SaleSnapshot.of(isbn, null, null, SaleStatus.UNKNOWN, clock.instant()));
    }

    @Transactional
    public void recordFailure(Isbn13 isbn) {
        Instant now = clock.instant();
        failures.findById(isbn.value())
                .ifPresentOrElse(f -> f.again(now), () -> failures.save(BookLookupFailure.first(isbn, now)));
    }

    public static Book toBook(CatalogBook c, Instant now) {
        return Book.of(c.isbn(), c.title(), c.author(), c.publisher(), c.pubDate(),
                c.coverUrl(), c.pageCount(), now);
    }

    private void upsertSale(SaleSnapshot snapshot) {
        sales.findById(snapshot.getIsbn13())
                .ifPresentOrElse(existing -> existing.replaceWith(snapshot), () -> sales.save(snapshot));
    }
}
