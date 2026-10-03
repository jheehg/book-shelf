package com.bookshelf.provider.domain;

/**
 * "지금은 이 제공자에게 물을 수 없다." 원인(타임아웃·5xx·파싱 실패·쿨다운)은 메시지와 cause에만 있다.
 * 호출하는 쪽이 할 일은 원인과 상관없이 같다 — 가진 것으로 보여주고 그 칸만 "확인 불가"다.
 */
public class ProviderUnavailableException extends RuntimeException {

    private final Provider provider;

    public ProviderUnavailableException(Provider provider, String message) {
        super(provider + ": " + message);
        this.provider = provider;
    }

    public ProviderUnavailableException(Provider provider, String message, Throwable cause) {
        super(provider + ": " + message, cause);
        this.provider = provider;
    }

    public Provider provider() {
        return provider;
    }
}
