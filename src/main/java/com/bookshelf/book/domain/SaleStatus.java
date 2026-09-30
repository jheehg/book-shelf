package com.bookshelf.book.domain;

/**
 * 새 책 판매 상태 (설계 문서 2-1). 카카오 실측에서 관측된 값은 "정상판매"·"주문판매"·빈 문자열뿐이다.
 * 빈 값을 절판으로 단정하지 않는다 — 모르면 UNKNOWN("판매 정보 없음")이다.
 */
public enum SaleStatus {
    ON_SALE,   // 판매 중
    ORDER,     // 주문 후 발송
    UNKNOWN    // 판매 정보 없음
}
