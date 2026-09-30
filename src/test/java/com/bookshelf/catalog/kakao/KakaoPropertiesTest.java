package com.bookshelf.catalog.kakao;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 키가 없으면 기동이 실패해야 한다 (전역 규칙: 시크릿에 기본값을 두지 않는다).
 * @ConfigurationProperties 바인딩은 치환 못 한 "${KAKAO_REST_API_KEY}"를 문자 그대로 넣고 넘어간다 —
 * @Value와 달리 기동이 실패하지 않는다(Task 4 Step 9에서 실제로 겪었다). 그래서 여기서 직접 막는다.
 */
class KakaoPropertiesTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "${KAKAO_REST_API_KEY}"})
    void 키가_없거나_치환되지_않았으면_기동이_실패한다(String key) {
        assertThatThrownBy(() -> new KakaoProperties(key, "https://dapi.kakao.com", Duration.ofSeconds(1),
                Duration.ofSeconds(2), 27000, 18000, ZoneId.of("Asia/Seoul"), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KAKAO_REST_API_KEY");
    }
}
