package com.bookshelf.record.application;

import com.bookshelf.common.ConflictException;
import com.bookshelf.common.Isbn13;
import com.bookshelf.common.NotFoundException;
import com.bookshelf.member.application.ActiveMemberLock;
import com.bookshelf.member.domain.Member;
import com.bookshelf.record.domain.ReadingRecord;
import com.bookshelf.record.domain.ReadingStatus;
import com.bookshelf.record.domain.Visibility;
import com.bookshelf.record.infrastructure.ReadingRecordRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/** 기록 쓰기. 제공자 호출(책 정보)은 RecordService가 트랜잭션 밖에서 한다 */
@Component
public class RecordWriter {

    public record Update(ReadingStatus status, Integer rating, String review, Visibility visibility,
                         LocalDate startedAt, LocalDate finishedAt) {
    }

    private final ReadingRecordRepository records;
    private final ActiveMemberLock memberLock;

    public RecordWriter(ReadingRecordRepository records, ActiveMemberLock memberLock) {
        this.records = records;
        this.memberLock = memberLock;
    }

    @Transactional
    public ReadingRecord create(Member member, Isbn13 isbn, ReadingStatus status, Instant now) {
        memberLock.lock(member.getId());   // 탈퇴와 겹치면 기록이 고아로 남지 않게
        if (records.existsByMemberIdAndIsbn13(member.getId(), isbn.value())) {
            throw new ConflictException("이미 기록한 책이에요");
        }
        return records.save(ReadingRecord.create(member.getId(), isbn, status, member.getDefaultVisibility(), now));
    }

    /** 전체를 바꾼다(PUT). null인 별점·한줄평·날짜는 지운다 */
    @Transactional
    public ReadingRecord update(long memberId, long id, Update u, Instant now) {
        ReadingRecord r = owned(memberId, id);
        r.changeStatus(u.status(), now);
        r.rate(u.rating());
        r.writeReview(u.review(), now);
        if (u.visibility() == null) {
            throw new com.bookshelf.common.DomainException("공개 범위가 없다");
        }
        r.changeVisibility(u.visibility(), now);
        r.readBetween(u.startedAt(), u.finishedAt(), now);
        return r;
    }

    @Transactional
    public void delete(long memberId, long id) {
        records.delete(owned(memberId, id));
    }

    ReadingRecord owned(long memberId, long id) {
        return records.findByIdAndMemberId(id, memberId).orElseThrow(() -> new NotFoundException("기록 " + id));
    }
}
