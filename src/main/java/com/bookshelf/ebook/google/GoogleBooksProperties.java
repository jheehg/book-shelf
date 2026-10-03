package com.bookshelf.ebook.google;

import com.bookshelf.provider.BudgetPolicy;
import com.bookshelf.provider.Provider;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

@ConfigurationProperties("providers.google")
public record GoogleBooksProperties(String apiKey, String baseUrl, Duration connectTimeout, Duration readTimeout,
                                    int dailyLimit, int backgroundCeiling, ZoneId resetZone, Duration coolDown) {

    public GoogleBooksProperties {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            throw new IllegalStateException("GOOGLE_BOOKS_KEY가 없다 — 기본값 없이 환경변수로 주입해야 한다");
        }
    }

    public BudgetPolicy policy() {
        return new BudgetPolicy(Provider.GOOGLE_BOOKS, dailyLimit, backgroundCeiling, resetZone);
    }
}
