package com.bookshelf.links.domain;

/** "그 서점에서 이 책 검색하기" — 그 서점에 책이 있다는 뜻이 아니다 */
public record StoreLink(String name, String url) {
}
