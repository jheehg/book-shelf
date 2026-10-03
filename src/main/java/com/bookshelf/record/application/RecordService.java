package com.bookshelf.record.application;

import com.bookshelf.common.DomainException;

import com.bookshelf.book.application.BookLookupService;
import com.bookshelf.book.application.BookView;
import com.bookshelf.book.domain.Book;
import com.bookshelf.book.infrastructure.persistence.BookRepository;
import com.bookshelf.common.Isbn13;
import com.bookshelf.common.NotFoundException;
import com.bookshelf.member.application.MemberService;
import com.bookshelf.provider.domain.CallPurpose;
import com.bookshelf.provider.domain.ProviderUnavailableException;
import com.bookshelf.provider.domain.UserQuotaExceededException;
import com.bookshelf.record.domain.ReadingRecord;
import com.bookshelf.record.domain.ReadingStatus;
import com.bookshelf.record.infrastructure.persistence.ReadingRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** @Transactional 없음 — 기록을 만든 뒤 책 정보를 가져오는 제공자 호출이 있다 */
@Slf4j
@Service
public class RecordService {

    private final MemberService members;
    private final RecordWriter writer;
    private final ReadingRecordRepository records;
    private final BookRepository books;
    private final BookLookupService lookup;
    private final Clock clock;

    public RecordService(MemberService members, RecordWriter writer, ReadingRecordRepository records,
                         BookRepository books, BookLookupService lookup, Clock clock) {
        this.members = members;
        this.writer = writer;
        this.records = records;
        this.books = books;
        this.lookup = lookup;
        this.clock = clock;
    }

    /**
     * 기록을 먼저 만들고(커밋) 책 정보를 가져온다. 제공자가 죽었거나 모르는 책이거나
     * 사용자 상한에 걸려도 기록은 남는다 — 책 정보는 백필이 나중에 붙인다(설계 문서 5장).
     */
    public RecordView create(long memberId, String rawIsbn, ReadingStatus status) {
        Isbn13 isbn = Isbn13.parse(rawIsbn);
        if (status == null) {
            throw new com.bookshelf.common.DomainException("읽는 상태가 없다");
        }
        ReadingRecord r = writer.create(members.active(memberId), isbn, status, clock.instant());
        BookView book = null;
        try {
            book = lookup.findOrFetch(isbn, CallPurpose.INTERACTIVE).orElse(null);
        } catch (ProviderUnavailableException | UserQuotaExceededException e) {
            log.info("기록은 만들고 책 정보는 나중에: isbn={} reason={}", isbn.value(), e.getMessage());
        }
        return RecordView.of(r, book);
    }

    public List<RecordView> list(long memberId) {
        members.active(memberId);
        List<ReadingRecord> mine = records.findByMemberIdOrderByCreatedAtDescIdDesc(memberId);
        Map<String, BookView> byIsbn = books.findAllById(mine.stream().map(ReadingRecord::getIsbn13).toList()).stream()
                .collect(Collectors.toMap(Book::getIsbn13, BookView::of, (a, b) -> a));
        return mine.stream().map(r -> RecordView.of(r, byIsbn.get(r.getIsbn13()))).toList();
    }

    public RecordView get(long memberId, long id) {
        members.active(memberId);
        ReadingRecord r = writer.owned(memberId, id);
        return RecordView.of(r, books.findById(r.getIsbn13()).map(BookView::of).orElse(null));
    }

    public RecordView update(long memberId, long id, RecordWriter.Update u) {
        members.active(memberId);
        ReadingRecord r = writer.update(memberId, id, u, clock.instant());
        return RecordView.of(r, books.findById(r.getIsbn13()).map(BookView::of).orElse(null));
    }

    public void delete(long memberId, long id) {
        members.active(memberId);
        writer.delete(memberId, id);
    }
}
