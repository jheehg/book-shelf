-- 사용법 (README 6절):
--   docker exec -i bookshelf-postgres psql -U bookshelf -d bookshelf -v ON_ERROR_STOP=1 \
--     -v slug=juhee -v name='주희' -f - < deploy/seed-owner.sql
-- Phase 2에서 Apple 로그인을 붙이면 이 회원에 Apple sub를 연결한다. 새로 만들지 않는다.
INSERT INTO member (slug, display_name, shelf_public, default_visibility, created_at)
VALUES (:'slug', :'name', TRUE, 'PRIVATE', now())
ON CONFLICT (slug) DO NOTHING;
