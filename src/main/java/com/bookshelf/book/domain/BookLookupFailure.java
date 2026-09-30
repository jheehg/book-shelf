package com.bookshelf.book.domain;

import com.bookshelf.common.Isbn13;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * 제공자에서 못 찾은 ISBN. 다음 시도 시각을 1시간 → 4시간 → 16시간 → 64시간 → 7일(상한)로 늘린다.
 * 없으면 백필이 10분마다 같은 ISBN을 물어 예산을 태운다(하루 144번).
 */
@Entity
@Table(name = "book_lookup_failure")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookLookupFailure {

    private static final Duration BASE = Duration.ofHours(1);
    private static final Duration MAX = Duration.ofDays(7);

    @Id
    private String isbn13;
    private int attempts;
    private Instant nextTryAt;

    public static BookLookupFailure first(Isbn13 isbn, Instant now) {
        BookLookupFailure f = new BookLookupFailure();
        f.isbn13 = isbn.value();
        f.attempts = 1;
        f.nextTryAt = now.plus(backoff(1));
        return f;
    }

    public void again(Instant now) {
        this.attempts++;
        this.nextTryAt = now.plus(backoff(attempts));
    }

    static Duration backoff(int attempts) {
        // 4^(n-1)시간. n이 커져도 넘치지 않게 지수를 제한한다
        long hours = 1L << (2 * Math.min(attempts - 1, 10));
        Duration d = BASE.multipliedBy(hours);
        return d.compareTo(MAX) > 0 ? MAX : d;
    }
}
