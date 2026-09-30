-- 사용법 (README 6절):
--   1) 앱에서 Apple로 로그인한다 → 새 회원 reader-xxxxxx가 생긴다(기록 없음)
--   2) 그 slug를 확인한다:  SELECT slug, created_at FROM member WHERE apple_sub IS NOT NULL ORDER BY created_at DESC LIMIT 3;
--   3) docker exec -i bookshelf-postgres psql -U bookshelf -d bookshelf -v ON_ERROR_STOP=1 \
--        -v owner=<본인 slug> -v new_slug=<reader-xxxxxx> -f - < deploy/link-owner-apple.sql
--   4) 앱에서 로그아웃 → 다시 로그인. 이번에는 본인 회원으로 들어간다
--
-- 새 회원에 기록이 있으면 지우지 않는다. 그러면 apple_sub가 UNIQUE라 옮기기가 실패하고,
-- ON_ERROR_STOP이 트랜잭션을 멈춰 아무것도 바뀌지 않는다.
BEGIN;

SELECT apple_sub AS new_sub FROM member WHERE slug = :'new_slug' AND apple_sub IS NOT NULL \gset

DELETE FROM refresh_token     WHERE member_id = (SELECT id FROM member WHERE slug = :'new_slug');
DELETE FROM user_call_counter WHERE member_id = (SELECT id FROM member WHERE slug = :'new_slug');
DELETE FROM member m
WHERE m.slug = :'new_slug'
  AND NOT EXISTS (SELECT 1 FROM reading_record r WHERE r.member_id = m.id)
  AND NOT EXISTS (SELECT 1 FROM member_library l WHERE l.member_id = m.id);

UPDATE member SET apple_sub = :'new_sub' WHERE slug = :'owner' AND deleted_at IS NULL;

SELECT slug, display_name, apple_sub IS NOT NULL AS linked FROM member WHERE slug IN (:'owner', :'new_slug');

COMMIT;
