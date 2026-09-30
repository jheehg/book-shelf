-- 정보나루 도서관 목록의 로컬 사본. 남의 데이터라 길이 제한을 두지 않는다(TEXT) —
-- 긴 값 하나가 저장에서 터지면 그 쪽 전체가 동기화되지 않는다
CREATE TABLE library (
    lib_code     VARCHAR(20)       PRIMARY KEY,
    name         TEXT              NOT NULL,
    address      TEXT,
    region_code  INTEGER           NOT NULL CHECK (region_code IN
                     (11, 21, 22, 23, 24, 25, 26, 29, 31, 32, 33, 34, 35, 36, 37, 38, 39)),
    tel          TEXT,
    latitude     DOUBLE PRECISION,
    longitude    DOUBLE PRECISION,
    homepage     TEXT,
    synced_at    TIMESTAMPTZ       NOT NULL
);
CREATE INDEX idx_library_region ON library (region_code);

-- 17개 지역을 모두 받은 동기화만 남는다. 다음 동기화 시점은 여기서 정한다
CREATE TABLE library_sync_run (
    id           BIGSERIAL    PRIMARY KEY,
    finished_at  TIMESTAMPTZ  NOT NULL,
    libraries    INTEGER      NOT NULL
);
