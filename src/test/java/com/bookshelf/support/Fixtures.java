package com.bookshelf.support;

import com.bookshelf.book.domain.Book;
import com.bookshelf.book.infrastructure.BookRepository;
import com.bookshelf.common.Isbn13;
import com.bookshelf.member.domain.Member;
import com.bookshelf.member.infrastructure.MemberRepository;
import com.bookshelf.record.domain.ReadingRecord;
import com.bookshelf.record.domain.ReadingStatus;
import com.bookshelf.record.domain.Visibility;
import com.bookshelf.record.infrastructure.ReadingRecordRepository;
import org.springframework.boot.test.context.TestComponent;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 통합 테스트용 데이터 생성기. 테스트 클래스에 @Import(Fixtures.class)로 붙인다.
 * @TestComponent는 컴포넌트 스캔에서 빠진다 — 모든 테스트 컨텍스트에 끼어들지 않게 한다.
 */
@TestComponent
public class Fixtures {

    public static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private final MemberRepository members;
    private final ReadingRecordRepository records;
    private final BookRepository books;

    public Fixtures(MemberRepository members, ReadingRecordRepository records, BookRepository books) {
        this.members = members;
        this.records = records;
        this.books = books;
    }

    public Member member(String slug, boolean shelfPublic) {
        Member m = Member.register(slug, slug + "의 이름", T0);
        if (shelfPublic) {
            m.openShelf();
        }
        return members.save(m);
    }

    public Member withdraw(Member m) {
        m.withdraw(T0);
        return members.save(m);
    }

    public ReadingRecord record(Member m, String isbn, Visibility visibility, Integer rating, String review) {
        ReadingRecord r = ReadingRecord.create(m.getId(), Isbn13.parse(isbn), ReadingStatus.DONE, visibility, T0);
        r.rate(rating);
        r.writeReview(review, T0);
        return records.save(r);
    }

    /** 쪽수 216으로 만든다 — "이미 아는 쪽수를 지우지 않는다" 테스트가 이 값에 기댄다 */
    public Book book(String isbn, String title) {
        return books.save(Book.of(Isbn13.parse(isbn), title, "저자", "출판사",
                LocalDate.of(2014, 5, 19), "https://image.example/cover.jpg", 216, T0));
    }
}
