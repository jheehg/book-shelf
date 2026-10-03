package com.bookshelf.member.application;

import com.bookshelf.common.BookshelfProperties;
import com.bookshelf.common.DomainException;
import com.bookshelf.member.domain.Member;
import com.bookshelf.record.domain.Visibility;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * 공유 흐름. 일괄 전환은 읽음(DONE) 기록만 바꾼다. 읽고 싶다·읽는 중과 회원의 기본 공개 범위는 그대로다.
 */
@Service
public class ShelfService {

    private final MemberService members;
    private final ActiveMemberLock memberLock;
    private final JdbcClient jdbc;
    private final BookshelfProperties properties;
    private final Clock clock;

    public ShelfService(MemberService members, ActiveMemberLock memberLock, JdbcClient jdbc,
                        BookshelfProperties properties, Clock clock) {
        this.members = members;
        this.memberLock = memberLock;
        this.jdbc = jdbc;
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
        jdbc.sql("""
                UPDATE reading_record SET visibility = :visibility, updated_at = :now
                WHERE member_id = :memberId AND status = 'DONE'
                """)
                .param("visibility", mode.name())
                .param("now", Timestamp.from(clock.instant()))
                .param("memberId", memberId)
                .update();
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
        record Counts(int done, int visible) {
        }
        Counts counts = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'DONE') AS done,
                       count(*) FILTER (WHERE visibility IN ('PUBLIC', 'RATING_ONLY')) AS visible
                FROM reading_record WHERE member_id = :memberId
                """)
                .param("memberId", member.getId())
                .query((rs, rowNum) -> new Counts(rs.getInt("done"), rs.getInt("visible")))
                .single();
        return new ShelfView(member.isShelfPublic(), properties.siteUrl() + "/u/" + member.getSlug(),
                counts.done(), counts.visible());
    }
}
