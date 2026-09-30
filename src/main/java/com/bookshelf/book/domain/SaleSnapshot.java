package com.bookshelf.book.domain;

import com.bookshelf.common.Isbn13;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/** 틀려도 되는 데이터. 대신 fetchedAt을 화면에 반드시 같이 보여준다 */
@Entity
@Table(name = "sale_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleSnapshot {

    @Id
    private String isbn13;
    private Integer listPrice;
    private Integer salePrice;
    @Enumerated(EnumType.STRING)
    private SaleStatus status;
    private Instant fetchedAt;

    public static SaleSnapshot of(Isbn13 isbn, Integer listPrice, Integer salePrice,
                                  SaleStatus status, Instant fetchedAt) {
        SaleSnapshot s = new SaleSnapshot();
        s.isbn13 = isbn.value();
        s.listPrice = listPrice;
        s.salePrice = salePrice;
        s.status = status;
        s.fetchedAt = fetchedAt;
        return s;
    }

    public void replaceWith(SaleSnapshot fetched) {
        this.listPrice = fetched.listPrice;
        this.salePrice = fetched.salePrice;
        this.status = fetched.status;
        this.fetchedAt = fetched.fetchedAt;
    }

    public boolean isFreshAt(Instant now, Duration ttl) {
        return fetchedAt.plus(ttl).isAfter(now);
    }
}
