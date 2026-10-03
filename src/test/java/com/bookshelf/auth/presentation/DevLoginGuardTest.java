package com.bookshelf.auth.presentation;

import com.bookshelf.auth.presentation.DevLoginController;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevLoginGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080", "http://localhost"})
    void 로컬_주소면_켤_수_있다(String siteUrl) {
        assertThatCode(() -> DevLoginController.requireLocal(siteUrl)).doesNotThrowAnyException();
    }

    /** 실수로 켠 채 배포해도 서버가 뜨지 않는다 */
    @ParameterizedTest
    @ValueSource(strings = {"https://book-shelf.example", "http://localhost.evil.example", "https://192.0.2.10"})
    void 배포_주소면_기동하지_않는다(String siteUrl) {
        assertThatThrownBy(() -> DevLoginController.requireLocal(siteUrl)).isInstanceOf(IllegalStateException.class);
    }
}
