package com.bookshelf.library;

import com.bookshelf.common.Isbn13;
import com.bookshelf.provider.CallPurpose;

/** 빌리기 칸의 제공자. 실패는 ProviderUnavailableException(예산 소진 포함) 또는 UserQuotaExceededException */
public interface LibraryAvailability {

    HoldingState holding(String libCode, Isbn13 isbn, CallPurpose purpose);
}
