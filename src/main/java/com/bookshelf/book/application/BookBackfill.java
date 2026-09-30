package com.bookshelf.book.application;

import com.bookshelf.common.BookshelfProperties;
import com.bookshelf.common.Isbn13;
import com.bookshelf.provider.BudgetExhaustedException;
import com.bookshelf.provider.CallPurpose;
import com.bookshelf.provider.ProviderUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

/** 책 정보 없이 생긴 기록에 책 정보를 붙인다 (설계 문서 5장) */
@Slf4j
@Component
public class BookBackfill {

    private final JdbcClient jdbc;
    private final BookLookupService lookup;
    private final BookWriter writer;
    private final BookshelfProperties props;
    private final Clock clock;

    public BookBackfill(JdbcClient jdbc, BookLookupService lookup, BookWriter writer,
                        BookshelfProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.lookup = lookup;
        this.writer = writer;
        this.props = props;
        this.clock = clock;
    }

    public int runOnce() {
        List<String> pending = jdbc.sql("""
                SELECT DISTINCT r.isbn13
                FROM reading_record r
                LEFT JOIN book b                ON b.isbn13 = r.isbn13
                LEFT JOIN book_lookup_failure f ON f.isbn13 = r.isbn13
                WHERE b.isbn13 IS NULL
                  AND (f.isbn13 IS NULL OR f.next_try_at <= :now)
                LIMIT :n
                """)
                .param("now", Timestamp.from(clock.instant()))
                .param("n", props.background().backfillBatchSize())
                .query(String.class)
                .list();

        int filled = 0;
        for (String raw : pending) {
            Isbn13 isbn = Isbn13.parse(raw);
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
