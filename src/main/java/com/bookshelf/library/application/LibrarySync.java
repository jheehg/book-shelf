package com.bookshelf.library.application;

import com.bookshelf.library.LibraryDirectory;
import com.bookshelf.library.LibraryPage;
import com.bookshelf.library.Region;
import com.bookshelf.provider.CallPurpose;
import com.bookshelf.provider.ProviderUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 전국 도서관 목록을 주 1회 받는다. 스케줄은 매일 돌고, 마지막 "완료" 동기화가 7일 넘었을 때만 부른다.
 * 실패하면 거기서 멈춘다(남은 지역을 불러 봐야 같은 장애에 예산만 쓴다). 받은 곳은 남긴다.
 */
@Slf4j
@Component
public class LibrarySync {

    static final int PAGE_SIZE = 500;
    static final Duration INTERVAL = Duration.ofDays(7);
    private static final int MAX_PAGES = 20;   // 지역당 1만 곳 — 응답이 이상해도 끝없이 돌지 않게

    private final LibraryDirectory directory;
    private final LibraryWriter writer;
    private final Clock clock;

    public LibrarySync(LibraryDirectory directory, LibraryWriter writer, Clock clock) {
        this.directory = directory;
        this.writer = writer;
        this.clock = clock;
    }

    public int runOnce() {
        Instant now = clock.instant();
        if (writer.lastFinishedAt().map(last -> last.plus(INTERVAL).isAfter(now)).orElse(false)) {
            return 0;
        }
        int saved = 0;
        try {
            for (Region region : Region.values()) {
                saved += syncRegion(region, now);
            }
        } catch (ProviderUnavailableException e) {
            log.warn("도서관 동기화 중단 ({}곳 저장): {}", saved, e.getMessage());
            return saved;
        }
        writer.finishRun(now, saved);
        log.info("도서관 동기화 완료: {}곳", saved);
        return saved;
    }

    private int syncRegion(Region region, Instant now) {
        int saved = 0;
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
            LibraryPage page = directory.libraries(region.code(), pageNo, PAGE_SIZE, CallPurpose.BACKGROUND);
            writer.upsert(region, page.libraries(), now);
            saved += page.libraries().size();
            if (page.libraries().size() < PAGE_SIZE) {
                break;
            }
        }
        return saved;
    }
}
