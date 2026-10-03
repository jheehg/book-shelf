package com.bookshelf.library.infrastructure.provider.data4library;

import com.bookshelf.provider.domain.BudgetPolicy;
import com.bookshelf.provider.domain.Provider;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

@ConfigurationProperties("providers.library")
public record LibraryProperties(
        String apiKey,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int dailyLimit,          // 450 — 서버 IP 미등록 일 500의 90%
        int backgroundCeiling,   // 300
        ZoneId resetZone,
        Duration coolDown
) {

    /** KakaoProperties와 같은 이유: 치환 못 한 "${LIBRARY_API_KEY}"가 문자 그대로 들어온다 */
    public LibraryProperties {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            throw new IllegalStateException("LIBRARY_API_KEY가 없다 — 기본값 없이 환경변수로 주입해야 한다");
        }
    }

    public BudgetPolicy policy() {
        return new BudgetPolicy(Provider.LIBRARY, dailyLimit, backgroundCeiling, resetZone);
    }
}
