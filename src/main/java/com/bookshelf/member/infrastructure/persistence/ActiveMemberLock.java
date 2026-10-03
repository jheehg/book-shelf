package com.bookshelf.member.infrastructure.persistence;

import com.bookshelf.auth.application.UnauthorizedException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 회원이 남기는 행을 쓰기 직전, 쓰는 트랜잭션 안에서 부른다. 회원 행을 FOR SHARE로 잠그고 탈퇴 여부를 다시 본다.
 * 탈퇴(MemberDeletion)는 같은 행을 FOR UPDATE로 잠그므로 둘은 줄을 선다 —
 * 쓰기가 먼저면 탈퇴가 그 행까지 지우고, 탈퇴가 먼저면 쓰기가 여기서 멈춘다.
 */
@Component
public class ActiveMemberLock {

    private final JdbcClient jdbc;

    public ActiveMemberLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(long memberId) {
        boolean active = jdbc.sql("SELECT deleted_at IS NULL FROM member WHERE id = :m FOR SHARE")
                .param("m", memberId).query(Boolean.class).optional().orElse(false);
        if (!active) {
            throw new UnauthorizedException("없거나 탈퇴한 회원의 쓰기: " + memberId);
        }
    }
}
