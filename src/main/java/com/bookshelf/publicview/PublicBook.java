package com.bookshelf.publicview;

import java.time.LocalDate;
import java.util.List;

public record PublicBook(String isbn13, String title, String author, String publisher, LocalDate pubDate,
                         String coverUrl, Integer pageCount, List<PublicReview> reviews) {
}
