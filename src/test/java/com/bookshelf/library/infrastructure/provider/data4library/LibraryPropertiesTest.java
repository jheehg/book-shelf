package com.bookshelf.library.infrastructure.provider.data4library;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibraryPropertiesTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "${LIBRARY_API_KEY}"})
    void 키가_없으면_기동하지_않는다(String key) {
        assertThatThrownBy(() -> new LibraryProperties(key, "https://data4library.kr", Duration.ofSeconds(1),
                Duration.ofSeconds(3), 450, 300, ZoneId.of("Asia/Seoul"), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIBRARY_API_KEY");
    }
}
