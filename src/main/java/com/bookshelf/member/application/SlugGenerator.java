package com.bookshelf.member.application;

import com.bookshelf.member.infrastructure.persistence.MemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.function.Predicate;

/**
 * 가입 시 자동 slug. 가입이 3초 기록을 막지 않게 한다 — 원하는 주소는 나중에 바꾼다(Task 5).
 * 36^6 ≈ 21억 가지라 겹칠 일은 드물지만, 겹치면 다시 뽑는다.
 */
@Component
public class SlugGenerator {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_TRIES = 10;
    private final SecureRandom random = new SecureRandom();
    private final Predicate<String> taken;

    @Autowired
    public SlugGenerator(MemberRepository members) {
        this(members::existsBySlug);
    }

    SlugGenerator(Predicate<String> taken) {
        this.taken = taken;
    }

    public String next() {
        for (int i = 0; i < MAX_TRIES; i++) {
            StringBuilder sb = new StringBuilder("reader-");
            for (int j = 0; j < 6; j++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String slug = sb.toString();
            if (!taken.test(slug)) {
                return slug;
            }
        }
        throw new IllegalStateException("slug를 " + MAX_TRIES + "번 뽑아도 겹쳤다");
    }
}
