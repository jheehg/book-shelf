package com.bookshelf.record.application;

import com.bookshelf.book.application.BookView;
import com.bookshelf.record.domain.ReadingRecord;

import java.time.LocalDate;

/** book은 책 정보가 아직 없으면 null — 앱은 회색 표지와 ISBN을 보여준다 */
public record RecordView(long id, String isbn13, String status, Integer rating, String review, String visibility,
                         LocalDate startedAt, LocalDate finishedAt, BookView book) {

    static RecordView of(ReadingRecord r, BookView book) {
        return new RecordView(r.getId(), r.getIsbn13(), r.getStatus().name(), r.getRating(), r.getReview(),
                r.getVisibility().name(), r.getStartedAt(), r.getFinishedAt(), book);
    }
}
