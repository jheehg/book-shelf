package com.bookshelf.publicview.application;

import com.bookshelf.record.domain.ReadingStatus;

public record ReviewedEntry(String isbn13, String title, String author, String coverUrl,
                            ReadingStatus status, Integer rating, String review) implements PublicEntry {
}
