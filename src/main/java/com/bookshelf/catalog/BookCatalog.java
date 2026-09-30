package com.bookshelf.catalog;

import com.bookshelf.common.Isbn13;
import com.bookshelf.provider.CallPurpose;

import java.util.Optional;

/**
 * 책 정보의 유일한 입구 (설계 문서 6-2). 서비스는 이 인터페이스만 안다 — 제공자를 바꾸면 구현만 바꾼다.
 *
 * lookup: 제공자가 모르는 책이면 empty.
 * 둘 다: 물을 수 없으면 ProviderUnavailableException (예산 소진은 하위 타입 BudgetExhaustedException).
 */
public interface BookCatalog {

    int PAGE_SIZE = 10;   // 휴대폰 한 화면이 5~6권이다 (설계 문서 2-1)

    Optional<CatalogBook> lookup(Isbn13 isbn, CallPurpose purpose);

    CatalogPage search(String query, int page, CallPurpose purpose);
}
