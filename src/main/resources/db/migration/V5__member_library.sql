-- 회원이 등록한 도서관(최대 3곳). 공개 조회(publicview)는 이 테이블을 읽지 않는다 (설계 7-3)
CREATE TABLE member_library (
    member_id  BIGINT       NOT NULL,
    lib_code   VARCHAR(20)  NOT NULL,
    position   SMALLINT     NOT NULL CHECK (position BETWEEN 1 AND 3),
    PRIMARY KEY (member_id, lib_code),
    CONSTRAINT uq_member_library_position UNIQUE (member_id, position)
);

-- 하루 변경 횟수. 날짜는 KST
CREATE TABLE member_library_change (
    member_id  BIGINT   NOT NULL,
    day        DATE     NOT NULL,
    changes    INTEGER  NOT NULL,
    PRIMARY KEY (member_id, day)
);

-- 도서관별 소장·대출 캐시. 확인 불가(UNKNOWN)는 저장하지 않는다
CREATE TABLE library_holding (
    lib_code    VARCHAR(20)  NOT NULL,
    isbn13      VARCHAR(13)  NOT NULL CHECK (isbn13 ~ '^97[89][0-9]{10}$'),
    state       VARCHAR(10)  NOT NULL CHECK (state IN ('AVAILABLE', 'ON_LOAN', 'NOT_HELD')),
    fetched_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (lib_code, isbn13)
);
