package com.bookshelf.library;

import java.util.List;

public record LibraryPage(List<LibraryInfo> libraries, int numFound) {
}
