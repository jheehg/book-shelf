package com.bookshelf.auth.application;

import com.bookshelf.auth.infrastructure.provider.apple.AppleTokenRevoker;
import com.bookshelf.common.DomainException;
import com.bookshelf.member.infrastructure.persistence.MemberDeletion;
import com.bookshelf.member.application.MemberService;
import com.bookshelf.member.domain.Member;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** @Transactional 없음 — Apple 호출(외부)을 DB 트랜잭션 안에 두지 않는다. DB 삭제는 MemberDeletion 한 트랜잭션 */
@Slf4j
@Service
public class AccountDeletionService {

    private final MemberService members;
    private final AppleTokenRevoker apple;
    private final MemberDeletion deletion;
    private final Clock clock;

    public AccountDeletionService(MemberService members, AppleTokenRevoker apple, MemberDeletion deletion, Clock clock) {
        this.members = members;
        this.apple = apple;
        this.deletion = deletion;
        this.clock = clock;
    }

    /** Apple 철회가 끝나야 지운다. Apple이 거부하거나 죽었으면 예외가 나가고 아무것도 지우지 않는다 */
    public void delete(long memberId, String authorizationCode) {
        Member member = members.active(memberId);
        // Apple로 가입하지 않은 회원(개발 로그인, SQL로 만든 회원)은 끊을 Apple 연결이 없다
        if (member.getAppleSub() != null) {
            if (authorizationCode == null || authorizationCode.isBlank()) {
                throw new DomainException("Apple 인증 코드가 없다");
            }
            apple.revokeWithCode(authorizationCode);
        }
        deletion.delete(memberId, clock.instant());
        log.info("회원 {} 탈퇴", memberId);
    }
}
