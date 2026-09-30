package com.bookshelf.record.domain;

import com.bookshelf.common.DomainException;
import com.bookshelf.common.Isbn13;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "reading_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReadingRecord {

    private static final int REVIEW_MAX = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long memberId;       // Member를 참조하지 않는다 — 도메인 사이에는 ID만

    private String isbn13;       // Book을 참조하지 않는다 — 책 정보가 없어도 기록은 있다

    @Enumerated(EnumType.STRING)
    private ReadingStatus status;

    private Integer rating;

    private String review;

    private LocalDate startedAt;

    private LocalDate finishedAt;

    @Enumerated(EnumType.STRING)
    private Visibility visibility;

    private Instant createdAt;

    private Instant updatedAt;

    public static ReadingRecord create(Long memberId, Isbn13 isbn, ReadingStatus status,
                                       Visibility visibility, Instant now) {
        ReadingRecord r = new ReadingRecord();
        r.memberId = memberId;
        r.isbn13 = isbn.value();
        r.status = status;
        r.visibility = visibility;
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    public void rate(Integer rating) {
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new DomainException("별점은 1~5다: " + rating);
        }
        this.rating = rating;
    }

    public void writeReview(String review, Instant now) {
        String stripped = review == null ? null : review.strip();
        if (stripped != null && stripped.isEmpty()) {
            stripped = null;
        }
        // DB VARCHAR(200)은 코드포인트 수다. String.length()는 이모지를 2로 센다
        if (stripped != null && stripped.codePointCount(0, stripped.length()) > REVIEW_MAX) {
            throw new DomainException("한줄평은 " + REVIEW_MAX + "자까지다");
        }
        this.review = stripped;
        this.updatedAt = now;
    }

    public void changeVisibility(Visibility visibility, Instant now) {
        this.visibility = visibility;
        this.updatedAt = now;
    }

    public void changeStatus(ReadingStatus status, Instant now) {
        if (status == null) {
            throw new DomainException("읽는 상태가 없다");
        }
        this.status = status;
        this.updatedAt = now;
    }

    /** 둘 다 비울 수 있다. 둘 다 있으면 완료일이 시작일보다 앞일 수 없다 */
    public void readBetween(LocalDate startedAt, LocalDate finishedAt, Instant now) {
        if (startedAt != null && finishedAt != null && finishedAt.isBefore(startedAt)) {
            throw new DomainException("다 읽은 날이 시작한 날보다 앞이다");
        }
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.updatedAt = now;
    }
}
