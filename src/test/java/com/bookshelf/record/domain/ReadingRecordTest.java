package com.bookshelf.record.domain;

import com.bookshelf.common.DomainException;
import com.bookshelf.common.Isbn13;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadingRecordTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Isbn13 ISBN = Isbn13.parse("9788936434120");

    private ReadingRecord newRecord() {
        return ReadingRecord.create(1L, ISBN, ReadingStatus.DONE, Visibility.PRIVATE, NOW);
    }

    @Test
    void 만들면_ISBN만으로_존재한다() {
        ReadingRecord r = newRecord();
        assertThat(r.getIsbn13()).isEqualTo("9788936434120");
        assertThat(r.getVisibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(r.getRating()).isNull();
        assertThat(r.getReview()).isNull();
    }

    @Test
    void 별점은_1부터_5까지이고_지울_수_있다() {
        ReadingRecord r = newRecord();
        r.rate(5);
        assertThat(r.getRating()).isEqualTo(5);
        r.rate(null);
        assertThat(r.getRating()).isNull();
        assertThatThrownBy(() -> r.rate(0)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> r.rate(6)).isInstanceOf(DomainException.class);
    }

    @Test
    void 한줄평은_앞뒤_공백을_지우고_빈_문자열은_없음으로_본다() {
        ReadingRecord r = newRecord();
        r.writeReview("  좋았다  ", NOW);
        assertThat(r.getReview()).isEqualTo("좋았다");
        r.writeReview("   ", NOW);
        assertThat(r.getReview()).isNull();
    }

    @Test
    void 한줄평은_200자까지다_이모지도_한_글자로_센다() {
        ReadingRecord r = newRecord();
        r.writeReview("📚".repeat(200), NOW);          // String.length()로는 400이다
        assertThat(r.getReview()).hasSize(400);
        assertThatThrownBy(() -> r.writeReview("가".repeat(201), NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void 공개_범위를_바꾼다() {
        ReadingRecord r = newRecord();
        r.changeVisibility(Visibility.RATING_ONLY, NOW);
        assertThat(r.getVisibility()).isEqualTo(Visibility.RATING_ONLY);
    }
}
