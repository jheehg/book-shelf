package com.bookshelf.book.application;

import com.bookshelf.book.domain.Book;

import java.time.LocalDate;

/** 엔티티는 서비스 밖으로 나가지 않는다 */
public record BookView(String isbn13, String title, String author, String publisher,
                       LocalDate pubDate, String coverUrl, Integer pageCount) {

    public static BookView of(Book b) {
        return new BookView(b.getIsbn13(), b.getTitle(), b.getAuthor(), b.getPublisher(),
                b.getPubDate(), b.getCoverUrl(), b.getPageCount());
    }
}
