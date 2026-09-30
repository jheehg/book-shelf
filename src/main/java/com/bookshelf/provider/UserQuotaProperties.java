package com.bookshelf.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("providers.user")
public record UserQuotaProperties(int dailyLimit) {
}
