package com.bookshelf.book.application;

import com.bookshelf.book.infrastructure.persistence.RefreshQueue;
import com.bookshelf.book.infrastructure.persistence.SaleSnapshotRepository;
import com.bookshelf.book.application.catalog.BookCatalog;
import com.bookshelf.book.application.catalog.CatalogBook;
import com.bookshelf.common.BookshelfProperties;
import com.bookshelf.common.Isbn13;
import com.bookshelf.provider.domain.BudgetExhaustedException;
import com.bookshelf.provider.domain.CallPurpose;
import com.bookshelf.provider.domain.ProviderUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/** 갱신 큐를 백그라운드 예산으로 비운다. 제공자가 죽거나 예산이 바닥나면 그 회차를 멈추고 큐는 남긴다 */
@Slf4j
@Component
public class RefreshWorker {

    private final RefreshQueue queue;
    private final SaleSnapshotRepository snapshots;
    private final BookCatalog catalog;
    private final BookWriter writer;
    private final BookshelfProperties props;
    private final Clock clock;

    public RefreshWorker(RefreshQueue queue, SaleSnapshotRepository snapshots, BookCatalog catalog,
                         BookWriter writer, BookshelfProperties props, Clock clock) {
        this.queue = queue;
        this.snapshots = snapshots;
        this.catalog = catalog;
        this.writer = writer;
        this.props = props;
        this.clock = clock;
    }

    /** @return 제공자를 부른 횟수 */
    public int runOnce() {
        int called = 0;
        for (Isbn13 isbn : queue.take(props.background().refreshBatchSize())) {
            boolean fresh = snapshots.findById(isbn.value())
                    .map(s -> s.isFreshAt(clock.instant(), props.saleTtl()))
                    .orElse(false);
            if (fresh) {
                queue.done(isbn);   // 그 사이 다른 경로(앱 스캔)가 갱신했다
                continue;
            }
            try {
                called++;
                Optional<CatalogBook> found = catalog.lookup(isbn, CallPurpose.BACKGROUND);
                if (found.isPresent()) {
                    writer.saveLookup(found.get());
                } else {
                    writer.saveUnknownSale(isbn);
                }
                queue.done(isbn);
            } catch (BudgetExhaustedException e) {
                log.info("갱신 중단: 백그라운드 예산 소진 (남은 큐 {})", queue.size());
                called--;   // 예산이 없어 보내지 않았다
                break;
            } catch (ProviderUnavailableException e) {
                log.warn("갱신 중단: {} (남은 큐 {})", e.getMessage(), queue.size());
                break;
            }
        }
        return called;
    }
}
