package com.bookshelf.book.application;

import com.bookshelf.common.BookshelfProperties;
import com.bookshelf.common.Isbn13;
import com.bookshelf.book.infrastructure.persistence.BookBackfillCandidates;
import com.bookshelf.provider.domain.BudgetExhaustedException;
import com.bookshelf.provider.domain.CallPurpose;
import com.bookshelf.provider.domain.ProviderUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** 책 정보 없이 생긴 기록에 책 정보를 붙인다 (설계 문서 5장) */
@Slf4j
@Component
public class BookBackfill {

    private final BookBackfillCandidates candidates;
    private final BookLookupService lookup;
    private final BookWriter writer;
    private final BookshelfProperties props;
    private final Clock clock;

    public BookBackfill(BookBackfillCandidates candidates, BookLookupService lookup, BookWriter writer,
                        BookshelfProperties props, Clock clock) {
        this.candidates = candidates;
        this.lookup = lookup;
        this.writer = writer;
        this.props = props;
        this.clock = clock;
    }

    public int runOnce() {
        List<String> pending = candidates.findPending(clock.instant(), props.background().backfillBatchSize());

        int filled = 0;
        for (String raw : pending) {
            // DB CHECK는 체크섬을 못 본다. 수동 SQL로 들어온 틀린 ISBN 한 줄이 회차 전체를 멈추지 않게 건너뛴다
            Optional<Isbn13> parsed = Isbn13.tryParse(raw);
            if (parsed.isEmpty()) {
                log.warn("백필: 체크섬이 틀린 ISBN 기록을 건너뛴다 isbn={} — reading_record를 직접 고쳐야 한다", raw);
                continue;
            }
            Isbn13 isbn = parsed.get();
            try {
                if (lookup.findOrFetch(isbn, CallPurpose.BACKGROUND).isPresent()) {
                    filled++;
                } else {
                    writer.recordFailure(isbn);
                }
            } catch (BudgetExhaustedException e) {
                log.info("백필 중단: 예산 소진 (채운 권수 {})", filled);
                break;
            } catch (ProviderUnavailableException e) {
                writer.recordFailure(isbn);
                log.warn("백필 중단: {} isbn={}", e.getMessage(), raw);
                break;
            }
        }
        if (!pending.isEmpty()) {
            log.info("백필 {}건 중 {}건 채움", pending.size(), filled);
        }
        return filled;
    }
}
