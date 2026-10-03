package com.bookshelf.search.application;

import com.bookshelf.book.application.BookView;

import java.time.Instant;
import java.util.List;

public record SearchResult(List<BookView> books, int total, boolean isEnd, Instant fetchedAt) {
}
