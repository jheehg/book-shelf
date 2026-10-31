package com.bookshelf.member;

import com.bookshelf.member.application.MemberService;
import com.bookshelf.member.application.ShelfService;
import com.bookshelf.member.domain.Member;
import com.bookshelf.member.infrastructure.persistence.MemberDeletion;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 트랜잭션이 회원 행을 잡고 있는 동안 회원 정보를 고치는 요청이 들어온 경우.
 * 수정이 잠그지 않고 읽으면 탈퇴 전 값으로 행 전체를 덮어써 deleted_at·apple_sub가 되살아난다.
 */
@SpringBootTest
@Import(Fixtures.class)
class MemberEditDuringDeletionTest {

    @Autowired JdbcClient jdbc;
    @Autowired Fixtures fx;
    @Autowired MemberService members;
    @Autowired ShelfService shelves;
    @Autowired MemberDeletion deletion;
    @Autowired TransactionTemplate tx;

    private long id;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        Member m = fx.member("leaving", true);
        id = m.getId();
        jdbc.sql("UPDATE member SET apple_sub = 'apple-sub-leaving' WHERE id = :id").param("id", id).update();
    }

    /** 탈퇴가 행을 잠근 사이 edit을 시작하고, edit이 막히거나 읽기를 끝낼 시간을 준 뒤 탈퇴를 커밋한다 */
    private void deleteWhile(Runnable edit) throws Exception {
        CompletableFuture<Void>[] running = new CompletableFuture[1];
        tx.executeWithoutResult(status -> {
            jdbc.sql("SELECT id FROM member WHERE id = :id FOR UPDATE").param("id", id).query(Long.class).single();
            running[0] = CompletableFuture.runAsync(edit);
            sleep(500);
            deletion.delete(id, Instant.now());
        });
        try {
            running[0].get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 탈퇴가 먼저 끝났으니 수정은 거부돼야 한다 — 거부 여부가 아니라 남은 행을 본다
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertStillDeleted() {
        Map<String, Object> row = jdbc.sql("SELECT deleted_at, apple_sub, display_name FROM member WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(row.get("deleted_at")).as("deleted_at").isNotNull();
        assertThat(row.get("apple_sub")).as("apple_sub").isNull();
        assertThat(row.get("display_name")).isEqualTo("탈퇴한 회원");
    }

    @Test
    void 탈퇴_중에_들어온_이름_변경이_탈퇴를_되돌리지_않는다() throws Exception {
        deleteWhile(() -> members.update(id, null, "새 이름", null));

        assertStillDeleted();
    }

    @Test
    void 탈퇴_중에_들어온_서재_닫기가_탈퇴를_되돌리지_않는다() throws Exception {
        deleteWhile(() -> shelves.close(id));

        assertStillDeleted();
    }
}
