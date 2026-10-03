package com.bookshelf.book.infrastructure.provider.kakao;

import com.bookshelf.common.Isbn13;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 카카오 isbn 필드 → 13자리 하나 (설계 문서 5장 ISBN 정규화).
 * 공백으로 나눠 13자리가 있으면 그것을, 없거나 틀리면 10자리를 13자리로 바꿔 쓴다. 체크섬이 틀리면 버린다.
 */
final class KakaoIsbn {

    private KakaoIsbn() {
    }

    static Optional<Isbn13> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        List<String> parts = Arrays.stream(raw.strip().split("\\s+")).filter(s -> !s.isEmpty()).toList();
        Optional<Isbn13> thirteen = parts.stream().filter(s -> s.length() == 13)
                .map(Isbn13::tryParse).flatMap(Optional::stream).findFirst();
        if (thirteen.isPresent()) {
            return thirteen;
        }
        return parts.stream().filter(s -> s.length() == 10)
                .map(Isbn13::tryParse).flatMap(Optional::stream).findFirst();
    }
}
