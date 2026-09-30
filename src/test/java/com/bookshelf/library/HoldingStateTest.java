package com.bookshelf.library;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingStateTest {

    /** 실측: 대출 가능(Y,Y) / 대출 중(Y,N) / 미소장(N,N). 모르는 ISBN도 (N,N) — docs/notes/providers.md */
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "Y, Y, AVAILABLE",
            "Y, N, ON_LOAN",
            "N, N, NOT_HELD",
            "N, Y, NOT_HELD",     // 소장하지 않았는데 대출 가능 — 말이 안 되는 조합은 소장 쪽을 믿는다
            "X, Y, UNKNOWN",
            "null, null, UNKNOWN"
    })
    void 두_값을_세_상태로_옮긴다(String hasBook, String loanAvailable, HoldingState expected) {
        assertThat(HoldingState.from(hasBook, loanAvailable)).isEqualTo(expected);
    }
}
