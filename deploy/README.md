# 배포 절차

## 1. 서버 준비 (최초 1회)
Ubuntu 24.04 (OCI E2.1.Micro, 메모리 1GB 기준):
```bash
# 스왑 2GB — 메모리 1GB로는 JVM·Postgres·Nginx가 빠듯하다. swappiness를 낮춰 평소에는 쓰지 않게 한다
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swap.conf && sudo sysctl --system

# Docker + compose 플러그인
curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker ubuntu   # 다시 로그인해야 그룹이 적용된다

# 방화벽 — OCI Ubuntu 이미지는 iptables가 22번만 열고 나머지를 REJECT한다. REJECT 규칙보다 앞에 넣는다
sudo iptables -L INPUT --line-numbers                      # REJECT 줄 번호 확인
sudo iptables -I INPUT <REJECT 줄 번호> -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT <REJECT 줄 번호> -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save                             # 재부팅 후에도 유지
```
OCI 콘솔의 Security List(또는 NSG)에도 80/443 ingress를 연다 — 서버 방화벽과 OCI 방화벽은 따로다.
`~/.ssh/config`에 `Host bookshelf` 항목을 만든다.

## 2. DNS
도메인의 A 레코드(`@`, `www`)를 인스턴스 공인 IP로. 확인: `dig +short <도메인>`

## 3. 이미지 (맥에서)
맥은 ARM, 서버는 x86이다. 그냥 빌드하면 서버에서 `exec format error`.
```bash
docker buildx build --platform linux/amd64 -t bookshelf-app:amd64 --load .
docker save bookshelf-app:amd64 | gzip | ssh bookshelf 'gunzip | docker load'
```

## 4. 최초 기동 (HTTP) → 인증서 → HTTPS
```bash
ssh bookshelf
git clone https://github.com/jheehg/book-shelf.git && cd book-shelf   # 이후에는 git pull
cat > .env <<'ENV'
POSTGRES_PASSWORD=<openssl rand -base64 24>
KAKAO_REST_API_KEY=<카카오 REST API 키>
SITE_URL=https://<도메인>
ENV
chmod 600 .env

NGINX_CONF=./nginx.http.conf docker compose -f deploy/compose.server.yaml --env-file .env up -d
curl -s localhost/actuator/health            # {"status":"UP"}

docker run --rm \
  -v bookshelf_certbot-webroot:/var/www/certbot \
  -v bookshelf_certbot-conf:/etc/letsencrypt \
  certbot/certbot certonly --webroot -w /var/www/certbot \
  -d <도메인> -d www.<도메인> --email <메일> --agree-tos --no-eff-email

docker compose -f deploy/compose.server.yaml --env-file .env up -d   # 기본 nginx.conf(HTTPS)로 다시
```
인증서 갱신 cron (월요일 03:00):
```
0 3 * * 1 docker run --rm -v bookshelf_certbot-webroot:/var/www/certbot -v bookshelf_certbot-conf:/etc/letsencrypt certbot/certbot renew --webroot -w /var/www/certbot && docker exec bookshelf-nginx nginx -s reload
```

## 5. 재배포
3절(이미지) → `git pull` → `docker compose -f deploy/compose.server.yaml --env-file .env up -d`.
스키마 변경은 Flyway가 기동 시 적용한다. **배포된 V 파일은 고치지 않는다.**

## 6. 본인 서재 (Phase 2 전까지)
```bash
docker exec -i bookshelf-postgres psql -U bookshelf -d bookshelf -v ON_ERROR_STOP=1 \
  -v slug=<slug> -v name='<이름>' -f - < deploy/seed-owner.sql
docker exec -i bookshelf-postgres psql -U bookshelf -d bookshelf -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO reading_record (member_id, isbn13, status, rating, review, visibility, created_at, updated_at)
SELECT id, v.isbn, 'DONE', v.rating, v.review, 'PUBLIC', now(), now()
FROM member, (VALUES ('9788936434120', 5, '오래 남는다')) AS v(isbn, rating, review)
WHERE slug = '<slug>';
SQL
```
백필이 10분 안에 책 정보를 채운다. 확인: `SELECT isbn13, title FROM book;`

## 7. 관측
```bash
docker exec bookshelf-postgres psql -U bookshelf -d bookshelf -c "SELECT * FROM provider_call_counter ORDER BY day DESC LIMIT 7"
docker exec bookshelf-postgres psql -U bookshelf -d bookshelf -c "SELECT count(*) FROM refresh_request"
docker logs bookshelf-app 2>&1 | grep -E '(호출|예산|갱신|백필)'
docker stats --no-stream
```

## 8. DB 접속 (맥에서)
```bash
ssh -L 15432:$(ssh bookshelf "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' bookshelf-postgres"):5432 bookshelf
# 다른 터미널: psql -h localhost -p 15432 -U bookshelf bookshelf
```
