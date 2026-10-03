package com.bookshelf.ebook;

import java.util.List;

/**
 * Google Books 결과 한 건에서 같은 책 확인에 쓰는 것만. 제공자를 바꿔도 이 모양은 그대로다.
 * ebookIsbn13이 없으면 volumeId로 같은 전자책을 가린다. link는 그 책의 Google Books 페이지다.
 */
public record EbookCandidate(String volumeId, String title, List<String> authors, String language,
                             boolean ebook, Integer retailPrice, String currency, String ebookIsbn13, String link) {
}
