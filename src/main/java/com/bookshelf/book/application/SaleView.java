package com.bookshelf.book.application;

import com.bookshelf.book.domain.SaleSnapshot;
import com.bookshelf.book.domain.SaleStatus;

import java.time.Instant;

/** fetchedAt이 null이면 "확인 불가". 화면은 fetchedAt을 반드시 같이 보여준다 */
public record SaleView(SaleStatus status, Integer listPrice, Integer salePrice, Instant fetchedAt) {

    public static SaleView unavailable() {
        return new SaleView(null, null, null, null);
    }

    static SaleView of(SaleSnapshot s) {
        return new SaleView(s.getStatus(), s.getListPrice(), s.getSalePrice(), s.getFetchedAt());
    }

    public boolean available() {
        return fetchedAt != null;
    }
}
