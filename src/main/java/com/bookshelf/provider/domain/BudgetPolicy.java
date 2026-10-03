package com.bookshelf.provider.domain;

import java.time.ZoneId;

/**
 * 제공자 하나의 예산. 공식 한도의 90%/60% (설계 문서 6-3).
 * resetZone은 그 제공자의 한도 초기화 시간대다 — 우리 서버의 시간대가 아니다.
 */
public record BudgetPolicy(Provider provider, int dailyLimit, int backgroundCeiling, ZoneId resetZone) {

    public int limitFor(CallPurpose purpose) {
        return purpose == CallPurpose.INTERACTIVE ? dailyLimit : backgroundCeiling;
    }
}
