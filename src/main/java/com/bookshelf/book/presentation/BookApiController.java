package com.bookshelf.book.presentation;

import com.bookshelf.common.ApiExceptionHandler;

import com.bookshelf.book.application.BookDetail;
import com.bookshelf.book.application.BookLookupService;
import com.bookshelf.book.application.BookView;
import com.bookshelf.book.application.SaleService;
import com.bookshelf.common.Isbn13;
import com.bookshelf.common.NotFoundException;
import com.bookshelf.links.domain.LinkGroup;
import com.bookshelf.links.domain.StoreLink;
import com.bookshelf.links.domain.StoreLinks;
import com.bookshelf.provider.domain.CallPurpose;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/books")
public class BookApiController {

    private final BookLookupService lookup;
    private final SaleService sales;
    private final StoreLinks links;

    public BookApiController(BookLookupService lookup, SaleService sales, StoreLinks links) {
        this.lookup = lookup;
        this.sales = sales;
        this.links = links;
    }

    /** 사람이 기다리는 호출 — 모르는 책이면 제공자를 부른다. 제공자 장애는 503(ApiExceptionHandler) */
    @GetMapping("/{isbn}")
    public BookDetail detail(@PathVariable String isbn) {
        Isbn13 i = Isbn13.parse(isbn);
        BookView book = lookup.findOrFetch(i, CallPurpose.INTERACTIVE)
                .orElseThrow(() -> new NotFoundException("책 " + i.value()));
        return new BookDetail(book, sales.current(i));
    }

    @GetMapping("/{isbn}/links")
    public Map<LinkGroup, List<StoreLink>> links(@PathVariable String isbn) {
        Isbn13 i = Isbn13.parse(isbn);
        BookView book = lookup.findLocal(i).orElseThrow(() -> new NotFoundException("책 " + i.value()));
        return links.forBook(book.isbn13(), book.title(), book.author());
    }
}
