package com.bookshelf.search;

import java.time.Instant;
import java.util.List;

/** fetchedAt: 이 결과를 제공자에서 가져온 시각. 캐시 결과면 과거다 — 앱은 "N시간 전 기준"을 보여준다 */
public record SearchResponse(List<SearchItem> items, int total, boolean isEnd, Instant fetchedAt) {
}
