package com.bookshelf.book.application;

/** 앱의 책 상세 화면. 판매 상태는 캐시만 읽는다 — 오래됐으면 갱신 큐에 넣고 지난 값을 준다 */
public record BookDetail(BookView book, SaleView sale) {
}
