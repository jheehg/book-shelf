package com.bookshelf.search;

import com.bookshelf.book.application.BookView;
import com.bookshelf.book.application.SaleView;

public record SearchItem(BookView book, SaleView sale) {
}
