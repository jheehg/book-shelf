package com.bookshelf.book.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 로직(RefreshWorker, BookBackfill)과 스케줄을 나눈다. 테스트는 스케줄 없이 runOnce()를 직접 부른다.
 * initialDelay를 주는 이유: 기동 직후 배포 확인 중에 외부 호출이 섞이지 않게 한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bookshelf.background.enabled", havingValue = "true")
class BackgroundJobs {

    private final RefreshWorker refresh;
    private final BookBackfill backfill;

    BackgroundJobs(RefreshWorker refresh, BookBackfill backfill) {
        this.refresh = refresh;
        this.backfill = backfill;
    }

    @Scheduled(fixedDelayString = "${bookshelf.background.refresh-interval}",
               initialDelayString = "${bookshelf.background.refresh-interval}")
    void refresh() {
        refresh.runOnce();
    }

    @Scheduled(fixedDelayString = "${bookshelf.background.backfill-interval}",
               initialDelayString = "${bookshelf.background.backfill-interval}")
    void backfill() {
        backfill.runOnce();
    }
}
