package com.bookshelf.publicview;

import com.bookshelf.record.domain.ReadingStatus;

/** 공개 서재의 한 칸. 한줄평은 ReviewedEntry에만 있다. title이 null이면 책 정보가 아직 없다 */
public sealed interface PublicEntry permits RatedEntry, ReviewedEntry {
    String isbn13();
    String title();
    String author();
    String coverUrl();
    ReadingStatus status();
    Integer rating();
}
