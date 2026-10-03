package com.bookshelf.book.application.catalog;

import java.util.List;

public record CatalogPage(List<CatalogBook> books, int total, boolean isEnd) {
}
