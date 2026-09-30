package com.bookshelf.member.application;

import com.bookshelf.auth.UnauthorizedException;
import com.bookshelf.common.ConflictException;
import com.bookshelf.member.domain.Member;
import com.bookshelf.member.infrastructure.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    private final MemberRepository members;

    public MemberService(MemberRepository members) {
        this.members = members;
    }

    /** access 토큰은 30분 동안 유효하다. 그 사이 탈퇴했으면 토큰이 남아 있어도 통하지 않게 한다 */
    public Member active(long memberId) {
        return members.findById(memberId).filter(m -> !m.isDeleted())
                .orElseThrow(() -> new UnauthorizedException("없거나 탈퇴한 회원의 토큰: " + memberId));
    }

    @Transactional(readOnly = true)
    public MeView me(long memberId) {
        return MeView.of(active(memberId));
    }

    /** slug를 바꾸면 옛 서재 링크는 404가 된다 — 앱이 바꾸기 전에 알린다(Phase 3) */
    @Transactional
    public MeView update(long memberId, String slug, String displayName) {
        Member m = active(memberId);
        if (slug != null && !slug.equals(m.getSlug())) {
            if (members.existsBySlug(slug)) {
                throw new ConflictException("이미 쓰는 주소예요");
            }
            m.changeSlug(slug);
        }
        if (displayName != null) {
            m.rename(displayName);
        }
        return MeView.of(m);
    }
}
