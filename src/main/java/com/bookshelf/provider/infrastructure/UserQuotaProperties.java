package com.bookshelf.provider.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("providers.user")
public record UserQuotaProperties(int dailyLimit) {
}
