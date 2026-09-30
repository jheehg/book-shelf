package com.bookshelf.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryNormalizerTest {

    @Test
    void 공백을_정리하고_소문자로_바꾼다() {
        assertThat(QueryNormalizer.normalize("  Harry   Potter \t")).isEqualTo("harry potter");
    }

    @Test
    void 자모가_분리된_한글도_같은_키가_된다() {
        String nfd = Normalizer.normalize("소년이 온다", Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("소년이 온다");                      // 눈에는 같지만 다른 문자열
        assertThat(QueryNormalizer.normalize(nfd)).isEqualTo("소년이 온다");
    }

    @Test
    void 두_글자면_받는다() {
        assertThat(QueryNormalizer.normalize("소년")).isEqualTo("소년");
        assertThat(QueryNormalizer.normalize("C++")).isEqualTo("c++");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "책", " a ", "ㅅㄴ", "ㅅ ㄴㅇ"})
    void 검색_의도가_아닌_입력은_거부한다(String raw) {
        assertThatThrownBy(() -> QueryNormalizer.normalize(raw)).isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void 너무_길면_거부한다() {
        assertThatThrownBy(() -> QueryNormalizer.normalize("가".repeat(101)))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void null은_거부한다() {
        assertThatThrownBy(() -> QueryNormalizer.normalize(null)).isInstanceOf(InvalidQueryException.class);
    }
}
