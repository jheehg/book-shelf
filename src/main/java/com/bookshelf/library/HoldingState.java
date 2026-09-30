package com.bookshelf.library;

/** 빌리기 칸의 한 도서관. UNKNOWN은 "확인 불가"(제공자 장애·처음 보는 값) */
public enum HoldingState {
    AVAILABLE,   // 대출 가능
    ON_LOAN,     // 소장, 대출 중
    NOT_HELD,    // 소장하지 않음
    UNKNOWN;

    public static HoldingState from(String hasBook, String loanAvailable) {
        if ("N".equals(hasBook)) {
            return NOT_HELD;
        }
        if ("Y".equals(hasBook) && "Y".equals(loanAvailable)) {
            return AVAILABLE;
        }
        if ("Y".equals(hasBook) && "N".equals(loanAvailable)) {
            return ON_LOAN;
        }
        return UNKNOWN;
    }
}
