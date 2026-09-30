-- V3: 사용자별 하루 제공자 호출 수 (Phase 2 Task 4). 캐시에 걸린 요청은 여기 오지 않는다
CREATE TABLE user_call_counter (
    member_id  BIGINT   NOT NULL,
    day        DATE     NOT NULL,      -- Asia/Seoul 기준
    calls      INTEGER  NOT NULL,
    PRIMARY KEY (member_id, day)
);
