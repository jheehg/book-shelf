package com.bookshelf.member.application;

import com.bookshelf.member.domain.Member;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SlugGeneratorTest {

    @Test
    void reader_뒤에_소문자와_숫자_6자이고_slug_규칙을_지킨다() {
        SlugGenerator g = new SlugGenerator(slug -> false);

        String slug = g.next();

        assertThat(slug).matches("reader-[a-z0-9]{6}");
        Member.register(slug, "독자", Instant.now());   // 규칙을 어기면 여기서 예외
    }

    @Test
    void 이미_있는_slug면_다시_뽑는다() {
        // 처음 한 번은 반드시 겹치게 만든다
        int[] calls = {0};
        SlugGenerator g = new SlugGenerator(s -> calls[0]++ == 0);

        assertThat(g.next()).isNotNull();
        assertThat(calls[0]).isEqualTo(2);
    }
}
