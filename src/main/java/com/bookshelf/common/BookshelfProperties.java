package com.bookshelf.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("bookshelf")
public record BookshelfProperties(String siteUrl, Duration saleTtl, Duration searchCacheTtl, Background background) {

    public record Background(boolean enabled, Duration refreshInterval, int refreshBatchSize,
                             Duration backfillInterval, int backfillBatchSize) {
    }
}
