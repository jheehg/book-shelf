package com.bookshelf.book.application;

import com.bookshelf.book.domain.SaleSnapshot;
import com.bookshelf.book.infrastructure.RefreshQueue;
import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
import com.bookshelf.common.BookshelfProperties;
import com.bookshelf.common.Isbn13;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

/**
 * 새 책 판매 칸. **제공자를 부르지 않는다** (설계 문서 2-1).
 * 없거나 오래됐으면 큐에 넣고, 가진 것을 기준 시각과 함께 돌려준다.
 */
@Service
public class SaleService {

    private final SaleSnapshotRepository snapshots;
    private final RefreshQueue queue;
    private final BookshelfProperties props;
    private final Clock clock;

    public SaleService(SaleSnapshotRepository snapshots, RefreshQueue queue,
                       BookshelfProperties props, Clock clock) {
        this.snapshots = snapshots;
        this.queue = queue;
        this.props = props;
        this.clock = clock;
    }

    public SaleView current(Isbn13 isbn) {
        Optional<SaleSnapshot> snapshot = snapshots.findById(isbn.value());
        if (snapshot.isEmpty() || !snapshot.get().isFreshAt(clock.instant(), props.saleTtl())) {
            queue.request(isbn);
        }
        return snapshot.map(SaleView::of).orElseGet(SaleView::unavailable);
    }
}
