package com.bookshelf.library.application;

import com.bookshelf.library.HoldingState;

import java.time.Instant;

/** fetchedAt: 정보나루에서 가져온 시각. 값 자체는 "전날 기준"이다 — 앱이 그렇게 적는다. UNKNOWN이면 null */
public record HoldingView(String libCode, String name, HoldingState state, Instant fetchedAt) {
}
