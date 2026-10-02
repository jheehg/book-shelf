# 저장소 규칙

## 커밋

- **커밋 메시지에 트레일러를 넣지 않는다.** `Co-Authored-By`, `Claude-Session`, `Signed-off-by` 등 어떤 트레일러도 붙이지 않고 커밋 메시지만 쓴다
- 비밀값(`.env`, 키, 토큰)은 절대 커밋하지 않는다. 커밋 전에 스테이징된 내용에 키가 없는지 확인한다

## 이 프로젝트의 규칙 (근거는 설계 문서 2·6·7장)

- **공개 조회(`publicview`)는 JPA 엔티티·리포지터리·서비스를 재사용하지 않는다.** SQL로 허용된 컬럼만 가져온다.
  ArchUnit 테스트(`PublicViewArchitectureTest`)가 강제한다
- 공개 URL에는 slug만. record id를 내보내지 않는다
- 비공개·없는·탈퇴한 서재는 **같은** 404
- **공개 페이지는 어떤 외부 제공자도 동기로 부르지 않는다.** 캐시만 보여주고 갱신은 큐에 넣는다
- 외부 제공자 호출은 그 제공자의 클라이언트 한 곳만 한다. 예산 차감이 거기서 일어난다 — 우회하면 카운터가 거짓말을 한다
- 트랜잭션 안에서 외부 API를 호출하지 않는다. DB 쓰기는 별도 빈(`*Writer`)에 둔다
- 스키마는 Flyway(`src/main/resources/db/migration`)만. **배포된 V 파일은 고치지 않는다**
- 시간은 `TIMESTAMPTZ` + `Instant`, "지금"은 `Clock` 빈에서
- 엔티티에 `@Setter`/`@Data`/`@ToString` 금지, 생성은 정적 팩토리
- 사용자 입력은 `th:text`로만 출력. `th:utext` 금지

## 로컬 실행
```bash
docker compose up -d        # Postgres (5433). 최초 1회 bookshelf_test DB도 만든다
set -a && . ./.env && set +a && ./gradlew bootRun
# 앱 개발용: Apple 로그인 대신 POST /api/auth/dev {"slug":"dev-me"} (로컬 전용), 외부 호출을 아끼려면 백그라운드 작업은 끈다
set -a && . ./.env && set +a && DEV_LOGIN_ENABLED=true BACKGROUND_JOBS_ENABLED=false ./gradlew bootRun
./gradlew test
```
