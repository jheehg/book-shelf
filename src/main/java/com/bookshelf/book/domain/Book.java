package com.bookshelf.book.domain;

import com.bookshelf.common.Isbn13;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/** 제공자 응답의 캐시일 뿐이다. 없어져도 기록은 멀쩡해야 한다 */
@Entity
@Table(name = "book")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Book {

    @Id
    private String isbn13;
    private String title;
    private String author;
    private String publisher;
    private LocalDate pubDate;
    private String coverUrl;
    private Integer pageCount;
    private Instant fetchedAt;

    public static Book of(Isbn13 isbn, String title, String author, String publisher,
                          LocalDate pubDate, String coverUrl, Integer pageCount, Instant fetchedAt) {
        Book b = new Book();
        b.isbn13 = isbn.value();
        b.title = title;
        b.author = author;
        b.publisher = publisher;
        b.pubDate = pubDate;
        b.coverUrl = coverUrl;
        b.pageCount = pageCount;
        b.fetchedAt = fetchedAt;
        return b;
    }

    /**
     * 제공자의 새 응답으로 갱신한다. 쪽수는 제공자마다 줄 수도 안 줄 수도 있다(카카오는 없다).
     * 새 응답에 쪽수가 없다고 이미 아는 쪽수를 지우지 않는다.
     */
    public void refreshFrom(Book fetched) {
        this.title = fetched.title;
        this.author = fetched.author;
        this.publisher = fetched.publisher;
        this.pubDate = fetched.pubDate;
        this.coverUrl = fetched.coverUrl;
        if (fetched.pageCount != null) {
            this.pageCount = fetched.pageCount;
        }
        this.fetchedAt = fetched.fetchedAt;
    }
}
