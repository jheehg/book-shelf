package com.bookshelf.library.domain;

import com.bookshelf.library.application.LibrarySync;

import com.bookshelf.provider.domain.CallPurpose;

/** 지역(시도 코드)별 도서관 목록. 동기화(LibrarySync)만 부른다 — 사용자의 도서관 검색은 로컬 테이블에서 한다 */
public interface LibraryDirectory {

    LibraryPage libraries(int region, int pageNo, int pageSize, CallPurpose purpose);
}
