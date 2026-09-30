package com.bookshelf.provider;

/** 누가 기다리는가. 예산 상한과 실패 시 동작이 이것으로 갈린다 */
public enum CallPurpose {
    /** 앱 사용자가 화면 앞에서 기다린다 — 스캔, 검색 */
    INTERACTIVE,
    /** 아무도 기다리지 않는다 — 공개 페이지 갱신 큐, 백필 */
    BACKGROUND
}
