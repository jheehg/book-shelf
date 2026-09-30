-- V2: Apple 로그인과 refresh 토큰 (Phase 2 Task 3)

-- Apple이 준 사용자 식별자. 탈퇴하면 NULL로 비운다 — 같은 Apple 계정으로 다시 가입하면 새 회원이다
ALTER TABLE member ADD COLUMN apple_sub VARCHAR(100) UNIQUE;

-- 원문이 아니라 SHA-256 해시만 둔다. DB가 새어도 토큰으로 쓸 수 없다
CREATE TABLE refresh_token (
    id          BIGSERIAL    PRIMARY KEY,
    member_id   BIGINT       NOT NULL,
    token_hash  CHAR(64)     NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ               -- 회전되면 찍힌다. 찍힌 토큰이 다시 오면 도난으로 본다
);
CREATE INDEX idx_refresh_member ON refresh_token (member_id);
