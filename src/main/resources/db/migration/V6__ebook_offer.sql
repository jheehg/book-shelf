-- V6: Google Play 전자책 가격. 24시간 캐시하며 같은 책 없음도 저장한다.
CREATE TABLE ebook_offer (
    isbn13      VARCHAR(13) PRIMARY KEY CHECK (isbn13 ~ '^97[89][0-9]{10}$'),
    matched     BOOLEAN NOT NULL,
    price       INTEGER CHECK (price > 0),
    link        TEXT,
    fetched_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_ebook_price CHECK (matched = (price IS NOT NULL))
);
