package com.bookshelf.publicview.presentation;

import com.bookshelf.publicview.presentation.AgeLabel;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AgeLabelTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "PT30S, 방금 기준",
            "PT5M,  5분 전 기준",
            "PT59M, 59분 전 기준",
            "PT1H,  1시간 전 기준",
            "PT3H10M, 3시간 전 기준",
            "PT23H59M, 23시간 전 기준",
            "PT24H, 1일 전 기준",
            "P3D,   3일 전 기준"
    })
    void 경과_시간을_사람의_말로(String elapsed, String expected) {
        assertThat(AgeLabel.of(NOW.minus(Duration.parse(elapsed)), NOW)).isEqualTo(expected);
    }
}
