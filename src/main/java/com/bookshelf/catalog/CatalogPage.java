package com.bookshelf.catalog;

import java.util.List;

public record CatalogPage(List<CatalogBook> books, int total, boolean isEnd) {
}
