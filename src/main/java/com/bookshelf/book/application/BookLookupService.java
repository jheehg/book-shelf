package com.bookshelf.book.application;

import com.bookshelf.book.infrastructure.BookRepository;
import com.bookshelf.catalog.BookCatalog;
import com.bookshelf.common.Isbn13;
import com.bookshelf.provider.CallPurpose;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 3층 방어의 3층: 로컬 Book 우선.
 * @Transactional을 붙이지 않는다 — 제공자 호출이 DB 커넥션을 쥔 채 기다리면 안 된다.
 */
@Service
public class BookLookupService {

    private final BookRepository books;
    private final BookCatalog catalog;
    private final BookWriter writer;

    public BookLookupService(BookRepository books, BookCatalog catalog, BookWriter writer) {
        this.books = books;
        this.catalog = catalog;
        this.writer = writer;
    }

    public Optional<BookView> findOrFetch(Isbn13 isbn, CallPurpose purpose) {
        Optional<BookView> local = books.findById(isbn.value()).map(BookView::of);
        if (local.isPresent()) {
            return local;
        }
        return catalog.lookup(isbn, purpose).map(writer::saveLookup);
    }
}
