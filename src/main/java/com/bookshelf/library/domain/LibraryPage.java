package com.bookshelf.library.domain;

import java.util.List;

public record LibraryPage(List<LibraryInfo> libraries, int numFound) {
}
