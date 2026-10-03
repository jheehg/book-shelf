package com.bookshelf.publicview.application;

import com.bookshelf.record.domain.ReadingStatus;

/** RATING_ONLY, 또는 한줄평을 쓰지 않은 PUBLIC. review 필드가 없다 — 없으면 실수로 채울 수 없다 */
public record RatedEntry(String isbn13, String title, String author, String coverUrl,
                         ReadingStatus status, Integer rating) implements PublicEntry {
}
