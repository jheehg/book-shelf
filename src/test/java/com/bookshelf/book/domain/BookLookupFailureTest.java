package com.bookshelf.book.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BookLookupFailureTest {

    @ParameterizedTest
    @CsvSource({"1, PT1H", "2, PT4H", "3, PT16H", "4, PT64H", "5, P7D", "30, P7D"})
    void 실패가_쌓일수록_늦게_다시_묻되_7일을_넘지_않는다(int attempts, String expected) {
        assertThat(BookLookupFailure.backoff(attempts)).isEqualTo(Duration.parse(expected));
    }
}
