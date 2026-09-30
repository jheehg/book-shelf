package com.bookshelf.ops;

import com.bookshelf.auth.RefreshTokenStore;
import com.bookshelf.library.application.LibrarySync;
import com.bookshelf.search.SearchCacheCleanup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;

/** 하루 한 번 쌓이기만 하는 행을 지운다. 로직은 각 클래스에 있고 여기는 스케줄만 — 테스트는 runOnce를 직접 부른다 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bookshelf.background.enabled", havingValue = "true")
class MaintenanceJobs {

    private final SearchCacheCleanup search;
    private final RefreshTokenStore tokens;
    private final LibrarySync librarySync;
    private final Clock clock;

    MaintenanceJobs(SearchCacheCleanup search, RefreshTokenStore tokens, LibrarySync librarySync, Clock clock) {
        this.search = search;
        this.tokens = tokens;
        this.librarySync = librarySync;
        this.clock = clock;
    }

    @Scheduled(cron = "${bookshelf.background.cleanup-cron}", zone = "Asia/Seoul")
    void cleanup() {
        search.runOnce();
        log.info("만료된 refresh 토큰 정리: {}행", tokens.purgeExpired(clock.instant()));
    }

    /** 매일 돌지만 마지막 완료가 7일 넘었을 때만 정보나루를 부른다(LibrarySync) */
    @Scheduled(cron = "${bookshelf.background.library-sync-cron}", zone = "Asia/Seoul")
    void librarySync() {
        librarySync.runOnce();
    }
}
