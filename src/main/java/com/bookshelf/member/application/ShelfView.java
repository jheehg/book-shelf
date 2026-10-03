package com.bookshelf.member.application;

/**
 * 공유 대화상자에 필요한 것. visibleCount는 서재가 열렸을 때 보이는 기록 수(PUBLIC·RATING_ONLY)다.
 * 앱은 이 숫자를 내 화면에만 쓴다. 공개 페이지의 집계는 publicview가 따로 센다.
 */
public record ShelfView(boolean shelfPublic, String url, int doneCount, int visibleCount) {
}
