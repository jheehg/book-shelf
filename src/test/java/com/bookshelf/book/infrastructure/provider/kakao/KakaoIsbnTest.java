package com.bookshelf.book.infrastructure.provider.kakao;

import com.bookshelf.common.Isbn13;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 카카오 isbn 필드는 실측에서 세 가지 모양으로 왔다 (docs/notes/providers.md) */
class KakaoIsbnTest {

    @ParameterizedTest
    // 앞 공백을 지키려면 ignoreLeadingAndTrailingWhitespace = false (기본값은 공백을 자른다)
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "8936434128 9788936434120|9788936434120",   // 10자리 13자리
            "9791168473690|9791168473690",              // 13자리만
            " 9791168478688|9791168478688",             // 앞에 공백
            "8936434128|9788936434120",                 // 10자리만 → 13자리로
            "8936434128 9788936434121|9788936434120",   // 13자리가 틀리면 10자리로
    })
    void 모양이_달라도_13자리_하나로_옮긴다(String raw, String expected) {
        assertThat(KakaoIsbn.parse(raw)).map(Isbn13::value).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "9788936434121", "abc"})
    void 쓸_수_있는_ISBN이_없으면_empty(String raw) {
        assertThat(KakaoIsbn.parse(raw)).isEmpty();
    }
}
