package com.bookshelf.record.domain;

/** 설계 문서 7-3. 기본값은 PRIVATE — 초기 버그가 나도 아무것도 새지 않는 쪽 */
public enum Visibility {
    PUBLIC,        // 표지·별점·한줄평
    RATING_ONLY,   // 서재에 표지·별점만. 책 페이지에는 아무것도 실리지 않는다
    PRIVATE        // 읽었다는 사실조차 숨긴다
}
