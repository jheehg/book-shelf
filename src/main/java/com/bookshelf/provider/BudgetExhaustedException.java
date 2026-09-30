package com.bookshelf.provider;

/** 우리가 정한 일일 상한에 닿았다. 백필·갱신 큐는 이것을 받으면 그 회차를 멈춘다 */
public class BudgetExhaustedException extends ProviderUnavailableException {

    public BudgetExhaustedException(Provider provider, CallPurpose purpose) {
        super(provider, "호출 예산 소진 (" + purpose + ")");
    }
}
