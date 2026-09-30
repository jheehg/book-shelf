package com.bookshelf.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Isbn13Test {

    @Test
    void 올바른_ISBN13을_받는다() {
        assertThat(Isbn13.parse("9788936434120").value()).isEqualTo("9788936434120");
    }

    @Test
    void 하이픈과_공백을_지운다() {
        assertThat(Isbn13.parse(" 978-89-364-3412-0 ").value()).isEqualTo("9788936434120");
    }

    @Test
    void ISBN10과_ISBN13은_같은_값이_된다() {
        // 89-364-3412-8 (ISBN-10) == 978-89-364-3412-0 — 카카오 실측 응답의 "8936434128 9788936434120"
        assertThat(Isbn13.parse("8936434128")).isEqualTo(Isbn13.parse("9788936434120"));
    }

    @Test
    void ISBN10의_체크숫자_X를_받는다() {
        // 0-8044-2957-X == 978-0-8044-2957-3
        assertThat(Isbn13.parse("080442957x").value()).isEqualTo("9780804429573");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9788936434121",   // 체크섬 틀림
            "8801234567893",   // 책이 아닌 상품 바코드 (978/979가 아니다)
            "97889364341",     // 자릿수 부족
            "8936434125",      // ISBN-10 체크섬 틀림
            "abcdefghijklm",
            ""
    })
    void 잘못된_입력은_도메인_예외다(String raw) {
        assertThatThrownBy(() -> Isbn13.parse(raw)).isInstanceOf(DomainException.class);
    }

    @Test
    void null은_도메인_예외다() {
        assertThatThrownBy(() -> Isbn13.parse(null)).isInstanceOf(DomainException.class);
    }

    @Test
    void tryParse는_예외_대신_empty다() {
        assertThat(Isbn13.tryParse("9788936434121")).isEmpty();
        assertThat(Isbn13.tryParse(null)).isEmpty();
        assertThat(Isbn13.tryParse("9788936434120")).map(Isbn13::value).contains("9788936434120");
    }

    @ParameterizedTest
    @CsvSource({
            "9788936434120, ko",   // 978-89 한국
            "9791161571188, ko",   // 979-11 한국
            "9781847941831, en",   // 978-1 영어권
            "9780804429573, en",   // 978-0 영어권
    })
    void ISBN_국가_번호로_언어를_짐작한다(String isbn, String lang) {
        assertThat(Isbn13.parse(isbn).languageHint()).contains(lang);
    }

    @Test
    void 그_밖의_국가_번호는_판단하지_않는다() {
        // 978-4 일본
        assertThat(Isbn13.parse("9784101001616").languageHint()).isEqualTo(Optional.empty());
    }
}
