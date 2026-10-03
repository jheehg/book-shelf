package com.bookshelf.book.application.catalog;

import com.bookshelf.book.domain.SaleStatus;
import com.bookshelf.common.Isbn13;

import java.time.LocalDate;

/** 제공자 응답을 우리 말로 옮긴 것. 가격이 없으면 null (-1·0을 담지 않는다) */
public record CatalogBook(
        Isbn13 isbn,
        String title,
        String author,
        String publisher,
        LocalDate pubDate,
        String coverUrl,
        Integer pageCount,
        Integer listPrice,
        Integer salePrice,
        SaleStatus saleStatus
) {
}
