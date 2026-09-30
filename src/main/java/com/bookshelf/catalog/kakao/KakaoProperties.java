package com.bookshelf.catalog.kakao;

import com.bookshelf.provider.BudgetPolicy;
import com.bookshelf.provider.Provider;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

@ConfigurationProperties("providers.kakao")
public record KakaoProperties(
        String restApiKey,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int dailyLimit,          // 사람이 기다리는 호출의 상한 (27,000)
        int backgroundCeiling,   // 사람이 기다리지 않는 호출의 상한 (18,000)
        ZoneId resetZone,        // 한도 초기화 시간대
        Duration coolDown        // 실패 후 백그라운드 호출을 쉬는 시간
) {

    /**
     * @ConfigurationProperties 바인딩은 치환 못 한 "${KAKAO_REST_API_KEY}"를 문자 그대로 넣고 넘어간다
     * (@Value와 달리 기동이 실패하지 않는다). 키 없이 뜬 서버는 모든 호출이 401이 되므로 여기서 기동을 막는다.
     */
    public KakaoProperties {
        if (restApiKey == null || restApiKey.isBlank() || restApiKey.startsWith("${")) {
            throw new IllegalStateException("KAKAO_REST_API_KEY가 없다 — 기본값 없이 환경변수로 주입해야 한다");
        }
    }

    public BudgetPolicy policy() {
        return new BudgetPolicy(Provider.KAKAO, dailyLimit, backgroundCeiling, resetZone);
    }
}
