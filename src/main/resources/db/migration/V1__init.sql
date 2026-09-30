-- V1: 초기 스키마 (Phase 1에서 쓰는 테이블만. 도서관·전자책 테이블은 실측 뒤 V2 이후로).
-- 첫 배포 이후에는 절대 이 파일을 고치지 않는다. 변경은 V2부터.

-- ── 내 데이터 ──────────────────────────────────────────────
CREATE TABLE member (
    id                  BIGSERIAL    PRIMARY KEY,
    slug                VARCHAR(30)  NOT NULL UNIQUE,   -- 공개 URL. 공개 조회의 유일한 입구
    display_name        VARCHAR(30)  NOT NULL,
    shelf_public        BOOLEAN      NOT NULL DEFAULT FALSE,  -- 서재 페이지 자체를 여닫는 스위치
    default_visibility  VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE',
    created_at          TIMESTAMPTZ  NOT NULL,
    deleted_at          TIMESTAMPTZ                     -- 탈퇴. 공개 조회는 이 컬럼이 NULL인 행만 본다
);

-- ★ 절대 잃으면 안 되는 것. book에 FK를 걸지 않는다 — 책 정보는 나중에 붙는다
CREATE TABLE reading_record (
    id           BIGSERIAL    PRIMARY KEY,
    member_id    BIGINT       NOT NULL,
    isbn13       VARCHAR(13)  NOT NULL,
    status       VARCHAR(10)  NOT NULL,
    rating       INTEGER      CHECK (rating BETWEEN 1 AND 5),
    review       VARCHAR(200),
    started_at   DATE,
    finished_at  DATE,
    visibility   VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_record_member_isbn UNIQUE (member_id, isbn13)
);
CREATE INDEX idx_record_isbn ON reading_record (isbn13);   -- 책 페이지의 한줄평 조회

-- ── 남의 데이터 (제공자 응답의 캐시) ──────────────────────────
CREATE TABLE book (
    isbn13       VARCHAR(13)   PRIMARY KEY,
    title        VARCHAR(500)  NOT NULL,
    author       VARCHAR(500),
    publisher    VARCHAR(200),
    pub_date     DATE,
    cover_url    VARCHAR(1000),
    page_count   INTEGER,          -- 카카오에는 없다. 정보나루가 주는지는 가정 (설계 문서 5장)
    fetched_at   TIMESTAMPTZ   NOT NULL
);

-- 원화 정수. 산술에 쓰지 않는 표시용 남의 데이터라 NUMERIC 대신 INTEGER. 없으면 NULL (-1원·0원을 저장하지 않는다)
CREATE TABLE sale_snapshot (
    isbn13       VARCHAR(13)  PRIMARY KEY,
    list_price   INTEGER,
    sale_price   INTEGER,
    status       VARCHAR(10)  NOT NULL,      -- ON_SALE / ORDER / UNKNOWN
    fetched_at   TIMESTAMPTZ  NOT NULL
);

-- ── 우리의 장부 ────────────────────────────────────────────
-- 제공자가 모르는 ISBN을 백필이 매번 다시 묻지 않게 한다 (BookBackfill)
CREATE TABLE book_lookup_failure (
    isbn13       VARCHAR(13)  PRIMARY KEY,
    attempts     INTEGER      NOT NULL,
    next_try_at  TIMESTAMPTZ  NOT NULL
);

-- 공개 페이지가 넣고 백그라운드 작업이 뺀다. ISBN당 한 줄 — 봇이 같은 페이지를 백 번 열어도 한 줄 (RefreshQueue)
CREATE TABLE refresh_request (
    isbn13        VARCHAR(13)  PRIMARY KEY,
    requested_at  TIMESTAMPTZ  NOT NULL
);

-- 정규화한 검색어 → 결과 ISBN 목록. 책 정보는 book 테이블에 있다 (SearchService)
CREATE TABLE search_cache (
    query_key   VARCHAR(200)  NOT NULL,
    page        INTEGER       NOT NULL,
    isbns       TEXT          NOT NULL,   -- 쉼표로 이은 isbn13, 결과 순서 그대로
    total       INTEGER       NOT NULL,
    fetched_at  TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (query_key, page)
);

-- 오늘 제공자별로 몇 번 불렀는가. 설계 문서 8장 "관측"의 전부 (CallBudget)
CREATE TABLE provider_call_counter (
    provider  VARCHAR(20)  NOT NULL,
    day       DATE         NOT NULL,      -- 그 제공자의 한도 초기화 시간대 기준 날짜
    calls     INTEGER      NOT NULL,
    PRIMARY KEY (provider, day)
);
