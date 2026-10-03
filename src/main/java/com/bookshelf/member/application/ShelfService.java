package com.bookshelf.member.application;

import com.bookshelf.common.BookshelfProperties;
import com.bookshelf.common.DomainException;
import com.bookshelf.member.domain.Member;
import com.bookshelf.member.infrastructure.persistence.ActiveMemberLock;
import com.bookshelf.member.infrastructure.persistence.ShelfPersistence;
import com.bookshelf.record.domain.Visibility;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 공유 흐름. 일괄 전환은 읽음(DONE) 기록만 바꾼다. 읽고 싶다·읽는 중과 회원의 기본 공개 범위는 그대로다.
 */
@Service
public class ShelfService {

    private final MemberService members;
    private final ActiveMemberLock memberLock;
    private final ShelfPersistence shelfPersistence;
    private final BookshelfProperties properties;
    private final Clock clock;

    public ShelfService(MemberService members, ActiveMemberLock memberLock, ShelfPersistence shelfPersistence,
                        BookshelfProperties properties, Clock clock) {
        this.members = members;
        this.memberLock = memberLock;
        this.shelfPersistence = shelfPersistence;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ShelfView view(long memberId) {
        return view(members.active(memberId));
    }

    @Transactional
    public ShelfView publish(long memberId, Visibility mode) {
        if (mode != Visibility.PUBLIC && mode != Visibility.RATING_ONLY) {
            throw new DomainException("공개 방식은 PUBLIC 또는 RATING_ONLY다: " + mode);
        }
        memberLock.lock(memberId);
        Member member = members.active(memberId);
        shelfPersistence.publishCompletedRecords(memberId, mode, clock.instant());
        member.openShelf();
        return view(member);
    }

    /** 기록의 visibility는 그대로 둔다. 다시 열면 닫기 전처럼 보인다. */
    @Transactional
    public ShelfView close(long memberId) {
        Member member = members.active(memberId);
        member.closeShelf();
        return view(member);
    }

    private ShelfView view(Member member) {
        ShelfPersistence.Counts counts = shelfPersistence.counts(member.getId());
        return new ShelfView(member.isShelfPublic(), properties.siteUrl() + "/u/" + member.getSlug(),
                counts.done(), counts.visible());
    }
}
