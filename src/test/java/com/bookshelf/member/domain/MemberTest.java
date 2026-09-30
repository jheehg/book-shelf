package com.bookshelf.member.domain;

import com.bookshelf.common.DomainException;
import com.bookshelf.record.domain.Visibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void 가입하면_서재는_닫혀_있고_기본_공개범위는_비공개다() {
        Member m = Member.register("juhee", "주희", NOW);
        assertThat(m.isShelfPublic()).isFalse();
        assertThat(m.getDefaultVisibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(m.getDeletedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "-juhee", "juhee-", "Juhee", "ju hee", "주희"})
    void slug_규칙을_어기면_거부한다(String slug) {
        assertThatThrownBy(() -> Member.register(slug, "주희", NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void slug는_30자까지다() {
        assertThat(Member.register("a".repeat(30), "주희", NOW).getSlug()).hasSize(30);
        assertThatThrownBy(() -> Member.register("a".repeat(31), "주희", NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void 서재를_열고_닫는다() {
        Member m = Member.register("juhee", "주희", NOW);
        m.openShelf();
        assertThat(m.isShelfPublic()).isTrue();
        m.closeShelf();
        assertThat(m.isShelfPublic()).isFalse();
    }

    @Test
    void 탈퇴하면_서재도_닫힌다() {
        Member m = Member.register("juhee", "주희", NOW);
        m.openShelf();
        m.withdraw(NOW);
        assertThat(m.getDeletedAt()).isEqualTo(NOW);
        assertThat(m.isShelfPublic()).isFalse();
    }
}
