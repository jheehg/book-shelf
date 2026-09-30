package com.bookshelf.member.application;

import com.bookshelf.member.domain.Member;
import com.bookshelf.member.infrastructure.MemberRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 회원이 남긴 것을 한 트랜잭션에서 지운다. 테이블을 추가해 member_id를 담으면 여기에도 더한다
 * (AccountDeletionTest가 테이블별로 확인한다).
 */
@Component
public class MemberDeletion {

    private static final String[] OWNED_TABLES = {"reading_record", "refresh_token", "user_call_counter",
            "member_library", "member_library_change"};

    private final JdbcClient jdbc;
    private final MemberRepository members;

    public MemberDeletion(JdbcClient jdbc, MemberRepository members) {
        this.jdbc = jdbc;
        this.members = members;
    }

    @Transactional
    public void delete(long memberId, Instant now) {
        // 회원 행을 먼저 잠근다. 진행 중인 refresh 회전·기록 생성·도서관 변경(모두 이 행을 잠근다)이 끝난 뒤에 지우고,
        // 탈퇴가 끝난 뒤에 시작된 것은 deleted_at을 보고 멈춘다
        jdbc.sql("SELECT id FROM member WHERE id = :m FOR UPDATE").param("m", memberId).query(Long.class).single();
        for (String table : OWNED_TABLES) {
            jdbc.sql("DELETE FROM " + table + " WHERE member_id = :m").param("m", memberId).update();
        }
        Member m = members.findById(memberId).orElseThrow();
        m.erase(now);
    }
}
