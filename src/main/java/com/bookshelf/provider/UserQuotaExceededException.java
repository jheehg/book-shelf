package com.bookshelf.provider;

/**
 * 한 사용자가 오늘 제공자를 너무 많이 불렀다. 그 사용자만 "잠시 후 다시"다.
 * ProviderUnavailableException의 하위로 두지 않는다 — 하위면 검색이 지난 캐시로 조용히 대신해
 * 사용자도 우리도 상한에 걸렸다는 사실을 모른다.
 */
public class UserQuotaExceededException extends RuntimeException {
    public UserQuotaExceededException(long memberId) {
        super("사용자 " + memberId + "의 오늘 제공자 호출 상한 초과");
    }
}
