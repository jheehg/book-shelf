package com.bookshelf.ebook.infrastructure.provider.google;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleBooksPropertiesTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "${GOOGLE_BOOKS_KEY}"})
    void 키가_없으면_기동하지_않는다(String key) {
        assertThatThrownBy(() -> new GoogleBooksProperties(key, "https://www.googleapis.com", Duration.ofSeconds(1),
                Duration.ofSeconds(3), 900, 300, ZoneId.of("America/Los_Angeles"), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE_BOOKS_KEY");
    }
}
