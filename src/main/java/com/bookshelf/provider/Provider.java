package com.bookshelf.provider;

/** 외부 제공자. 설계 문서 6-2의 표와 같다 */
public enum Provider {
    KAKAO,         // 책 정보·검색·새 책 상태
    LIBRARY,       // 도서관 정보나루 — 빌리기 (Phase 2)
    GOOGLE_BOOKS   // 전자책 가격 (Phase 3)
}
