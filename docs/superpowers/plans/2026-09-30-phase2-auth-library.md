# Phase 2 — 인증과 도서관 (W6~W7) 구현 계획 · 기록

> 이 문서는 당시의 상세 구현 기록이다. 현재 작업에는 특정 에이전트 프레임워크를 요구하지 않으며, 저장소 루트의 `AGENTS.md`와 `agent-rules/`를 따른다. 체크박스는 작성 당시 상태라 완료 여부의 기준이 아니다.

**Goal:** 앱이 쓸 서버 API를 인증 뒤에 연다. Apple 로그인(서버가 Apple 공개키로 서명 검증) → 우리 JWT, 사용자별 호출 상한, 기록·검색·책 API, 계정 삭제(Apple 토큰 철회), 정보나루 연동(도서관 동기화, 내 도서관 3곳, 소장·대출 여부).

**Architecture:** Spring Security를 붙인다. `/api/**`는 인증 필수(우리 JWT, HS256, resource-server 스타터가 검증), 공개 웹(`/u/**`, `/book/**`, 사이트맵)은 지금처럼 열어 둔다. Refresh 토큰은 JWT가 아니라 **불투명한 난수**이고 DB에는 해시만 둔다(회전, 재사용 감지). 사용자별 상한은 `CallBudget`에 합쳐 **모든 제공자 호출이 한 곳에서** 사용자 상한과 제공자 상한을 함께 지난다. 정보나루는 `LibraryAvailability` 인터페이스 뒤에 두고, 도서관 목록은 주 1회 로컬로 동기화해 검색은 로컬에서만 한다.

**Tech Stack:** Phase 1 그대로 + `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-resource-server`(Boot 4의 새 이름 — Maven Central에서 확인), Nimbus JOSE(스타터에 포함), `spring-security-test`

**Spec:** `docs/superpowers/specs/2026-09-27-book-shelf-design.md` (2026-10 정보나루 실측 반영본). 제공자 실측: `docs/notes/providers.md`
**이전 계획:** `docs/superpowers/plans/2026-09-30-phase1-web-first.md` (Task 1~11 완료, Task 12는 파일까지 — 배포 대기)

**이 계획의 범위:** 설계 문서 9장의 W6(인증·사용자별 상한·계정 삭제 서버)과 W7(정보나루 서버). 앱이 W8~W10에 쓸 API를 모두 만든다. 넘기는 것:
- Phase 3 (W8~W11): Flutter 앱 전부, 공유 흐름(일괄 공개 전환 API 포함), Google Play 가격
- Phase 4: 공개 경계 보강, TestFlight, 강제 업데이트 스위치, Phase 1 Minor(M1~M6)

**사용자가 정한 것 (2026-09-30):**
1. JWT 검증은 **resource-server 스타터**에 맡긴다(spring-study에서 필터를 이미 직접 만들어 봤다)
2. 계정 삭제는 **기록·등록 도서관·토큰은 완전히 지우고 member 행은 이름을 지운 채 slug만 예약**한다 — 탈퇴한 사람의 서재 링크를 다른 사람이 가로채지 못하게
3. slug는 **가입 시 자동 생성**(`reader-xxxxxx`)하고 나중에 바꿀 수 있다

**설계에서 정한 것 (이 계획):**
- **Apple 토큰은 저장하지 않는다.** 로그인은 identity token 검증만 한다. 계정 삭제 때 앱이 Apple로 다시 인증해 받은 **새 authorization code**를 보내면, 서버가 그 자리에서 교환해 철회한다. Apple refresh 토큰을 DB에 두지 않으므로 새어 나갈 비밀이 하나 줄어든다
- 공개 페이지의 404·HTML은 Phase 1 그대로다. 보안 설정이 공개 페이지를 막지 않는지 회귀 테스트로 지킨다

## Global Constraints

- 커밋·푸시·검증 규칙은 `agent-rules/workflow.md`를 따른다.
- **Flyway V1은 고치지 않는다.** Phase 2 스키마는 그 테이블을 처음 쓰는 Task가 `V2`, `V3`… 로 하나씩 더한다 (Phase 1이 아직 배포 전이어도 V1은 닫는다 — 배포된 뒤의 습관을 지금 들인다)
- 시크릿(`JWT_SECRET`, `APPLE_*`, `LIBRARY_API_KEY`, `KAKAO_REST_API_KEY`)에 기본값을 두지 않는다. **`@ConfigurationProperties`는 치환 못 한 `${...}`를 문자 그대로 넣으므로** 각 Properties의 compact 생성자에서 null·빈 값·`${`로 시작하는 값을 막는다(Phase 1 Task 4에서 겪음)
- `JWT_SECRET`은 32바이트 이상 (HS256)
- JWT payload에 개인정보를 넣지 않는다. `sub`는 member id
- **인증 실패 응답은 구분하지 않는다.** 토큰 없음·만료·위조·Apple 토큰 불량이 모두 같은 401 본문
- **요청 본문으로 주체를 받지 않는다.** 기록의 주인은 토큰에서 온다
- **남의 리소스는 404.** 403은 존재를 알려준다
- 공개 조회(`publicview`)는 여전히 SQL 전용이고 `MemberLibrary`·`library_holding`을 절대 읽지 않는다 (spec 7-3)
- 사용자별 상한 **300회/일**(캐시에 걸린 요청은 세지 않고, 걸리지 않은 요청은 결과가 비어도 센다), 등록 도서관 **최대 3곳**, 변경 **하루 3회** (spec 6-3)
- 빌리기 조회의 도서관은 **서버가 `member_library`에서만** 꺼낸다. 요청으로 도서관 코드를 받지 않는다 (spec 6-3)
- 정보나루 상한 **450/일**, 백그라운드 **300/일**, `https://data4library.kr`, connect 1s / read 3s (실측 0.33~0.65s) (spec 6-3, providers.md)
- `library_holding` 캐시는 **조회한 날 다음 날 00:00 KST까지** (spec 6-5). 정보나루가 원래 전날 상태를 준다
- 제공자가 죽으면 **그 칸만** "확인 불가"다. 빌리기 조회가 실패해도 다른 도서관·다른 칸은 멀쩡하다 (spec 6-6)
- 트랜잭션 안에서 외부 API를 호출하지 않는다. DB 쓰기는 `*Writer`
- 에러 응답은 하나의 모양 `{"code": "...", "message": "..."}`. `@ExceptionHandler(Exception.class)`를 만들지 않는다(예상 못 한 예외는 500으로 드러나야 한다)
- 시간은 `Instant`/`TIMESTAMPTZ`, 날짜 경계는 `Asia/Seoul`, "지금"은 `Clock` 빈

## Review Focus

설계 문서가 말하지 않았지만 실제 사용자(와 공격자)가 가장 먼저 부딪힐 다섯 가지다. 각 줄의 테스트는 담당 Task에 들어 있다.

1. **남의 기록을 id로 건드린다** — 다른 회원의 기록 id로 조회·수정·삭제하면 404이고 아무것도 바뀌지 않는다 → Task 6
2. **Refresh 토큰이 도난당했다** — 이미 쓴(회전된) refresh 토큰이 다시 오면 그 회원의 모든 refresh 토큰을 폐기한다. 정상 사용자도 다시 로그인해야 하지만 공격자는 쫓겨난다 → Task 3
3. **Apple 토큰을 흉내 낸다** — 다른 앱(aud)용 토큰, 만료된 토큰, 우리 키가 아닌 키로 서명한 토큰, nonce가 다른 토큰이 모두 같은 401이다 → Task 2
4. **보안을 붙였더니 공개 페이지가 막힌다** — `/u/**`, `/book/**`, `/sitemap.xml`, `/robots.txt`, `/css/**`, `/actuator/health`는 토큰 없이 그대로 200이다 → Task 1
5. **등록 도서관을 바꿔 끼우며 훑는다** — 하루 4번째 변경은 429이고, 요청 파라미터로 도서관 코드를 넣어도 빌리기 조회에 쓰이지 않는다 → Task 11

## 파일 구조 (새로 생기거나 바뀌는 것)

```
src/main/java/com/bookshelf/
├── common/              CurrentMember, ApiError, ApiExceptionHandler, NotFoundException, ConflictException   (Task 1)
├── auth/                SecurityConfig, JwtProperties, JwtConfig, ApiAuthenticationEntryPoint (1), UnauthorizedException (2),
│                        TokenIssuer, RefreshTokenStore, AuthTokens, AuthService, AuthController (3),
│                        AccountDeletionService, AccountController (8)
├── auth/apple/          AppleProperties, AppleIdentity, AppleIdTokenVerifier (2),
│                        AppleClientSecret, AppleTokenRevoker, AppleRestClientConfig, AppleRejectedException, AppleUnavailableException (8)
├── provider/            CallBudget#acquire, UserQuotaProperties, UserQuotaExceededException (4)
├── member/application/  SlugGenerator (3), MemberService, MeView (5), MemberDeletion (8)
├── member/presentation/ MeController (5)
├── record/application/  RecordService, RecordWriter, RecordView (6)
├── record/presentation/ RecordController (6)
├── book/application/    BookLookupService#findLocal, BookDetail (7)
├── book/presentation/   BookApiController (7)
├── search/              SearchController, SearchItem, SearchResponse, SearchCacheCleanup (7)
├── ops/                 MaintenanceJobs (7, 10)
├── library/             HoldingState, LibraryAvailability, LibraryDirectory, LibraryInfo, LibraryPage (9), Region (10)
├── library/data4library/ LibraryProperties, LibraryRestClientConfig, Data4LibraryResponse, Data4LibraryClient (9)
├── library/application/ LibraryWriter, LibrarySync, LibrarySearch, LibraryView (10),
│                        MyLibraries, MyLibrariesWriter, LibraryChangeLimitException, HoldingCache, HoldingView, BorrowService (11)
└── library/presentation/ LibraryController (10), MyLibrariesController, BorrowController (11)
src/main/resources/db/migration/  V2__auth.sql (3), V3__user_quota.sql (4), V4__library.sql (10), V5__member_library.sql (11)
src/test/java/com/bookshelf/support/  TestJwt (1), AppleStub (2, 8), AsMember (4), LibraryStub (9, 10)
src/test/resources/apple/             test-client-key.p8, test-client-key.pub.pem (1) — 테스트 전용 키
src/test/resources/library/           정보나루 실측 원본 (0)
deploy/                               compose.server.yaml, README.md, link-owner-apple.sql (12)
```

---

### Task 0: 준비물 (코드 없음)

- [ ] **Step 1: Apple Developer 승인 확인과 Sign in with Apple 설정** — 승인이 났다면:
  1. Certificates, Identifiers & Profiles → Identifiers → App ID(번들 ID, 예: `com.bookshelf.app`)에 **Sign in with Apple** 켜기
  2. Keys → 새 키에 **Sign in with Apple**을 켜고 `.p8` 파일을 받는다(한 번만 받을 수 있다). Key ID를 적는다
  3. 우측 상단의 Team ID를 적는다
  4. `.env`에 추가한다(커밋하지 않는다):
     ```bash
     printf '\nAPPLE_CLIENT_ID=%s\nAPPLE_TEAM_ID=%s\nAPPLE_KEY_ID=%s\n' <번들ID> <TeamID> <KeyID> >> .env
     printf 'APPLE_PRIVATE_KEY="%s"\n' "$(cat AuthKey_XXXX.p8 | tr '\n' '|' )" >> .env   # 줄바꿈을 |로 — 앱이 되돌린다
     ```
  **승인 전이라면** 로컬 기동용 가짜 값을 만든다(테스트는 자체 키를 쓴다 — 이 값은 bootRun용):
  ```bash
  openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt > /tmp/dummy.p8
  printf '\nAPPLE_CLIENT_ID=com.bookshelf.dev\nAPPLE_TEAM_ID=DEVTEAM000\nAPPLE_KEY_ID=DEVKEY0000\n' >> .env
  printf 'APPLE_PRIVATE_KEY="%s"\n' "$(tr '\n' '|' < /tmp/dummy.p8)" >> .env && rm /tmp/dummy.p8
  ```
- [ ] **Step 2: JWT 비밀키와 정보나루 키 이름**
  ```bash
  grep -q '^JWT_SECRET=' .env || printf '\nJWT_SECRET=%s\n' "$(openssl rand -base64 48)" >> .env
  grep -q '^LIBRARY_API_KEY=' .env || printf '\nLIBRARY_API_KEY=%s\n' "$(grep -E '^LIBRARY_KEY=' .env | cut -d= -f2-)" >> .env
  sed -E 's/=.*/=<hidden>/' .env        # 이름만 확인. 줄이 붙지 않았는지 본다
  ```
- [ ] **Step 3: 정보나루 실측 원본을 fixture로**

  소장 fixture는 **스파이크 때 받은 원본을 복사한다**(대출 상태는 날마다 바뀌어서, 다시 받으면 테스트 기대값과 어긋날 수 있다). 원본은 이 세션의 스크래치 `raw/`에 있다: `lib-exist-111456.json`(Y,Y), `lib-exist-711618.json`(Y,N). 도서관 목록은 값이 거의 바뀌지 않으므로 새로 받는다(호출 1회).
  ```bash
  mkdir -p src/test/resources/library
  RAW=/private/tmp/claude-501/-Users-juheehong-repo-book-shelf/c48ae307-db3f-4a13-9d6a-293ef86bea6e/scratchpad/raw
  cp "$RAW/lib-exist-111456.json" src/test/resources/library/bookExist-111456.json
  cp "$RAW/lib-exist-711618.json" src/test/resources/library/bookExist-711618.json
  K=$(grep -E '^LIBRARY_API_KEY=' .env | cut -d= -f2-)
  curl -sS "https://data4library.kr/api/libSrch?authKey=$K&region=11&pageNo=1&pageSize=3&format=json" > src/test/resources/library/libSrch-seoul-3.json
  grep -c "$K" src/test/resources/library/*.json     # 모두 0 — 응답에 키가 들어가지 않는다
  grep -o '"result":{[^}]*}' src/test/resources/library/bookExist-*.json
  ```
  Expected: `bookExist-111456.json:"result":{"hasBook":"Y","loanAvailable":"Y"}`, `bookExist-711618.json:"result":{"hasBook":"Y","loanAvailable":"N"}`

  스크래치 원본이 없어졌으면(세션이 바뀌었으면) 두 파일을 손으로 만든다 — 실측 응답 모양 그대로다:
  ```bash
  printf '%s' '{"response":{"request":{"isbn13":"9788936434120","libCode":"111456","format":"json"},"result":{"hasBook":"Y","loanAvailable":"Y"}}}' > src/test/resources/library/bookExist-111456.json
  printf '%s' '{"response":{"request":{"isbn13":"9788936434120","libCode":"711618","format":"json"},"result":{"hasBook":"Y","loanAvailable":"N"}}}' > src/test/resources/library/bookExist-711618.json
  ```

---

### Task 1: 보안 골격 — `/api`는 잠그고 공개 웹은 그대로

**Files:**
- Modify: `build.gradle.kts`, `src/main/resources/application.yaml`
- Create: `src/main/java/com/bookshelf/auth/JwtProperties.java`, `JwtConfig.java`, `SecurityConfig.java`, `ApiAuthenticationEntryPoint.java`
- Create: `src/main/java/com/bookshelf/common/ApiError.java`, `ApiExceptionHandler.java`, `NotFoundException.java`, `ConflictException.java`, `CurrentMember.java`
- Test: `src/test/java/com/bookshelf/auth/JwtPropertiesTest.java`, `src/test/java/com/bookshelf/auth/SecurityBoundaryTest.java`
- Create (test 지원): `src/test/java/com/bookshelf/support/TestJwt.java`

**Interfaces:**
- Produces:
  - `record JwtProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl)` prefix `jwt` — secret 32바이트 미만·`${`는 기동 실패
  - 빈 `JwtEncoder`, `JwtDecoder` (HS256, issuer `book-shelf`)
  - `record ApiError(String code, String message)`, 예외 → HTTP: `DomainException` 400 `BAD_REQUEST`, `NotFoundException` 404 `NOT_FOUND`, `ConflictException` 409 `CONFLICT`, `UnauthorizedException`(Task 2) 401 `UNAUTHORIZED`, `UserQuotaExceededException`(Task 4) 429 `TOO_MANY_REQUESTS`, `SearchUnavailableException`·`ProviderUnavailableException` 503 `TRY_LATER`
  - `CurrentMember.id() -> Optional<Long>` (정적, SecurityContext의 JWT `sub`), `CurrentMember.require() -> Long`
  - 테스트 지원: `TestJwt.bearer(JwtEncoder, long memberId) -> String` ("Bearer …")

- [ ] **Step 1: 의존성과 설정**

  `build.gradle.kts`의 `dependencies`에 추가:

  ```kotlin
      implementation("org.springframework.boot:spring-boot-starter-security")
      // Boot 4의 새 이름. 우리 JWT(HS256) 검증과 Apple identity token(RS256, JWKS) 검증을 Nimbus로 한다
      implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
      testImplementation("org.springframework.security:spring-security-test")
  ```

  `tasks.withType<Test>`에 테스트용 값(모두 가짜):

  ```kotlin
      environment("JWT_SECRET", "test-jwt-secret-test-jwt-secret-0123456789abcdef")
      environment("LIBRARY_API_KEY", "test-library-key")
      environment("APPLE_CLIENT_ID", "com.bookshelf.test")
      environment("APPLE_TEAM_ID", "TESTTEAM00")
      environment("APPLE_KEY_ID", "TESTKEY000")
      // 테스트용 EC 개인키(PKCS8, 줄바꿈은 |). 실제 Apple 키가 아니다 — AppleStub이 같은 키로 검증한다
      environment("APPLE_PRIVATE_KEY", file("src/test/resources/apple/test-client-key.p8").readText().replace("\n", "|"))
      systemProperty("providers.library.cool-down", "0s")
  ```

  테스트용 키를 만든다(커밋해도 되는 **테스트 전용** 키다 — 어디에도 등록되지 않는다):

  ```bash
  mkdir -p src/test/resources/apple
  openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt > src/test/resources/apple/test-client-key.p8
  # 공개키: Task 8에서 우리가 만든 client secret(ES256)의 서명을 테스트가 검증한다
  openssl pkey -in src/test/resources/apple/test-client-key.p8 -pubout > src/test/resources/apple/test-client-key.pub.pem
  head -1 src/test/resources/apple/test-client-key.p8     # -----BEGIN PRIVATE KEY-----
  ```

  `application.yaml`에 추가:

  ```yaml
  jwt:
    secret: ${JWT_SECRET}            # 기본값 없음. 32바이트 이상 (JwtProperties가 검사)
    access-token-ttl: 30m
    refresh-token-ttl: 14d
  ```

- [ ] **Step 2: 실패하는 테스트 — 설정과 경계**

  `src/test/java/com/bookshelf/auth/JwtPropertiesTest.java`:

  ```java
  package com.bookshelf.auth;

  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.NullSource;
  import org.junit.jupiter.params.provider.ValueSource;

  import java.time.Duration;

  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  class JwtPropertiesTest {

      @ParameterizedTest
      @NullSource
      @ValueSource(strings = {"", "${JWT_SECRET}", "too-short-secret"})
      void 비밀키가_없거나_짧으면_기동이_실패한다(String secret) {
          assertThatThrownBy(() -> new JwtProperties(secret, Duration.ofMinutes(30), Duration.ofDays(14)))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("JWT_SECRET");
      }
  }
  ```

  `src/test/java/com/bookshelf/support/TestJwt.java`:

  ```java
  package com.bookshelf.support;

  import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
  import org.springframework.security.oauth2.jwt.JwsHeader;
  import org.springframework.security.oauth2.jwt.JwtClaimsSet;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

  import java.time.Instant;
  import java.time.temporal.ChronoUnit;

  /** 테스트에서 앱이 보내는 Authorization 헤더를 만든다. 실제 발급기와 같은 인코더를 쓴다 */
  public final class TestJwt {

      private TestJwt() {
      }

      public static String bearer(JwtEncoder encoder, long memberId) {
          Instant now = Instant.now();
          JwtClaimsSet claims = JwtClaimsSet.builder()
                  .issuer("book-shelf")
                  .subject(Long.toString(memberId))
                  .issuedAt(now)
                  .expiresAt(now.plus(30, ChronoUnit.MINUTES))
                  .build();
          String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                  .getTokenValue();
          return "Bearer " + token;
      }
  }
  ```

  `src/test/java/com/bookshelf/auth/SecurityBoundaryTest.java`:

  ```java
  package com.bookshelf.auth;

  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.TestJwt;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.ValueSource;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.test.web.servlet.MockMvc;

  import static com.bookshelf.record.domain.Visibility.PUBLIC;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(Fixtures.class)
  class SecurityBoundaryTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired Fixtures fx;
      @Autowired JwtEncoder encoder;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          fx.book("9788936434120", "소년이 온다");
          fx.record(fx.member("open", true), "9788936434120", PUBLIC, 5, null);
      }

      /** 보안을 붙였더니 공개 페이지가 막히는 일이 없어야 한다 (Review Focus 4) */
      @ParameterizedTest
      @ValueSource(strings = {"/u/open", "/book/9788936434120", "/sitemap.xml", "/robots.txt", "/css/site.css", "/actuator/health"})
      void 공개_경로는_토큰_없이_열린다(String path) throws Exception {
          mvc.perform(get(path)).andExpect(status().isOk());
      }

      @Test
      void API는_토큰이_없으면_401이다() throws Exception {
          mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
      }

      @Test
      void 없음_만료_위조가_모두_같은_401_본문이다() throws Exception {
          String none = mvc.perform(get("/api/me")).andReturn().getResponse().getContentAsString();
          String garbage = mvc.perform(get("/api/me").header("Authorization", "Bearer not-a-jwt"))
                  .andReturn().getResponse().getContentAsString();
          String forged = mvc.perform(get("/api/me").header("Authorization",
                          "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwiaXNzIjoiYm9vay1zaGVsZiJ9.AAAA"))
                  .andReturn().getResponse().getContentAsString();

          assertThat(none).isEqualTo(garbage).isEqualTo(forged).contains("\"code\":\"UNAUTHORIZED\"");
      }

      @Test
      void 우리가_발급한_토큰이면_API를_통과한다() throws Exception {
          // /api/me는 Task 5에서 생긴다. 지금은 인증을 통과해 핸들러가 없어 404가 나면 된다 — 401이 아니면 통과한 것이다
          int status = mvc.perform(get("/api/me").header("Authorization", TestJwt.bearer(encoder, 1)))
                  .andReturn().getResponse().getStatus();
          assertThat(status).isNotEqualTo(401);
      }
  }
  ```

- [ ] **Step 3: 실패 확인**

  Run: `./gradlew test --tests JwtPropertiesTest --tests SecurityBoundaryTest`
  Expected: FAIL — 컴파일 실패 (`JwtProperties`, `JwtEncoder` 빈 없음)

- [ ] **Step 4: 구현**

  `src/main/java/com/bookshelf/auth/JwtProperties.java`:

  ```java
  package com.bookshelf.auth;

  import org.springframework.boot.context.properties.ConfigurationProperties;

  import java.nio.charset.StandardCharsets;
  import java.time.Duration;

  @ConfigurationProperties("jwt")
  public record JwtProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {

      /** @ConfigurationProperties는 치환 못 한 ${...}를 문자 그대로 넣는다 — 여기서 기동을 막는다 */
      public JwtProperties {
          if (secret == null || secret.isBlank() || secret.startsWith("${")
                  || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
              throw new IllegalStateException("JWT_SECRET이 없거나 32바이트보다 짧다 — openssl rand -base64 48 로 만든다");
          }
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/JwtConfig.java`:

  ```java
  package com.bookshelf.auth;

  import com.nimbusds.jose.jwk.source.ImmutableSecret;
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
  import org.springframework.security.oauth2.jwt.JwtDecoder;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.security.oauth2.jwt.JwtValidators;
  import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
  import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

  import javax.crypto.SecretKey;
  import javax.crypto.spec.SecretKeySpec;
  import java.nio.charset.StandardCharsets;

  /** 우리 access 토큰(HS256). 발급과 검증이 같은 비밀키를 쓴다 */
  @Configuration
  public class JwtConfig {

      public static final String ISSUER = "book-shelf";

      private final SecretKey key;

      public JwtConfig(JwtProperties props) {
          this.key = new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      }

      @Bean
      JwtEncoder jwtEncoder() {
          return new NimbusJwtEncoder(new ImmutableSecret<>(key));
      }

      @Bean
      JwtDecoder jwtDecoder() {
          NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
          decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));   // 만료·issuer
          return decoder;
      }
  }
  ```

  `src/main/java/com/bookshelf/common/ApiError.java`:

  ```java
  package com.bookshelf.common;

  /** 모든 API 오류의 모양 하나 */
  public record ApiError(String code, String message) {
  }
  ```

  `src/main/java/com/bookshelf/common/NotFoundException.java`:

  ```java
  package com.bookshelf.common;

  /** 없거나, 남의 것이다 — 둘을 구분하지 않는다(403은 존재를 알려준다) */
  public class NotFoundException extends RuntimeException {
      public NotFoundException(String message) {
          super(message);
      }
  }
  ```

  `src/main/java/com/bookshelf/common/ConflictException.java`:

  ```java
  package com.bookshelf.common;

  public class ConflictException extends RuntimeException {
      public ConflictException(String message) {
          super(message);
      }
  }
  ```

  `src/main/java/com/bookshelf/common/CurrentMember.java`:

  ```java
  package com.bookshelf.common;

  import org.springframework.security.core.Authentication;
  import org.springframework.security.core.context.SecurityContextHolder;
  import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

  import java.util.Optional;

  /**
   * 지금 요청의 회원 id. 토큰의 sub에서만 온다 — 요청 본문으로 주체를 받지 않는다.
   * 백그라운드 작업에는 인증이 없으므로 empty다.
   */
  public final class CurrentMember {

      private CurrentMember() {
      }

      public static Optional<Long> id() {
          Authentication auth = SecurityContextHolder.getContext().getAuthentication();
          if (auth instanceof JwtAuthenticationToken jwt) {
              return Optional.of(Long.parseLong(jwt.getToken().getSubject()));
          }
          return Optional.empty();
      }

      public static Long require() {
          return id().orElseThrow(() -> new IllegalStateException("인증되지 않은 요청에서 회원을 찾았다"));
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/ApiAuthenticationEntryPoint.java`:

  ```java
  package com.bookshelf.auth;

  import jakarta.servlet.http.HttpServletRequest;
  import jakarta.servlet.http.HttpServletResponse;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.http.MediaType;
  import org.springframework.security.core.AuthenticationException;
  import org.springframework.security.web.AuthenticationEntryPoint;
  import org.springframework.stereotype.Component;

  import java.io.IOException;
  import java.nio.charset.StandardCharsets;

  /**
   * 인증 실패는 모두 같은 401. 없음·만료·위조를 구분하면 공격자에게 힌트가 된다. 이유는 로그에만 남긴다.
   * 필터에서 거부된 요청은 @RestControllerAdvice에 닿지 않으므로 여기서 직접 쓴다.
   */
  @Slf4j
  @Component
  public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

      static final String BODY = "{\"code\":\"UNAUTHORIZED\",\"message\":\"다시 로그인해 주세요\"}";

      @Override
      public void commence(HttpServletRequest request, HttpServletResponse response,
                           AuthenticationException e) throws IOException {
          log.debug("인증 실패 path={} reason={}", request.getRequestURI(), e.getMessage());
          response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
          response.setCharacterEncoding(StandardCharsets.UTF_8.name());
          response.getWriter().write(BODY);
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/SecurityConfig.java`:

  ```java
  package com.bookshelf.auth;

  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.security.config.annotation.web.builders.HttpSecurity;
  import org.springframework.security.config.http.SessionCreationPolicy;
  import org.springframework.security.web.SecurityFilterChain;

  /**
   * /api/** 는 인증 필수, 그 밖(공개 웹·정적 파일·health)은 열어 둔다.
   * CSRF: API는 Authorization 헤더로만 인증한다(브라우저가 자동으로 붙이지 않는다) — 켜도 무의미하다.
   * 공개 웹은 GET만 있고 쿠키를 쓰지 않는다.
   */
  @Configuration
  public class SecurityConfig {

      @Bean
      SecurityFilterChain filterChain(HttpSecurity http, ApiAuthenticationEntryPoint entryPoint) throws Exception {
          return http
                  .csrf(c -> c.disable())
                  .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                  .authorizeHttpRequests(a -> a
                          .requestMatchers("/api/auth/**").permitAll()
                          .requestMatchers("/api/**").authenticated()
                          .anyRequest().permitAll())
                  .oauth2ResourceServer(o -> o.jwt(j -> { }).authenticationEntryPoint(entryPoint))
                  .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                  .build();
      }
  }
  ```

  `src/main/java/com/bookshelf/common/ApiExceptionHandler.java`:

  ```java
  package com.bookshelf.common;

  import com.bookshelf.provider.ProviderUnavailableException;
  import com.bookshelf.search.SearchUnavailableException;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.http.HttpStatus;
  import org.springframework.http.ResponseEntity;
  import org.springframework.web.bind.annotation.ExceptionHandler;
  import org.springframework.web.bind.annotation.RestControllerAdvice;

  /**
   * /api 의 예외 → 응답. Exception.class를 잡지 않는다 — 예상 못 한 예외는 500으로 드러나야 한다.
   * 4xx는 warn이 아니라 debug: 사용자의 잘못된 입력은 운영 경보가 아니다. 503은 warn(제공자 장애 신호).
   */
  @Slf4j
  @RestControllerAdvice(basePackages = "com.bookshelf")
  public class ApiExceptionHandler {

      @ExceptionHandler(DomainException.class)
      ResponseEntity<ApiError> badRequest(DomainException e) {
          log.debug("400 {}", e.getMessage());
          return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", e.getMessage()));
      }

      @ExceptionHandler(NotFoundException.class)
      ResponseEntity<ApiError> notFound(NotFoundException e) {
          log.debug("404 {}", e.getMessage());
          return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", "찾을 수 없어요"));
      }

      @ExceptionHandler(ConflictException.class)
      ResponseEntity<ApiError> conflict(ConflictException e) {
          return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("CONFLICT", e.getMessage()));
      }

      @ExceptionHandler({SearchUnavailableException.class, ProviderUnavailableException.class})
      ResponseEntity<ApiError> tryLater(RuntimeException e) {
          log.warn("503 {}", e.getMessage());
          return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                  .body(new ApiError("TRY_LATER", "잠시 후 다시 시도해 주세요"));
      }
  }
  ```

  공개 페이지 컨트롤러(`@Controller`)의 예외는 이 핸들러가 JSON으로 바꾸면 안 된다. 공개 페이지는 예외를 던지지 않고 `PublicPages.notFound()`를 돌려준다(Phase 1). 그대로 둔다.

- [ ] **Step 5: 통과 확인**

  Run: `./gradlew test --tests JwtPropertiesTest --tests SecurityBoundaryTest`
  Expected: PASS (4 + 6 + 3 = 13개)

- [ ] **Step 6: Phase 1 회귀 확인 — 보안을 붙여도 공개 웹이 그대로인가**

  Run: `./gradlew test`
  Expected: 전부 PASS. `PublicPagesTest`가 깨지면 보안 설정이 공개 경로를 막은 것이다 — `anyRequest().permitAll()`과 정적 경로를 확인한다

- [ ] **Step 7: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `build.gradle.kts src/main/resources/application.yaml src/main/java/com/bookshelf/auth src/main/java/com/bookshelf/common src/test/java/com/bookshelf/auth src/test/java/com/bookshelf/support/TestJwt.java src/test/resources/apple`

  ```bash
  git commit -m "feat: 보안 골격 — /api는 우리 JWT로 잠그고 공개 웹은 그대로

  인증 실패는 없음·만료·위조 모두 같은 401 본문이다. 이유는 로그에만 남긴다.
  JWT_SECRET은 기본값 없이 32바이트 이상이어야 기동된다(@ConfigurationProperties는
  치환 못 한 \${...}를 그대로 넣으므로 생성자에서 막는다).
  공개 경로 6개가 토큰 없이 200인지 회귀 테스트로 지킨다."
  ```

---

### Task 2: Apple identity token 검증 — 앱이 보낸 토큰을 그냥 믿지 않는다

설계 문서 7-1: *"앱이 보낸 토큰을 그냥 믿으면 누구나 남의 계정이 된다."* 서버가 Apple 공개키(JWKS)로 서명을 직접 검증하고, 발급자·대상(우리 앱)·만료·nonce를 모두 본다. 하나라도 틀리면 **같은 401**이다(Review Focus 3).

nonce: 앱은 무작위 문자열(raw nonce)을 만들어 **SHA-256 해시**를 Apple 로그인 요청에 넣고, raw nonce는 서버로 보낸다. Apple이 준 토큰의 `nonce` 클레임은 그 해시다. 서버가 raw nonce를 해시해 비교하면, 가로챈 토큰을 다른 로그인 시도에 재사용할 수 없다.

**Files:**
- Create: `src/main/java/com/bookshelf/auth/apple/AppleProperties.java`, `AppleIdentity.java`, `AppleIdTokenVerifier.java`
- Create: `src/main/java/com/bookshelf/auth/UnauthorizedException.java`
- Modify: `src/main/java/com/bookshelf/common/ApiExceptionHandler.java` (401 매핑), `src/main/resources/application.yaml`
- Create (test 지원): `src/test/java/com/bookshelf/support/AppleStub.java`
- Test: `src/test/java/com/bookshelf/auth/apple/ApplePropertiesTest.java`, `AppleIdTokenVerifierTest.java`

**Interfaces:**
- Consumes: `ApiAuthenticationEntryPoint.BODY`(같은 401 본문)
- Produces:
  - `record AppleProperties(String clientId, String teamId, String keyId, String privateKey, String authBaseUrl, String jwksUri)` prefix `apple`, `#privateKeyPem()`(`|`→줄바꿈) — 시크릿 네 개는 기동 시 검사
  - `record AppleIdentity(String sub)`
  - `AppleIdTokenVerifier#verify(String identityToken, String rawNonce) -> AppleIdentity` — 실패는 모두 `UnauthorizedException`
  - `UnauthorizedException(String reasonForLog)` → 401, 본문은 `ApiAuthenticationEntryPoint.BODY`와 같다
  - 테스트 지원: `AppleStub.baseUrl()`, `jwksUri()`, `reset()`, `identityToken(sub, aud, rawNonce, expiresAt)`, `tokenSignedByStranger(sub, aud, rawNonce)`, `sha256Hex(String)`, `server()`

- [ ] **Step 1: 설정** — `application.yaml`에 추가

  ```yaml
  apple:
    client-id: ${APPLE_CLIENT_ID}          # 앱 번들 ID. identity token의 aud
    team-id: ${APPLE_TEAM_ID}
    key-id: ${APPLE_KEY_ID}
    private-key: ${APPLE_PRIVATE_KEY}      # .p8 내용, 줄바꿈은 | 로 (환경변수 한 줄에 담기 위해)
    auth-base-url: ${APPLE_AUTH_BASE_URL:https://appleid.apple.com}
    jwks-uri: ${APPLE_JWKS_URI:https://appleid.apple.com/auth/keys}
  ```

- [ ] **Step 2: 테스트 지원 — 가짜 Apple**

  `src/test/java/com/bookshelf/support/AppleStub.java`:

  ```java
  package com.bookshelf.support;

  import com.github.tomakehurst.wiremock.WireMockServer;
  import com.nimbusds.jose.JOSEException;
  import com.nimbusds.jose.jwk.JWKSet;
  import com.nimbusds.jose.jwk.RSAKey;
  import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
  import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
  import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
  import org.springframework.security.oauth2.jwt.JwsHeader;
  import org.springframework.security.oauth2.jwt.JwtClaimsSet;
  import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
  import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

  import java.nio.charset.StandardCharsets;
  import java.security.MessageDigest;
  import java.security.NoSuchAlgorithmException;
  import java.time.Instant;
  import java.util.HexFormat;
  import java.util.List;

  import static com.github.tomakehurst.wiremock.client.WireMock.*;
  import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

  /**
   * 가짜 Apple: 공개키 목록(JWKS), 토큰 교환, 토큰 철회. JVM 하나에 서버 하나.
   * 스프링 테스트는 @DynamicPropertySource로 apple.jwks-uri, apple.auth-base-url을 바꾼다.
   */
  public final class AppleStub {

      public static final String ISSUER = "https://appleid.apple.com";
      public static final String CLIENT_ID = "com.bookshelf.test";   // build.gradle.kts의 APPLE_CLIENT_ID와 같다

      private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());
      private static final RSAKey APPLE_KEY = rsa("apple-test-key");
      private static final RSAKey STRANGER_KEY = rsa("apple-test-key");   // 같은 kid, 다른 키 — 위조

      static {
          SERVER.start();
          reset();
      }

      private AppleStub() {
      }

      public static String baseUrl() {
          return SERVER.baseUrl();
      }

      public static String jwksUri() {
          return SERVER.baseUrl() + "/auth/keys";
      }

      public static WireMockServer server() {
          return SERVER;
      }

      /** JWKS 스텁은 항상 다시 건다 — 다른 스텁을 지워도 공개키는 남아야 한다 */
      public static void reset() {
          SERVER.resetAll();
          SERVER.stubFor(get(urlPathEqualTo("/auth/keys"))
                  .willReturn(okJson(new JWKSet(APPLE_KEY.toPublicJWK()).toString())));
      }

      public static String identityToken(String sub, String aud, String rawNonce, Instant expiresAt) {
          return sign(APPLE_KEY, ISSUER, sub, aud, rawNonce, expiresAt);
      }

      public static String identityToken(String sub, String rawNonce) {
          return identityToken(sub, CLIENT_ID, rawNonce, Instant.now().plusSeconds(600));
      }

      public static String tokenSignedByStranger(String sub, String aud, String rawNonce) {
          return sign(STRANGER_KEY, ISSUER, sub, aud, rawNonce, Instant.now().plusSeconds(600));
      }

      public static String tokenFromIssuer(String issuer, String sub, String rawNonce) {
          return sign(APPLE_KEY, issuer, sub, CLIENT_ID, rawNonce, Instant.now().plusSeconds(600));
      }

      public static String sha256Hex(String s) {
          try {
              return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
          } catch (NoSuchAlgorithmException e) {
              throw new IllegalStateException(e);
          }
      }

      private static String sign(RSAKey key, String issuer, String sub, String aud, String rawNonce, Instant expiresAt) {
          NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
          JwtClaimsSet claims = JwtClaimsSet.builder()
                  .issuer(issuer)
                  .subject(sub)
                  .audience(List.of(aud))
                  .issuedAt(expiresAt.minusSeconds(600))
                  .expiresAt(expiresAt)
                  .claim("nonce", sha256Hex(rawNonce))
                  .build();
          return encoder.encode(JwtEncoderParameters.from(
                  JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(), claims)).getTokenValue();
      }

      private static RSAKey rsa(String kid) {
          try {
              return new RSAKeyGenerator(2048).keyID(kid).generate();
          } catch (JOSEException e) {
              throw new IllegalStateException(e);
          }
      }
  }
  ```

- [ ] **Step 3: 실패하는 테스트**

  `src/test/java/com/bookshelf/auth/apple/ApplePropertiesTest.java`:

  ```java
  package com.bookshelf.auth.apple;

  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.NullSource;
  import org.junit.jupiter.params.provider.ValueSource;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  class ApplePropertiesTest {

      private static AppleProperties with(String clientId, String privateKey) {
          return new AppleProperties(clientId, "TEAM", "KEY", privateKey, "https://appleid.apple.com",
                  "https://appleid.apple.com/auth/keys");
      }

      @ParameterizedTest
      @NullSource
      @ValueSource(strings = {"", "${APPLE_CLIENT_ID}"})
      void 시크릿이_없거나_치환되지_않았으면_기동이_실패한다(String value) {
          assertThatThrownBy(() -> with(value, "k")).isInstanceOf(IllegalStateException.class);
          assertThatThrownBy(() -> with("com.bookshelf", value)).isInstanceOf(IllegalStateException.class);
      }

      @org.junit.jupiter.api.Test
      void 개인키의_구분자를_줄바꿈으로_되돌린다() {
          assertThat(with("com.bookshelf", "-----BEGIN PRIVATE KEY-----|abc|-----END PRIVATE KEY-----").privateKeyPem())
                  .isEqualTo("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----");
      }
  }
  ```

  `src/test/java/com/bookshelf/auth/apple/AppleIdTokenVerifierTest.java`:

  ```java
  package com.bookshelf.auth.apple;

  import com.bookshelf.auth.UnauthorizedException;
  import com.bookshelf.support.AppleStub;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;

  import java.time.Instant;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  /** Review Focus 3: 흉내 낸 토큰은 모두 같은 401 */
  @SpringBootTest
  class AppleIdTokenVerifierTest {

      private static final String NONCE = "raw-nonce-1234";

      @DynamicPropertySource
      static void apple(DynamicPropertyRegistry r) {
          r.add("apple.jwks-uri", AppleStub::jwksUri);
          r.add("apple.auth-base-url", AppleStub::baseUrl);
      }

      @Autowired AppleIdTokenVerifier verifier;

      @BeforeEach
      void setUp() {
          AppleStub.reset();
      }

      @Test
      void 올바른_토큰이면_Apple_사용자_식별자를_준다() {
          AppleIdentity id = verifier.verify(AppleStub.identityToken("001234.abcd", NONCE), NONCE);

          assertThat(id.sub()).isEqualTo("001234.abcd");
      }

      @Test
      void 다른_앱용_토큰은_거부한다() {
          String token = AppleStub.identityToken("001234.abcd", "com.other.app", NONCE, Instant.now().plusSeconds(600));

          assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(UnauthorizedException.class);
      }

      @Test
      void 만료된_토큰은_거부한다() {
          String token = AppleStub.identityToken("001234.abcd", AppleStub.CLIENT_ID, NONCE, Instant.now().minusSeconds(600));

          assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(UnauthorizedException.class);
      }

      @Test
      void Apple_키가_아닌_키로_서명한_토큰은_거부한다() {
          String token = AppleStub.tokenSignedByStranger("001234.abcd", AppleStub.CLIENT_ID, NONCE);

          assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(UnauthorizedException.class);
      }

      @Test
      void 발급자가_Apple이_아니면_거부한다() {
          String token = AppleStub.tokenFromIssuer("https://evil.example", "001234.abcd", NONCE);

          assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(UnauthorizedException.class);
      }

      @Test
      void nonce가_다르면_거부한다() {
          String token = AppleStub.identityToken("001234.abcd", NONCE);

          assertThatThrownBy(() -> verifier.verify(token, "another-nonce")).isInstanceOf(UnauthorizedException.class);
      }

      @Test
      void 토큰_모양이_아니면_거부한다() {
          assertThatThrownBy(() -> verifier.verify("not-a-jwt", NONCE)).isInstanceOf(UnauthorizedException.class);
          assertThatThrownBy(() -> verifier.verify(null, NONCE)).isInstanceOf(UnauthorizedException.class);
      }
  }
  ```

- [ ] **Step 4: 실패 확인**

  Run: `./gradlew test --tests ApplePropertiesTest --tests AppleIdTokenVerifierTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 5: 구현**

  `src/main/java/com/bookshelf/auth/UnauthorizedException.java`:

  ```java
  package com.bookshelf.auth;

  /** 인증 실패. 메시지는 로그용이고 응답에는 나가지 않는다 — 응답은 언제나 같은 401 본문이다 */
  public class UnauthorizedException extends RuntimeException {
      public UnauthorizedException(String reasonForLog) {
          super(reasonForLog);
      }
  }
  ```

  `ApiExceptionHandler`에 추가(import `com.bookshelf.auth.UnauthorizedException`, `org.springframework.http.MediaType`):

  ```java
      /** 필터에서 거부된 401과 본문이 같아야 한다 — 어느 단계에서 실패했는지 드러내지 않는다 */
      @ExceptionHandler(UnauthorizedException.class)
      ResponseEntity<String> unauthorized(UnauthorizedException e) {
          log.debug("401 {}", e.getMessage());
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                  .body(com.bookshelf.auth.ApiAuthenticationEntryPoint.BODY);
      }
  ```

  `ApiAuthenticationEntryPoint.BODY`를 `public static final`로 바꾼다(지금은 package-private).

  `src/main/java/com/bookshelf/auth/apple/AppleProperties.java`:

  ```java
  package com.bookshelf.auth.apple;

  import org.springframework.boot.context.properties.ConfigurationProperties;

  @ConfigurationProperties("apple")
  public record AppleProperties(String clientId, String teamId, String keyId, String privateKey,
                                String authBaseUrl, String jwksUri) {

      public AppleProperties {
          require(clientId, "APPLE_CLIENT_ID");
          require(teamId, "APPLE_TEAM_ID");
          require(keyId, "APPLE_KEY_ID");
          require(privateKey, "APPLE_PRIVATE_KEY");
      }

      /** 환경변수 한 줄에 담으려고 줄바꿈을 |로 바꿔 넣었다 */
      public String privateKeyPem() {
          return privateKey.replace("|", "\n");
      }

      private static void require(String value, String name) {
          if (value == null || value.isBlank() || value.startsWith("${")) {
              throw new IllegalStateException(name + "가 없다 — 기본값 없이 환경변수로 주입해야 한다");
          }
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/apple/AppleIdentity.java`:

  ```java
  package com.bookshelf.auth.apple;

  /** Apple이 보증한 사용자 식별자. 앱마다 다르고 바뀌지 않는다. 이메일은 받지 않는다(개인정보를 들이지 않는다) */
  public record AppleIdentity(String sub) {
  }
  ```

  `src/main/java/com/bookshelf/auth/apple/AppleIdTokenVerifier.java`:

  ```java
  package com.bookshelf.auth.apple;

  import com.bookshelf.auth.UnauthorizedException;
  import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
  import org.springframework.security.oauth2.core.OAuth2TokenValidator;
  import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
  import org.springframework.security.oauth2.jwt.Jwt;
  import org.springframework.security.oauth2.jwt.JwtClaimNames;
  import org.springframework.security.oauth2.jwt.JwtClaimValidator;
  import org.springframework.security.oauth2.jwt.JwtException;
  import org.springframework.security.oauth2.jwt.JwtValidators;
  import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
  import org.springframework.stereotype.Component;

  import java.nio.charset.StandardCharsets;
  import java.security.MessageDigest;
  import java.security.NoSuchAlgorithmException;
  import java.util.HexFormat;
  import java.util.List;

  /**
   * 앱이 보낸 Apple identity token을 서버가 직접 검증한다 (설계 문서 7-1).
   * 서명(Apple JWKS, RS256) · 발급자 · 대상(우리 앱) · 만료 · nonce. 하나라도 틀리면 같은 401.
   * 네이티브 앱에는 리다이렉트가 없어서 스프링이 대신 해 주던 이 검증을 우리가 한다.
   */
  @Component
  public class AppleIdTokenVerifier {

      static final String ISSUER = "https://appleid.apple.com";

      private final NimbusJwtDecoder decoder;

      public AppleIdTokenVerifier(AppleProperties props) {
          this.decoder = NimbusJwtDecoder.withJwkSetUri(props.jwksUri()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
          OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                  aud -> aud != null && aud.contains(props.clientId()));
          decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                  JwtValidators.createDefaultWithIssuer(ISSUER), audience));
      }

      public AppleIdentity verify(String identityToken, String rawNonce) {
          if (identityToken == null || rawNonce == null) {
              throw new UnauthorizedException("Apple 토큰 또는 nonce 없음");
          }
          Jwt jwt;
          try {
              jwt = decoder.decode(identityToken);
          } catch (JwtException e) {
              throw new UnauthorizedException("Apple 토큰 검증 실패: " + e.getMessage());
          }
          // 앱은 raw nonce의 SHA-256을 Apple에 보냈다 — 토큰에는 그 해시가 들어 있다
          if (!sha256Hex(rawNonce).equals(jwt.getClaimAsString("nonce"))) {
              throw new UnauthorizedException("Apple 토큰 nonce 불일치");
          }
          return new AppleIdentity(jwt.getSubject());
      }

      private static String sha256Hex(String s) {
          try {
              return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
          } catch (NoSuchAlgorithmException e) {
              throw new IllegalStateException(e);
          }
      }
  }
  ```

- [ ] **Step 6: 통과 확인**

  Run: `./gradlew test --tests ApplePropertiesTest --tests AppleIdTokenVerifierTest`
  Expected: PASS (4 + 7 = 11개)

  `Apple_키가_아닌_키로_서명한_토큰은_거부한다`가 통과하지 않으면(위조 토큰이 검증된다면) JWKS 스텁과 서명 키가 같은 것이다 — `STRANGER_KEY`가 별도로 생성되는지 본다

- [ ] **Step 7: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `src/main/java/com/bookshelf/auth src/main/java/com/bookshelf/common/ApiExceptionHandler.java src/main/resources/application.yaml src/test/java/com/bookshelf/auth src/test/java/com/bookshelf/support/AppleStub.java`

  ```bash
  git commit -m "feat: Apple identity token을 서버가 직접 검증한다

  앱이 보낸 토큰을 그냥 믿으면 누구나 남의 계정이 된다. 서명(Apple JWKS)·발급자·
  대상(우리 앱)·만료·nonce를 모두 보고, 하나라도 틀리면 같은 401이다.
  nonce는 가로챈 토큰을 다른 로그인 시도에 재사용하지 못하게 한다."
  ```

---

### Task 3: 로그인과 토큰 — refresh 토큰은 해시로만, 재사용은 도난으로 본다

로그인 흐름 (설계 문서 7-1):

```
앱: Apple 로그인 → identityToken + rawNonce (+ 처음이면 이름) → POST /api/auth/apple
서버: identityToken 검증(Task 2) → apple_sub로 회원 찾기, 없으면 만들기(slug 자동) → access(JWT 30분) + refresh(난수 14일)
앱: access로 API 호출, 만료되면 POST /api/auth/refresh → 새 access + 새 refresh (옛 refresh는 끝)
```

- **refresh 토큰은 JWT가 아니다.** 32바이트 난수이고 DB에는 SHA-256 해시만 둔다. DB가 새어도 토큰으로 쓸 수 없다. JWT가 아니므로 "refresh 토큰으로 API를 호출하는" 혼동(spring-study의 `tokenType` 문제)도 생기지 않는다
- **회전 + 재사용 감지 (Review Focus 2).** refresh를 쓰면 그 토큰은 `used_at`이 찍히고 새 토큰이 나간다. **이미 쓴 토큰이 다시 오면** 누군가 훔쳐 쓴 것으로 보고 그 회원의 refresh 토큰을 **모두** 폐기한다. 대가: 앱이 같은 refresh로 동시에 두 번 요청하면(네트워크 재시도) 정상 사용자도 로그아웃된다 — 앱은 refresh 요청을 한 번에 하나만 보낸다(Phase 3 앱 규칙)

**Files:**
- Create: `src/main/resources/db/migration/V2__auth.sql`
- Modify: `src/main/java/com/bookshelf/member/domain/Member.java`, `member/infrastructure/MemberRepository.java`
- Create: `src/main/java/com/bookshelf/member/application/SlugGenerator.java`
- Create: `src/main/java/com/bookshelf/auth/TokenIssuer.java`, `RefreshTokenStore.java`, `AuthTokens.java`, `AuthService.java`, `AuthController.java`
- Modify: `src/test/java/com/bookshelf/support/DbCleaner.java`
- Test: `src/test/java/com/bookshelf/member/application/SlugGeneratorTest.java`, `src/test/java/com/bookshelf/auth/AuthFlowTest.java`

**Interfaces:**
- Consumes: `AppleIdTokenVerifier#verify`, `JwtEncoder`, `JwtProperties`, `Member.register`, `Clock`
- Produces:
  - `member.apple_sub` (UNIQUE, nullable — 탈퇴하면 비운다), `refresh_token(id, member_id, token_hash, expires_at, created_at, used_at)`
  - `Member.registerWithApple(String appleSub, String slug, String displayName, Instant now)`, getter `appleSub`
  - `MemberRepository#findByAppleSub(String) -> Optional<Member>`, `#existsBySlug(String) -> boolean`
  - `SlugGenerator#next() -> String` (`reader-` + 소문자·숫자 6자, 이미 있으면 다시 뽑음)
  - `TokenIssuer#access(long memberId, Instant now) -> String`
  - `RefreshTokenStore#issue(long memberId, Instant now) -> String`(원문), `#rotate(String raw, Instant now) -> Rotated(long memberId, String newRaw)`(실패는 `UnauthorizedException`), `#revoke(String raw)`, `#revokeAll(long memberId)`
  - `record AuthTokens(String accessToken, String refreshToken, long expiresIn, boolean newMember)`
  - HTTP: `POST /api/auth/apple {identityToken, nonce, displayName?}`, `POST /api/auth/refresh {refreshToken}`, `POST /api/auth/logout {refreshToken}` (204)

- [ ] **Step 1: 스키마** — `src/main/resources/db/migration/V2__auth.sql`

  ```sql
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
  ```

  `DbCleaner`의 TRUNCATE 목록에 `refresh_token`을 더한다.

- [ ] **Step 2: 실패하는 테스트**

  `src/test/java/com/bookshelf/member/application/SlugGeneratorTest.java`:

  ```java
  package com.bookshelf.member.application;

  import com.bookshelf.member.domain.Member;
  import org.junit.jupiter.api.Test;

  import java.time.Instant;

  import static org.assertj.core.api.Assertions.assertThat;

  class SlugGeneratorTest {

      @Test
      void reader_뒤에_소문자와_숫자_6자이고_slug_규칙을_지킨다() {
          SlugGenerator g = new SlugGenerator(slug -> false);

          String slug = g.next();

          assertThat(slug).matches("reader-[a-z0-9]{6}");
          Member.register(slug, "독자", Instant.now());   // 규칙을 어기면 여기서 예외
      }

      @Test
      void 이미_있는_slug면_다시_뽑는다() {
          // 처음 한 번은 반드시 겹치게 만든다
          int[] calls = {0};
          SlugGenerator g = new SlugGenerator(s -> calls[0]++ == 0);

          assertThat(g.next()).isNotNull();
          assertThat(calls[0]).isEqualTo(2);
      }
  }
  ```

  `src/test/java/com/bookshelf/auth/AuthFlowTest.java`:

  ```java
  package com.bookshelf.auth;

  import com.bookshelf.support.AppleStub;
  import com.bookshelf.support.DbCleaner;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.http.MediaType;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;
  import org.springframework.test.web.servlet.MockMvc;
  import org.springframework.test.web.servlet.MvcResult;
  import tools.jackson.databind.JsonNode;
  import tools.jackson.databind.ObjectMapper;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @SpringBootTest
  @AutoConfigureMockMvc
  class AuthFlowTest {

      private static final String NONCE = "raw-nonce-1234";
      private static final String SUB = "001234.apple-user";

      @DynamicPropertySource
      static void apple(DynamicPropertyRegistry r) {
          r.add("apple.jwks-uri", AppleStub::jwksUri);
          r.add("apple.auth-base-url", AppleStub::baseUrl);
      }

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired ObjectMapper json;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          AppleStub.reset();
      }

      private MvcResult login(String sub, String displayName) throws Exception {
          String body = json.writeValueAsString(java.util.Map.of(
                  "identityToken", AppleStub.identityToken(sub, NONCE), "nonce", NONCE,
                  "displayName", displayName == null ? "" : displayName));
          return mvc.perform(post("/api/auth/apple").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
      }

      private JsonNode body(MvcResult r) throws Exception {
          return json.readTree(r.getResponse().getContentAsString());
      }

      private MvcResult refresh(String refreshToken) throws Exception {
          return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                  .content("{\"refreshToken\":\"" + refreshToken + "\"}")).andReturn();
      }

      @Test
      void 처음_로그인하면_회원을_만들고_토큰을_준다() throws Exception {
          MvcResult r = login(SUB, "주희");

          assertThat(r.getResponse().getStatus()).isEqualTo(200);
          JsonNode b = body(r);
          assertThat(b.get("newMember").asBoolean()).isTrue();
          assertThat(b.get("accessToken").asString()).isNotBlank();
          assertThat(b.get("refreshToken").asString()).isNotBlank();
          assertThat(jdbc.sql("SELECT slug FROM member WHERE apple_sub = :s").param("s", SUB).query(String.class).single())
                  .matches("reader-[a-z0-9]{6}");
          assertThat(jdbc.sql("SELECT display_name FROM member").query(String.class).single()).isEqualTo("주희");
      }

      @Test
      void 이름을_주지_않으면_기본_이름으로_만든다() throws Exception {
          login(SUB, null);

          assertThat(jdbc.sql("SELECT display_name FROM member").query(String.class).single()).isEqualTo("독자");
      }

      @Test
      void 같은_Apple_계정으로_다시_로그인하면_같은_회원이다() throws Exception {
          login(SUB, "주희");
          MvcResult again = login(SUB, "다른 이름");

          assertThat(body(again).get("newMember").asBoolean()).isFalse();
          assertThat(jdbc.sql("SELECT count(*) FROM member").query(Integer.class).single()).isEqualTo(1);
          assertThat(jdbc.sql("SELECT display_name FROM member").query(String.class).single()).isEqualTo("주희");
      }

      @Test
      void 흉내_낸_Apple_토큰은_같은_401이다() throws Exception {
          String forged = json.writeValueAsString(java.util.Map.of(
                  "identityToken", AppleStub.tokenSignedByStranger(SUB, AppleStub.CLIENT_ID, NONCE), "nonce", NONCE));
          MvcResult r = mvc.perform(post("/api/auth/apple").contentType(MediaType.APPLICATION_JSON).content(forged)).andReturn();

          assertThat(r.getResponse().getStatus()).isEqualTo(401);
          assertThat(r.getResponse().getContentAsString())
                  .isEqualTo(mvc.perform(get("/api/me")).andReturn().getResponse().getContentAsString());
          assertThat(jdbc.sql("SELECT count(*) FROM member").query(Integer.class).single()).isZero();
      }

      @Test
      void 발급한_access_토큰으로_API를_통과한다() throws Exception {
          String access = body(login(SUB, "주희")).get("accessToken").asString();

          int status = mvc.perform(get("/api/me").header("Authorization", "Bearer " + access))
                  .andReturn().getResponse().getStatus();
          assertThat(status).isNotEqualTo(401);
      }

      @Test
      void refresh하면_새_토큰_쌍을_주고_옛_refresh는_다시_못_쓴다() throws Exception {
          String r1 = body(login(SUB, "주희")).get("refreshToken").asString();

          MvcResult rotated = refresh(r1);
          assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
          String r2 = body(rotated).get("refreshToken").asString();
          assertThat(r2).isNotEqualTo(r1);

          assertThat(refresh(r1).getResponse().getStatus()).isEqualTo(401);
      }

      /** Review Focus 2: 이미 쓴 토큰이 다시 오면 도난으로 보고 그 회원의 모든 refresh를 폐기한다 */
      @Test
      void 이미_쓴_refresh가_다시_오면_그_회원의_모든_refresh를_폐기한다() throws Exception {
          String phone = body(login(SUB, "주희")).get("refreshToken").asString();
          String tablet = body(login(SUB, "주희")).get("refreshToken").asString();
          String phone2 = body(refresh(phone)).get("refreshToken").asString();

          assertThat(refresh(phone).getResponse().getStatus()).isEqualTo(401);    // 재사용 → 전부 폐기

          assertThat(refresh(phone2).getResponse().getStatus()).isEqualTo(401);
          assertThat(refresh(tablet).getResponse().getStatus()).isEqualTo(401);
      }

      @Test
      void 만료된_refresh는_401이다() throws Exception {
          String r1 = body(login(SUB, "주희")).get("refreshToken").asString();
          jdbc.sql("UPDATE refresh_token SET expires_at = now() - interval '1 minute'").update();

          assertThat(refresh(r1).getResponse().getStatus()).isEqualTo(401);
      }

      @Test
      void 로그아웃한_refresh는_401이다() throws Exception {
          String r1 = body(login(SUB, "주희")).get("refreshToken").asString();
          mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                  .content("{\"refreshToken\":\"" + r1 + "\"}")).andExpect(status().isNoContent());

          assertThat(refresh(r1).getResponse().getStatus()).isEqualTo(401);
      }

      @Test
      void DB에는_refresh_원문이_없다() throws Exception {
          String r1 = body(login(SUB, "주희")).get("refreshToken").asString();

          assertThat(jdbc.sql("SELECT count(*) FROM refresh_token WHERE token_hash = :r").param("r", r1)
                  .query(Integer.class).single()).isZero();
          assertThat(jdbc.sql("SELECT count(*) FROM refresh_token WHERE token_hash = :h")
                  .param("h", AppleStub.sha256Hex(r1)).query(Integer.class).single()).isEqualTo(1);
      }
  }
  ```

- [ ] **Step 3: 실패 확인**

  Run: `./gradlew test --tests SlugGeneratorTest --tests AuthFlowTest`
  Expected: FAIL — 컴파일 실패 (`SlugGenerator` 등 없음)

- [ ] **Step 4: 구현 — 회원**

  `Member.java`에 필드와 팩토리를 더한다:

  ```java
      private String appleSub;

      /** Apple 로그인으로 처음 들어온 사람. 서재는 닫혀 있고 기본 공개 범위는 비공개다(register와 같다) */
      public static Member registerWithApple(String appleSub, String slug, String displayName, Instant now) {
          if (appleSub == null || appleSub.isBlank()) {
              throw new DomainException("Apple 사용자 식별자가 없다");
          }
          Member m = register(slug, displayName, now);
          m.appleSub = appleSub;
          return m;
      }
  ```

  `MemberRepository.java`:

  ```java
  public interface MemberRepository extends JpaRepository<Member, Long> {

      Optional<Member> findByAppleSub(String appleSub);

      boolean existsBySlug(String slug);
  }
  ```

  (import `java.util.Optional`)

  `src/main/java/com/bookshelf/member/application/SlugGenerator.java`:

  ```java
  package com.bookshelf.member.application;

  import com.bookshelf.member.infrastructure.MemberRepository;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.stereotype.Component;

  import java.security.SecureRandom;
  import java.util.function.Predicate;

  /**
   * 가입 시 자동 slug. 가입이 3초 기록을 막지 않게 한다 — 원하는 주소는 나중에 바꾼다(Task 5).
   * 36^6 ≈ 21억 가지라 겹칠 일은 드물지만, 겹치면 다시 뽑는다.
   */
  @Component
  public class SlugGenerator {

      private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
      private static final int MAX_TRIES = 10;
      private final SecureRandom random = new SecureRandom();
      private final Predicate<String> taken;

      @Autowired
      public SlugGenerator(MemberRepository members) {
          this(members::existsBySlug);
      }

      SlugGenerator(Predicate<String> taken) {
          this.taken = taken;
      }

      public String next() {
          for (int i = 0; i < MAX_TRIES; i++) {
              StringBuilder sb = new StringBuilder("reader-");
              for (int j = 0; j < 6; j++) {
                  sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
              }
              String slug = sb.toString();
              if (!taken.test(slug)) {
                  return slug;
              }
          }
          throw new IllegalStateException("slug를 " + MAX_TRIES + "번 뽑아도 겹쳤다");
      }
  }
  ```

- [ ] **Step 5: 구현 — 토큰**

  `src/main/java/com/bookshelf/auth/TokenIssuer.java`:

  ```java
  package com.bookshelf.auth;

  import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
  import org.springframework.security.oauth2.jwt.JwsHeader;
  import org.springframework.security.oauth2.jwt.JwtClaimsSet;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
  import org.springframework.stereotype.Component;

  import java.time.Instant;

  /** access 토큰. payload에는 회원 id뿐이다 — Base64는 암호화가 아니라 누구나 읽는다 */
  @Component
  public class TokenIssuer {

      private final JwtEncoder encoder;
      private final JwtProperties props;

      public TokenIssuer(JwtEncoder encoder, JwtProperties props) {
          this.encoder = encoder;
          this.props = props;
      }

      public String access(long memberId, Instant now) {
          JwtClaimsSet claims = JwtClaimsSet.builder()
                  .issuer(JwtConfig.ISSUER)
                  .subject(Long.toString(memberId))
                  .issuedAt(now)
                  .expiresAt(now.plus(props.accessTokenTtl()))
                  .build();
          return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                  .getTokenValue();
      }

      public long accessTtlSeconds() {
          return props.accessTokenTtl().toSeconds();
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/RefreshTokenStore.java`:

  ```java
  package com.bookshelf.auth;

  import lombok.extern.slf4j.Slf4j;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;
  import org.springframework.transaction.annotation.Transactional;

  import java.nio.charset.StandardCharsets;
  import java.security.MessageDigest;
  import java.security.NoSuchAlgorithmException;
  import java.security.SecureRandom;
  import java.sql.Timestamp;
  import java.time.Instant;
  import java.time.OffsetDateTime;
  import java.util.Base64;
  import java.util.HexFormat;

  /**
   * refresh 토큰: 32바이트 난수. DB에는 SHA-256 해시만.
   * 회전: 쓰면 used_at이 찍히고 새 토큰이 나간다. 찍힌 토큰이 다시 오면 도난으로 보고 전부 폐기한다.
   */
  @Slf4j
  @Component
  public class RefreshTokenStore {

      public record Rotated(long memberId, String newRaw) {
      }

      private record Row(long id, long memberId, Instant expiresAt, Instant usedAt) {
      }

      private final JdbcClient jdbc;
      private final JwtProperties props;
      private final SecureRandom random = new SecureRandom();

      public RefreshTokenStore(JdbcClient jdbc, JwtProperties props) {
          this.jdbc = jdbc;
          this.props = props;
      }

      public String issue(long memberId, Instant now) {
          byte[] bytes = new byte[32];
          random.nextBytes(bytes);
          String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
          jdbc.sql("""
                  INSERT INTO refresh_token (member_id, token_hash, expires_at, created_at)
                  VALUES (:m, :h, :exp, :now)
                  """)
                  .param("m", memberId)
                  .param("h", hash(raw))
                  .param("exp", Timestamp.from(now.plus(props.refreshTokenTtl())))
                  .param("now", Timestamp.from(now))
                  .update();
          return raw;
      }

      /**
       * 한 트랜잭션 안에서 행을 잠그고 판정한다 — 같은 토큰의 동시 요청 둘이 모두 통과하지 않게.
       * noRollbackFor: 재사용을 감지해 지운 행은 UnauthorizedException과 함께 롤백되면 안 된다(폐기가 남아야 한다)
       */
      @Transactional(noRollbackFor = UnauthorizedException.class)
      public Rotated rotate(String raw, Instant now) {
          Row row = jdbc.sql("SELECT id, member_id, expires_at, used_at FROM refresh_token WHERE token_hash = :h FOR UPDATE")
                  .param("h", hash(raw))
                  .query((rs, n) -> new Row(rs.getLong("id"), rs.getLong("member_id"),
                          rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                          rs.getObject("used_at", OffsetDateTime.class) == null ? null
                                  : rs.getObject("used_at", OffsetDateTime.class).toInstant()))
                  .optional()
                  .orElseThrow(() -> new UnauthorizedException("모르는 refresh 토큰"));

          if (row.usedAt() != null) {
              revokeAll(row.memberId());
              log.warn("refresh 토큰 재사용 감지 — 회원 {}의 refresh 토큰을 모두 폐기했다", row.memberId());
              throw new UnauthorizedException("이미 쓴 refresh 토큰");
          }
          if (!row.expiresAt().isAfter(now)) {
              throw new UnauthorizedException("만료된 refresh 토큰");
          }
          jdbc.sql("UPDATE refresh_token SET used_at = :now WHERE id = :id")
                  .param("now", Timestamp.from(now)).param("id", row.id()).update();
          return new Rotated(row.memberId(), issue(row.memberId(), now));
      }

      public void revoke(String raw) {
          jdbc.sql("DELETE FROM refresh_token WHERE token_hash = :h").param("h", hash(raw)).update();
      }

      public void revokeAll(long memberId) {
          jdbc.sql("DELETE FROM refresh_token WHERE member_id = :m").param("m", memberId).update();
      }

      static String hash(String raw) {
          try {
              return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
          } catch (NoSuchAlgorithmException e) {
              throw new IllegalStateException(e);
          }
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/AuthTokens.java`:

  ```java
  package com.bookshelf.auth;

  /** 응답에 refresh 원문이 담긴다 — record 기본 toString이 로그에 원문을 남기지 않게 재정의한다 */
  public record AuthTokens(String accessToken, String refreshToken, long expiresIn, boolean newMember) {
      @Override
      public String toString() {
          return "AuthTokens[expiresIn=" + expiresIn + ", newMember=" + newMember + "]";
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/AuthService.java`:

  ```java
  package com.bookshelf.auth;

  import com.bookshelf.auth.apple.AppleIdTokenVerifier;
  import com.bookshelf.auth.apple.AppleIdentity;
  import com.bookshelf.member.application.SlugGenerator;
  import com.bookshelf.member.domain.Member;
  import com.bookshelf.member.infrastructure.MemberRepository;
  import org.springframework.stereotype.Service;

  import java.time.Clock;
  import java.time.Instant;
  import java.util.Optional;

  @Service
  public class AuthService {

      static final String DEFAULT_NAME = "독자";

      private final AppleIdTokenVerifier apple;
      private final MemberRepository members;
      private final SlugGenerator slugs;
      private final TokenIssuer tokens;
      private final RefreshTokenStore refresh;
      private final Clock clock;

      public AuthService(AppleIdTokenVerifier apple, MemberRepository members, SlugGenerator slugs,
                         TokenIssuer tokens, RefreshTokenStore refresh, Clock clock) {
          this.apple = apple;
          this.members = members;
          this.slugs = slugs;
          this.tokens = tokens;
          this.refresh = refresh;
          this.clock = clock;
      }

      /**
       * Apple 토큰 검증은 JWKS를 부를 수 있다(캐시가 비었을 때). 그래서 검증을 먼저, 트랜잭션 밖에서 한다.
       * Apple은 이름을 처음 로그인 때만 준다 — 이미 있는 회원의 이름은 바꾸지 않는다.
       */
      public AuthTokens loginWithApple(String identityToken, String rawNonce, String displayName) {
          AppleIdentity id = apple.verify(identityToken, rawNonce);
          Instant now = clock.instant();
          Optional<Member> existing = members.findByAppleSub(id.sub());
          Member member = existing.orElseGet(() -> register(id, displayName, now));
          return issue(member.getId(), now, existing.isEmpty());
      }

      public AuthTokens refresh(String raw) {
          if (raw == null || raw.isBlank()) {
              throw new UnauthorizedException("refresh 토큰 없음");
          }
          Instant now = clock.instant();
          RefreshTokenStore.Rotated r = refresh.rotate(raw, now);
          return new AuthTokens(tokens.access(r.memberId(), now), r.newRaw(), tokens.accessTtlSeconds(), false);
      }

      public void logout(String raw) {
          if (raw != null && !raw.isBlank()) {
              refresh.revoke(raw);
          }
      }

      /** save 한 번이라 리포지터리의 트랜잭션으로 충분하다. 같은 클래스에서 부르므로 @Transactional을 붙여도 적용되지 않는다 */
      private Member register(AppleIdentity id, String displayName, Instant now) {
          String name = displayName == null || displayName.isBlank() ? DEFAULT_NAME : displayName;
          return members.save(Member.registerWithApple(id.sub(), slugs.next(), name, now));
      }

      private AuthTokens issue(long memberId, Instant now, boolean newMember) {
          return new AuthTokens(tokens.access(memberId, now), refresh.issue(memberId, now),
                  tokens.accessTtlSeconds(), newMember);
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/AuthController.java`:

  ```java
  package com.bookshelf.auth;

  import org.springframework.http.ResponseEntity;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;

  @RestController
  @RequestMapping("/api/auth")
  public class AuthController {

      /** displayName은 Apple이 처음 로그인 때만 준다. 없으면 기본 이름 */
      public record AppleLoginRequest(String identityToken, String nonce, String displayName) {
      }

      public record RefreshRequest(String refreshToken) {
          @Override
          public String toString() {
              return "RefreshRequest[refreshToken=***]";   // 로그에 원문이 남지 않게
          }
      }

      private final AuthService auth;

      public AuthController(AuthService auth) {
          this.auth = auth;
      }

      @PostMapping("/apple")
      public AuthTokens apple(@RequestBody AppleLoginRequest req) {
          return auth.loginWithApple(req.identityToken(), req.nonce(), req.displayName());
      }

      @PostMapping("/refresh")
      public AuthTokens refresh(@RequestBody RefreshRequest req) {
          return auth.refresh(req.refreshToken());
      }

      @PostMapping("/logout")
      public ResponseEntity<Void> logout(@RequestBody RefreshRequest req) {
          auth.logout(req.refreshToken());
          return ResponseEntity.noContent().build();
      }
  }
  ```

- [ ] **Step 6: 통과 확인**

  Run: `./gradlew test --tests SlugGeneratorTest --tests AuthFlowTest`
  Expected: PASS (2 + 10 = 12개)

- [ ] **Step 7: 대조군 — 재사용 감지가 정말 폐기를 남기는가**

  `rotate`의 `noRollbackFor = UnauthorizedException.class`를 지우고 `이미_쓴_refresh가_다시_오면…`만 돌린다 → **FAIL**(tablet 토큰이 여전히 통한다 — 폐기가 롤백됐다). 되돌리고 PASS를 확인한다. 결과를 커밋 메시지에 적는다

- [ ] **Step 8: 전체 테스트 후 커밋 (사용자 허용 후)**

  Run: `./gradlew test` → 전부 PASS

  허용을 요청할 경로: `src/main/resources/db/migration/V2__auth.sql src/main/java/com/bookshelf/member src/main/java/com/bookshelf/auth src/test/java/com/bookshelf/member src/test/java/com/bookshelf/auth src/test/java/com/bookshelf/support/DbCleaner.java`

  ```bash
  git commit -m "feat: Apple 로그인과 토큰 — refresh는 해시로만, 재사용은 도난으로 본다

  refresh 토큰은 JWT가 아닌 32바이트 난수이고 DB에는 SHA-256만 둔다.
  회전된 토큰이 다시 오면 그 회원의 refresh를 모두 폐기한다.
  대조군: 폐기가 예외와 함께 롤백되면 다른 기기 토큰이 계속 통했다 — noRollbackFor로 남긴다.
  가입 시 slug는 reader-xxxxxx로 자동 생성한다(나중에 변경)."
  ```

---

### Task 4: 사용자별 호출 상한 — 스크립트 하나가 한도를 태우지 못하게

설계 문서 6-3: 서로 다른 검색어·없는 ISBN은 캐시에 걸리지 않으므로, 로그인한 스크립트 하나가 카카오 27,000을 다 쓸 수 있다. **사용자별 하루 300회**를 둔다.

어디서 세는가: 설계는 "캐시에 걸린 요청은 세지 않는다"고 했다. 실제로 제공자를 부르는 순간에만 세면 저절로 그렇게 된다. 모든 제공자 호출은 `CallBudget`을 지나므로(Phase 1 규칙), **`CallBudget#acquire` 한 곳**에서 사용자 상한 → 제공자 상한 순으로 본다. 사용자를 먼저 보는 이유: 상한을 넘은 사용자의 요청이 제공자 예산을 깎지 않게.

누가 사용자인가: `CurrentMember`(토큰의 `sub`). 백그라운드 작업(갱신 큐, 백필, 도서관 동기화)에는 인증이 없으므로 사용자 상한을 보지 않는다.

**Files:**
- Create: `src/main/resources/db/migration/V3__user_quota.sql`
- Create: `src/main/java/com/bookshelf/provider/UserQuotaProperties.java`, `UserQuotaExceededException.java`
- Modify: `src/main/java/com/bookshelf/provider/CallBudget.java` (`acquire` 추가, 생성자에 `UserQuotaProperties`)
- Modify: `src/main/java/com/bookshelf/catalog/kakao/KakaoBookCatalog.java` (`tryAcquire` → `acquire`)
- Modify: `src/main/java/com/bookshelf/common/ApiExceptionHandler.java` (429), `application.yaml`
- Modify: `src/test/java/com/bookshelf/provider/CallBudgetTest.java`, `catalog/kakao/KakaoBookCatalogTest.java` (생성자), `support/DbCleaner.java`
- Test: `src/test/java/com/bookshelf/provider/UserQuotaTest.java`
- Create (test 지원): `src/test/java/com/bookshelf/support/AsMember.java`

**Interfaces:**
- Consumes: `CurrentMember.id()`, `provider_call_counter`
- Produces:
  - `user_call_counter(member_id, day, calls)` — 날짜는 `Asia/Seoul`
  - `record UserQuotaProperties(int dailyLimit)` prefix `providers.user`
  - `CallBudget#acquire(BudgetPolicy, CallPurpose)` — 사람의 요청(`INTERACTIVE` + 인증됨)이면 사용자 상한부터, 넘으면 `UserQuotaExceededException`. 이어서 제공자 상한, 넘으면 `BudgetExhaustedException`. `tryAcquire`는 그대로 남긴다(제공자만 보는 것)
  - `UserQuotaExceededException` (RuntimeException — `ProviderUnavailableException`의 하위가 **아니다**. 하위면 검색이 지난 캐시로 조용히 넘겨 버려 사용자가 한도를 알 수 없다) → 429 `TOO_MANY_REQUESTS`
  - 테스트 지원: `AsMember.run(long memberId, Runnable)` — SecurityContext에 그 회원의 JWT 인증을 넣고 실행

- [ ] **Step 1: 스키마와 설정**

  `src/main/resources/db/migration/V3__user_quota.sql`:

  ```sql
  -- V3: 사용자별 하루 제공자 호출 수 (Phase 2 Task 4). 캐시에 걸린 요청은 여기 오지 않는다
  CREATE TABLE user_call_counter (
      member_id  BIGINT   NOT NULL,
      day        DATE     NOT NULL,      -- Asia/Seoul 기준
      calls      INTEGER  NOT NULL,
      PRIMARY KEY (member_id, day)
  );
  ```

  `application.yaml`의 `providers:` 아래에:

  ```yaml
    user:
      daily-limit: 300    # 사용자별 하루 제공자 호출 (캐시 미스만 센다) — 설계 문서 6-3
  ```

  `DbCleaner`의 TRUNCATE 목록에 `user_call_counter`를 더한다.

- [ ] **Step 2: 테스트 지원**

  `src/test/java/com/bookshelf/support/AsMember.java`:

  ```java
  package com.bookshelf.support;

  import org.springframework.security.core.context.SecurityContextHolder;
  import org.springframework.security.oauth2.jwt.Jwt;
  import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

  import java.time.Instant;

  /** 서비스 테스트에서 "이 회원의 요청"을 흉내 낸다. HTTP를 거치지 않을 때 쓴다 */
  public final class AsMember {

      private AsMember() {
      }

      public static void run(long memberId, Runnable body) {
          Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject(Long.toString(memberId))
                  .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
          SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
          try {
              body.run();
          } finally {
              SecurityContextHolder.clearContext();
          }
      }
  }
  ```

- [ ] **Step 3: 실패하는 테스트**

  `src/test/java/com/bookshelf/provider/UserQuotaTest.java`:

  ```java
  package com.bookshelf.provider;

  import com.bookshelf.support.AsMember;
  import com.bookshelf.support.DbCleaner;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.jdbc.core.simple.JdbcClient;

  import java.time.Clock;
  import java.time.Instant;
  import java.time.ZoneId;

  import static com.bookshelf.provider.CallPurpose.BACKGROUND;
  import static com.bookshelf.provider.CallPurpose.INTERACTIVE;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  @SpringBootTest
  class UserQuotaTest {

      private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
      private static final BudgetPolicy KAKAO = new BudgetPolicy(Provider.KAKAO, 100, 100, SEOUL);

      @Autowired JdbcClient jdbc;

      private CallBudget budget;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          budget = new CallBudget(jdbc, Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"), SEOUL), new UserQuotaProperties(2));
      }

      private int providerCalls() {
          return budget.usedToday(KAKAO);
      }

      @Test
      void 사람의_요청은_사용자별_상한에서_멈춘다() {
          AsMember.run(7, () -> {
              budget.acquire(KAKAO, INTERACTIVE);
              budget.acquire(KAKAO, INTERACTIVE);
              assertThatThrownBy(() -> budget.acquire(KAKAO, INTERACTIVE)).isInstanceOf(UserQuotaExceededException.class);
          });
      }

      @Test
      void 사용자_상한에_걸린_요청은_제공자_예산을_깎지_않는다() {
          AsMember.run(7, () -> {
              budget.acquire(KAKAO, INTERACTIVE);
              budget.acquire(KAKAO, INTERACTIVE);
              assertThatThrownBy(() -> budget.acquire(KAKAO, INTERACTIVE)).isInstanceOf(UserQuotaExceededException.class);
          });

          assertThat(providerCalls()).isEqualTo(2);
      }

      @Test
      void 사용자끼리_섞이지_않는다() {
          AsMember.run(7, () -> {
              budget.acquire(KAKAO, INTERACTIVE);
              budget.acquire(KAKAO, INTERACTIVE);
          });
          AsMember.run(8, () -> budget.acquire(KAKAO, INTERACTIVE));   // 예외 없음
      }

      @Test
      void 백그라운드_호출은_사용자_상한을_보지_않는다() {
          AsMember.run(7, () -> {
              for (int i = 0; i < 5; i++) {
                  budget.acquire(KAKAO, BACKGROUND);
              }
          });
          assertThat(jdbc.sql("SELECT count(*) FROM user_call_counter").query(Integer.class).single()).isZero();
      }

      @Test
      void 인증이_없는_요청은_사용자_상한을_보지_않는다() {
          for (int i = 0; i < 5; i++) {
              budget.acquire(KAKAO, INTERACTIVE);   // 백그라운드 작업처럼 SecurityContext가 비어 있다
          }
          assertThat(providerCalls()).isEqualTo(5);
      }

      @Test
      void 제공자_상한은_그대로_지킨다() {
          CallBudget tight = new CallBudget(jdbc, Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"), SEOUL),
                  new UserQuotaProperties(100));
          BudgetPolicy one = new BudgetPolicy(Provider.KAKAO, 1, 1, SEOUL);
          AsMember.run(7, () -> {
              tight.acquire(one, INTERACTIVE);
              assertThatThrownBy(() -> tight.acquire(one, INTERACTIVE)).isInstanceOf(BudgetExhaustedException.class);
          });
      }
  }
  ```

- [ ] **Step 4: 실패 확인**

  Run: `./gradlew test --tests UserQuotaTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 5: 구현**

  `src/main/java/com/bookshelf/provider/UserQuotaProperties.java`:

  ```java
  package com.bookshelf.provider;

  import org.springframework.boot.context.properties.ConfigurationProperties;

  @ConfigurationProperties("providers.user")
  public record UserQuotaProperties(int dailyLimit) {
  }
  ```

  `src/main/java/com/bookshelf/provider/UserQuotaExceededException.java`:

  ```java
  package com.bookshelf.provider;

  /**
   * 한 사용자가 오늘 제공자를 너무 많이 불렀다. 그 사용자만 "잠시 후 다시"다.
   * ProviderUnavailableException의 하위로 두지 않는다 — 하위면 검색이 지난 캐시로 조용히 대신해
   * 사용자도 우리도 상한에 걸렸다는 사실을 모른다.
   */
  public class UserQuotaExceededException extends RuntimeException {
      public UserQuotaExceededException(long memberId) {
          super("사용자 " + memberId + "의 오늘 제공자 호출 상한 초과");
      }
  }
  ```

  `CallBudget.java` — 생성자와 `acquire`를 더한다(기존 `tryAcquire`, `usedToday`는 그대로):

  ```java
      private final JdbcClient jdbc;
      private final Clock clock;
      private final UserQuotaProperties userQuota;

      public CallBudget(JdbcClient jdbc, Clock clock, UserQuotaProperties userQuota) {
          this.jdbc = jdbc;
          this.clock = clock;
          this.userQuota = userQuota;
      }

      /**
       * 제공자를 실제로 부르기 직전에 부른다. 캐시에 걸린 요청은 여기까지 오지 않으므로 세지 않는다.
       * 사람의 요청이면 사용자 상한부터 본다 — 상한을 넘은 요청이 제공자 예산을 깎지 않게.
       */
      public void acquire(BudgetPolicy policy, CallPurpose purpose) {
          if (purpose == CallPurpose.INTERACTIVE) {
              CurrentMember.id().ifPresent(this::acquireForMember);
          }
          if (!tryAcquire(policy, purpose)) {
              throw new BudgetExhaustedException(policy.provider(), purpose);
          }
      }

      private void acquireForMember(long memberId) {
          List<Integer> after = jdbc.sql("""
                  INSERT INTO user_call_counter (member_id, day, calls) VALUES (:m, :day, 1)
                  ON CONFLICT (member_id, day) DO UPDATE SET calls = user_call_counter.calls + 1
                  WHERE user_call_counter.calls < :limit
                  RETURNING calls
                  """)
                  .param("m", memberId)
                  .param("day", LocalDate.ofInstant(clock.instant(), ClockConfig.SEOUL))
                  .param("limit", userQuota.dailyLimit())
                  .query(Integer.class)
                  .list();
          if (after.isEmpty()) {
              log.warn("사용자 {} 오늘 제공자 호출 상한({}) 초과", memberId, userQuota.dailyLimit());
              throw new UserQuotaExceededException(memberId);
          }
      }
  ```

  (import `com.bookshelf.common.ClockConfig`, `com.bookshelf.common.CurrentMember`)

  `KakaoBookCatalog.call`의 예산 부분을 바꾼다:

  ```java
          budget.acquire(props.policy(), purpose);   // 사용자 상한 → 제공자 상한. 넘으면 예외
  ```

  (기존 `if (!budget.tryAcquire(...)) throw new BudgetExhaustedException(...)` 두 줄을 이 한 줄로 바꾼다)

  `ApiExceptionHandler`에 추가(import `com.bookshelf.provider.UserQuotaExceededException`):

  ```java
      @ExceptionHandler(UserQuotaExceededException.class)
      ResponseEntity<ApiError> tooMany(UserQuotaExceededException e) {
          log.debug("429 {}", e.getMessage());
          return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                  .body(new ApiError("TOO_MANY_REQUESTS", "오늘은 검색을 너무 많이 했어요. 내일 다시 시도해 주세요"));
      }
  ```

  테스트 두 곳의 `new CallBudget(jdbc, clock)`을 `new CallBudget(jdbc, clock, new UserQuotaProperties(300))`로 바꾼다(`CallBudgetTest.budgetAt`, `KakaoBookCatalogTest.catalog`).

- [ ] **Step 6: 통과 확인**

  Run: `./gradlew test --tests UserQuotaTest --tests CallBudgetTest --tests KakaoBookCatalogTest`
  Expected: PASS (UserQuotaTest 6개, 나머지는 Phase 1 그대로)

- [ ] **Step 7: 전체 테스트 후 커밋 (사용자 허용 후)**

  Run: `./gradlew test` → 전부 PASS

  허용을 요청할 경로: `src/main/resources/db/migration/V3__user_quota.sql src/main/resources/application.yaml src/main/java/com/bookshelf/provider src/main/java/com/bookshelf/catalog/kakao/KakaoBookCatalog.java src/main/java/com/bookshelf/common/ApiExceptionHandler.java src/test/java/com/bookshelf/provider src/test/java/com/bookshelf/catalog src/test/java/com/bookshelf/support`

  ```bash
  git commit -m "feat: 사용자별 호출 상한 — 스크립트 하나가 한도를 태우지 못하게

  모든 제공자 호출이 지나는 CallBudget.acquire 한 곳에서 사용자 상한(300/일) → 제공자 상한 순으로 본다.
  캐시에 걸린 요청은 여기 오지 않으므로 세지 않는다. 상한을 넘은 요청은 제공자 예산을 깎지 않는다.
  백그라운드 작업에는 인증이 없어 사용자 상한을 보지 않는다."
  ```

---

### Task 5: 내 정보 API — slug를 바꾸고, 탈퇴한 회원의 토큰은 통하지 않는다

**Files:**
- Modify: `src/main/java/com/bookshelf/member/domain/Member.java` (`changeSlug`, `rename`, `isDeleted`)
- Create: `src/main/java/com/bookshelf/member/application/MemberService.java`, `MeView.java`
- Create: `src/main/java/com/bookshelf/member/presentation/MeController.java`
- Modify: `src/main/java/com/bookshelf/common/ApiExceptionHandler.java` (UNIQUE 위반 → 409), `src/test/java/com/bookshelf/auth/SecurityBoundaryTest.java`
- Test: `src/test/java/com/bookshelf/member/MeApiTest.java`

**Interfaces:**
- Consumes: `CurrentMember.require()`, `MemberRepository`, `UnauthorizedException`, `ConflictException`
- Produces:
  - `Member#changeSlug(String)`(규칙은 `register`와 같다), `#rename(String)`, `#isDeleted()`
  - `MemberService#active(long memberId) -> Member` — 없거나 탈퇴했으면 `UnauthorizedException`(access 토큰이 남아 있어도 통하지 않는다)
  - `MemberService#me(long) -> MeView`, `#update(long, String slug, String displayName) -> MeView` — slug가 이미 있으면 `ConflictException`
  - `record MeView(String slug, String displayName, boolean shelfPublic, String defaultVisibility)`
  - HTTP: `GET /api/me`, `PATCH /api/me {slug?, displayName?}`

- [ ] **Step 1: 실패하는 테스트**

  `src/test/java/com/bookshelf/member/MeApiTest.java`:

  ```java
  package com.bookshelf.member;

  import com.bookshelf.member.domain.Member;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.TestJwt;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.http.MediaType;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.test.web.servlet.MockMvc;

  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(Fixtures.class)
  class MeApiTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired Fixtures fx;
      @Autowired JwtEncoder encoder;

      private Member me;
      private String auth;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          me = fx.member("reader-abc123", false);
          fx.member("taken", false);
          auth = TestJwt.bearer(encoder, me.getId());
      }

      @Test
      void 내_정보를_준다() throws Exception {
          mvc.perform(get("/api/me").header("Authorization", auth))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.slug").value("reader-abc123"))
                  .andExpect(jsonPath("$.shelfPublic").value(false))
                  .andExpect(jsonPath("$.defaultVisibility").value("PRIVATE"));
      }

      @Test
      void slug와_이름을_바꾼다() throws Exception {
          mvc.perform(patch("/api/me").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                          .content("{\"slug\":\"juhee\",\"displayName\":\"주희\"}"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.slug").value("juhee"))
                  .andExpect(jsonPath("$.displayName").value("주희"));
      }

      @Test
      void 이미_있는_slug는_409다() throws Exception {
          mvc.perform(patch("/api/me").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                          .content("{\"slug\":\"taken\"}"))
                  .andExpect(status().isConflict())
                  .andExpect(jsonPath("$.code").value("CONFLICT"));
      }

      @Test
      void 규칙에_맞지_않는_slug는_400이다() throws Exception {
          mvc.perform(patch("/api/me").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                          .content("{\"slug\":\"Juhee Hong\"}"))
                  .andExpect(status().isBadRequest())
                  .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
      }

      @Test
      void 탈퇴한_회원의_access_토큰은_401이다() throws Exception {
          fx.withdraw(me);

          mvc.perform(get("/api/me").header("Authorization", auth)).andExpect(status().isUnauthorized());
      }
  }
  ```

- [ ] **Step 2: 실패 확인**

  Run: `./gradlew test --tests MeApiTest`
  Expected: FAIL — 404(핸들러 없음) 여러 개

- [ ] **Step 3: 구현**

  `Member.java`에 추가:

  ```java
      public void changeSlug(String slug) {
          if (slug == null || !SLUG.matcher(slug).matches()) {
              throw new DomainException("slug 규칙 위반: " + slug);
          }
          this.slug = slug;
      }

      public void rename(String displayName) {
          String name = displayName == null ? "" : displayName.strip();
          if (name.isEmpty() || name.codePointCount(0, name.length()) > 30) {
              throw new DomainException("표시 이름은 1~30자다");
          }
          this.displayName = name;
      }

      public boolean isDeleted() {
          return deletedAt != null;
      }
  ```

  `src/main/java/com/bookshelf/member/application/MeView.java`:

  ```java
  package com.bookshelf.member.application;

  import com.bookshelf.member.domain.Member;

  public record MeView(String slug, String displayName, boolean shelfPublic, String defaultVisibility) {

      static MeView of(Member m) {
          return new MeView(m.getSlug(), m.getDisplayName(), m.isShelfPublic(), m.getDefaultVisibility().name());
      }
  }
  ```

  `src/main/java/com/bookshelf/member/application/MemberService.java`:

  ```java
  package com.bookshelf.member.application;

  import com.bookshelf.auth.UnauthorizedException;
  import com.bookshelf.common.ConflictException;
  import com.bookshelf.member.domain.Member;
  import com.bookshelf.member.infrastructure.MemberRepository;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;

  @Service
  public class MemberService {

      private final MemberRepository members;

      public MemberService(MemberRepository members) {
          this.members = members;
      }

      /** access 토큰은 30분 동안 유효하다. 그 사이 탈퇴했으면 토큰이 남아 있어도 통하지 않게 한다 */
      public Member active(long memberId) {
          return members.findById(memberId).filter(m -> !m.isDeleted())
                  .orElseThrow(() -> new UnauthorizedException("없거나 탈퇴한 회원의 토큰: " + memberId));
      }

      @Transactional(readOnly = true)
      public MeView me(long memberId) {
          return MeView.of(active(memberId));
      }

      /** slug를 바꾸면 옛 서재 링크는 404가 된다 — 앱이 바꾸기 전에 알린다(Phase 3) */
      @Transactional
      public MeView update(long memberId, String slug, String displayName) {
          Member m = active(memberId);
          if (slug != null && !slug.equals(m.getSlug())) {
              if (members.existsBySlug(slug)) {
                  throw new ConflictException("이미 쓰는 주소예요");
              }
              m.changeSlug(slug);
          }
          if (displayName != null) {
              m.rename(displayName);
          }
          return MeView.of(m);
      }
  }
  ```

  `src/main/java/com/bookshelf/member/presentation/MeController.java`:

  ```java
  package com.bookshelf.member.presentation;

  import com.bookshelf.common.CurrentMember;
  import com.bookshelf.member.application.MeView;
  import com.bookshelf.member.application.MemberService;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PatchMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;

  @RestController
  @RequestMapping("/api/me")
  public class MeController {

      /** 바꿀 것만 보낸다. null은 그대로 둔다 */
      public record UpdateRequest(String slug, String displayName) {
      }

      private final MemberService members;

      public MeController(MemberService members) {
          this.members = members;
      }

      @GetMapping
      public MeView me() {
          return members.me(CurrentMember.require());
      }

      @PatchMapping
      public MeView update(@RequestBody UpdateRequest req) {
          return members.update(CurrentMember.require(), req.slug(), req.displayName());
      }
  }
  ```

  동시에 두 사람이 같은 slug로 바꾸면 `existsBySlug` 검사를 둘 다 통과하고 UNIQUE 제약에서 한쪽이 `DataIntegrityViolationException`으로 끝난다. `ApiExceptionHandler`에 409로 번역을 더한다(import `org.springframework.dao.DataIntegrityViolationException`):

  ```java
      /** 검사를 통과한 동시 요청이 UNIQUE 제약에 걸린 경우. 제약 이름을 응답에 내보내지 않는다 */
      @ExceptionHandler(DataIntegrityViolationException.class)
      ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
          log.debug("409 {}", e.getMostSpecificCause().getMessage());
          return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("CONFLICT", "이미 있어요"));
      }
  ```

  `SecurityBoundaryTest.우리가_발급한_토큰이면_API를_통과한다`(Task 1)는 "핸들러가 없어 404"를 가정했다. 이제 `/api/me`가 생겼고, 없는 회원 1의 토큰은 `MemberService.active`에서 401이 되므로 그 테스트가 깨진다. 실제 회원의 토큰을 쓰게 바꾸고 주석을 고친다:

  ```java
      @Test
      void 우리가_발급한_토큰이면_API를_통과한다() throws Exception {
          long id = fx.member("token-owner", false).getId();

          mvc.perform(get("/api/me").header("Authorization", TestJwt.bearer(encoder, id))).andExpect(status().isOk());
      }
  ```

- [ ] **Step 4: 통과 확인**

  Run: `./gradlew test --tests MeApiTest --tests SecurityBoundaryTest --tests AuthFlowTest`
  Expected: PASS (MeApiTest 5개 포함)

- [ ] **Step 5: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `src/main/java/com/bookshelf/member src/main/java/com/bookshelf/common/ApiExceptionHandler.java src/test/java/com/bookshelf/member src/test/java/com/bookshelf/auth/SecurityBoundaryTest.java`

  ```bash
  git commit -m "feat: 내 정보 API — slug 변경, 탈퇴한 회원의 토큰은 통하지 않게

  access 토큰은 30분 유효하다. 그 사이 탈퇴했으면 토큰이 남아 있어도 401이다.
  이미 있는 slug는 409 — 동시 요청이 UNIQUE에 걸려도 같은 409로 번역한다."
  ```

---

### Task 6: 기록 API — 주인은 토큰에서, 남의 기록은 404

설계 문서 4장 "찍는다 → 기록된다". 스캔한 ISBN으로 기록을 만들면 그 자리에서 책 정보를 가져온다(사람이 기다리는 호출). 제공자가 죽었거나 모르는 책이어도 **기록은 만들어진다** — 책 정보는 백필이 나중에 붙인다(설계 문서 5장).

**Files:**
- Modify: `src/main/java/com/bookshelf/record/domain/ReadingRecord.java` (`changeStatus`, `readBetween`)
- Modify: `src/main/java/com/bookshelf/record/infrastructure/ReadingRecordRepository.java`
- Create: `src/main/java/com/bookshelf/record/application/RecordService.java`, `RecordWriter.java`, `RecordView.java`
- Create: `src/main/java/com/bookshelf/record/presentation/RecordController.java`
- Modify: `src/main/java/com/bookshelf/common/ApiExceptionHandler.java` (본문 파싱 실패 → 400)
- Test: `src/test/java/com/bookshelf/record/domain/ReadingRecordTest.java` (추가), `src/test/java/com/bookshelf/record/RecordApiTest.java`

**Interfaces:**
- Consumes: `MemberService#active`, `BookLookupService#findOrFetch(INTERACTIVE)`, `BookRepository`, `BookView`, `CurrentMember`
- Produces:
  - `ReadingRecord#changeStatus(ReadingStatus, Instant)`, `#readBetween(LocalDate started, LocalDate finished, Instant)` — 완료일이 시작일보다 앞이면 `DomainException`
  - `ReadingRecordRepository#findByMemberIdOrderByCreatedAtDescIdDesc(Long)`, `#findByIdAndMemberId(Long, Long)`, `#existsByMemberIdAndIsbn13(Long, String)`
  - `record RecordView(long id, String isbn13, String status, Integer rating, String review, String visibility, LocalDate startedAt, LocalDate finishedAt, BookView book)` — `book`은 책 정보가 아직 없으면 null
  - `RecordService#create(long memberId, String isbn, ReadingStatus status) -> RecordView`, `#list`, `#get`, `#update`, `#delete`
  - HTTP: `POST /api/records {isbn, status}` 201, `GET /api/records`, `GET /api/records/{id}`, `PUT /api/records/{id} {status, rating, review, visibility, startedAt, finishedAt}`, `DELETE /api/records/{id}` 204

- [ ] **Step 1: 실패하는 단위 테스트 — 도메인** — `ReadingRecordTest`에 추가

  ```java
      @Test
      void 읽는_상태를_바꾼다() {
          ReadingRecord r = newRecord();
          r.changeStatus(ReadingStatus.READING, NOW);
          assertThat(r.getStatus()).isEqualTo(ReadingStatus.READING);
      }

      @Test
      void 완료일이_시작일보다_앞이면_거부한다() {
          ReadingRecord r = newRecord();
          r.readBetween(java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 20), NOW);
          assertThat(r.getFinishedAt()).isEqualTo(java.time.LocalDate.of(2026, 9, 20));
          assertThatThrownBy(() -> r.readBetween(java.time.LocalDate.of(2026, 9, 20), java.time.LocalDate.of(2026, 9, 1), NOW))
                  .isInstanceOf(DomainException.class);
      }
  ```

  Run: `./gradlew test --tests ReadingRecordTest` → FAIL(컴파일). 구현(`ReadingRecord.java`):

  ```java
      public void changeStatus(ReadingStatus status, Instant now) {
          if (status == null) {
              throw new DomainException("읽는 상태가 없다");
          }
          this.status = status;
          this.updatedAt = now;
      }

      /** 둘 다 비울 수 있다. 둘 다 있으면 완료일이 시작일보다 앞일 수 없다 */
      public void readBetween(LocalDate startedAt, LocalDate finishedAt, Instant now) {
          if (startedAt != null && finishedAt != null && finishedAt.isBefore(startedAt)) {
              throw new DomainException("다 읽은 날이 시작한 날보다 앞이다");
          }
          this.startedAt = startedAt;
          this.finishedAt = finishedAt;
          this.updatedAt = now;
      }
  ```

  Run: `./gradlew test --tests ReadingRecordTest` → PASS

- [ ] **Step 2: 실패하는 통합 테스트**

  `src/test/java/com/bookshelf/record/RecordApiTest.java`:

  ```java
  package com.bookshelf.record;

  import com.bookshelf.member.domain.Member;
  import com.bookshelf.record.domain.Visibility;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.KakaoStub;
  import com.bookshelf.support.TestJwt;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.http.MediaType;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;
  import org.springframework.test.web.servlet.MockMvc;
  import org.springframework.test.web.servlet.ResultActions;

  import static com.bookshelf.support.KakaoStub.doc;
  import static com.bookshelf.support.KakaoStub.response;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.hamcrest.Matchers.nullValue;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(Fixtures.class)
  class RecordApiTest {

      private static final String A = "9788936434120";

      @DynamicPropertySource
      static void kakao(DynamicPropertyRegistry r) {
          r.add("providers.kakao.base-url", KakaoStub::baseUrl);
      }

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired Fixtures fx;
      @Autowired JwtEncoder encoder;

      private Member me;
      private Member other;
      private String auth;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          KakaoStub.reset();
          me = fx.member("mine", false);
          other = fx.member("other", false);
          auth = TestJwt.bearer(encoder, me.getId());
      }

      private ResultActions create(String body) throws Exception {
          return mvc.perform(post("/api/records").header("Authorization", auth)
                  .contentType(MediaType.APPLICATION_JSON).content(body));
      }

      private int count(String where) {
          return jdbc.sql("SELECT count(*) FROM reading_record WHERE " + where).query(Integer.class).single();
      }

      @Test
      void 스캔한_ISBN으로_기록을_만들면_책_정보도_채운다() throws Exception {
          KakaoStub.returns(A, response(1, true, doc(A, "소년이 온다", 15000, 13500, "정상판매")));

          create("{\"isbn\":\"" + A + "\",\"status\":\"READING\"}")
                  .andExpect(status().isCreated())
                  .andExpect(jsonPath("$.isbn13").value(A))
                  .andExpect(jsonPath("$.status").value("READING"))
                  .andExpect(jsonPath("$.book.title").value("소년이 온다"));
      }

      @Test
      void 제공자가_죽어도_기록은_만들어지고_책은_비어_있다() throws Exception {
          KakaoStub.failsWith(503);

          create("{\"isbn\":\"" + A + "\",\"status\":\"DONE\"}")
                  .andExpect(status().isCreated())
                  .andExpect(jsonPath("$.book").value(nullValue()));
          assertThat(count("isbn13 = '" + A + "'")).isEqualTo(1);
      }

      @Test
      void ISBN10으로_만들어도_13자리로_저장된다() throws Exception {
          KakaoStub.returns(A, response(0, true));

          create("{\"isbn\":\"8936434128\",\"status\":\"DONE\"}").andExpect(status().isCreated())
                  .andExpect(jsonPath("$.isbn13").value(A));
      }

      @Test
      void 새_기록의_공개_범위는_회원_기본값이다() throws Exception {
          KakaoStub.returns(A, response(0, true));

          create("{\"isbn\":\"" + A + "\",\"status\":\"DONE\"}").andExpect(jsonPath("$.visibility").value("PRIVATE"));
      }

      @Test
      void 같은_책을_두_번_기록하면_409다() throws Exception {
          fx.record(me, A, Visibility.PRIVATE, null, null);

          create("{\"isbn\":\"" + A + "\",\"status\":\"DONE\"}").andExpect(status().isConflict());
      }

      @Test
      void 잘못된_ISBN과_잘못된_상태는_400이다() throws Exception {
          create("{\"isbn\":\"9788936434121\",\"status\":\"DONE\"}").andExpect(status().isBadRequest());
          create("{\"isbn\":\"" + A + "\",\"status\":\"FINISHED\"}").andExpect(status().isBadRequest())
                  .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
          create("{\"isbn\":\"" + A + "\"}").andExpect(status().isBadRequest());
      }

      @Test
      void 요청_본문의_회원_id는_무시하고_토큰의_회원으로_만든다() throws Exception {
          KakaoStub.returns(A, response(0, true));

          create("{\"isbn\":\"" + A + "\",\"status\":\"DONE\",\"memberId\":" + other.getId() + "}").andExpect(status().isCreated());

          assertThat(count("member_id = " + me.getId())).isEqualTo(1);
          assertThat(count("member_id = " + other.getId())).isZero();
      }

      @Test
      void 내_기록만_최신순으로_준다() throws Exception {
          fx.book(A, "소년이 온다");
          fx.record(me, A, Visibility.PRIVATE, 5, "좋았다");
          fx.record(me, "9791100000007", Visibility.PRIVATE, null, null);
          fx.record(other, A, Visibility.PUBLIC, 1, "남의 기록");

          mvc.perform(get("/api/records").header("Authorization", auth))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.length()").value(2))
                  .andExpect(jsonPath("$[1].book.title").value("소년이 온다"))
                  .andExpect(jsonPath("$[0].book").value(nullValue()));
      }

      @Test
      void 기록을_고친다() throws Exception {
          long id = fx.record(me, A, Visibility.PRIVATE, null, null).getId();

          mvc.perform(put("/api/records/" + id).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                          .content("""
                                  {"status":"DONE","rating":4,"review":"  남는다 ","visibility":"RATING_ONLY",
                                   "startedAt":"2026-09-01","finishedAt":"2026-09-20"}
                                  """))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.rating").value(4))
                  .andExpect(jsonPath("$.review").value("남는다"))
                  .andExpect(jsonPath("$.visibility").value("RATING_ONLY"))
                  .andExpect(jsonPath("$.finishedAt").value("2026-09-20"));
      }

      @Test
      void 완료일이_시작일보다_앞이면_400이다() throws Exception {
          long id = fx.record(me, A, Visibility.PRIVATE, null, null).getId();

          mvc.perform(put("/api/records/" + id).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                          .content("{\"status\":\"DONE\",\"visibility\":\"PRIVATE\",\"startedAt\":\"2026-09-20\",\"finishedAt\":\"2026-09-01\"}"))
                  .andExpect(status().isBadRequest());
      }

      /** Review Focus 1: 남의 기록은 조회·수정·삭제 모두 404이고 아무것도 바뀌지 않는다 */
      @Test
      void 남의_기록은_404이고_바뀌지_않는다() throws Exception {
          long theirs = fx.record(other, A, Visibility.PRIVATE, 3, "남의 한줄평").getId();

          mvc.perform(get("/api/records/" + theirs).header("Authorization", auth)).andExpect(status().isNotFound());
          mvc.perform(put("/api/records/" + theirs).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                  .content("{\"status\":\"DONE\",\"rating\":1,\"visibility\":\"PUBLIC\"}")).andExpect(status().isNotFound());
          mvc.perform(delete("/api/records/" + theirs).header("Authorization", auth)).andExpect(status().isNotFound());

          assertThat(jdbc.sql("SELECT visibility FROM reading_record WHERE id = :id").param("id", theirs)
                  .query(String.class).single()).isEqualTo("PRIVATE");
          assertThat(count("id = " + theirs)).isEqualTo(1);
      }

      @Test
      void 기록을_지운다() throws Exception {
          long id = fx.record(me, A, Visibility.PRIVATE, null, null).getId();

          mvc.perform(delete("/api/records/" + id).header("Authorization", auth)).andExpect(status().isNoContent());

          assertThat(count("id = " + id)).isZero();
      }
  }
  ```

- [ ] **Step 3: 실패 확인**

  Run: `./gradlew test --tests RecordApiTest`
  Expected: FAIL — 404(핸들러 없음)

- [ ] **Step 4: 구현**

  `ReadingRecordRepository.java`:

  ```java
  public interface ReadingRecordRepository extends JpaRepository<ReadingRecord, Long> {

      List<ReadingRecord> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId);

      /** 소유권은 쿼리 조건으로 건다 — 가져온 뒤 비교하지 않는다 */
      Optional<ReadingRecord> findByIdAndMemberId(Long id, Long memberId);

      boolean existsByMemberIdAndIsbn13(Long memberId, String isbn13);
  }
  ```

  (import `java.util.List`, `java.util.Optional`)

  `src/main/java/com/bookshelf/record/application/RecordView.java`:

  ```java
  package com.bookshelf.record.application;

  import com.bookshelf.book.application.BookView;
  import com.bookshelf.record.domain.ReadingRecord;

  import java.time.LocalDate;

  /** book은 책 정보가 아직 없으면 null — 앱은 회색 표지와 ISBN을 보여준다 */
  public record RecordView(long id, String isbn13, String status, Integer rating, String review, String visibility,
                           LocalDate startedAt, LocalDate finishedAt, BookView book) {

      static RecordView of(ReadingRecord r, BookView book) {
          return new RecordView(r.getId(), r.getIsbn13(), r.getStatus().name(), r.getRating(), r.getReview(),
                  r.getVisibility().name(), r.getStartedAt(), r.getFinishedAt(), book);
      }
  }
  ```

  `src/main/java/com/bookshelf/record/application/RecordWriter.java`:

  ```java
  package com.bookshelf.record.application;

  import com.bookshelf.common.ConflictException;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.common.NotFoundException;
  import com.bookshelf.member.domain.Member;
  import com.bookshelf.record.domain.ReadingRecord;
  import com.bookshelf.record.domain.ReadingStatus;
  import com.bookshelf.record.domain.Visibility;
  import com.bookshelf.record.infrastructure.ReadingRecordRepository;
  import org.springframework.stereotype.Component;
  import org.springframework.transaction.annotation.Transactional;

  import java.time.Instant;
  import java.time.LocalDate;

  /** 기록 쓰기. 제공자 호출(책 정보)은 RecordService가 트랜잭션 밖에서 한다 */
  @Component
  public class RecordWriter {

      public record Update(ReadingStatus status, Integer rating, String review, Visibility visibility,
                           LocalDate startedAt, LocalDate finishedAt) {
      }

      private final ReadingRecordRepository records;

      public RecordWriter(ReadingRecordRepository records) {
          this.records = records;
      }

      @Transactional
      public ReadingRecord create(Member member, Isbn13 isbn, ReadingStatus status, Instant now) {
          if (records.existsByMemberIdAndIsbn13(member.getId(), isbn.value())) {
              throw new ConflictException("이미 기록한 책이에요");
          }
          return records.save(ReadingRecord.create(member.getId(), isbn, status, member.getDefaultVisibility(), now));
      }

      /** 전체를 바꾼다(PUT). null인 별점·한줄평·날짜는 지운다 */
      @Transactional
      public ReadingRecord update(long memberId, long id, Update u, Instant now) {
          ReadingRecord r = owned(memberId, id);
          r.changeStatus(u.status(), now);
          r.rate(u.rating());
          r.writeReview(u.review(), now);
          if (u.visibility() == null) {
              throw new com.bookshelf.common.DomainException("공개 범위가 없다");
          }
          r.changeVisibility(u.visibility(), now);
          r.readBetween(u.startedAt(), u.finishedAt(), now);
          return r;
      }

      @Transactional
      public void delete(long memberId, long id) {
          records.delete(owned(memberId, id));
      }

      ReadingRecord owned(long memberId, long id) {
          return records.findByIdAndMemberId(id, memberId).orElseThrow(() -> new NotFoundException("기록 " + id));
      }
  }
  ```

  `src/main/java/com/bookshelf/record/application/RecordService.java`:

  ```java
  package com.bookshelf.record.application;

  import com.bookshelf.book.application.BookLookupService;
  import com.bookshelf.book.application.BookView;
  import com.bookshelf.book.domain.Book;
  import com.bookshelf.book.infrastructure.BookRepository;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.common.NotFoundException;
  import com.bookshelf.member.application.MemberService;
  import com.bookshelf.provider.CallPurpose;
  import com.bookshelf.provider.ProviderUnavailableException;
  import com.bookshelf.provider.UserQuotaExceededException;
  import com.bookshelf.record.domain.ReadingRecord;
  import com.bookshelf.record.domain.ReadingStatus;
  import com.bookshelf.record.infrastructure.ReadingRecordRepository;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.stereotype.Service;

  import java.time.Clock;
  import java.util.List;
  import java.util.Map;
  import java.util.function.Function;
  import java.util.stream.Collectors;

  /** @Transactional 없음 — 기록을 만든 뒤 책 정보를 가져오는 제공자 호출이 있다 */
  @Slf4j
  @Service
  public class RecordService {

      private final MemberService members;
      private final RecordWriter writer;
      private final ReadingRecordRepository records;
      private final BookRepository books;
      private final BookLookupService lookup;
      private final Clock clock;

      public RecordService(MemberService members, RecordWriter writer, ReadingRecordRepository records,
                           BookRepository books, BookLookupService lookup, Clock clock) {
          this.members = members;
          this.writer = writer;
          this.records = records;
          this.books = books;
          this.lookup = lookup;
          this.clock = clock;
      }

      /**
       * 기록을 먼저 만들고(커밋) 책 정보를 가져온다. 제공자가 죽었거나 모르는 책이거나
       * 사용자 상한에 걸려도 기록은 남는다 — 책 정보는 백필이 나중에 붙인다(설계 문서 5장).
       */
      public RecordView create(long memberId, String rawIsbn, ReadingStatus status) {
          Isbn13 isbn = Isbn13.parse(rawIsbn);
          if (status == null) {
              throw new com.bookshelf.common.DomainException("읽는 상태가 없다");
          }
          ReadingRecord r = writer.create(members.active(memberId), isbn, status, clock.instant());
          BookView book = null;
          try {
              book = lookup.findOrFetch(isbn, CallPurpose.INTERACTIVE).orElse(null);
          } catch (ProviderUnavailableException | UserQuotaExceededException e) {
              log.info("기록은 만들고 책 정보는 나중에: isbn={} reason={}", isbn.value(), e.getMessage());
          }
          return RecordView.of(r, book);
      }

      public List<RecordView> list(long memberId) {
          members.active(memberId);
          List<ReadingRecord> mine = records.findByMemberIdOrderByCreatedAtDescIdDesc(memberId);
          Map<String, BookView> byIsbn = books.findAllById(mine.stream().map(ReadingRecord::getIsbn13).toList()).stream()
                  .collect(Collectors.toMap(Book::getIsbn13, BookView::of, (a, b) -> a));
          return mine.stream().map(r -> RecordView.of(r, byIsbn.get(r.getIsbn13()))).toList();
      }

      public RecordView get(long memberId, long id) {
          members.active(memberId);
          ReadingRecord r = writer.owned(memberId, id);
          return RecordView.of(r, books.findById(r.getIsbn13()).map(BookView::of).orElse(null));
      }

      public RecordView update(long memberId, long id, RecordWriter.Update u) {
          members.active(memberId);
          ReadingRecord r = writer.update(memberId, id, u, clock.instant());
          return RecordView.of(r, books.findById(r.getIsbn13()).map(BookView::of).orElse(null));
      }

      public void delete(long memberId, long id) {
          members.active(memberId);
          writer.delete(memberId, id);
      }
  }
  ```

  `src/main/java/com/bookshelf/record/presentation/RecordController.java`:

  ```java
  package com.bookshelf.record.presentation;

  import com.bookshelf.common.CurrentMember;
  import com.bookshelf.record.application.RecordService;
  import com.bookshelf.record.application.RecordView;
  import com.bookshelf.record.application.RecordWriter;
  import com.bookshelf.record.domain.ReadingStatus;
  import com.bookshelf.record.domain.Visibility;
  import org.springframework.http.HttpStatus;
  import org.springframework.http.ResponseEntity;
  import org.springframework.web.bind.annotation.*;

  import java.time.LocalDate;
  import java.util.List;

  /** 주체(회원)는 토큰에서만 온다. 요청 DTO에 회원 id 필드를 두지 않는다 — 보내도 버려진다 */
  @RestController
  @RequestMapping("/api/records")
  public class RecordController {

      public record CreateRequest(String isbn, ReadingStatus status) {
      }

      public record UpdateRequest(ReadingStatus status, Integer rating, String review, Visibility visibility,
                                  LocalDate startedAt, LocalDate finishedAt) {
      }

      private final RecordService records;

      public RecordController(RecordService records) {
          this.records = records;
      }

      @PostMapping
      public ResponseEntity<RecordView> create(@RequestBody CreateRequest req) {
          return ResponseEntity.status(HttpStatus.CREATED)
                  .body(records.create(CurrentMember.require(), req.isbn(), req.status()));
      }

      @GetMapping
      public List<RecordView> list() {
          return records.list(CurrentMember.require());
      }

      @GetMapping("/{id}")
      public RecordView get(@PathVariable long id) {
          return records.get(CurrentMember.require(), id);
      }

      @PutMapping("/{id}")
      public RecordView update(@PathVariable long id, @RequestBody UpdateRequest req) {
          return records.update(CurrentMember.require(), id, new RecordWriter.Update(req.status(), req.rating(),
                  req.review(), req.visibility(), req.startedAt(), req.finishedAt()));
      }

      @DeleteMapping("/{id}")
      public ResponseEntity<Void> delete(@PathVariable long id) {
          records.delete(CurrentMember.require(), id);
          return ResponseEntity.noContent().build();
      }
  }
  ```

  `ApiExceptionHandler`에 본문 파싱 실패(없는 enum 값 `"FINISHED"`, 날짜 형식 오류)를 같은 400 모양으로 더한다(import `org.springframework.http.converter.HttpMessageNotReadableException`):

  ```java
      @ExceptionHandler(HttpMessageNotReadableException.class)
      ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
          log.debug("400 본문 해석 실패 {}", e.getMostSpecificCause().getMessage());
          return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "요청 형식이 올바르지 않아요"));
      }
  ```

- [ ] **Step 5: 통과 확인**

  Run: `./gradlew test --tests RecordApiTest --tests ReadingRecordTest`
  Expected: PASS (RecordApiTest 12개)

- [ ] **Step 6: 전체 테스트 후 커밋 (사용자 허용 후)**

  Run: `./gradlew test` → 전부 PASS

  허용을 요청할 경로: `src/main/java/com/bookshelf/record src/main/java/com/bookshelf/common/ApiExceptionHandler.java src/test/java/com/bookshelf/record`

  ```bash
  git commit -m "feat: 기록 API — 주인은 토큰에서, 남의 기록은 404

  소유권은 findByIdAndMemberId로 쿼리 조건에 건다. 남의 기록 id로 조회·수정·삭제하면 404이고
  아무것도 바뀌지 않는다. 요청 본문의 회원 id는 DTO에 필드가 없어 버려진다.
  기록을 먼저 커밋하고 책 정보를 가져온다 — 제공자가 죽어도 기록은 남는다."
  ```

---

### Task 7: 책·검색 API — 앱의 검색 화면과 책 상세

설계 문서 2-1 "한 화면에 읽는 방법 전부". 앱이 쓰는 세 가지: 책 상세(책 정보 + 새 책 판매 상태), 서점 링크, 검색. 검색 캐시가 쌓이기만 하던 문제(Phase 1 최종 리뷰 M7)도 여기서 닫는다 — 검색을 앱에 여는 순간부터 행이 늘기 때문이다.

**Files:**
- Modify: `src/main/java/com/bookshelf/book/application/BookLookupService.java` (`findLocal`)
- Create: `src/main/java/com/bookshelf/book/application/BookDetail.java`
- Create: `src/main/java/com/bookshelf/book/presentation/BookApiController.java`
- Create: `src/main/java/com/bookshelf/search/SearchController.java`, `SearchItem.java`, `SearchResponse.java`, `SearchCacheCleanup.java`
- Modify: `src/main/java/com/bookshelf/auth/RefreshTokenStore.java` (`purgeExpired`)
- Create: `src/main/java/com/bookshelf/ops/MaintenanceJobs.java`
- Modify: `src/main/resources/application.yaml` (`bookshelf.background.cleanup-cron`)
- Test: `src/test/java/com/bookshelf/book/BookApiTest.java`, `src/test/java/com/bookshelf/search/SearchApiTest.java`, `src/test/java/com/bookshelf/search/CleanupTest.java`

**Interfaces:**
- Consumes: `BookLookupService#findOrFetch(Isbn13, CallPurpose) -> Optional<BookView>`, `SaleService#current(Isbn13) -> SaleView`, `StoreLinks#forBook(isbn13, title, author) -> Map<LinkGroup, List<StoreLink>>`, `SearchService#search(String, int) -> SearchResult`, `UserQuotaExceededException`(→429, Task 4)
- Produces:
  - `BookLookupService#findLocal(Isbn13) -> Optional<BookView>` — 제공자를 부르지 않는다
  - `record BookDetail(BookView book, SaleView sale)`
  - `record SearchItem(BookView book, SaleView sale)`, `record SearchResponse(List<SearchItem> items, int total, boolean isEnd, Instant fetchedAt)`
  - `SearchCacheCleanup#runOnce() -> int` — `fetched_at`이 7일보다 오래된 행을 지운다
  - `RefreshTokenStore#purgeExpired(Instant now) -> int`
  - HTTP: `GET /api/books/{isbn}`, `GET /api/books/{isbn}/links`, `GET /api/search?q=&page=1`

- [ ] **Step 1: 실패하는 테스트 — 책 API**

  `src/test/java/com/bookshelf/book/BookApiTest.java`:

  ```java
  package com.bookshelf.book;

  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.KakaoStub;
  import com.bookshelf.support.TestJwt;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;
  import org.springframework.test.web.servlet.MockMvc;

  import static com.bookshelf.support.KakaoStub.doc;
  import static com.bookshelf.support.KakaoStub.response;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.hamcrest.Matchers.containsString;
  import static org.hamcrest.Matchers.nullValue;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(Fixtures.class)
  class BookApiTest {

      private static final String A = "9788936434120";

      @DynamicPropertySource
      static void kakao(DynamicPropertyRegistry r) {
          r.add("providers.kakao.base-url", KakaoStub::baseUrl);
      }

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired Fixtures fx;
      @Autowired JwtEncoder encoder;

      private String auth;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          KakaoStub.reset();
          auth = TestJwt.bearer(encoder, fx.member("mine", false).getId());
      }

      @Test
      void 아는_책은_제공자를_부르지_않고_준다() throws Exception {
          fx.book(A, "소년이 온다");

          mvc.perform(get("/api/books/" + A).header("Authorization", auth))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.book.title").value("소년이 온다"))
                  .andExpect(jsonPath("$.sale.fetchedAt").value(nullValue()));   // 판매 상태는 아직 모른다 — 갱신 큐에 들어간다
          assertThat(KakaoStub.calls()).isZero();
          assertThat(jdbc.sql("SELECT count(*) FROM refresh_request").query(Integer.class).single()).isEqualTo(1);
      }

      @Test
      void 모르는_책은_제공자에서_가져온다() throws Exception {
          KakaoStub.returns(A, response(1, true, doc(A, "소년이 온다", 15000, 13500, "정상판매")));

          mvc.perform(get("/api/books/" + A).header("Authorization", auth))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.book.title").value("소년이 온다"))
                  .andExpect(jsonPath("$.sale.status").value("ON_SALE"));
      }

      @Test
      void 제공자도_모르는_책은_404다() throws Exception {
          KakaoStub.returns(A, response(0, true));

          mvc.perform(get("/api/books/" + A).header("Authorization", auth)).andExpect(status().isNotFound());
      }

      @Test
      void 제공자가_죽었으면_503이다() throws Exception {
          KakaoStub.failsWith(503);

          mvc.perform(get("/api/books/" + A).header("Authorization", auth))
                  .andExpect(status().isServiceUnavailable())
                  .andExpect(jsonPath("$.code").value("TRY_LATER"));
      }

      @Test
      void 잘못된_ISBN은_400이다() throws Exception {
          mvc.perform(get("/api/books/9788936434121").header("Authorization", auth)).andExpect(status().isBadRequest());
      }

      @Test
      void 서점_링크를_칸별로_준다() throws Exception {
          fx.book(A, "소년이 온다");

          mvc.perform(get("/api/books/" + A + "/links").header("Authorization", auth))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.NEW[0].url").value(containsString(A)))
                  .andExpect(jsonPath("$.USED").isArray())
                  .andExpect(jsonPath("$.EBOOK[0].url").value(containsString("%EC%86%8C%EB%85%84")));
          assertThat(KakaoStub.calls()).isZero();
      }

      @Test
      void 모르는_책의_링크는_404다() throws Exception {
          mvc.perform(get("/api/books/" + A + "/links").header("Authorization", auth)).andExpect(status().isNotFound());
          assertThat(KakaoStub.calls()).isZero();   // 링크 때문에 제공자를 부르지 않는다
      }
  }
  ```

- [ ] **Step 2: 실패하는 테스트 — 검색 API**

  `src/test/java/com/bookshelf/search/SearchApiTest.java`:

  ```java
  package com.bookshelf.search;

  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.KakaoStub;
  import com.bookshelf.support.TestJwt;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;
  import org.springframework.test.web.servlet.MockMvc;

  import static com.bookshelf.support.KakaoStub.doc;
  import static com.bookshelf.support.KakaoStub.response;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(Fixtures.class)
  class SearchApiTest {

      private static final String A = "9788936434120";
      private static final String Q = "소년이 온다";

      @DynamicPropertySource
      static void props(DynamicPropertyRegistry r) {
          r.add("providers.kakao.base-url", KakaoStub::baseUrl);
          r.add("providers.user.daily-limit", () -> 2);
      }

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired Fixtures fx;
      @Autowired JwtEncoder encoder;

      private String auth;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          KakaoStub.reset();
          auth = TestJwt.bearer(encoder, fx.member("mine", false).getId());
      }

      private int userCalls() {
          return jdbc.sql("SELECT coalesce(sum(calls), 0) FROM user_call_counter").query(Integer.class).single();
      }

      @Test
      void 검색_결과에_새_책_판매_상태가_같이_온다() throws Exception {
          KakaoStub.returns(Q, response(1, true, doc(A, "소년이 온다", 15000, 13500, "정상판매")));

          mvc.perform(get("/api/search").param("q", Q).header("Authorization", auth))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.items[0].book.isbn13").value(A))
                  .andExpect(jsonPath("$.items[0].sale.status").value("ON_SALE"))
                  .andExpect(jsonPath("$.isEnd").value(true));
      }

      @Test
      void 캐시에_걸린_검색은_사용자_상한을_깎지_않는다() throws Exception {
          KakaoStub.returns(Q, response(1, true, doc(A, "소년이 온다", 15000, 13500, "정상판매")));

          for (int i = 0; i < 5; i++) {
              mvc.perform(get("/api/search").param("q", Q).header("Authorization", auth)).andExpect(status().isOk());
          }

          assertThat(KakaoStub.calls()).isEqualTo(1);
          assertThat(userCalls()).isEqualTo(1);
      }

      @Test
      void 사용자_상한을_넘으면_429다() throws Exception {
          // 검색어는 2자 이상이다(QueryNormalizer.MIN). 서로 다른 검색어라 캐시에 걸리지 않는다
          KakaoStub.returns("가나", response(0, true));
          KakaoStub.returns("나다", response(0, true));
          KakaoStub.returns("다라", response(0, true));

          mvc.perform(get("/api/search").param("q", "가나").header("Authorization", auth)).andExpect(status().isOk());
          mvc.perform(get("/api/search").param("q", "나다").header("Authorization", auth)).andExpect(status().isOk());
          mvc.perform(get("/api/search").param("q", "다라").header("Authorization", auth))
                  .andExpect(status().isTooManyRequests())
                  .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
      }

      @Test
      void 빈_검색어와_범위_밖_페이지는_400이다() throws Exception {
          mvc.perform(get("/api/search").param("q", "  ").header("Authorization", auth)).andExpect(status().isBadRequest());
          mvc.perform(get("/api/search").param("q", Q).param("page", "11").header("Authorization", auth))
                  .andExpect(status().isBadRequest());
          mvc.perform(get("/api/search").param("q", Q).param("page", "abc").header("Authorization", auth))
                  .andExpect(status().isBadRequest());
      }

      @Test
      void 제공자가_죽었고_캐시도_없으면_503이다() throws Exception {
          KakaoStub.failsWith(503);

          mvc.perform(get("/api/search").param("q", Q).header("Authorization", auth))
                  .andExpect(status().isServiceUnavailable());
      }
  }
  ```

  `빈_검색어`: `QueryNormalizer.normalize`가 빈 검색어에 `InvalidQueryException`(DomainException 하위)을 던진다 → 400(Phase 1). `page=abc`는 `MethodArgumentTypeMismatchException`이다 — Step 5에서 400으로 더한다.

- [ ] **Step 3: 실패하는 테스트 — 정리 작업**

  `src/test/java/com/bookshelf/search/CleanupTest.java`:

  ```java
  package com.bookshelf.search;

  import com.bookshelf.auth.RefreshTokenStore;
  import com.bookshelf.support.DbCleaner;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.jdbc.core.simple.JdbcClient;

  import java.sql.Timestamp;
  import java.time.Duration;
  import java.time.Instant;

  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest
  class CleanupTest {

      @Autowired JdbcClient jdbc;
      @Autowired SearchCacheCleanup searchCleanup;
      @Autowired RefreshTokenStore tokens;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
      }

      private void cache(String key, Duration age) {
          jdbc.sql("INSERT INTO search_cache (query_key, page, isbns, total, fetched_at) VALUES (:k, 1, '', 0, :at)")
                  .param("k", key).param("at", Timestamp.from(Instant.now().minus(age))).update();
      }

      @Test
      void 일주일_넘은_검색_캐시만_지운다() {
          cache("old", Duration.ofDays(8));
          cache("recent", Duration.ofDays(6));

          assertThat(searchCleanup.runOnce()).isEqualTo(1);

          assertThat(jdbc.sql("SELECT query_key FROM search_cache").query(String.class).list()).containsExactly("recent");
      }

      @Test
      void 만료된_refresh_토큰만_지운다() {
          Instant now = Instant.now();
          tokens.issue(1L, now.minus(Duration.ofDays(15)));   // TTL 14일(jwt.refresh-token-ttl) → 이미 만료
          tokens.issue(1L, now);

          assertThat(tokens.purgeExpired(now)).isEqualTo(1);

          assertThat(jdbc.sql("SELECT count(*) FROM refresh_token").query(Integer.class).single()).isEqualTo(1);
      }
  }
  ```

- [ ] **Step 4: 실패 확인**

  Run: `./gradlew test --tests BookApiTest --tests SearchApiTest --tests CleanupTest`
  Expected: FAIL — 404(핸들러 없음), CleanupTest는 컴파일 실패

- [ ] **Step 5: 구현**

  `BookLookupService.java`에 추가:

  ```java
      /** 링크처럼 책 정보만 있으면 되는 곳에서 쓴다. 제공자를 부르지 않는다 */
      public Optional<BookView> findLocal(Isbn13 isbn) {
          return books.findById(isbn.value()).map(BookView::of);
      }
  ```

  `src/main/java/com/bookshelf/book/application/BookDetail.java`:

  ```java
  package com.bookshelf.book.application;

  /** 앱의 책 상세 화면. 판매 상태는 캐시만 읽는다 — 오래됐으면 갱신 큐에 넣고 지난 값을 준다 */
  public record BookDetail(BookView book, SaleView sale) {
  }
  ```

  `src/main/java/com/bookshelf/book/presentation/BookApiController.java`:

  ```java
  package com.bookshelf.book.presentation;

  import com.bookshelf.book.application.BookDetail;
  import com.bookshelf.book.application.BookLookupService;
  import com.bookshelf.book.application.BookView;
  import com.bookshelf.book.application.SaleService;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.common.NotFoundException;
  import com.bookshelf.links.LinkGroup;
  import com.bookshelf.links.StoreLink;
  import com.bookshelf.links.StoreLinks;
  import com.bookshelf.provider.CallPurpose;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;

  import java.util.List;
  import java.util.Map;

  @RestController
  @RequestMapping("/api/books")
  public class BookApiController {

      private final BookLookupService lookup;
      private final SaleService sales;
      private final StoreLinks links;

      public BookApiController(BookLookupService lookup, SaleService sales, StoreLinks links) {
          this.lookup = lookup;
          this.sales = sales;
          this.links = links;
      }

      /** 사람이 기다리는 호출 — 모르는 책이면 제공자를 부른다. 제공자 장애는 503(ApiExceptionHandler) */
      @GetMapping("/{isbn}")
      public BookDetail detail(@PathVariable String isbn) {
          Isbn13 i = Isbn13.parse(isbn);
          BookView book = lookup.findOrFetch(i, CallPurpose.INTERACTIVE)
                  .orElseThrow(() -> new NotFoundException("책 " + i.value()));
          return new BookDetail(book, sales.current(i));
      }

      @GetMapping("/{isbn}/links")
      public Map<LinkGroup, List<StoreLink>> links(@PathVariable String isbn) {
          Isbn13 i = Isbn13.parse(isbn);
          BookView book = lookup.findLocal(i).orElseThrow(() -> new NotFoundException("책 " + i.value()));
          return links.forBook(book.isbn13(), book.title(), book.author());
      }
  }
  ```

  `src/main/java/com/bookshelf/search/SearchItem.java`:

  ```java
  package com.bookshelf.search;

  import com.bookshelf.book.application.BookView;
  import com.bookshelf.book.application.SaleView;

  public record SearchItem(BookView book, SaleView sale) {
  }
  ```

  `src/main/java/com/bookshelf/search/SearchResponse.java`:

  ```java
  package com.bookshelf.search;

  import java.time.Instant;
  import java.util.List;

  /** fetchedAt: 이 결과를 제공자에서 가져온 시각. 캐시 결과면 과거다 — 앱은 "N시간 전 기준"을 보여준다 */
  public record SearchResponse(List<SearchItem> items, int total, boolean isEnd, Instant fetchedAt) {
  }
  ```

  `src/main/java/com/bookshelf/search/SearchController.java`:

  ```java
  package com.bookshelf.search;

  import com.bookshelf.book.application.SaleService;
  import com.bookshelf.common.Isbn13;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.RequestParam;
  import org.springframework.web.bind.annotation.RestController;

  @RestController
  public class SearchController {

      private final SearchService search;
      private final SaleService sales;

      public SearchController(SearchService search, SaleService sales) {
          this.search = search;
          this.sales = sales;
      }

      /** 판매 상태는 검색이 방금 저장했거나(제공자 호출) 캐시에 있다. 여기서 제공자를 더 부르지 않는다 */
      @GetMapping("/api/search")
      public SearchResponse search(@RequestParam("q") String q, @RequestParam(defaultValue = "1") int page) {
          SearchResult r = search.search(q, page);
          return new SearchResponse(
                  r.books().stream().map(b -> new SearchItem(b, sales.current(Isbn13.parse(b.isbn13())))).toList(),
                  r.total(), r.isEnd(), r.fetchedAt());
      }
  }
  ```

  `ApiExceptionHandler`에 파라미터 오류 두 가지를 더한다(import `org.springframework.web.method.annotation.MethodArgumentTypeMismatchException`, `org.springframework.web.bind.MissingServletRequestParameterException`):

  ```java
      @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
      ResponseEntity<ApiError> badParam(Exception e) {
          log.debug("400 파라미터 {}", e.getMessage());
          return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", "요청 형식이 올바르지 않아요"));
      }
  ```

  `src/main/java/com/bookshelf/search/SearchCacheCleanup.java`:

  ```java
  package com.bookshelf.search;

  import lombok.extern.slf4j.Slf4j;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;

  import java.sql.Timestamp;
  import java.time.Clock;
  import java.time.Duration;

  /**
   * TTL(24시간)이 지난 캐시는 제공자 장애 때 "지난 결과라도" 보여주는 데 쓰인다(SearchService).
   * 그래서 TTL이 지나자마자 지우지 않고 일주일 둔다. 그보다 오래된 결과는 장애 때도 보여줄 가치가 없다.
   */
  @Slf4j
  @Component
  public class SearchCacheCleanup {

      static final Duration RETENTION = Duration.ofDays(7);

      private final JdbcClient jdbc;
      private final Clock clock;

      public SearchCacheCleanup(JdbcClient jdbc, Clock clock) {
          this.jdbc = jdbc;
          this.clock = clock;
      }

      public int runOnce() {
          int deleted = jdbc.sql("DELETE FROM search_cache WHERE fetched_at < :cut")
                  .param("cut", Timestamp.from(clock.instant().minus(RETENTION)))
                  .update();
          log.info("검색 캐시 정리: {}행", deleted);
          return deleted;
      }
  }
  ```

  `RefreshTokenStore.java`에 추가:

  ```java
      /** 만료된 토큰은 어차피 401이다. 회전된(used_at) 토큰도 만료 전까지는 재사용 감지에 필요하므로 만료 후에만 지운다 */
      public int purgeExpired(Instant now) {
          return jdbc.sql("DELETE FROM refresh_token WHERE expires_at < :now")
                  .param("now", Timestamp.from(now))
                  .update();
      }
  ```

  `src/main/java/com/bookshelf/ops/MaintenanceJobs.java`:

  ```java
  package com.bookshelf.ops;

  import com.bookshelf.auth.RefreshTokenStore;
  import com.bookshelf.search.SearchCacheCleanup;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.scheduling.annotation.EnableScheduling;
  import org.springframework.scheduling.annotation.Scheduled;

  import java.time.Clock;

  /** 하루 한 번 쌓이기만 하는 행을 지운다. 로직은 각 클래스에 있고 여기는 스케줄만 — 테스트는 runOnce를 직접 부른다 */
  @Slf4j
  @Configuration
  @EnableScheduling
  @ConditionalOnProperty(name = "bookshelf.background.enabled", havingValue = "true")
  class MaintenanceJobs {

      private final SearchCacheCleanup search;
      private final RefreshTokenStore tokens;
      private final Clock clock;

      MaintenanceJobs(SearchCacheCleanup search, RefreshTokenStore tokens, Clock clock) {
          this.search = search;
          this.tokens = tokens;
          this.clock = clock;
      }

      @Scheduled(cron = "${bookshelf.background.cleanup-cron}", zone = "Asia/Seoul")
      void cleanup() {
          search.runOnce();
          log.info("만료된 refresh 토큰 정리: {}행", tokens.purgeExpired(clock.instant()));
      }
  }
  ```

  `application.yaml`의 `bookshelf.background` 아래에 추가:

  ```yaml
      cleanup-cron: "0 30 4 * * *"   # 매일 04:30 KST — 사용자가 가장 적은 시간
  ```

  `BookshelfProperties.Background` 레코드에는 넣지 않는다 — `@Scheduled`가 플레이스홀더로 직접 읽고, 레코드에 없는 키는 바인딩이 무시한다.

- [ ] **Step 6: 통과 확인**

  Run: `./gradlew test --tests BookApiTest --tests SearchApiTest --tests CleanupTest`
  Expected: PASS (7 + 5 + 2 = 14개)

- [ ] **Step 7: 전체 테스트 후 커밋 (사용자 허용 후)**

  Run: `./gradlew test` → 전부 PASS

  허용을 요청할 경로: `src/main/java/com/bookshelf/book src/main/java/com/bookshelf/search src/main/java/com/bookshelf/ops src/main/java/com/bookshelf/auth/RefreshTokenStore.java src/main/java/com/bookshelf/common/ApiExceptionHandler.java src/main/resources/application.yaml src/test/java/com/bookshelf/book/BookApiTest.java src/test/java/com/bookshelf/search`

  ```bash
  git commit -m "feat: 책·검색 API와 매일 정리 작업

  책 상세는 모르는 책일 때만 제공자를 부르고, 링크는 제공자를 부르지 않는다.
  검색 캐시에 걸린 요청은 사용자 상한을 깎지 않는다.
  검색 캐시는 일주일, refresh 토큰은 만료 후 지운다 — 쌓이기만 하던 행을 닫는다."
  ```

---

### Task 8: 회원 탈퇴 — Apple 연결을 끊은 뒤에만 지운다

App Store 심사 규칙: 계정을 만들 수 있는 앱은 앱 안에서 계정을 지울 수 있어야 하고, Sign in with Apple을 쓰면 탈퇴할 때 Apple 토큰을 철회해야 한다. 우리는 Apple 토큰을 저장하지 않으므로(Global Constraints) 탈퇴 요청 때 앱이 **방금 받은 authorizationCode**를 보낸다. 서버는 그 코드를 Apple의 refresh 토큰으로 바꾼 뒤 곧바로 철회한다.

순서가 의미를 가진다: **Apple 철회 → 우리 DB 삭제.** 반대로 하면 Apple이 거부했을 때 회원만 지워지고 Apple 연결은 남는다. 이 순서면 Apple이 거부하거나 죽었을 때 아무것도 지우지 않는다(사용자는 다시 시도한다).

사용자가 고른 탈퇴 범위: 기록·도서관·토큰·사용량은 지운다. 회원 행은 이름과 Apple 식별자를 지운 채 남긴다 — slug를 남겨 옛 공개 서재 링크를 다른 사람이 가로채지 못하게 한다.

**Files:**
- Modify: `src/main/java/com/bookshelf/member/domain/Member.java` (`erase`)
- Create: `src/main/java/com/bookshelf/member/application/MemberDeletion.java`
- Create: `src/main/java/com/bookshelf/auth/apple/AppleClientSecret.java`, `AppleTokenRevoker.java`, `AppleRestClientConfig.java`, `AppleRejectedException.java`, `AppleUnavailableException.java`
- Create: `src/main/java/com/bookshelf/auth/AccountDeletionService.java`, `AccountController.java`
- Modify: `src/main/java/com/bookshelf/common/ApiExceptionHandler.java` (Apple 거부 400, Apple 장애 503)
- Modify: `src/main/resources/application.yaml` (`apple.connect-timeout`, `apple.read-timeout`), `AppleProperties.java`
- Modify: `src/test/java/com/bookshelf/support/AppleStub.java` (토큰 교환·철회 스텁), `src/test/java/com/bookshelf/auth/apple/ApplePropertiesTest.java` (생성자 인자)
- Test: `src/test/java/com/bookshelf/auth/apple/AppleClientSecretTest.java`, `src/test/java/com/bookshelf/auth/AccountDeletionTest.java`

**Interfaces:**
- Consumes: `AppleProperties`(Task 2), `MemberService#active`(Task 5), `AuthController` 로그인 `POST /api/auth/apple {identityToken, nonce, displayName}`(Task 3), `src/test/resources/apple/test-client-key.pub.pem`(Task 1 Step 1)
- Produces:
  - `AppleProperties`에 `Duration connectTimeout, Duration readTimeout` 추가(레코드 끝에)
  - `AppleClientSecret#create(Instant now) -> String` — ES256, 헤더 `kid=keyId`, 클레임 `iss=teamId, sub=clientId, aud=https://appleid.apple.com, iat, exp=iat+5분`
  - `AppleTokenRevoker#revokeWithCode(String authorizationCode)` — 거부(4xx)는 `AppleRejectedException`, 장애(5xx·타임아웃·형식 오류)는 `AppleUnavailableException`
  - `Member#erase(Instant now)` — `withdraw` + `displayName = "탈퇴한 회원"` + `appleSub = null`, slug는 그대로
  - `MemberDeletion#delete(long memberId, Instant now)` — 한 트랜잭션. Task 11이 `member_library`, `member_library_change` 삭제를 더한다
  - `AccountDeletionService#delete(long memberId, String authorizationCode)`
  - HTTP: `DELETE /api/me {authorizationCode}` → 204
  - 테스트 지원: `AppleStub.exchangeReturns(code, refreshToken)`, `exchangeRejects(code)`, `revokeOk()`, `revokeFails(status)`, `appleDown()`, `formOf(path) -> Map<String,String>`(마지막 요청의 form), `calls(path) -> int`

- [ ] **Step 1: 설정** — `application.yaml`의 `apple:` 아래에 추가

  ```yaml
    # Apple 토큰 교환·철회. 탈퇴 때만 부른다 — 사람이 기다린다
    connect-timeout: 2s
    read-timeout: 5s
  ```

  `AppleProperties`의 레코드 끝에 `Duration connectTimeout, Duration readTimeout`을 더한다(import `java.time.Duration`). Task 2의 `ApplePropertiesTest.with(...)`는 생성자를 직접 부르므로 끝에 `Duration.ofSeconds(2), Duration.ofSeconds(5)`를 더한다(import `java.time.Duration`).

- [ ] **Step 2: 테스트 지원 — 가짜 Apple에 교환·철회를 더한다** — `AppleStub.java`에 추가

  ```java
      /** 이 코드로 교환하면 이 refresh 토큰을 준다 */
      public static void exchangeReturns(String code, String refreshToken) {
          SERVER.stubFor(post(urlPathEqualTo("/auth/token")).withFormParam("code", equalTo(code))
                  .willReturn(okJson("{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
                          + "\"refresh_token\":\"" + refreshToken + "\",\"id_token\":\"x\"}")));
      }

      /** 만료됐거나 이미 쓴 코드 — Apple은 400 invalid_grant를 준다 */
      public static void exchangeRejects(String code) {
          SERVER.stubFor(post(urlPathEqualTo("/auth/token")).withFormParam("code", equalTo(code))
                  .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                          .withBody("{\"error\":\"invalid_grant\"}")));
      }

      public static void revokeOk() {
          SERVER.stubFor(post(urlPathEqualTo("/auth/revoke")).willReturn(ok()));
      }

      public static void revokeFails(int status) {
          SERVER.stubFor(post(urlPathEqualTo("/auth/revoke")).willReturn(aResponse().withStatus(status)));
      }

      public static void appleDown() {
          SERVER.stubFor(post(urlPathEqualTo("/auth/token")).willReturn(aResponse().withStatus(503)));
          SERVER.stubFor(post(urlPathEqualTo("/auth/revoke")).willReturn(aResponse().withStatus(503)));
      }

      public static int calls(String path) {
          return SERVER.findAll(postRequestedFor(urlPathEqualTo(path))).size();
      }

      /** 그 경로로 온 마지막 요청의 form 본문 */
      public static Map<String, String> formOf(String path) {
          List<LoggedRequest> all = SERVER.findAll(postRequestedFor(urlPathEqualTo(path)));
          if (all.isEmpty()) {
              throw new AssertionError(path + "로 온 요청이 없다");
          }
          Map<String, String> form = new LinkedHashMap<>();
          for (String pair : all.getLast().getBodyAsString().split("&")) {
              String[] kv = pair.split("=", 2);
              form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                      kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
          }
          return form;
      }
  ```

  (import `com.github.tomakehurst.wiremock.verification.LoggedRequest`, `java.net.URLDecoder`, `java.util.LinkedHashMap`, `java.util.Map`. `post`, `postRequestedFor`, `equalTo`, `ok`, `aResponse`는 이미 있는 `WireMock.*` static import에 들어 있다)

- [ ] **Step 3: 실패하는 단위 테스트 — client secret**

  `src/test/java/com/bookshelf/auth/apple/AppleClientSecretTest.java`:

  ```java
  package com.bookshelf.auth.apple;

  import com.nimbusds.jose.JWSAlgorithm;
  import com.nimbusds.jose.crypto.ECDSAVerifier;
  import com.nimbusds.jose.jwk.ECKey;
  import com.nimbusds.jwt.SignedJWT;
  import org.junit.jupiter.api.Test;

  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.time.Duration;
  import java.time.Instant;

  import static org.assertj.core.api.Assertions.assertThat;

  class AppleClientSecretTest {

      private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

      private static AppleProperties props() throws Exception {
          String pem = Files.readString(Path.of("src/test/resources/apple/test-client-key.p8")).replace("\n", "|");
          return new AppleProperties("com.bookshelf.test", "TESTTEAM00", "TESTKEY000", pem,
                  "https://appleid.apple.com", "https://appleid.apple.com/auth/keys",
                  Duration.ofSeconds(2), Duration.ofSeconds(5));
      }

      @Test
      void 우리_키로_ES256_서명하고_Apple이_요구하는_클레임을_담는다() throws Exception {
          SignedJWT jwt = SignedJWT.parse(new AppleClientSecret(props()).create(NOW));

          ECKey pub = (ECKey) ECKey.parseFromPEMEncodedObjects(
                  Files.readString(Path.of("src/test/resources/apple/test-client-key.pub.pem")));
          assertThat(jwt.verify(new ECDSAVerifier(pub))).isTrue();
          assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
          assertThat(jwt.getHeader().getKeyID()).isEqualTo("TESTKEY000");
          assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("TESTTEAM00");
          assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("com.bookshelf.test");
          assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("https://appleid.apple.com");
          assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
      }
  }
  ```

  Run: `./gradlew test --tests AppleClientSecretTest`
  Expected: FAIL — 컴파일 실패(`AppleClientSecret` 없음, `AppleProperties` 인자 수)

- [ ] **Step 4: 구현 — client secret**

  `src/main/java/com/bookshelf/auth/apple/AppleClientSecret.java`:

  ```java
  package com.bookshelf.auth.apple;

  import com.nimbusds.jose.JOSEException;
  import com.nimbusds.jose.JWSAlgorithm;
  import com.nimbusds.jose.JWSHeader;
  import com.nimbusds.jose.crypto.ECDSASigner;
  import com.nimbusds.jwt.JWTClaimsSet;
  import com.nimbusds.jwt.SignedJWT;
  import org.springframework.stereotype.Component;

  import java.security.KeyFactory;
  import java.security.interfaces.ECPrivateKey;
  import java.security.spec.PKCS8EncodedKeySpec;
  import java.time.Duration;
  import java.time.Instant;
  import java.util.Base64;
  import java.util.Date;

  /**
   * Apple 토큰 API의 client_secret. Apple이 준 .p8(ES256) 키로 우리가 서명하는 짧은 JWT다.
   * 개인키는 기동 때 한 번 읽는다 — 키가 깨졌으면 탈퇴 요청 때가 아니라 기동 때 알아야 한다.
   */
  @Component
  public class AppleClientSecret {

      static final String AUDIENCE = "https://appleid.apple.com";
      static final Duration LIFETIME = Duration.ofMinutes(5);   // Apple 상한은 6개월. 요청마다 새로 만드니 짧게

      private final AppleProperties props;
      private final ECPrivateKey key;

      public AppleClientSecret(AppleProperties props) {
          this.props = props;
          this.key = parse(props.privateKeyPem());
      }

      public String create(Instant now) {
          JWTClaimsSet claims = new JWTClaimsSet.Builder()
                  .issuer(props.teamId())
                  .subject(props.clientId())
                  .audience(AUDIENCE)
                  .issueTime(Date.from(now))
                  .expirationTime(Date.from(now.plus(LIFETIME)))
                  .build();
          SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(props.keyId()).build(), claims);
          try {
              jwt.sign(new ECDSASigner(key));
          } catch (JOSEException e) {
              throw new IllegalStateException("Apple client secret 서명 실패", e);
          }
          return jwt.serialize();
      }

      private static ECPrivateKey parse(String pem) {
          String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                  .replace("-----END PRIVATE KEY-----", "")
                  .replaceAll("\\s", "");
          try {
              return (ECPrivateKey) KeyFactory.getInstance("EC")
                      .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
          } catch (Exception e) {
              // 키 내용은 메시지에 넣지 않는다
              throw new IllegalStateException("APPLE_PRIVATE_KEY를 EC 개인키(PKCS#8)로 읽지 못했다", e);
          }
      }
  }
  ```

  Run: `./gradlew test --tests AppleClientSecretTest`
  Expected: PASS

- [ ] **Step 5: 실패하는 통합 테스트 — 탈퇴**

  `src/test/java/com/bookshelf/auth/AccountDeletionTest.java`:

  ```java
  package com.bookshelf.auth;

  import com.bookshelf.support.AppleStub;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.TestJwt;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.http.MediaType;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;
  import org.springframework.test.web.servlet.MockMvc;
  import org.springframework.test.web.servlet.ResultActions;
  import tools.jackson.databind.JsonNode;
  import tools.jackson.databind.ObjectMapper;

  import java.util.Map;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(Fixtures.class)
  class AccountDeletionTest {

      private static final String NONCE = "raw-nonce-1234";
      private static final String SUB = "001234.apple-user";
      private static final String CODE = "fresh-auth-code";
      private static final String A = "9788936434120";

      @DynamicPropertySource
      static void apple(DynamicPropertyRegistry r) {
          r.add("apple.jwks-uri", AppleStub::jwksUri);
          r.add("apple.auth-base-url", AppleStub::baseUrl);
      }

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired ObjectMapper json;
      @Autowired Fixtures fx;
      @Autowired JwtEncoder encoder;

      private long memberId;
      private String access;
      private String refresh;

      @BeforeEach
      void setUp() throws Exception {
          DbCleaner.clean(jdbc);
          AppleStub.reset();
          JsonNode tokens = login();
          access = "Bearer " + tokens.get("accessToken").asString();
          refresh = tokens.get("refreshToken").asString();
          memberId = jdbc.sql("SELECT id FROM member WHERE apple_sub = :s").param("s", SUB).query(Long.class).single();
          // 공개 서재에 공개 기록 하나 — 탈퇴 뒤 공개 페이지에서 사라지는지 본다
          jdbc.sql("UPDATE member SET slug = 'leaving', shelf_public = TRUE WHERE id = :id").param("id", memberId).update();
          fx.book(A, "소년이 온다");
          jdbc.sql("""
                  INSERT INTO reading_record (member_id, isbn13, status, visibility, rating, review, created_at, updated_at)
                  VALUES (:m, :isbn, 'DONE', 'PUBLIC', 5, '떠나는 사람의 한줄평', now(), now())
                  """).param("m", memberId).param("isbn", A).update();
          jdbc.sql("INSERT INTO user_call_counter (member_id, day, calls) VALUES (:m, current_date, 3)").param("m", memberId).update();
      }

      private JsonNode login() throws Exception {
          String body = json.writeValueAsString(Map.of(
                  "identityToken", AppleStub.identityToken(SUB, NONCE), "nonce", NONCE, "displayName", "주희"));
          String res = mvc.perform(post("/api/auth/apple").contentType(MediaType.APPLICATION_JSON).content(body))
                  .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
          return json.readTree(res);
      }

      private ResultActions deleteAccount(String body) throws Exception {
          return mvc.perform(delete("/api/me").header("Authorization", access)
                  .contentType(MediaType.APPLICATION_JSON).content(body));
      }

      private int count(String table) {
          return jdbc.sql("SELECT count(*) FROM " + table + " WHERE member_id = :m").param("m", memberId)
                  .query(Integer.class).single();
      }

      @Test
      void 탈퇴하면_기록_토큰_사용량을_지우고_회원_행은_이름과_Apple_식별자를_지운_채_남긴다() throws Exception {
          AppleStub.exchangeReturns(CODE, "apple-refresh-1");
          AppleStub.revokeOk();

          deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isNoContent());

          assertThat(count("reading_record")).isZero();
          assertThat(count("refresh_token")).isZero();
          assertThat(count("user_call_counter")).isZero();
          Map<String, Object> row = jdbc.sql("SELECT slug, display_name, apple_sub, shelf_public, deleted_at FROM member WHERE id = :id")
                  .param("id", memberId).query().singleRow();
          assertThat(row.get("slug")).isEqualTo("leaving");
          assertThat(row.get("display_name")).isEqualTo("탈퇴한 회원");
          assertThat(row.get("apple_sub")).isNull();
          assertThat(row.get("shelf_public")).isEqualTo(false);
          assertThat(row.get("deleted_at")).isNotNull();
      }

      @Test
      void Apple에서_받은_refresh_토큰을_철회한다() throws Exception {
          AppleStub.exchangeReturns(CODE, "apple-refresh-1");
          AppleStub.revokeOk();

          deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isNoContent());

          Map<String, String> exchange = AppleStub.formOf("/auth/token");
          assertThat(exchange).containsEntry("grant_type", "authorization_code")
                  .containsEntry("code", CODE).containsEntry("client_id", AppleStub.CLIENT_ID).containsKey("client_secret");
          assertThat(AppleStub.formOf("/auth/revoke")).containsEntry("token", "apple-refresh-1")
                  .containsEntry("token_type_hint", "refresh_token").containsEntry("client_id", AppleStub.CLIENT_ID);
      }

      @Test
      void 탈퇴한_서재는_404이고_그_slug는_다른_사람이_쓰지_못한다() throws Exception {
          AppleStub.exchangeReturns(CODE, "apple-refresh-1");
          AppleStub.revokeOk();
          deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isNoContent());

          mvc.perform(get("/u/leaving")).andExpect(status().isNotFound());
          mvc.perform(get("/book/" + A)).andExpect(status().isNotFound());   // 공개 기록이 사라져 책 페이지도 없다

          String other = TestJwt.bearer(encoder, fx.member("other", false).getId());
          mvc.perform(patch("/api/me").header("Authorization", other).contentType(MediaType.APPLICATION_JSON)
                  .content("{\"slug\":\"leaving\"}")).andExpect(status().isConflict());
      }

      @Test
      void 탈퇴_전에_받은_토큰은_모두_통하지_않는다() throws Exception {
          AppleStub.exchangeReturns(CODE, "apple-refresh-1");
          AppleStub.revokeOk();
          deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isNoContent());

          mvc.perform(get("/api/me").header("Authorization", access)).andExpect(status().isUnauthorized());
          mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                  .content("{\"refreshToken\":\"" + refresh + "\"}")).andExpect(status().isUnauthorized());
      }

      @Test
      void 같은_Apple_계정으로_다시_가입하면_새_회원이다() throws Exception {
          AppleStub.exchangeReturns(CODE, "apple-refresh-1");
          AppleStub.revokeOk();
          deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isNoContent());

          login();

          long newId = jdbc.sql("SELECT id FROM member WHERE apple_sub = :s").param("s", SUB).query(Long.class).single();
          assertThat(newId).isNotEqualTo(memberId);
          assertThat(jdbc.sql("SELECT count(*) FROM reading_record WHERE member_id = :m").param("m", newId)
                  .query(Integer.class).single()).isZero();
      }

      @Test
      void Apple이_코드를_거부하면_400이고_아무것도_지우지_않는다() throws Exception {
          AppleStub.exchangeRejects(CODE);

          deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isBadRequest());

          assertThat(count("reading_record")).isEqualTo(1);
          assertThat(count("refresh_token")).isEqualTo(1);
          mvc.perform(get("/api/me").header("Authorization", access)).andExpect(status().isOk());
          assertThat(AppleStub.calls("/auth/revoke")).isZero();
      }

      @Test
      void Apple이_죽었으면_503이고_아무것도_지우지_않는다() throws Exception {
          AppleStub.appleDown();

          deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isServiceUnavailable());

          assertThat(count("reading_record")).isEqualTo(1);
          mvc.perform(get("/u/leaving")).andExpect(status().isOk());
      }

      @Test
      void 철회가_실패해도_아무것도_지우지_않는다() throws Exception {
          AppleStub.exchangeReturns(CODE, "apple-refresh-1");
          AppleStub.revokeFails(500);

          deleteAccount("{\"authorizationCode\":\"" + CODE + "\"}").andExpect(status().isServiceUnavailable());

          assertThat(count("reading_record")).isEqualTo(1);
      }

      @Test
      void 코드가_없으면_400이고_Apple을_부르지_않는다() throws Exception {
          deleteAccount("{}").andExpect(status().isBadRequest());
          deleteAccount("{\"authorizationCode\":\"  \"}").andExpect(status().isBadRequest());

          assertThat(AppleStub.calls("/auth/token")).isZero();
          assertThat(count("reading_record")).isEqualTo(1);
      }
  }
  ```

- [ ] **Step 6: 실패 확인**

  Run: `./gradlew test --tests AccountDeletionTest`
  Expected: FAIL — `DELETE /api/me` 핸들러 없음(405 또는 404)

- [ ] **Step 7: 구현**

  `Member.java`에 추가:

  ```java
      static final String ERASED_NAME = "탈퇴한 회원";

      /**
       * 탈퇴. slug는 남긴다 — 옛 공개 서재 링크를 다른 사람이 이어받지 못하게(설계 결정).
       * Apple 식별자를 지워 같은 Apple 계정의 다음 로그인은 새 회원이 된다.
       */
      public void erase(Instant now) {
          withdraw(now);
          this.displayName = ERASED_NAME;
          this.appleSub = null;
      }
  ```

  `src/main/java/com/bookshelf/member/application/MemberDeletion.java`:

  ```java
  package com.bookshelf.member.application;

  import com.bookshelf.member.domain.Member;
  import com.bookshelf.member.infrastructure.MemberRepository;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;
  import org.springframework.transaction.annotation.Transactional;

  import java.time.Instant;

  /**
   * 회원이 남긴 것을 한 트랜잭션에서 지운다. 테이블을 추가해 member_id를 담으면 여기에도 더한다
   * (AccountDeletionTest가 테이블별로 확인한다).
   */
  @Component
  public class MemberDeletion {

      private static final String[] OWNED_TABLES = {"reading_record", "refresh_token", "user_call_counter"};

      private final JdbcClient jdbc;
      private final MemberRepository members;

      public MemberDeletion(JdbcClient jdbc, MemberRepository members) {
          this.jdbc = jdbc;
          this.members = members;
      }

      @Transactional
      public void delete(long memberId, Instant now) {
          for (String table : OWNED_TABLES) {
              jdbc.sql("DELETE FROM " + table + " WHERE member_id = :m").param("m", memberId).update();
          }
          Member m = members.findById(memberId).orElseThrow();
          m.erase(now);
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/apple/AppleRejectedException.java`:

  ```java
  package com.bookshelf.auth.apple;

  /** Apple이 코드를 거부했다(만료·이미 씀). 앱이 Apple 인증을 다시 받아 오면 된다 */
  public class AppleRejectedException extends RuntimeException {
      public AppleRejectedException(String message) {
          super(message);
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/apple/AppleUnavailableException.java`:

  ```java
  package com.bookshelf.auth.apple;

  public class AppleUnavailableException extends RuntimeException {
      public AppleUnavailableException(String message, Throwable cause) {
          super(message, cause);
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/apple/AppleRestClientConfig.java`:

  ```java
  package com.bookshelf.auth.apple;

  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.http.client.JdkClientHttpRequestFactory;
  import org.springframework.web.client.RestClient;

  import java.net.http.HttpClient;

  /** HTTP/1.1 고정 이유는 KakaoRestClientConfig와 같다(WireMock이 HTTP/2 업그레이드를 받지 못한다) */
  @Configuration
  public class AppleRestClientConfig {

      @Bean
      RestClient appleRestClient(AppleProperties props, RestClient.Builder builder) {
          HttpClient http = HttpClient.newBuilder()
                  .version(HttpClient.Version.HTTP_1_1)
                  .connectTimeout(props.connectTimeout())
                  .build();
          JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
          factory.setReadTimeout(props.readTimeout());
          return builder.baseUrl(props.authBaseUrl()).requestFactory(factory).build();
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/apple/AppleTokenRevoker.java`:

  ```java
  package com.bookshelf.auth.apple;

  import com.fasterxml.jackson.annotation.JsonProperty;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.http.HttpStatusCode;
  import org.springframework.http.MediaType;
  import org.springframework.stereotype.Component;
  import org.springframework.util.LinkedMultiValueMap;
  import org.springframework.util.MultiValueMap;
  import org.springframework.web.client.HttpClientErrorException;
  import org.springframework.web.client.RestClient;
  import org.springframework.web.client.RestClientException;

  import java.time.Clock;

  /**
   * 탈퇴 때 Apple 연결을 끊는다: 앱이 방금 받은 authorizationCode → Apple refresh 토큰 → 철회.
   * 우리는 Apple 토큰을 저장하지 않는다 — 그래서 탈퇴마다 새 코드가 필요하다.
   */
  @Slf4j
  @Component
  public class AppleTokenRevoker {

      /** Apple 응답의 나머지 필드(access_token, id_token 등)는 쓰지 않는다 */
      record TokenResponse(@JsonProperty("refresh_token") String refreshToken,
                           @JsonProperty("access_token") String accessToken) {
      }

      private final RestClient rest;
      private final AppleProperties props;
      private final AppleClientSecret secret;
      private final Clock clock;

      public AppleTokenRevoker(RestClient appleRestClient, AppleProperties props, AppleClientSecret secret, Clock clock) {
          this.rest = appleRestClient;
          this.props = props;
          this.secret = secret;
          this.clock = clock;
      }

      public void revokeWithCode(String authorizationCode) {
          String clientSecret = secret.create(clock.instant());
          TokenResponse tokens = exchange(authorizationCode, clientSecret);
          boolean hasRefresh = tokens.refreshToken() != null && !tokens.refreshToken().isBlank();
          revoke(hasRefresh ? tokens.refreshToken() : tokens.accessToken(),
                  hasRefresh ? "refresh_token" : "access_token", clientSecret);
      }

      private TokenResponse exchange(String code, String clientSecret) {
          MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
          form.add("client_id", props.clientId());
          form.add("client_secret", clientSecret);
          form.add("code", code);
          form.add("grant_type", "authorization_code");
          try {
              TokenResponse r = rest.post().uri("/auth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                      .body(form).retrieve().body(TokenResponse.class);
              if (r == null || (r.refreshToken() == null && r.accessToken() == null)) {
                  throw new AppleUnavailableException("Apple 토큰 응답에 토큰이 없다", null);
              }
              return r;
          } catch (HttpClientErrorException e) {
              // 응답 본문(error 코드)만 남긴다. 코드와 secret은 로그에 남기지 않는다
              log.info("Apple이 탈퇴 코드를 거부: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
              throw new AppleRejectedException("Apple이 코드를 거부했다: " + e.getStatusCode());
          } catch (RestClientException e) {
              throw new AppleUnavailableException("Apple 토큰 교환 실패", e);
          }
      }

      private void revoke(String token, String hint, String clientSecret) {
          MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
          form.add("client_id", props.clientId());
          form.add("client_secret", clientSecret);
          form.add("token", token);
          form.add("token_type_hint", hint);
          try {
              HttpStatusCode status = rest.post().uri("/auth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                      .body(form).retrieve().toBodilessEntity().getStatusCode();
              log.info("Apple 토큰 철회: {}", status);
          } catch (RestClientException e) {
              // 교환은 됐는데 철회가 안 됐다 — 연결이 남은 채 회원을 지우지 않는다
              throw new AppleUnavailableException("Apple 토큰 철회 실패", e);
          }
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/AccountDeletionService.java`:

  ```java
  package com.bookshelf.auth;

  import com.bookshelf.auth.apple.AppleTokenRevoker;
  import com.bookshelf.common.DomainException;
  import com.bookshelf.member.application.MemberDeletion;
  import com.bookshelf.member.application.MemberService;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.stereotype.Service;

  import java.time.Clock;

  /** @Transactional 없음 — Apple 호출(외부)을 DB 트랜잭션 안에 두지 않는다. DB 삭제는 MemberDeletion 한 트랜잭션 */
  @Slf4j
  @Service
  public class AccountDeletionService {

      private final MemberService members;
      private final AppleTokenRevoker apple;
      private final MemberDeletion deletion;
      private final Clock clock;

      public AccountDeletionService(MemberService members, AppleTokenRevoker apple, MemberDeletion deletion, Clock clock) {
          this.members = members;
          this.apple = apple;
          this.deletion = deletion;
          this.clock = clock;
      }

      /** Apple 철회가 끝나야 지운다. Apple이 거부하거나 죽었으면 예외가 나가고 아무것도 지우지 않는다 */
      public void delete(long memberId, String authorizationCode) {
          members.active(memberId);
          if (authorizationCode == null || authorizationCode.isBlank()) {
              throw new DomainException("Apple 인증 코드가 없다");
          }
          apple.revokeWithCode(authorizationCode);
          deletion.delete(memberId, clock.instant());
          log.info("회원 {} 탈퇴", memberId);
      }
  }
  ```

  `src/main/java/com/bookshelf/auth/AccountController.java`:

  ```java
  package com.bookshelf.auth;

  import com.bookshelf.common.CurrentMember;
  import org.springframework.http.ResponseEntity;
  import org.springframework.web.bind.annotation.DeleteMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RestController;

  /** GET·PATCH /api/me는 MeController(member), DELETE만 여기 — 탈퇴는 Apple 연결 해제가 먼저라 auth에 둔다 */
  @RestController
  public class AccountController {

      public record DeleteRequest(String authorizationCode) {
      }

      private final AccountDeletionService deletion;

      public AccountController(AccountDeletionService deletion) {
          this.deletion = deletion;
      }

      @DeleteMapping("/api/me")
      public ResponseEntity<Void> delete(@RequestBody DeleteRequest req) {
          deletion.delete(CurrentMember.require(), req.authorizationCode());
          return ResponseEntity.noContent().build();
      }
  }
  ```

  `ApiExceptionHandler`에 추가(import `com.bookshelf.auth.apple.AppleRejectedException`, `com.bookshelf.auth.apple.AppleUnavailableException`):

  ```java
      @ExceptionHandler(AppleRejectedException.class)
      ResponseEntity<ApiError> appleRejected(AppleRejectedException e) {
          return ResponseEntity.badRequest().body(new ApiError("APPLE_REAUTH", "Apple 인증을 다시 해 주세요"));
      }

      @ExceptionHandler(AppleUnavailableException.class)
      ResponseEntity<ApiError> appleDown(AppleUnavailableException e) {
          log.warn("503 {}", e.getMessage());
          return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                  .body(new ApiError("TRY_LATER", "잠시 후 다시 시도해 주세요"));
      }
  ```

- [ ] **Step 8: 통과 확인**

  Run: `./gradlew test --tests AccountDeletionTest --tests AppleClientSecretTest`
  Expected: PASS (9 + 1 = 10개)

- [ ] **Step 9: 전체 테스트 후 커밋 (사용자 허용 후)**

  Run: `./gradlew test` → 전부 PASS

  허용을 요청할 경로: `src/main/java/com/bookshelf/auth src/main/java/com/bookshelf/member src/main/java/com/bookshelf/common/ApiExceptionHandler.java src/main/resources/application.yaml src/test/java/com/bookshelf/auth src/test/java/com/bookshelf/support/AppleStub.java`

  커밋 전 확인: `git diff --cached | grep -iE "BEGIN PRIVATE KEY|client_secret=ey"` 결과가 비어야 한다(테스트 키 파일은 Task 1에서 이미 들어갔다 — 실제 .p8이 섞이지 않았는지 본다).

  ```bash
  git commit -m "feat: 회원 탈퇴 — Apple 연결을 끊은 뒤에만 지운다

  앱이 보낸 새 authorizationCode를 Apple refresh 토큰으로 바꿔 곧바로 철회하고, 그다음 DB를 지운다.
  Apple이 거부하면 400, 죽었으면 503이고 둘 다 아무것도 지우지 않는다.
  회원 행은 이름과 Apple 식별자를 지운 채 남긴다 — slug를 다른 사람이 이어받지 못하게."
  ```

---

### Task 9: 정보나루 클라이언트 — 빌리기 칸의 유일한 입구

카카오 클라이언트(Phase 1)와 같은 순서: **쿨다운 → 예산(사용자 → 제공자) → HTTP → 번역.** 다른 점 두 가지:
- 정보나루는 인증키를 **쿼리 파라미터**(`authKey`)로 받는다. Spring의 `ResourceAccessException` 메시지에는 요청 URL이 통째로 들어가므로 **예외 메시지를 로그·예외에 옮기지 않는다** — 예외 클래스 이름만 남긴다.
- 정보나루는 오류도 HTTP 200으로 준다: `{"response":{"errCode":"...","error":"..."}}`(실측). `error`가 있거나 `result`가 없으면 장애로 본다.

**Files:**
- Create: `src/main/java/com/bookshelf/library/HoldingState.java`, `LibraryAvailability.java`, `LibraryDirectory.java`, `LibraryInfo.java`, `LibraryPage.java`
- Create: `src/main/java/com/bookshelf/library/data4library/LibraryProperties.java`, `LibraryRestClientConfig.java`, `Data4LibraryResponse.java`, `Data4LibraryClient.java`
- Modify: `src/main/resources/application.yaml` (`providers.library`)
- Create: `src/test/java/com/bookshelf/support/LibraryStub.java`
- Create: `src/test/resources/library/*.json` (Task 0 Step 3에서 받은 것)
- Test: `src/test/java/com/bookshelf/library/data4library/LibraryPropertiesTest.java`, `Data4LibraryClientTest.java`, `src/test/java/com/bookshelf/library/HoldingStateTest.java`

**Interfaces:**
- Consumes: `CallBudget#acquire(BudgetPolicy, CallPurpose)`(Task 4), `BudgetPolicy(Provider, int dailyLimit, int backgroundCeiling, ZoneId resetZone)`, `Provider.LIBRARY`, `ProviderUnavailableException(Provider, String[, Throwable])`
- Produces:
  - `enum HoldingState { AVAILABLE, ON_LOAN, NOT_HELD, UNKNOWN }`, `static HoldingState from(String hasBook, String loanAvailable)` — 처음 보는 값은 `UNKNOWN`
  - `interface LibraryAvailability { HoldingState holding(String libCode, Isbn13 isbn, CallPurpose purpose); }`
  - `interface LibraryDirectory { LibraryPage libraries(int region, int pageNo, int pageSize, CallPurpose purpose); }`
  - `record LibraryInfo(String libCode, String name, String address, String tel, Double latitude, Double longitude, String homepage)`
  - `record LibraryPage(List<LibraryInfo> libraries, int numFound)`
  - `record LibraryProperties(String apiKey, String baseUrl, Duration connectTimeout, Duration readTimeout, int dailyLimit, int backgroundCeiling, ZoneId resetZone, Duration coolDown)` prefix `providers.library`, `#policy()`
  - 테스트 지원: `LibraryStub.baseUrl()`, `reset()`, `holding(libCode, isbn, body)`, `libraries(region, pageNo, body)`, `failsWith(status)`, `fixture(name)`, `server()`

- [ ] **Step 1: fixture를 테스트 자원으로** — Task 0 Step 3에서 이미 받았으면 건너뛴다

  ```bash
  ls src/test/resources/library/
  # bookExist-111456.json  bookExist-711618.json  libSrch-seoul-3.json
  grep -c "authKey" src/test/resources/library/*.json   # 요청 에코에 키가 없다 — 모두 0
  ```

- [ ] **Step 2: 설정** — `application.yaml`의 `providers:` 아래에 추가

  ```yaml
    library:
      api-key: ${LIBRARY_API_KEY}           # 기본값 없음 — 없으면 기동 실패
      base-url: ${LIBRARY_BASE_URL:https://data4library.kr}
      # 실측 330~650ms (docs/notes/providers.md)
      connect-timeout: 1s
      read-timeout: 3s
      # 서버 IP 미등록 일 500의 90% / 60% (설계 문서 6-3, 6-7). IP를 등록해 한도가 오르면 여기만 바꾼다
      daily-limit: 450
      background-ceiling: 300
      reset-zone: Asia/Seoul
      cool-down: 60s
  ```

- [ ] **Step 3: 실패하는 단위 테스트 — 설정 검사와 상태 변환**

  `src/test/java/com/bookshelf/library/data4library/LibraryPropertiesTest.java`:

  ```java
  package com.bookshelf.library.data4library;

  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.NullAndEmptySource;
  import org.junit.jupiter.params.provider.ValueSource;

  import java.time.Duration;
  import java.time.ZoneId;

  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  class LibraryPropertiesTest {

      @ParameterizedTest
      @NullAndEmptySource
      @ValueSource(strings = {"  ", "${LIBRARY_API_KEY}"})
      void 키가_없으면_기동하지_않는다(String key) {
          assertThatThrownBy(() -> new LibraryProperties(key, "https://data4library.kr", Duration.ofSeconds(1),
                  Duration.ofSeconds(3), 450, 300, ZoneId.of("Asia/Seoul"), Duration.ofSeconds(60)))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("LIBRARY_API_KEY");
      }
  }
  ```

  `src/test/java/com/bookshelf/library/HoldingStateTest.java`:

  ```java
  package com.bookshelf.library;

  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.CsvSource;

  import static org.assertj.core.api.Assertions.assertThat;

  class HoldingStateTest {

      /** 실측: 대출 가능(Y,Y) / 대출 중(Y,N) / 미소장(N,N). 모르는 ISBN도 (N,N) — docs/notes/providers.md */
      @ParameterizedTest
      @CsvSource(nullValues = "null", value = {
              "Y, Y, AVAILABLE",
              "Y, N, ON_LOAN",
              "N, N, NOT_HELD",
              "N, Y, NOT_HELD",     // 소장하지 않았는데 대출 가능 — 말이 안 되는 조합은 소장 쪽을 믿는다
              "X, Y, UNKNOWN",
              "null, null, UNKNOWN"
      })
      void 두_값을_세_상태로_옮긴다(String hasBook, String loanAvailable, HoldingState expected) {
          assertThat(HoldingState.from(hasBook, loanAvailable)).isEqualTo(expected);
      }
  }
  ```

  Run: `./gradlew test --tests LibraryPropertiesTest --tests HoldingStateTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 4: 구현 — 설정과 도메인 타입**

  `src/main/java/com/bookshelf/library/HoldingState.java`:

  ```java
  package com.bookshelf.library;

  /** 빌리기 칸의 한 도서관. UNKNOWN은 "확인 불가"(제공자 장애·처음 보는 값) */
  public enum HoldingState {
      AVAILABLE,   // 대출 가능
      ON_LOAN,     // 소장, 대출 중
      NOT_HELD,    // 소장하지 않음
      UNKNOWN;

      public static HoldingState from(String hasBook, String loanAvailable) {
          if ("N".equals(hasBook)) {
              return NOT_HELD;
          }
          if ("Y".equals(hasBook) && "Y".equals(loanAvailable)) {
              return AVAILABLE;
          }
          if ("Y".equals(hasBook) && "N".equals(loanAvailable)) {
              return ON_LOAN;
          }
          return UNKNOWN;
      }
  }
  ```

  `src/main/java/com/bookshelf/library/LibraryAvailability.java`:

  ```java
  package com.bookshelf.library;

  import com.bookshelf.common.Isbn13;
  import com.bookshelf.provider.CallPurpose;

  /** 빌리기 칸의 제공자. 실패는 ProviderUnavailableException(예산 소진 포함) 또는 UserQuotaExceededException */
  public interface LibraryAvailability {

      HoldingState holding(String libCode, Isbn13 isbn, CallPurpose purpose);
  }
  ```

  `src/main/java/com/bookshelf/library/LibraryDirectory.java`:

  ```java
  package com.bookshelf.library;

  import com.bookshelf.provider.CallPurpose;

  /** 지역(시도 코드)별 도서관 목록. 동기화(LibrarySync)만 부른다 — 사용자의 도서관 검색은 로컬 테이블에서 한다 */
  public interface LibraryDirectory {

      LibraryPage libraries(int region, int pageNo, int pageSize, CallPurpose purpose);
  }
  ```

  `src/main/java/com/bookshelf/library/LibraryInfo.java`:

  ```java
  package com.bookshelf.library;

  /** 좌표는 정보나루가 문자열로 준다. 읽지 못하면 null — 목록에서 빼지 않는다 */
  public record LibraryInfo(String libCode, String name, String address, String tel,
                            Double latitude, Double longitude, String homepage) {
  }
  ```

  `src/main/java/com/bookshelf/library/LibraryPage.java`:

  ```java
  package com.bookshelf.library;

  import java.util.List;

  public record LibraryPage(List<LibraryInfo> libraries, int numFound) {
  }
  ```

  `src/main/java/com/bookshelf/library/data4library/LibraryProperties.java`:

  ```java
  package com.bookshelf.library.data4library;

  import com.bookshelf.provider.BudgetPolicy;
  import com.bookshelf.provider.Provider;
  import org.springframework.boot.context.properties.ConfigurationProperties;

  import java.time.Duration;
  import java.time.ZoneId;

  @ConfigurationProperties("providers.library")
  public record LibraryProperties(
          String apiKey,
          String baseUrl,
          Duration connectTimeout,
          Duration readTimeout,
          int dailyLimit,          // 450 — 서버 IP 미등록 일 500의 90%
          int backgroundCeiling,   // 300
          ZoneId resetZone,
          Duration coolDown
  ) {

      /** KakaoProperties와 같은 이유: 치환 못 한 "${LIBRARY_API_KEY}"가 문자 그대로 들어온다 */
      public LibraryProperties {
          if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
              throw new IllegalStateException("LIBRARY_API_KEY가 없다 — 기본값 없이 환경변수로 주입해야 한다");
          }
      }

      public BudgetPolicy policy() {
          return new BudgetPolicy(Provider.LIBRARY, dailyLimit, backgroundCeiling, resetZone);
      }
  }
  ```

  Run: `./gradlew test --tests LibraryPropertiesTest --tests HoldingStateTest`
  Expected: PASS (4 + 6 = 10개)

- [ ] **Step 5: 테스트 지원 — 가짜 정보나루**

  `src/test/java/com/bookshelf/support/LibraryStub.java`:

  ```java
  package com.bookshelf.support;

  import com.github.tomakehurst.wiremock.WireMockServer;

  import java.io.IOException;
  import java.io.InputStream;
  import java.io.UncheckedIOException;
  import java.nio.charset.StandardCharsets;

  import static com.github.tomakehurst.wiremock.client.WireMock.*;
  import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

  /** 가짜 정보나루. 스프링 테스트는 @DynamicPropertySource로 providers.library.base-url을 이 주소로 바꾼다 */
  public final class LibraryStub {

      private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());

      static {
          SERVER.start();
      }

      private LibraryStub() {
      }

      public static String baseUrl() {
          return SERVER.baseUrl();
      }

      public static WireMockServer server() {
          return SERVER;
      }

      public static void reset() {
          SERVER.resetAll();
      }

      public static void holding(String libCode, String isbn13, String body) {
          SERVER.stubFor(get(urlPathEqualTo("/api/bookExist"))
                  .withQueryParam("libCode", equalTo(libCode))
                  .withQueryParam("isbn13", equalTo(isbn13))
                  .willReturn(okJson(body)));
      }

      public static void libraries(int region, int pageNo, String body) {
          SERVER.stubFor(get(urlPathEqualTo("/api/libSrch"))
                  .withQueryParam("region", equalTo(String.valueOf(region)))
                  .withQueryParam("pageNo", equalTo(String.valueOf(pageNo)))
                  .willReturn(okJson(body)));
      }

      public static void failsWith(int status) {
          SERVER.stubFor(any(urlPathMatching("/api/.*")).willReturn(aResponse().withStatus(status)));
      }

      public static String holdingBody(String hasBook, String loanAvailable) {
          return "{\"response\":{\"result\":{\"hasBook\":\"" + hasBook + "\",\"loanAvailable\":\"" + loanAvailable + "\"}}}";
      }

      /** 실측: 오류도 HTTP 200으로 온다 */
      public static String errorBody() {
          return "{\"response\":{\"errCode\":\"isbnMpngErr\",\"error\":\"ISBN에 해당하는 도서가 없습니다.\"}}";
      }

      public static String emptyLibraries() {
          return "{\"response\":{\"pageNo\":1,\"pageSize\":500,\"numFound\":0,\"resultNum\":0,\"libs\":[]}}";
      }

      public static int calls() {
          return SERVER.findAll(anyRequestedFor(urlPathMatching("/api/.*"))).size();
      }

      public static String fixture(String name) {
          try (InputStream in = LibraryStub.class.getResourceAsStream("/library/" + name)) {
              if (in == null) {
                  throw new IllegalArgumentException("fixture 없음: " + name);
              }
              return new String(in.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
              throw new UncheckedIOException(e);
          }
      }
  }
  ```

- [ ] **Step 6: 실패하는 통합 테스트 — 클라이언트**

  `src/test/java/com/bookshelf/library/data4library/Data4LibraryClientTest.java`:

  ```java
  package com.bookshelf.library.data4library;

  import com.bookshelf.common.Isbn13;
  import com.bookshelf.library.HoldingState;
  import com.bookshelf.library.LibraryInfo;
  import com.bookshelf.library.LibraryPage;
  import com.bookshelf.provider.CallPurpose;
  import com.bookshelf.provider.ProviderUnavailableException;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.LibraryStub;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;

  import static com.github.tomakehurst.wiremock.client.WireMock.*;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  @SpringBootTest
  class Data4LibraryClientTest {

      private static final Isbn13 A = Isbn13.parse("9788936434120");

      @DynamicPropertySource
      static void library(DynamicPropertyRegistry r) {
          r.add("providers.library.base-url", LibraryStub::baseUrl);
      }

      @Autowired Data4LibraryClient client;
      @Autowired JdbcClient jdbc;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          LibraryStub.reset();
      }

      @Test
      void 실측_응답으로_대출_가능과_대출_중을_읽는다() {
          LibraryStub.holding("111456", A.value(), LibraryStub.fixture("bookExist-111456.json"));
          LibraryStub.holding("711618", A.value(), LibraryStub.fixture("bookExist-711618.json"));

          assertThat(client.holding("111456", A, CallPurpose.INTERACTIVE)).isEqualTo(HoldingState.AVAILABLE);
          assertThat(client.holding("711618", A, CallPurpose.INTERACTIVE)).isEqualTo(HoldingState.ON_LOAN);
      }

      @Test
      void 키와_형식을_쿼리로_보낸다() {
          LibraryStub.holding("111456", A.value(), LibraryStub.holdingBody("N", "N"));

          client.holding("111456", A, CallPurpose.INTERACTIVE);

          LibraryStub.server().verify(getRequestedFor(urlPathEqualTo("/api/bookExist"))
                  .withQueryParam("authKey", equalTo("test-library-key"))
                  .withQueryParam("format", equalTo("json")));
      }

      @Test
      void HTTP_200_오류_응답도_제공자_장애다() {
          LibraryStub.holding("111456", A.value(), LibraryStub.errorBody());

          assertThatThrownBy(() -> client.holding("111456", A, CallPurpose.INTERACTIVE))
                  .isInstanceOf(ProviderUnavailableException.class);
      }

      /** ResourceAccessException 메시지에는 요청 URL(= authKey)이 들어간다. 그 메시지를 옮기지 않는다 */
      @Test
      void 장애_예외에_인증키가_들어가지_않는다() {
          LibraryStub.failsWith(500);
          assertThatThrownBy(() -> client.holding("111456", A, CallPurpose.INTERACTIVE))
                  .isInstanceOf(ProviderUnavailableException.class)
                  .satisfies(e -> {
                      assertThat(e.getMessage()).doesNotContain("test-library-key");
                      assertThat(e.getCause()).isNull();   // cause의 메시지·스택에도 URL이 있다 — 붙이지 않는다
                  });

          LibraryStub.reset();
          LibraryStub.server().stubFor(get(urlPathEqualTo("/api/bookExist"))
                  .willReturn(aResponse().withFixedDelay(4_000).withBody("{}")));   // read-timeout 3s
          assertThatThrownBy(() -> client.holding("111456", A, CallPurpose.INTERACTIVE))
                  .isInstanceOf(ProviderUnavailableException.class)
                  .satisfies(e -> assertThat(e.getMessage()).doesNotContain("test-library-key"));
      }

      @Test
      void 호출마다_정보나루_예산을_센다() {
          LibraryStub.holding("111456", A.value(), LibraryStub.holdingBody("Y", "Y"));

          client.holding("111456", A, CallPurpose.INTERACTIVE);
          client.holding("111456", A, CallPurpose.INTERACTIVE);

          assertThat(jdbc.sql("SELECT calls FROM provider_call_counter WHERE provider = 'LIBRARY'")
                  .query(Integer.class).single()).isEqualTo(2);
      }

      @Test
      void 실측_응답으로_도서관_목록을_읽는다() {
          LibraryStub.libraries(11, 1, LibraryStub.fixture("libSrch-seoul-3.json"));

          LibraryPage page = client.libraries(11, 1, 3, CallPurpose.BACKGROUND);

          assertThat(page.numFound()).isGreaterThan(300);   // 실측 서울 359곳
          assertThat(page.libraries()).hasSize(3);
          LibraryInfo first = page.libraries().getFirst();
          assertThat(first.libCode()).isNotBlank();
          assertThat(first.name()).isNotBlank();
          assertThat(first.address()).startsWith("서울");
          assertThat(first.latitude()).isBetween(37.0, 38.0);
      }

      @Test
      void 좌표가_숫자가_아니어도_도서관을_버리지_않는다() {
          LibraryStub.libraries(11, 1, """
                  {"response":{"numFound":1,"libs":[{"lib":{"libCode":"999999","libName":"좌표 없는 도서관",
                   "address":"서울특별시 어딘가","tel":"","latitude":"","longitude":"-","homepage":""}}]}}
                  """);

          LibraryInfo lib = client.libraries(11, 1, 500, CallPurpose.BACKGROUND).libraries().getFirst();

          assertThat(lib.name()).isEqualTo("좌표 없는 도서관");
          assertThat(lib.latitude()).isNull();
          assertThat(lib.homepage()).isNull();
      }
  }
  ```

- [ ] **Step 7: 실패 확인**

  Run: `./gradlew test --tests Data4LibraryClientTest`
  Expected: FAIL — 컴파일 실패(`Data4LibraryClient` 없음)

- [ ] **Step 8: 구현 — 클라이언트**

  `src/main/java/com/bookshelf/library/data4library/LibraryRestClientConfig.java`:

  ```java
  package com.bookshelf.library.data4library;

  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.http.client.JdkClientHttpRequestFactory;
  import org.springframework.web.client.RestClient;

  import java.net.http.HttpClient;

  /** 정보나루 전용 RestClient. HTTP/1.1 고정 이유는 KakaoRestClientConfig와 같다 */
  @Configuration
  public class LibraryRestClientConfig {

      @Bean
      RestClient libraryRestClient(LibraryProperties props, RestClient.Builder builder) {
          HttpClient http = HttpClient.newBuilder()
                  .version(HttpClient.Version.HTTP_1_1)
                  .connectTimeout(props.connectTimeout())
                  .build();
          JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
          factory.setReadTimeout(props.readTimeout());
          return builder.baseUrl(props.baseUrl()).requestFactory(factory).build();
      }
  }
  ```

  `src/main/java/com/bookshelf/library/data4library/Data4LibraryResponse.java`:

  ```java
  package com.bookshelf.library.data4library;

  import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

  import java.util.List;

  /** 정보나루는 모든 응답을 {"response": {...}}로 감싼다. 오류면 errCode·error가 있고 나머지가 없다 */
  final class Data4LibraryResponse {

      private Data4LibraryResponse() {
      }

      @JsonIgnoreProperties(ignoreUnknown = true)
      record Exist(Body response) {
          @JsonIgnoreProperties(ignoreUnknown = true)
          record Body(String errCode, String error, Result result) {
          }

          @JsonIgnoreProperties(ignoreUnknown = true)
          record Result(String hasBook, String loanAvailable) {
          }
      }

      @JsonIgnoreProperties(ignoreUnknown = true)
      record LibSrch(Body response) {
          @JsonIgnoreProperties(ignoreUnknown = true)
          record Body(String errCode, String error, Integer numFound, List<Item> libs) {
          }

          @JsonIgnoreProperties(ignoreUnknown = true)
          record Item(Lib lib) {
          }

          @JsonIgnoreProperties(ignoreUnknown = true)
          record Lib(String libCode, String libName, String address, String tel,
                     String latitude, String longitude, String homepage) {
          }
      }
  }
  ```

  `src/main/java/com/bookshelf/library/data4library/Data4LibraryClient.java`:

  ```java
  package com.bookshelf.library.data4library;

  import com.bookshelf.common.Isbn13;
  import com.bookshelf.library.HoldingState;
  import com.bookshelf.library.LibraryAvailability;
  import com.bookshelf.library.LibraryDirectory;
  import com.bookshelf.library.LibraryInfo;
  import com.bookshelf.library.LibraryPage;
  import com.bookshelf.provider.CallBudget;
  import com.bookshelf.provider.CallPurpose;
  import com.bookshelf.provider.Provider;
  import com.bookshelf.provider.ProviderUnavailableException;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.stereotype.Component;
  import org.springframework.web.client.RestClient;
  import org.springframework.web.client.RestClientException;
  import org.springframework.web.util.UriBuilder;
  import tools.jackson.core.JacksonException;
  import tools.jackson.databind.json.JsonMapper;

  import java.net.URI;
  import java.time.Clock;
  import java.time.Instant;
  import java.util.List;
  import java.util.Objects;
  import java.util.function.Function;

  /**
   * 정보나루 호출의 유일한 입구. 순서는 카카오와 같다: 쿨다운 → 예산 → HTTP → 번역.
   * 인증키가 URL에 있으므로 HTTP 예외의 메시지와 cause를 밖으로 내보내지 않는다.
   */
  @Slf4j
  @Component
  public class Data4LibraryClient implements LibraryAvailability, LibraryDirectory {

      private static final JsonMapper JSON = JsonMapper.builder().build();

      private final RestClient rest;
      private final CallBudget budget;
      private final LibraryProperties props;
      private final Clock clock;

      private volatile Instant coolDownUntil = Instant.MIN;

      public Data4LibraryClient(RestClient libraryRestClient, CallBudget budget, LibraryProperties props, Clock clock) {
          this.rest = libraryRestClient;
          this.budget = budget;
          this.props = props;
          this.clock = clock;
      }

      @Override
      public HoldingState holding(String libCode, Isbn13 isbn, CallPurpose purpose) {
          Data4LibraryResponse.Exist r = call(purpose, Data4LibraryResponse.Exist.class, b -> b.path("/api/bookExist")
                  .queryParam("libCode", "{lib}")
                  .queryParam("isbn13", isbn.value())
                  .queryParam("authKey", "{key}")
                  .queryParam("format", "json")
                  .build(libCode, props.apiKey()));
          if (r.response() == null || r.response().error() != null || r.response().result() == null) {
              throw failed("bookExist 오류 응답: " + (r.response() == null ? "본문 없음" : r.response().errCode()));
          }
          HoldingState state = HoldingState.from(r.response().result().hasBook(), r.response().result().loanAvailable());
          if (state == HoldingState.UNKNOWN) {
              log.info("처음 보는 bookExist 값: {}", r.response().result());
          }
          return state;
      }

      @Override
      public LibraryPage libraries(int region, int pageNo, int pageSize, CallPurpose purpose) {
          Data4LibraryResponse.LibSrch r = call(purpose, Data4LibraryResponse.LibSrch.class, b -> b.path("/api/libSrch")
                  .queryParam("region", region)
                  .queryParam("pageNo", pageNo)
                  .queryParam("pageSize", pageSize)
                  .queryParam("authKey", "{key}")
                  .queryParam("format", "json")
                  .build(props.apiKey()));
          if (r.response() == null || r.response().error() != null) {
              throw failed("libSrch 오류 응답: " + (r.response() == null ? "본문 없음" : r.response().errCode()));
          }
          List<LibraryInfo> libs = r.response().libs() == null ? List.of() : r.response().libs().stream()
                  .map(Data4LibraryResponse.LibSrch.Item::lib)
                  .filter(Objects::nonNull)
                  .filter(l -> l.libCode() != null && !l.libCode().isBlank() && l.libName() != null)
                  .map(l -> new LibraryInfo(l.libCode().strip(), l.libName().strip(), blankToNull(l.address()),
                          blankToNull(l.tel()), coordinate(l.latitude()), coordinate(l.longitude()), blankToNull(l.homepage())))
                  .toList();
          int numFound = r.response().numFound() == null ? libs.size() : r.response().numFound();
          return new LibraryPage(libs, numFound);
      }

      private <T> T call(CallPurpose purpose, Class<T> type, Function<UriBuilder, URI> uri) {
          if (purpose == CallPurpose.BACKGROUND && clock.instant().isBefore(coolDownUntil)) {
              throw new ProviderUnavailableException(Provider.LIBRARY, "쿨다운 중 (" + coolDownUntil + "까지)");
          }
          budget.acquire(props.policy(), purpose);
          try {
              byte[] body = rest.get().uri(uri).retrieve().body(byte[].class);
              return JSON.readValue(body, type);
          } catch (RestClientException | JacksonException e) {
              // e.getMessage()에는 authKey가 든 URL이 있다 — 클래스 이름만 남기고 cause도 붙이지 않는다
              throw failed("호출 실패: " + e.getClass().getSimpleName());
          }
      }

      private ProviderUnavailableException failed(String reason) {
          coolDownUntil = clock.instant().plus(props.coolDown());
          return new ProviderUnavailableException(Provider.LIBRARY, reason);
      }

      private static Double coordinate(String raw) {
          if (raw == null || raw.isBlank()) {
              return null;
          }
          try {
              return Double.valueOf(raw.strip());
          } catch (NumberFormatException e) {
              return null;
          }
      }

      private static String blankToNull(String s) {
          return s == null || s.isBlank() ? null : s.strip();
      }
  }
  ```

  `holding`의 URI 변수 순서: `{lib}` → `libCode`, `{key}` → `apiKey`. `isbn13`은 숫자뿐이라 변수로 넘기지 않는다.

- [ ] **Step 9: 통과 확인**

  Run: `./gradlew test --tests Data4LibraryClientTest --tests LibraryPropertiesTest --tests HoldingStateTest`
  Expected: PASS (7 + 4 + 6 = 17개)

- [ ] **Step 10: 전체 테스트 후 커밋 (사용자 허용 후)**

  Run: `./gradlew test` → 전부 PASS

  허용을 요청할 경로: `src/main/java/com/bookshelf/library src/main/resources/application.yaml src/test/java/com/bookshelf/library src/test/java/com/bookshelf/support/LibraryStub.java src/test/resources/library`

  커밋 전 확인: `git diff --cached | grep -c "$(grep -E '^LIBRARY_API_KEY=' .env | cut -d= -f2-)"` → `0`

  ```bash
  git commit -m "feat: 정보나루 클라이언트 — 빌리기 칸의 유일한 입구

  bookExist의 hasBook·loanAvailable을 대출 가능/대출 중/미소장으로 옮긴다.
  정보나루는 오류도 HTTP 200으로 준다 — error가 있으면 장애로 본다.
  인증키가 URL에 있어 HTTP 예외의 메시지와 cause를 밖으로 내보내지 않는다."
  ```

---

### Task 10: 도서관 목록 동기화와 지역으로 찾기 — 찾기는 로컬에서만

설계 문서 5장 "도서관 목록은 주 1회 로컬로 동기화, 검색은 로컬에서만". 사용자가 도서관을 찾을 때마다 정보나루를 부르면 하루 450회가 금방 탄다. 전국 도서관 목록은 17개 시도 × 1~3쪽(쪽당 500곳)이라 **주 1회 30회 안쪽**이다.

지역 코드는 정보나루 매뉴얼의 시도 코드다(실측 서울 11 → 359곳): 11 서울, 21 부산, 22 대구, 23 인천, 24 광주, 25 대전, 26 울산, 29 세종, 31 경기, 32 강원, 33 충북, 34 충남, 35 전북, 36 전남, 37 경북, 38 경남, 39 제주.

**완료의 기준을 따로 적는다.** 동기화가 17개 지역을 모두 끝냈을 때만 `library_sync_run`에 한 줄을 남긴다. 중간에 정보나루가 죽으면 받은 곳까지는 저장하되 완료로 치지 않아 다음 날 다시 시도한다. `max(synced_at)`로 판단하면 한 지역만 받고 실패해도 "일주일 안에 동기화함"이 되어 나머지 지역이 일주일 동안 비어 있다.

정보나루 목록에서 사라진 도서관은 지우지 않는다 — 누군가 등록해 둔 도서관이 사라지면 그 사람의 빌리기 칸이 말없이 줄어든다. 사라진 도서관의 `bookExist`는 오류나 미소장으로 오고, 그 칸은 "확인 불가"나 "미소장"이 된다.

**Files:**
- Create: `src/main/resources/db/migration/V4__library.sql`
- Create: `src/main/java/com/bookshelf/library/Region.java`
- Create: `src/main/java/com/bookshelf/library/application/LibraryWriter.java`, `LibrarySync.java`, `LibrarySearch.java`, `LibraryView.java`
- Create: `src/main/java/com/bookshelf/library/presentation/LibraryController.java`
- Modify: `src/main/java/com/bookshelf/ops/MaintenanceJobs.java`, `src/main/resources/application.yaml` (`bookshelf.background.library-sync-cron`)
- Modify: `src/test/java/com/bookshelf/support/DbCleaner.java`, `LibraryStub.java` (`librariesDefault`, `librariesBody`)
- Test: `src/test/java/com/bookshelf/library/RegionTest.java`, `src/test/java/com/bookshelf/library/application/LibrarySyncTest.java`, `src/test/java/com/bookshelf/library/LibraryApiTest.java`

**Interfaces:**
- Consumes: `LibraryDirectory#libraries(region, pageNo, pageSize, purpose) -> LibraryPage`, `LibraryInfo`, `MutableClock`(테스트)
- Produces:
  - 테이블 `library(lib_code PK, name, address, region_code, tel, latitude, longitude, homepage, synced_at)`, `library_sync_run(id, finished_at, libraries)`
  - `enum Region { SEOUL(11, "서울") … JEJU(39, "제주") }`, `#code()`, `#label()`, `static Region of(int code)` — 모르는 코드는 `DomainException`
  - `LibraryWriter#upsert(Region, List<LibraryInfo>, Instant now)`, `#finishRun(Instant, int libraries)`, `#lastFinishedAt() -> Optional<Instant>`, `#exists(String libCode) -> boolean`
  - `LibrarySync#runOnce() -> int`(이번에 저장한 도서관 수, 건너뛰면 0), `static final int PAGE_SIZE = 500`, `static final Duration INTERVAL = Duration.ofDays(7)`
  - `record LibraryView(String libCode, String name, String address, String tel, String homepage, int regionCode)`
  - `LibrarySearch#search(int regionCode, String q) -> List<LibraryView>`(최대 50곳), `#find(String libCode) -> Optional<LibraryView>`
  - HTTP: `GET /api/libraries/regions` → `[{code, label}]`, `GET /api/libraries?region=11&q=송파`
  - 테스트 지원: `LibraryStub.librariesDefault(body)`(다른 스텁이 없을 때의 응답), `LibraryStub.librariesBody(prefix, n)`(도서관 n곳짜리 응답)

- [ ] **Step 1: 스키마** — `src/main/resources/db/migration/V4__library.sql`

  ```sql
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
  ```

  `DbCleaner`의 TRUNCATE 목록에 `library, library_sync_run`을 더한다.

- [ ] **Step 2: 실패하는 단위 테스트 — 지역 코드**

  `src/test/java/com/bookshelf/library/RegionTest.java`:

  ```java
  package com.bookshelf.library;

  import com.bookshelf.common.DomainException;
  import org.junit.jupiter.api.Test;

  import java.util.Arrays;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  class RegionTest {

      @Test
      void 정보나루_시도_코드_17개다() {
          assertThat(Arrays.stream(Region.values()).map(Region::code))
                  .containsExactly(11, 21, 22, 23, 24, 25, 26, 29, 31, 32, 33, 34, 35, 36, 37, 38, 39);
          assertThat(Region.of(11).label()).isEqualTo("서울");
          assertThat(Region.of(39).label()).isEqualTo("제주");
      }

      @Test
      void 모르는_코드는_거부한다() {
          assertThatThrownBy(() -> Region.of(27)).isInstanceOf(DomainException.class);
          assertThatThrownBy(() -> Region.of(11010)).isInstanceOf(DomainException.class);   // 시군구 코드는 받지 않는다
      }
  }
  ```

  Run: `./gradlew test --tests RegionTest` → FAIL(컴파일). 구현:

  `src/main/java/com/bookshelf/library/Region.java`:

  ```java
  package com.bookshelf.library;

  import com.bookshelf.common.DomainException;

  /** 정보나루 시도 코드(매뉴얼). 순서는 화면의 지역 목록 순서다 */
  public enum Region {
      SEOUL(11, "서울"), BUSAN(21, "부산"), DAEGU(22, "대구"), INCHEON(23, "인천"), GWANGJU(24, "광주"),
      DAEJEON(25, "대전"), ULSAN(26, "울산"), SEJONG(29, "세종"), GYEONGGI(31, "경기"), GANGWON(32, "강원"),
      CHUNGBUK(33, "충북"), CHUNGNAM(34, "충남"), JEONBUK(35, "전북"), JEONNAM(36, "전남"),
      GYEONGBUK(37, "경북"), GYEONGNAM(38, "경남"), JEJU(39, "제주");

      private final int code;
      private final String label;

      Region(int code, String label) {
          this.code = code;
          this.label = label;
      }

      public int code() {
          return code;
      }

      public String label() {
          return label;
      }

      public static Region of(int code) {
          for (Region r : values()) {
              if (r.code == code) {
                  return r;
              }
          }
          throw new DomainException("모르는 지역 코드: " + code);
      }
  }
  ```

  Run: `./gradlew test --tests RegionTest` → PASS

- [ ] **Step 3: 테스트 지원** — `LibraryStub.java`에 추가

  ```java
      /** 다른 libSrch 스텁에 걸리지 않은 요청의 응답(우선순위가 낮다) */
      public static void librariesDefault(String body) {
          SERVER.stubFor(get(urlPathEqualTo("/api/libSrch")).atPriority(10).willReturn(okJson(body)));
      }

      /** 도서관 n곳짜리 libSrch 응답. 코드는 prefix + 번호 */
      public static String librariesBody(String prefix, int n) {
          StringBuilder libs = new StringBuilder();
          for (int i = 0; i < n; i++) {
              if (i > 0) {
                  libs.append(',');
              }
              libs.append("{\"lib\":{\"libCode\":\"").append(prefix).append(i)
                      .append("\",\"libName\":\"").append(prefix).append(" 도서관 ").append(i)
                      .append("\",\"address\":\"주소 ").append(i)
                      .append("\",\"tel\":\"02-000-0000\",\"latitude\":\"37.5\",\"longitude\":\"127.0\",\"homepage\":\"\"}}");
          }
          return "{\"response\":{\"numFound\":" + n + ",\"resultNum\":" + n + ",\"libs\":[" + libs + "]}}";
      }
  ```

  `holding`, `libraries` 스텁은 기본 우선순위(5)라 `librariesDefault`보다 먼저 걸린다.

- [ ] **Step 4: 실패하는 통합 테스트 — 동기화**

  `src/test/java/com/bookshelf/library/application/LibrarySyncTest.java`:

  ```java
  package com.bookshelf.library.application;

  import com.bookshelf.library.LibraryDirectory;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.LibraryStub;
  import com.bookshelf.support.MutableClock;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;

  import java.time.Duration;
  import java.time.Instant;

  import static com.github.tomakehurst.wiremock.client.WireMock.*;
  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest
  class LibrarySyncTest {

      private static final Instant T0 = Instant.parse("2026-10-05T20:00:00Z");   // 월요일 05:00 KST

      @DynamicPropertySource
      static void library(DynamicPropertyRegistry r) {
          r.add("providers.library.base-url", LibraryStub::baseUrl);
      }

      @Autowired JdbcClient jdbc;
      @Autowired LibraryDirectory directory;
      @Autowired LibraryWriter writer;

      private MutableClock clock;
      private LibrarySync sync;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          LibraryStub.reset();
          LibraryStub.librariesDefault(LibraryStub.emptyLibraries());
          clock = new MutableClock(T0);
          sync = new LibrarySync(directory, writer, clock);
      }

      private int libraries() {
          return jdbc.sql("SELECT count(*) FROM library").query(Integer.class).single();
      }

      private int runs() {
          return jdbc.sql("SELECT count(*) FROM library_sync_run").query(Integer.class).single();
      }

      @Test
      void 비어_있으면_17개_지역을_모두_받는다() {
          LibraryStub.libraries(11, 1, LibraryStub.fixture("libSrch-seoul-3.json"));

          assertThat(sync.runOnce()).isEqualTo(3);

          assertThat(LibraryStub.calls()).isEqualTo(17);
          assertThat(jdbc.sql("SELECT DISTINCT region_code FROM library").query(Integer.class).list()).containsExactly(11);
          assertThat(runs()).isEqualTo(1);
      }

      @Test
      void 한_쪽이_꽉_차면_다음_쪽을_받는다() {
          LibraryStub.libraries(31, 1, LibraryStub.librariesBody("G", 500));
          LibraryStub.libraries(31, 2, LibraryStub.librariesBody("H", 2));

          sync.runOnce();

          assertThat(libraries()).isEqualTo(502);
          LibraryStub.server().verify(0, getRequestedFor(urlPathEqualTo("/api/libSrch"))
                  .withQueryParam("region", equalTo("31")).withQueryParam("pageNo", equalTo("3")));
      }

      @Test
      void 일주일_안에_끝난_동기화가_있으면_부르지_않는다() {
          sync.runOnce();
          int calls = LibraryStub.calls();

          clock.advance(Duration.ofDays(6));
          assertThat(sync.runOnce()).isZero();
          assertThat(LibraryStub.calls()).isEqualTo(calls);

          clock.advance(Duration.ofDays(1));
          sync.runOnce();
          assertThat(LibraryStub.calls()).isEqualTo(calls + 17);
      }

      @Test
      void 중간에_실패하면_받은_곳은_남기고_완료로_치지_않는다() {
          LibraryStub.libraries(11, 1, LibraryStub.fixture("libSrch-seoul-3.json"));
          LibraryStub.server().stubFor(get(urlPathEqualTo("/api/libSrch")).withQueryParam("region", equalTo("21"))
                  .willReturn(aResponse().withStatus(500)));

          sync.runOnce();

          assertThat(libraries()).isEqualTo(3);
          assertThat(runs()).isZero();
          assertThat(LibraryStub.calls()).isEqualTo(2);   // 실패한 뒤 나머지 지역을 부르지 않는다 — 예산을 아낀다

          // 다음 날 다시 시도한다
          LibraryStub.reset();
          LibraryStub.librariesDefault(LibraryStub.emptyLibraries());
          clock.advance(Duration.ofDays(1));
          sync.runOnce();
          assertThat(runs()).isEqualTo(1);
      }

      @Test
      void 다시_받으면_바뀐_정보를_고친다() {
          LibraryStub.libraries(11, 1, LibraryStub.librariesBody("S", 1));
          sync.runOnce();

          LibraryStub.reset();
          LibraryStub.librariesDefault(LibraryStub.emptyLibraries());
          LibraryStub.libraries(11, 1, LibraryStub.librariesBody("S", 1).replace("S 도서관 0", "새 이름 도서관"));
          clock.advance(Duration.ofDays(8));
          sync.runOnce();

          assertThat(jdbc.sql("SELECT name FROM library WHERE lib_code = 'S0'").query(String.class).single())
                  .isEqualTo("새 이름 도서관");
      }

      @Test
      void 목록에서_사라진_도서관은_지우지_않는다() {
          LibraryStub.libraries(11, 1, LibraryStub.librariesBody("S", 2));
          sync.runOnce();

          LibraryStub.reset();
          LibraryStub.librariesDefault(LibraryStub.emptyLibraries());
          LibraryStub.libraries(11, 1, LibraryStub.librariesBody("S", 1));
          clock.advance(Duration.ofDays(8));
          sync.runOnce();

          assertThat(libraries()).isEqualTo(2);
      }
  }
  ```

  `중간에_실패하면`: 지역 순서는 `Region.values()` 순서(11 → 21 → …)다. 11이 성공하고 21에서 실패하므로 호출은 2번이다. 첫 실패로 쿨다운(테스트는 0s)이 걸리고, 사용자 없이 도는 BACKGROUND 호출이라 사용자 상한은 보지 않는다.

- [ ] **Step 5: 실패하는 통합 테스트 — 찾기 API**

  `src/test/java/com/bookshelf/library/LibraryApiTest.java`:

  ```java
  package com.bookshelf.library;

  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.TestJwt;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.test.web.servlet.MockMvc;

  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(Fixtures.class)
  class LibraryApiTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired Fixtures fx;
      @Autowired JwtEncoder encoder;

      private String auth;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          auth = TestJwt.bearer(encoder, fx.member("mine", false).getId());
          library("111456", "가락몰도서관", "서울특별시 송파구 양재대로 932", 11);
          library("111457", "송파어린이도서관", "서울특별시 송파구 가락로 71", 11);
          library("111458", "100% 작은도서관", "서울특별시 노원구", 11);
          library("211001", "부산시민도서관", "부산광역시 부산진구", 21);
      }

      private void library(String code, String name, String address, int region) {
          jdbc.sql("""
                  INSERT INTO library (lib_code, name, address, region_code, synced_at)
                  VALUES (:c, :n, :a, :r, now())
                  """).param("c", code).param("n", name).param("a", address).param("r", region).update();
      }

      @Test
      void 지역_목록을_준다() throws Exception {
          mvc.perform(get("/api/libraries/regions").header("Authorization", auth))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.length()").value(17))
                  .andExpect(jsonPath("$[0].code").value(11))
                  .andExpect(jsonPath("$[0].label").value("서울"));
      }

      @Test
      void 지역의_도서관을_이름순으로_준다() throws Exception {
          mvc.perform(get("/api/libraries").param("region", "11").header("Authorization", auth))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.length()").value(3))
                  .andExpect(jsonPath("$[0].name").value("100% 작은도서관"))
                  .andExpect(jsonPath("$[1].name").value("가락몰도서관"));
      }

      @Test
      void 이름이나_주소로_좁힌다() throws Exception {
          mvc.perform(get("/api/libraries").param("region", "11").param("q", "송파").header("Authorization", auth))
                  .andExpect(jsonPath("$.length()").value(2));
          mvc.perform(get("/api/libraries").param("region", "11").param("q", "어린이").header("Authorization", auth))
                  .andExpect(jsonPath("$.length()").value(1))
                  .andExpect(jsonPath("$[0].libCode").value("111457"));
      }

      /** 검색어의 %와 _는 글자 그대로다 — "%"로 모든 도서관을 긁어 가지 못한다 */
      @Test
      void 검색어의_와일드카드는_글자_그대로다() throws Exception {
          mvc.perform(get("/api/libraries").param("region", "11").param("q", "%").header("Authorization", auth))
                  .andExpect(jsonPath("$.length()").value(1))
                  .andExpect(jsonPath("$[0].libCode").value("111458"));
          mvc.perform(get("/api/libraries").param("region", "11").param("q", "_").header("Authorization", auth))
                  .andExpect(jsonPath("$.length()").value(0));
      }

      @Test
      void 모르는_지역과_빠진_지역은_400이다() throws Exception {
          mvc.perform(get("/api/libraries").param("region", "27").header("Authorization", auth)).andExpect(status().isBadRequest());
          mvc.perform(get("/api/libraries").header("Authorization", auth)).andExpect(status().isBadRequest());
      }

      @Test
      void 토큰이_없으면_401이다() throws Exception {
          mvc.perform(get("/api/libraries").param("region", "11")).andExpect(status().isUnauthorized());
      }
  }
  ```

- [ ] **Step 6: 실패 확인**

  Run: `./gradlew test --tests LibrarySyncTest --tests LibraryApiTest`
  Expected: FAIL — 컴파일 실패(`LibrarySync`, `LibraryWriter` 없음)

- [ ] **Step 7: 구현**

  `src/main/java/com/bookshelf/library/application/LibraryWriter.java`:

  ```java
  package com.bookshelf.library.application;

  import com.bookshelf.library.LibraryInfo;
  import com.bookshelf.library.Region;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;
  import org.springframework.transaction.annotation.Transactional;

  import java.sql.Timestamp;
  import java.time.Instant;
  import java.util.List;
  import java.util.Optional;

  @Component
  public class LibraryWriter {

      private final JdbcClient jdbc;

      public LibraryWriter(JdbcClient jdbc) {
          this.jdbc = jdbc;
      }

      /** 한 쪽(최대 500곳)을 한 트랜잭션에 */
      @Transactional
      public void upsert(Region region, List<LibraryInfo> libraries, Instant now) {
          for (LibraryInfo l : libraries) {
              jdbc.sql("""
                      INSERT INTO library (lib_code, name, address, region_code, tel, latitude, longitude, homepage, synced_at)
                      VALUES (:code, :name, :address, :region, :tel, :lat, :lng, :home, :now)
                      ON CONFLICT (lib_code) DO UPDATE SET
                          name = EXCLUDED.name, address = EXCLUDED.address, region_code = EXCLUDED.region_code,
                          tel = EXCLUDED.tel, latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude,
                          homepage = EXCLUDED.homepage, synced_at = EXCLUDED.synced_at
                      """)
                      .param("code", l.libCode())
                      .param("name", l.name())
                      .param("address", l.address())
                      .param("region", region.code())
                      .param("tel", l.tel())
                      .param("lat", l.latitude())
                      .param("lng", l.longitude())
                      .param("home", l.homepage())
                      .param("now", Timestamp.from(now))
                      .update();
          }
      }

      public void finishRun(Instant now, int libraries) {
          jdbc.sql("INSERT INTO library_sync_run (finished_at, libraries) VALUES (:now, :n)")
                  .param("now", Timestamp.from(now)).param("n", libraries).update();
      }

      public Optional<Instant> lastFinishedAt() {
          return jdbc.sql("SELECT finished_at FROM library_sync_run ORDER BY finished_at DESC LIMIT 1")
                  .query(Timestamp.class).optional().map(Timestamp::toInstant);
      }

      public boolean exists(String libCode) {
          return jdbc.sql("SELECT EXISTS (SELECT 1 FROM library WHERE lib_code = :c)")
                  .param("c", libCode).query(Boolean.class).single();
      }
  }
  ```

  `src/main/java/com/bookshelf/library/application/LibrarySync.java`:

  ```java
  package com.bookshelf.library.application;

  import com.bookshelf.library.LibraryDirectory;
  import com.bookshelf.library.LibraryPage;
  import com.bookshelf.library.Region;
  import com.bookshelf.provider.CallPurpose;
  import com.bookshelf.provider.ProviderUnavailableException;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.stereotype.Component;

  import java.time.Clock;
  import java.time.Duration;
  import java.time.Instant;

  /**
   * 전국 도서관 목록을 주 1회 받는다. 스케줄은 매일 돌고, 마지막 "완료" 동기화가 7일 넘었을 때만 부른다.
   * 실패하면 거기서 멈춘다(남은 지역을 불러 봐야 같은 장애에 예산만 쓴다). 받은 곳은 남긴다.
   */
  @Slf4j
  @Component
  public class LibrarySync {

      static final int PAGE_SIZE = 500;
      static final Duration INTERVAL = Duration.ofDays(7);
      private static final int MAX_PAGES = 20;   // 지역당 1만 곳 — 응답이 이상해도 끝없이 돌지 않게

      private final LibraryDirectory directory;
      private final LibraryWriter writer;
      private final Clock clock;

      public LibrarySync(LibraryDirectory directory, LibraryWriter writer, Clock clock) {
          this.directory = directory;
          this.writer = writer;
          this.clock = clock;
      }

      public int runOnce() {
          Instant now = clock.instant();
          if (writer.lastFinishedAt().map(last -> last.plus(INTERVAL).isAfter(now)).orElse(false)) {
              return 0;
          }
          int saved = 0;
          try {
              for (Region region : Region.values()) {
                  saved += syncRegion(region, now);
              }
          } catch (ProviderUnavailableException e) {
              log.warn("도서관 동기화 중단 ({}곳 저장): {}", saved, e.getMessage());
              return saved;
          }
          writer.finishRun(now, saved);
          log.info("도서관 동기화 완료: {}곳", saved);
          return saved;
      }

      private int syncRegion(Region region, Instant now) {
          int saved = 0;
          for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
              LibraryPage page = directory.libraries(region.code(), pageNo, PAGE_SIZE, CallPurpose.BACKGROUND);
              writer.upsert(region, page.libraries(), now);
              saved += page.libraries().size();
              if (page.libraries().size() < PAGE_SIZE) {
                  break;
              }
          }
          return saved;
      }
  }
  ```

  `src/main/java/com/bookshelf/library/application/LibraryView.java`:

  ```java
  package com.bookshelf.library.application;

  public record LibraryView(String libCode, String name, String address, String tel, String homepage, int regionCode) {
  }
  ```

  `src/main/java/com/bookshelf/library/application/LibrarySearch.java`:

  ```java
  package com.bookshelf.library.application;

  import com.bookshelf.library.Region;
  import org.springframework.jdbc.core.RowMapper;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;

  import java.util.List;
  import java.util.Optional;

  /** 로컬 테이블만 읽는다 — 사용자의 도서관 찾기는 정보나루를 부르지 않는다 */
  @Component
  public class LibrarySearch {

      static final int LIMIT = 50;
      private static final String COLUMNS = "lib_code, name, address, tel, homepage, region_code";
      private static final RowMapper<LibraryView> ROW = (rs, n) -> new LibraryView(rs.getString("lib_code"),
              rs.getString("name"), rs.getString("address"), rs.getString("tel"), rs.getString("homepage"),
              rs.getInt("region_code"));

      private final JdbcClient jdbc;

      public LibrarySearch(JdbcClient jdbc) {
          this.jdbc = jdbc;
      }

      public List<LibraryView> search(int regionCode, String q) {
          Region region = Region.of(regionCode);
          String pattern = q == null || q.isBlank() ? null : "%" + escapeLike(q.strip()) + "%";
          return jdbc.sql("SELECT " + COLUMNS + " FROM library WHERE region_code = :r"
                          + (pattern == null ? "" : " AND (name ILIKE :p ESCAPE '\\' OR address ILIKE :p ESCAPE '\\')")
                          + " ORDER BY name LIMIT " + LIMIT)
                  .param("r", region.code())
                  .param("p", pattern)          // 검색어가 없으면 SQL에 :p가 없다 — 남는 파라미터는 무시된다
                  .query(ROW)
                  .list();
      }

      public Optional<LibraryView> find(String libCode) {
          return jdbc.sql("SELECT " + COLUMNS + " FROM library WHERE lib_code = :c")
                  .param("c", libCode).query(ROW).optional();
      }

      /** ILIKE의 %, _ 와 이스케이프 문자 자체를 글자 그대로로 */
      static String escapeLike(String s) {
          return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
      }
  }
  ```

  이름 순 정렬은 DB collation을 따른다. 테스트의 "100% 작은도서관"이 "가락몰도서관"보다 앞인 것은 숫자가 한글보다 앞이기 때문이다(C·en_US·ko_KR 모두 같다).

  `src/main/java/com/bookshelf/library/presentation/LibraryController.java`:

  ```java
  package com.bookshelf.library.presentation;

  import com.bookshelf.library.Region;
  import com.bookshelf.library.application.LibrarySearch;
  import com.bookshelf.library.application.LibraryView;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RequestParam;
  import org.springframework.web.bind.annotation.RestController;

  import java.util.Arrays;
  import java.util.List;

  @RestController
  @RequestMapping("/api/libraries")
  public class LibraryController {

      public record RegionView(int code, String label) {
      }

      private final LibrarySearch search;

      public LibraryController(LibrarySearch search) {
          this.search = search;
      }

      @GetMapping("/regions")
      public List<RegionView> regions() {
          return Arrays.stream(Region.values()).map(r -> new RegionView(r.code(), r.label())).toList();
      }

      @GetMapping
      public List<LibraryView> search(@RequestParam int region, @RequestParam(required = false) String q) {
          return search.search(region, q);
      }
  }
  ```

  `MaintenanceJobs`에 동기화를 더한다(생성자에 `LibrarySync librarySync` 추가, import `com.bookshelf.library.application.LibrarySync`):

  ```java
      /** 매일 돌지만 마지막 완료가 7일 넘었을 때만 정보나루를 부른다(LibrarySync) */
      @Scheduled(cron = "${bookshelf.background.library-sync-cron}", zone = "Asia/Seoul")
      void librarySync() {
          librarySync.runOnce();
      }
  ```

  `application.yaml`의 `bookshelf.background` 아래에 추가:

  ```yaml
      library-sync-cron: "0 0 5 * * *"   # 매일 05:00 KST. 첫 배포 다음 날 새벽에 처음 채워진다
  ```

- [ ] **Step 8: 통과 확인**

  Run: `./gradlew test --tests RegionTest --tests LibrarySyncTest --tests LibraryApiTest`
  Expected: PASS (2 + 6 + 6 = 14개)

- [ ] **Step 9: 전체 테스트 후 커밋 (사용자 허용 후)**

  Run: `./gradlew test` → 전부 PASS

  허용을 요청할 경로: `src/main/resources/db/migration/V4__library.sql src/main/java/com/bookshelf/library src/main/java/com/bookshelf/ops src/main/resources/application.yaml src/test/java/com/bookshelf/library src/test/java/com/bookshelf/support`

  ```bash
  git commit -m "feat: 도서관 목록 동기화와 지역으로 찾기

  전국 목록을 주 1회 받아 로컬에 두고, 사용자의 도서관 찾기는 로컬에서만 한다.
  17개 지역을 모두 받은 동기화만 완료로 친다 — 중간에 실패하면 다음 날 다시 시도한다.
  목록에서 사라진 도서관은 지우지 않는다(등록한 사람의 빌리기 칸이 말없이 줄지 않게)."
  ```

---

### Task 11: 내 도서관과 빌리기 칸 — 도서관은 서버가 고른다

설계 문서 2-1의 빌리기 칸과 6-3의 두 규칙:
- **빌리기 조회의 도서관은 서버가 `member_library`에서만 꺼낸다.** 요청으로 도서관 코드를 받으면 공개 웹에서 막은 "지역×ISBN 훑기"가 로그인 뒤로 옮겨갈 뿐이다.
- **등록 도서관은 최대 3곳, 변경은 하루 3회.** 변경을 막지 않으면 등록을 계속 바꿔 끼우며 같은 훑기를 할 수 있다(Review Focus 5).

캐시는 **조회한 날 다음 날 00:00 KST까지**다(설계 6-5). 정보나루가 전날 대출 상태를 주므로 그날 안에 다시 물어도 같은 값이다. 실패(UNKNOWN)는 캐시하지 않는다 — 다음 조회가 다시 시도한다.

도서관 하나가 실패해도 나머지 도서관은 따로 조회한다(설계 6-6 "그 칸만 확인 불가"). 최악의 경우 3곳 × read-timeout 3초 = 9초다. 앱은 빌리기 칸을 다른 칸과 따로 늦게 채운다(설계 2-1) — 이 API만 늦고 책 상세는 기다리지 않는다.

**Files:**
- Create: `src/main/resources/db/migration/V5__member_library.sql`
- Create: `src/main/java/com/bookshelf/library/application/MyLibraries.java`, `MyLibrariesWriter.java`, `LibraryChangeLimitException.java`
- Create: `src/main/java/com/bookshelf/library/application/BorrowService.java`, `HoldingCache.java`, `HoldingView.java`
- Create: `src/main/java/com/bookshelf/library/presentation/MyLibrariesController.java`, `BorrowController.java`
- Modify: `src/main/java/com/bookshelf/member/application/MemberDeletion.java` (`OWNED_TABLES`)
- Modify: `src/main/java/com/bookshelf/common/ApiExceptionHandler.java` (429)
- Modify: `src/test/java/com/bookshelf/support/DbCleaner.java`
- Modify: `src/test/java/com/bookshelf/auth/AccountDeletionTest.java`, `src/test/java/com/bookshelf/publicview/PublicViewArchitectureTest.java`, `PublicBoundaryTest.java`
- Test: `src/test/java/com/bookshelf/library/MyLibrariesApiTest.java`, `src/test/java/com/bookshelf/library/BorrowApiTest.java`

**Interfaces:**
- Consumes: `LibraryAvailability#holding(libCode, Isbn13, purpose) -> HoldingState`(Task 9), `LibrarySearch#find`, `LibraryView`, `LibraryWriter#exists`(Task 10), `MemberService#active`(Task 5), `MemberDeletion`(Task 8), `LibraryStub`(Task 9·10)
- Produces:
  - 테이블 `member_library(member_id, lib_code, position 1~3)`, `member_library_change(member_id, day, changes)`, `library_holding(lib_code, isbn13, state, fetched_at, expires_at)`
  - `MyLibraries#list(long memberId) -> List<LibraryView>`, `#replace(long memberId, List<String> libCodes) -> List<LibraryView>`
  - `MyLibrariesWriter#replace(long memberId, List<String> libCodes, LocalDate today)` — 바뀌지 않았으면 세지 않는다. 넷째 변경은 `LibraryChangeLimitException`
  - `record HoldingView(String libCode, String name, HoldingState state, Instant fetchedAt)` — `fetchedAt`은 UNKNOWN이면 null
  - `BorrowService#availability(long memberId, String isbn) -> List<HoldingView>` — 등록 순서대로
  - `HoldingCache#find(libCode, Isbn13, Instant now) -> Optional<HoldingCache.Cached>`(`record Cached(HoldingState state, Instant fetchedAt)`), `#save(libCode, Isbn13, HoldingState, Instant now)` — UNKNOWN은 저장하지 않는다
  - HTTP: `GET /api/me/libraries`, `PUT /api/me/libraries {libCodes: [...]}`, `GET /api/books/{isbn}/libraries`
  - 상수: `MyLibraries.MAX_LIBRARIES = 3`, `MyLibrariesWriter.MAX_CHANGES_PER_DAY = 3`

- [ ] **Step 1: 스키마** — `src/main/resources/db/migration/V5__member_library.sql`

  ```sql
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
  ```

  `DbCleaner`의 TRUNCATE 목록에 `member_library, member_library_change, library_holding`을 더한다.

- [ ] **Step 2: 실패하는 통합 테스트 — 내 도서관**

  `src/test/java/com/bookshelf/library/MyLibrariesApiTest.java`:

  ```java
  package com.bookshelf.library;

  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.TestJwt;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.http.MediaType;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.test.web.servlet.MockMvc;
  import org.springframework.test.web.servlet.ResultActions;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(Fixtures.class)
  class MyLibrariesApiTest {

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired Fixtures fx;
      @Autowired JwtEncoder encoder;

      private long me;
      private String auth;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          me = fx.member("mine", false).getId();
          auth = TestJwt.bearer(encoder, me);
          for (String code : new String[]{"L1", "L2", "L3", "L4"}) {
              jdbc.sql("INSERT INTO library (lib_code, name, region_code, synced_at) VALUES (:c, :n, 11, now())")
                      .param("c", code).param("n", code + " 도서관").update();
          }
      }

      private ResultActions replace(String codes) throws Exception {
          return mvc.perform(put("/api/me/libraries").header("Authorization", auth)
                  .contentType(MediaType.APPLICATION_JSON).content("{\"libCodes\":[" + codes + "]}"));
      }

      @Test
      void 도서관을_등록하면_보낸_순서대로_준다() throws Exception {
          replace("\"L3\",\"L1\"").andExpect(status().isOk())
                  .andExpect(jsonPath("$[0].libCode").value("L3"))
                  .andExpect(jsonPath("$[1].libCode").value("L1"));

          mvc.perform(get("/api/me/libraries").header("Authorization", auth))
                  .andExpect(jsonPath("$.length()").value(2))
                  .andExpect(jsonPath("$[0].name").value("L3 도서관"));
      }

      @Test
      void 네_곳은_400이다() throws Exception {
          replace("\"L1\",\"L2\",\"L3\",\"L4\"").andExpect(status().isBadRequest());
      }

      @Test
      void 모르는_도서관과_같은_도서관_두_번은_400이다() throws Exception {
          replace("\"L1\",\"NOPE\"").andExpect(status().isBadRequest());
          replace("\"L1\",\"L1\"").andExpect(status().isBadRequest());
          replace("null").andExpect(status().isBadRequest());
          assertThat(jdbc.sql("SELECT count(*) FROM member_library").query(Integer.class).single()).isZero();
      }

      /** Review Focus 5: 등록을 바꿔 끼우며 훑지 못하게 */
      @Test
      void 하루_네_번째_변경은_429이고_다음_날엔_된다() throws Exception {
          replace("\"L1\"").andExpect(status().isOk());
          replace("\"L2\"").andExpect(status().isOk());
          replace("\"L3\"").andExpect(status().isOk());
          replace("\"L4\"").andExpect(status().isTooManyRequests())
                  .andExpect(jsonPath("$.code").value("TOO_MANY_CHANGES"));

          mvc.perform(get("/api/me/libraries").header("Authorization", auth))
                  .andExpect(jsonPath("$[0].libCode").value("L3"));   // 거부된 변경은 반영되지 않았다

          jdbc.sql("UPDATE member_library_change SET day = day - 1").update();   // 하루가 지났다
          replace("\"L4\"").andExpect(status().isOk());
      }

      @Test
      void 같은_목록을_다시_보내면_변경으로_세지_않는다() throws Exception {
          replace("\"L1\",\"L2\"").andExpect(status().isOk());
          for (int i = 0; i < 5; i++) {
              replace("\"L1\",\"L2\"").andExpect(status().isOk());
          }

          assertThat(jdbc.sql("SELECT changes FROM member_library_change").query(Integer.class).single()).isEqualTo(1);
      }

      @Test
      void 빈_목록이면_모두_해제한다() throws Exception {
          replace("\"L1\"").andExpect(status().isOk());
          replace("").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
      }

      @Test
      void 남의_등록은_보이지_않는다() throws Exception {
          long other = fx.member("other", false).getId();
          jdbc.sql("INSERT INTO member_library (member_id, lib_code, position) VALUES (:m, 'L4', 1)").param("m", other).update();

          mvc.perform(get("/api/me/libraries").header("Authorization", auth)).andExpect(jsonPath("$.length()").value(0));
      }
  }
  ```

- [ ] **Step 3: 실패하는 통합 테스트 — 빌리기 칸**

  `src/test/java/com/bookshelf/library/BorrowApiTest.java`:

  ```java
  package com.bookshelf.library;

  import com.bookshelf.common.ClockConfig;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.LibraryStub;
  import com.bookshelf.support.TestJwt;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.security.oauth2.jwt.JwtEncoder;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;
  import org.springframework.test.web.servlet.MockMvc;

  import java.sql.Timestamp;
  import java.time.Instant;
  import java.time.LocalDate;

  import static com.github.tomakehurst.wiremock.client.WireMock.*;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.hamcrest.Matchers.nullValue;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(Fixtures.class)
  class BorrowApiTest {

      private static final String A = "9788936434120";

      @DynamicPropertySource
      static void props(DynamicPropertyRegistry r) {
          r.add("providers.library.base-url", LibraryStub::baseUrl);
          r.add("providers.user.daily-limit", () -> 5);
      }

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired Fixtures fx;
      @Autowired JwtEncoder encoder;

      private long me;
      private String auth;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          LibraryStub.reset();
          me = fx.member("mine", false).getId();
          auth = TestJwt.bearer(encoder, me);
          for (String code : new String[]{"L1", "L2", "L3", "999999"}) {
              jdbc.sql("INSERT INTO library (lib_code, name, region_code, synced_at) VALUES (:c, :n, 11, now())")
                      .param("c", code).param("n", code + " 도서관").update();
          }
      }

      private void register(String... codes) {
          for (int i = 0; i < codes.length; i++) {
              jdbc.sql("INSERT INTO member_library (member_id, lib_code, position) VALUES (:m, :c, :p)")
                      .param("m", me).param("c", codes[i]).param("p", i + 1).update();
          }
      }

      private org.springframework.test.web.servlet.ResultActions borrow() throws Exception {
          return mvc.perform(get("/api/books/" + A + "/libraries").header("Authorization", auth));
      }

      @Test
      void 등록한_도서관마다_상태를_등록_순서대로_준다() throws Exception {
          register("L2", "L1", "L3");
          LibraryStub.holding("L1", A, LibraryStub.holdingBody("Y", "Y"));
          LibraryStub.holding("L2", A, LibraryStub.holdingBody("Y", "N"));
          LibraryStub.holding("L3", A, LibraryStub.holdingBody("N", "N"));

          borrow().andExpect(status().isOk())
                  .andExpect(jsonPath("$[0].libCode").value("L2"))
                  .andExpect(jsonPath("$[0].state").value("ON_LOAN"))
                  .andExpect(jsonPath("$[1].state").value("AVAILABLE"))
                  .andExpect(jsonPath("$[2].state").value("NOT_HELD"))
                  .andExpect(jsonPath("$[2].name").value("L3 도서관"));
      }

      @Test
      void 그날_안에는_다시_부르지_않고_캐시는_다음_날_0시_KST까지다() throws Exception {
          register("L1");
          LibraryStub.holding("L1", A, LibraryStub.holdingBody("Y", "Y"));

          borrow().andExpect(status().isOk());
          borrow().andExpect(jsonPath("$[0].state").value("AVAILABLE"));

          assertThat(LibraryStub.calls()).isEqualTo(1);
          Instant expires = jdbc.sql("SELECT expires_at FROM library_holding").query(Timestamp.class).single().toInstant();
          Instant nextMidnight = LocalDate.now(ClockConfig.SEOUL).plusDays(1).atStartOfDay(ClockConfig.SEOUL).toInstant();
          assertThat(expires).isEqualTo(nextMidnight);
      }

      @Test
      void 캐시가_만료되면_다시_부른다() throws Exception {
          register("L1");
          LibraryStub.holding("L1", A, LibraryStub.holdingBody("Y", "Y"));
          borrow().andExpect(status().isOk());

          jdbc.sql("UPDATE library_holding SET expires_at = now() - interval '1 second'").update();
          LibraryStub.holding("L1", A, LibraryStub.holdingBody("Y", "N"));

          borrow().andExpect(jsonPath("$[0].state").value("ON_LOAN"));
          assertThat(LibraryStub.calls()).isEqualTo(2);
      }

      @Test
      void 한_도서관이_실패하면_그_도서관만_확인_불가이고_실패는_캐시하지_않는다() throws Exception {
          register("L1", "L2");
          LibraryStub.holding("L1", A, LibraryStub.errorBody());
          LibraryStub.holding("L2", A, LibraryStub.holdingBody("Y", "Y"));

          borrow().andExpect(status().isOk())
                  .andExpect(jsonPath("$[0].state").value("UNKNOWN"))
                  .andExpect(jsonPath("$[0].fetchedAt").value(nullValue()))
                  .andExpect(jsonPath("$[1].state").value("AVAILABLE"));

          borrow();
          LibraryStub.server().verify(2, getRequestedFor(urlPathEqualTo("/api/bookExist"))
                  .withQueryParam("libCode", equalTo("L1")));
      }

      /** Review Focus 5: 요청으로 도서관 코드를 받지 않는다 */
      @Test
      void 요청_파라미터의_도서관_코드는_쓰이지_않는다() throws Exception {
          register("L1");
          LibraryStub.holding("L1", A, LibraryStub.holdingBody("Y", "Y"));

          mvc.perform(get("/api/books/" + A + "/libraries").param("libCode", "999999").param("libCodes", "999999")
                          .header("Authorization", auth))
                  .andExpect(jsonPath("$.length()").value(1))
                  .andExpect(jsonPath("$[0].libCode").value("L1"));

          LibraryStub.server().verify(0, getRequestedFor(urlPathEqualTo("/api/bookExist"))
                  .withQueryParam("libCode", equalTo("999999")));
      }

      @Test
      void 등록한_도서관이_없으면_빈_목록이고_부르지_않는다() throws Exception {
          borrow().andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
          assertThat(LibraryStub.calls()).isZero();
      }

      @Test
      void 사용자_상한에_걸리면_확인_불가이고_부르지_않는다() throws Exception {
          register("L1");
          jdbc.sql("INSERT INTO user_call_counter (member_id, day, calls) VALUES (:m, :d, 5)")
                  .param("m", me).param("d", LocalDate.now(ClockConfig.SEOUL)).update();

          borrow().andExpect(status().isOk()).andExpect(jsonPath("$[0].state").value("UNKNOWN"));
          assertThat(LibraryStub.calls()).isZero();
      }

      @Test
      void 잘못된_ISBN은_400이다() throws Exception {
          mvc.perform(get("/api/books/9788936434121/libraries").header("Authorization", auth))
                  .andExpect(status().isBadRequest());
      }
  }
  ```

- [ ] **Step 4: 실패 확인**

  Run: `./gradlew test --tests MyLibrariesApiTest --tests BorrowApiTest`
  Expected: FAIL — 404(핸들러 없음)

- [ ] **Step 5: 구현**

  `src/main/java/com/bookshelf/library/application/LibraryChangeLimitException.java`:

  ```java
  package com.bookshelf.library.application;

  public class LibraryChangeLimitException extends RuntimeException {
      public LibraryChangeLimitException(long memberId) {
          super("회원 " + memberId + "의 오늘 도서관 변경 횟수 초과");
      }
  }
  ```

  `src/main/java/com/bookshelf/library/application/MyLibrariesWriter.java`:

  ```java
  package com.bookshelf.library.application;

  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;
  import org.springframework.transaction.annotation.Transactional;

  import java.time.LocalDate;
  import java.util.List;

  @Component
  public class MyLibrariesWriter {

      static final int MAX_CHANGES_PER_DAY = 3;

      private final JdbcClient jdbc;

      public MyLibrariesWriter(JdbcClient jdbc) {
          this.jdbc = jdbc;
      }

      public List<String> current(long memberId) {
          return jdbc.sql("SELECT lib_code FROM member_library WHERE member_id = :m ORDER BY position")
                  .param("m", memberId).query(String.class).list();
      }

      /**
       * 같은 목록이면 아무것도 하지 않는다(세지 않는다). 다르면 횟수를 먼저 올리고(원자적, 상한이면 빈 결과)
       * 목록을 통째로 바꾼다. 한 트랜잭션이라 상한에 걸리면 목록도 그대로다.
       */
      @Transactional
      public void replace(long memberId, List<String> libCodes, LocalDate today) {
          if (current(memberId).equals(libCodes)) {
              return;
          }
          List<Integer> after = jdbc.sql("""
                  INSERT INTO member_library_change (member_id, day, changes) VALUES (:m, :day, 1)
                  ON CONFLICT (member_id, day) DO UPDATE SET changes = member_library_change.changes + 1
                  WHERE member_library_change.changes < :limit
                  RETURNING changes
                  """)
                  .param("m", memberId).param("day", today).param("limit", MAX_CHANGES_PER_DAY)
                  .query(Integer.class).list();
          if (after.isEmpty()) {
              throw new LibraryChangeLimitException(memberId);
          }
          jdbc.sql("DELETE FROM member_library WHERE member_id = :m").param("m", memberId).update();
          for (int i = 0; i < libCodes.size(); i++) {
              jdbc.sql("INSERT INTO member_library (member_id, lib_code, position) VALUES (:m, :c, :p)")
                      .param("m", memberId).param("c", libCodes.get(i)).param("p", i + 1).update();
          }
      }
  }
  ```

  `src/main/java/com/bookshelf/library/application/MyLibraries.java`:

  ```java
  package com.bookshelf.library.application;

  import com.bookshelf.common.ClockConfig;
  import com.bookshelf.common.DomainException;
  import com.bookshelf.member.application.MemberService;
  import org.springframework.stereotype.Service;

  import java.time.Clock;
  import java.time.LocalDate;
  import java.util.HashSet;
  import java.util.List;

  @Service
  public class MyLibraries {

      public static final int MAX_LIBRARIES = 3;

      private final MemberService members;
      private final MyLibrariesWriter writer;
      private final LibraryWriter libraries;
      private final LibrarySearch search;
      private final Clock clock;

      public MyLibraries(MemberService members, MyLibrariesWriter writer, LibraryWriter libraries,
                         LibrarySearch search, Clock clock) {
          this.members = members;
          this.writer = writer;
          this.libraries = libraries;
          this.search = search;
          this.clock = clock;
      }

      public List<LibraryView> list(long memberId) {
          members.active(memberId);
          return writer.current(memberId).stream().map(code -> search.find(code).orElseThrow()).toList();
      }

      public List<LibraryView> replace(long memberId, List<String> libCodes) {
          members.active(memberId);
          if (libCodes == null) {
              throw new DomainException("도서관 목록이 없다");
          }
          if (libCodes.size() > MAX_LIBRARIES) {
              throw new DomainException("도서관은 " + MAX_LIBRARIES + "곳까지 등록할 수 있다");
          }
          if (new HashSet<>(libCodes).size() != libCodes.size()) {
              throw new DomainException("같은 도서관이 두 번 있다");
          }
          for (String code : libCodes) {
              if (code == null || !libraries.exists(code)) {
                  throw new DomainException("모르는 도서관: " + code);
              }
          }
          writer.replace(memberId, List.copyOf(libCodes), LocalDate.ofInstant(clock.instant(), ClockConfig.SEOUL));
          return list(memberId);
      }
  }
  ```

  `list`의 `orElseThrow()`: `member_library.lib_code`는 등록할 때 `library`에 있었고, 동기화는 도서관을 지우지 않는다(Task 10) — 그래서 항상 있다.

  `src/main/java/com/bookshelf/library/application/HoldingView.java`:

  ```java
  package com.bookshelf.library.application;

  import com.bookshelf.library.HoldingState;

  import java.time.Instant;

  /** fetchedAt: 정보나루에서 가져온 시각. 값 자체는 "전날 기준"이다 — 앱이 그렇게 적는다. UNKNOWN이면 null */
  public record HoldingView(String libCode, String name, HoldingState state, Instant fetchedAt) {
  }
  ```

  `src/main/java/com/bookshelf/library/application/HoldingCache.java`:

  ```java
  package com.bookshelf.library.application;

  import com.bookshelf.common.ClockConfig;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.library.HoldingState;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;

  import java.sql.Timestamp;
  import java.time.Instant;
  import java.time.LocalDate;
  import java.util.Optional;

  @Component
  public class HoldingCache {

      public record Cached(HoldingState state, Instant fetchedAt) {
      }

      private final JdbcClient jdbc;

      public HoldingCache(JdbcClient jdbc) {
          this.jdbc = jdbc;
      }

      public Optional<Cached> find(String libCode, Isbn13 isbn, Instant now) {
          return jdbc.sql("""
                  SELECT state, fetched_at FROM library_holding
                  WHERE lib_code = :c AND isbn13 = :i AND expires_at > :now
                  """)
                  .param("c", libCode).param("i", isbn.value()).param("now", Timestamp.from(now))
                  .query((rs, n) -> new Cached(HoldingState.valueOf(rs.getString("state")),
                          rs.getTimestamp("fetched_at").toInstant()))
                  .optional();
      }

      /** 정보나루는 조회일 기준 전날 상태를 준다 — 오늘 안에는 다시 물어도 같다. 다음 날 00:00 KST까지 */
      public void save(String libCode, Isbn13 isbn, HoldingState state, Instant now) {
          if (state == HoldingState.UNKNOWN) {
              return;
          }
          Instant expires = LocalDate.ofInstant(now, ClockConfig.SEOUL).plusDays(1).atStartOfDay(ClockConfig.SEOUL).toInstant();
          jdbc.sql("""
                  INSERT INTO library_holding (lib_code, isbn13, state, fetched_at, expires_at)
                  VALUES (:c, :i, :s, :now, :exp)
                  ON CONFLICT (lib_code, isbn13) DO UPDATE SET
                      state = EXCLUDED.state, fetched_at = EXCLUDED.fetched_at, expires_at = EXCLUDED.expires_at
                  """)
                  .param("c", libCode).param("i", isbn.value()).param("s", state.name())
                  .param("now", Timestamp.from(now)).param("exp", Timestamp.from(expires))
                  .update();
      }
  }
  ```

  `src/main/java/com/bookshelf/library/application/BorrowService.java`:

  ```java
  package com.bookshelf.library.application;

  import com.bookshelf.common.Isbn13;
  import com.bookshelf.library.HoldingState;
  import com.bookshelf.library.LibraryAvailability;
  import com.bookshelf.member.application.MemberService;
  import com.bookshelf.provider.CallPurpose;
  import com.bookshelf.provider.ProviderUnavailableException;
  import com.bookshelf.provider.UserQuotaExceededException;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.stereotype.Service;

  import java.time.Clock;
  import java.time.Instant;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.Optional;

  /**
   * 빌리기 칸. 도서관은 member_library에서만 꺼낸다 — 요청으로 받지 않는다(설계 6-3).
   * @Transactional 없음 — 도서관마다 정보나루를 부른다. 저장(HoldingCache.save)은 한 문장이라 따로 트랜잭션이 필요 없다.
   */
  @Slf4j
  @Service
  public class BorrowService {

      private final MemberService members;
      private final MyLibraries myLibraries;
      private final HoldingCache cache;
      private final LibraryAvailability availability;
      private final Clock clock;

      public BorrowService(MemberService members, MyLibraries myLibraries, HoldingCache cache,
                           LibraryAvailability availability, Clock clock) {
          this.members = members;
          this.myLibraries = myLibraries;
          this.cache = cache;
          this.availability = availability;
          this.clock = clock;
      }

      public List<HoldingView> availability(long memberId, String rawIsbn) {
          Isbn13 isbn = Isbn13.parse(rawIsbn);
          members.active(memberId);
          List<HoldingView> out = new ArrayList<>();
          for (LibraryView lib : myLibraries.list(memberId)) {
              out.add(holding(lib, isbn));
          }
          return out;
      }

      /** 한 도서관의 실패는 그 도서관만 UNKNOWN이다. 다음 도서관은 따로 부른다 */
      private HoldingView holding(LibraryView lib, Isbn13 isbn) {
          Instant now = clock.instant();
          Optional<HoldingCache.Cached> cached = cache.find(lib.libCode(), isbn, now);
          if (cached.isPresent()) {
              return new HoldingView(lib.libCode(), lib.name(), cached.get().state(), cached.get().fetchedAt());
          }
          try {
              HoldingState state = availability.holding(lib.libCode(), isbn, CallPurpose.INTERACTIVE);
              cache.save(lib.libCode(), isbn, state, now);
              return new HoldingView(lib.libCode(), lib.name(), state, state == HoldingState.UNKNOWN ? null : now);
          } catch (ProviderUnavailableException | UserQuotaExceededException e) {
              log.info("빌리기 확인 불가: lib={} isbn={} reason={}", lib.libCode(), isbn.value(), e.getMessage());
              return new HoldingView(lib.libCode(), lib.name(), HoldingState.UNKNOWN, null);
          }
      }
  }
  ```

  `src/main/java/com/bookshelf/library/presentation/MyLibrariesController.java`:

  ```java
  package com.bookshelf.library.presentation;

  import com.bookshelf.common.CurrentMember;
  import com.bookshelf.library.application.LibraryView;
  import com.bookshelf.library.application.MyLibraries;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PutMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;

  import java.util.List;

  @RestController
  @RequestMapping("/api/me/libraries")
  public class MyLibrariesController {

      /** 등록 순서 그대로. 빈 목록이면 모두 해제 */
      public record ReplaceRequest(List<String> libCodes) {
      }

      private final MyLibraries libraries;

      public MyLibrariesController(MyLibraries libraries) {
          this.libraries = libraries;
      }

      @GetMapping
      public List<LibraryView> list() {
          return libraries.list(CurrentMember.require());
      }

      @PutMapping
      public List<LibraryView> replace(@RequestBody ReplaceRequest req) {
          return libraries.replace(CurrentMember.require(), req.libCodes());
      }
  }
  ```

  `src/main/java/com/bookshelf/library/presentation/BorrowController.java`:

  ```java
  package com.bookshelf.library.presentation;

  import com.bookshelf.common.CurrentMember;
  import com.bookshelf.library.application.BorrowService;
  import com.bookshelf.library.application.HoldingView;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.RestController;

  import java.util.List;

  /** 도서관 코드를 파라미터로 받지 않는다 — 받을 곳 자체가 없다(설계 6-3) */
  @RestController
  public class BorrowController {

      private final BorrowService borrow;

      public BorrowController(BorrowService borrow) {
          this.borrow = borrow;
      }

      @GetMapping("/api/books/{isbn}/libraries")
      public List<HoldingView> availability(@PathVariable String isbn) {
          return borrow.availability(CurrentMember.require(), isbn);
      }
  }
  ```

  `ApiExceptionHandler`에 추가(import `com.bookshelf.library.application.LibraryChangeLimitException`):

  ```java
      @ExceptionHandler(LibraryChangeLimitException.class)
      ResponseEntity<ApiError> tooManyChanges(LibraryChangeLimitException e) {
          log.debug("429 {}", e.getMessage());
          return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                  .body(new ApiError("TOO_MANY_CHANGES", "도서관은 하루 3번까지 바꿀 수 있어요. 내일 다시 해 주세요"));
      }
  ```

  `MemberDeletion.OWNED_TABLES`에 `"member_library"`, `"member_library_change"`를 더한다.

- [ ] **Step 6: 통과 확인**

  Run: `./gradlew test --tests MyLibrariesApiTest --tests BorrowApiTest`
  Expected: PASS (7 + 8 = 15개)

- [ ] **Step 7: 탈퇴와 공개 경계에 도서관을 더한다**

  `AccountDeletionTest.setUp()` 끝에 추가:

  ```java
          jdbc.sql("INSERT INTO library (lib_code, name, region_code, synced_at) VALUES ('L1', '내 도서관', 11, now())").update();
          jdbc.sql("INSERT INTO member_library (member_id, lib_code, position) VALUES (:m, 'L1', 1)").param("m", memberId).update();
          jdbc.sql("INSERT INTO member_library_change (member_id, day, changes) VALUES (:m, current_date, 1)").param("m", memberId).update();
  ```

  `탈퇴하면_기록_토큰_사용량을_지우고…` 테스트에 추가:

  ```java
          assertThat(count("member_library")).isZero();
          assertThat(count("member_library_change")).isZero();
          assertThat(jdbc.sql("SELECT count(*) FROM library").query(Integer.class).single()).isEqualTo(1);   // 도서관 자체는 남는다
  ```

  `PublicViewArchitectureTest`에 규칙을 더한다:

  ```java
      @ArchTest
      static final ArchRule 공개_조회는_도서관_코드를_쓰지_않는다 = noClasses()
              .that().resideInAPackage("..publicview..")
              .should().dependOnClassesThat().resideInAnyPackage("..library..")
              .because("누가 어느 도서관을 등록했는지는 사는 동네를 알려준다 (설계 7-3)");
  ```

  `PublicBoundaryTest`에 SQL 문자열 검사를 더한다(ArchUnit은 SQL 안의 테이블 이름을 보지 못한다):

  ```java
      /** 공개 조회 SQL이 등록 도서관·소장 캐시 테이블을 읽지 않는다. SQL은 모두 publicview 패키지의 소스에 있다 */
      @Test
      void 공개_조회_SQL은_도서관_테이블을_읽지_않는다() throws Exception {
          try (var files = java.nio.file.Files.list(java.nio.file.Path.of("src/main/java/com/bookshelf/publicview"))) {
              for (var file : files.toList()) {
                  String src = java.nio.file.Files.readString(file);
                  assertThat(src).as(file.toString()).doesNotContain("member_library").doesNotContain("library_holding");
              }
          }
      }
  ```

  Run: `./gradlew test --tests AccountDeletionTest --tests PublicViewArchitectureTest --tests PublicBoundaryTest`
  Expected: PASS. `AccountDeletionTest`가 `member_library`에서 실패하면 `MemberDeletion.OWNED_TABLES`에 더하지 않은 것이다

- [ ] **Step 8: 전체 테스트 후 커밋 (사용자 허용 후)**

  Run: `./gradlew test` → 전부 PASS

  허용을 요청할 경로: `src/main/resources/db/migration/V5__member_library.sql src/main/java/com/bookshelf/library src/main/java/com/bookshelf/member/application/MemberDeletion.java src/main/java/com/bookshelf/common/ApiExceptionHandler.java src/test/java/com/bookshelf/library src/test/java/com/bookshelf/auth/AccountDeletionTest.java src/test/java/com/bookshelf/publicview src/test/java/com/bookshelf/support/DbCleaner.java`

  ```bash
  git commit -m "feat: 내 도서관과 빌리기 칸 — 도서관은 서버가 고른다

  빌리기 조회는 member_library의 도서관만 쓴다. 요청으로 도서관 코드를 받을 곳이 없다.
  등록은 3곳, 변경은 하루 3번 — 바꿔 끼우며 훑지 못하게.
  소장·대출은 다음 날 0시(KST)까지 캐시하고, 한 도서관의 실패는 그 도서관만 확인 불가다."
  ```

---

### Task 12: 운영 준비 — 새 시크릿, 정보나루 서버 IP, 본인 서재를 Apple 계정에 잇기

코드는 없다. Phase 1의 배포 구성(`deploy/`)에 Phase 2의 환경변수를 더하고, Phase 1에서 SQL로 만든 본인 회원(`seed-owner.sql`)을 Apple 로그인과 잇는 절차를 만든다. `seed-owner.sql`의 주석이 약속한 것("이 회원에 Apple sub를 연결한다. 새로 만들지 않는다")을 지킨다.

잇는 방법: 앱에서 Apple로 한 번 로그인하면 **새 회원**(`reader-xxxxxx`, 기록 없음)이 생긴다. 그 회원의 `apple_sub`를 본인 회원으로 옮기고 빈 새 회원을 지운다. 새 회원에 기록이 하나라도 있으면 지우지 않고 멈춘다(UNIQUE 제약이 옮기기를 막는다 — 한 트랜잭션이라 아무것도 바뀌지 않는다).

**Files:**
- Modify: `deploy/compose.server.yaml` (app 환경변수)
- Modify: `deploy/README.md` (4절 `.env`, 6절 교체, 7절 관측, 9절 정보나루 IP)
- Create: `deploy/link-owner-apple.sql`
- Modify: `deploy/seed-owner.sql` (주석이 새 파일을 가리키게)

**Interfaces:**
- Consumes: 환경변수 이름 — `JWT_SECRET`, `APPLE_CLIENT_ID`, `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY`, `LIBRARY_API_KEY`(Task 1·2·8·9의 `application.yaml`)
- Produces: `deploy/link-owner-apple.sql`(psql 변수 `owner`, `new_slug`)

- [ ] **Step 1: compose에 환경변수** — `deploy/compose.server.yaml`의 app `environment:`에서 `KAKAO_REST_API_KEY` 줄 아래에 추가

  ```yaml
      JWT_SECRET: ${JWT_SECRET:?JWT_SECRET을 지정해야 한다 (openssl rand -base64 48)}
      APPLE_CLIENT_ID: ${APPLE_CLIENT_ID:?APPLE_CLIENT_ID를 지정해야 한다 (앱 번들 ID)}
      APPLE_TEAM_ID: ${APPLE_TEAM_ID:?APPLE_TEAM_ID를 지정해야 한다}
      APPLE_KEY_ID: ${APPLE_KEY_ID:?APPLE_KEY_ID를 지정해야 한다}
      # .p8 내용을 한 줄로 — 줄바꿈을 |로 바꿔 넣는다. 앱이 되돌린다 (AppleProperties.privateKeyPem)
      APPLE_PRIVATE_KEY: ${APPLE_PRIVATE_KEY:?APPLE_PRIVATE_KEY를 지정해야 한다}
      LIBRARY_API_KEY: ${LIBRARY_API_KEY:?LIBRARY_API_KEY를 지정해야 한다 (정보나루 인증키)}
  ```

- [ ] **Step 2: compose 검증** — 가짜 값으로 해석만 해 본다(아무것도 띄우지 않는다)

  ```bash
  printf 'POSTGRES_PASSWORD=x\nKAKAO_REST_API_KEY=x\nSITE_URL=https://example.com\nJWT_SECRET=x\nAPPLE_CLIENT_ID=x\nAPPLE_TEAM_ID=x\nAPPLE_KEY_ID=x\nAPPLE_PRIVATE_KEY="a|b"\nLIBRARY_API_KEY=x\n' > /tmp/compose-check.env
  docker compose -f deploy/compose.server.yaml --env-file /tmp/compose-check.env config > /dev/null && echo OK
  sed -i '' '/^LIBRARY_API_KEY/d' /tmp/compose-check.env
  docker compose -f deploy/compose.server.yaml --env-file /tmp/compose-check.env config 2>&1 | grep -o "LIBRARY_API_KEY를 지정해야 한다"
  rm /tmp/compose-check.env
  ```
  Expected: `OK`, 그다음 `LIBRARY_API_KEY를 지정해야 한다` — 빠진 키는 기동 전에 compose가 막는다

- [ ] **Step 3: 본인 서재를 Apple 계정에 잇는 SQL** — `deploy/link-owner-apple.sql`

  ```sql
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
  ```

  `\gset`은 결과가 없으면(잘못된 `new_slug`) 변수를 만들지 않아 다음 줄의 `:'new_sub'`에서 오류가 난다 → `ON_ERROR_STOP`으로 멈춘다.

- [ ] **Step 4: 로컬 DB에서 SQL을 확인한다** — 개발 DB(`bookshelf`)는 `./gradlew bootRun` 한 번으로 V5까지 올라가 있어야 한다

  ```bash
  docker exec -i bookshelf-postgres psql -U bookshelf -d bookshelf -v ON_ERROR_STOP=1 <<'SQL'
  INSERT INTO member (slug, display_name, shelf_public, default_visibility, created_at)
  VALUES ('owner-check', '본인', TRUE, 'PRIVATE', now());
  INSERT INTO member (slug, display_name, apple_sub, shelf_public, default_visibility, created_at)
  VALUES ('reader-check1', '독자', 'apple-sub-check', FALSE, 'PRIVATE', now());
  SQL
  docker exec -i bookshelf-postgres psql -U bookshelf -d bookshelf -v ON_ERROR_STOP=1 \
    -v owner=owner-check -v new_slug=reader-check1 -f - < deploy/link-owner-apple.sql
  ```
  Expected: 마지막 SELECT가 `owner-check | 본인 | t` 한 줄(새 회원은 지워졌다)

  기록이 있는 새 회원은 멈추는지:

  ```bash
  docker exec -i bookshelf-postgres psql -U bookshelf -d bookshelf -v ON_ERROR_STOP=1 <<'SQL'
  UPDATE member SET apple_sub = NULL WHERE slug = 'owner-check';
  INSERT INTO member (slug, display_name, apple_sub, shelf_public, default_visibility, created_at)
  VALUES ('reader-check2', '독자', 'apple-sub-check', FALSE, 'PRIVATE', now());
  INSERT INTO reading_record (member_id, isbn13, status, visibility, created_at, updated_at)
  SELECT id, '9788936434120', 'DONE', 'PRIVATE', now(), now() FROM member WHERE slug = 'reader-check2';
  SQL
  docker exec -i bookshelf-postgres psql -U bookshelf -d bookshelf -v ON_ERROR_STOP=1 \
    -v owner=owner-check -v new_slug=reader-check2 -f - < deploy/link-owner-apple.sql; echo "exit=$?"
  ```
  Expected: `duplicate key value violates unique constraint` 오류와 `exit=3`. `SELECT slug, apple_sub FROM member WHERE slug LIKE '%check%'`에서 `reader-check2`가 그대로 `apple-sub-check`를 갖고 있다

  정리:

  ```bash
  docker exec -i bookshelf-postgres psql -U bookshelf -d bookshelf <<'SQL'
  DELETE FROM reading_record WHERE member_id IN (SELECT id FROM member WHERE slug LIKE '%-check%');
  DELETE FROM member WHERE slug LIKE '%-check%';
  SQL
  ```

- [ ] **Step 5: README** — `deploy/README.md`

  4절의 `.env` 예시를 바꾼다:

  ```bash
  cat > .env <<'ENV'
  POSTGRES_PASSWORD=<openssl rand -base64 24>
  KAKAO_REST_API_KEY=<카카오 REST API 키>
  SITE_URL=https://<도메인>
  JWT_SECRET=<openssl rand -base64 48>
  APPLE_CLIENT_ID=<앱 번들 ID>
  APPLE_TEAM_ID=<Apple Team ID>
  APPLE_KEY_ID=<Sign in with Apple 키 ID>
  APPLE_PRIVATE_KEY="<AuthKey_XXXX.p8 내용, 줄바꿈을 |로>"
  LIBRARY_API_KEY=<정보나루 인증키>
  ENV
  chmod 600 .env
  ```

  그 아래에 한 줄: `APPLE_PRIVATE_KEY`는 맥에서 `tr '\n' '|' < AuthKey_XXXX.p8`로 만든다. **`JWT_SECRET`을 바꾸면 모든 access 토큰이 무효가 된다**(refresh 토큰은 DB에 있어 그대로다 — 앱은 다음 요청에서 refresh로 새 access를 받는다).

  6절 제목과 내용을 바꾼다 — `## 6. 본인 서재`: 기존 `seed-owner.sql` 절차는 그대로 두고, 그 아래에 "Apple 로그인을 붙인 뒤(Phase 2 배포 후) 한 번: `deploy/link-owner-apple.sql`의 사용법 주석을 따른다"를 더한다.

  7절 관측에 두 줄을 더한다:

  ```bash
  docker exec bookshelf-postgres psql -U bookshelf -d bookshelf -c "SELECT member_id, calls FROM user_call_counter WHERE day = current_date ORDER BY calls DESC LIMIT 5"
  docker exec bookshelf-postgres psql -U bookshelf -d bookshelf -c "SELECT finished_at, libraries FROM library_sync_run ORDER BY finished_at DESC LIMIT 3"
  ```

  9절을 새로 쓴다:

  ```markdown
  ## 9. 정보나루 서버 IP (한도를 올릴 때)
  서버 IP를 등록하지 않으면 정보나루는 하루 500회까지다. 우리는 450(백그라운드 300)에서 멈춘다(설계 6-3).
  `provider_call_counter`의 LIBRARY가 며칠 연속 360(80%)을 넘거나 공개 출시 전이면:
  1. 정보나루 마이페이지 → 인증키 → 서버 정보에 서버 공인 IP를 등록한다 (`curl -s ifconfig.me`)
  2. 오른 한도를 확인한 뒤 `application.yaml`의 `providers.library.daily-limit`·`background-ceiling`을 그 90%·60%로 바꿔 재배포한다
  ```

  `seed-owner.sql`의 둘째 주석 줄을 바꾼다:

  ```sql
  -- Apple 로그인을 붙인 뒤에는 이 회원을 Apple 계정에 잇는다 — deploy/link-owner-apple.sql. 새로 만들지 않는다.
  ```

- [ ] **Step 6: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `deploy/compose.server.yaml deploy/README.md deploy/link-owner-apple.sql deploy/seed-owner.sql`

  커밋 전 확인: `git diff --cached | grep -iE "BEGIN PRIVATE KEY|authKey=[0-9a-f]{20,}"` 결과가 비어야 한다

  ```bash
  git commit -m "chore: 배포 구성에 Phase 2 시크릿과 본인 서재 잇기 절차

  JWT·Apple·정보나루 키를 compose가 기동 전에 요구한다.
  SQL로 만든 본인 회원을 첫 Apple 로그인이 만든 빈 회원과 잇는다 — 새 회원에 기록이 있으면 멈춘다.
  정보나루 한도를 올릴 때의 서버 IP 등록 절차를 적는다."
  ```

---

## 마치며 — 이 계획이 끝나면

- 앱(Phase 3)이 쓸 API가 모두 인증 뒤에 있다: 로그인·refresh·로그아웃, 내 정보, 기록, 책 상세·링크·검색, 도서관 찾기·등록, 빌리기, 탈퇴
- 배포는 여전히 Phase 1 Task 12 Step 7~12(서버·도메인·레포가 준비되면)다. Phase 2까지 한 번에 배포해도 된다 — Flyway가 V1~V5를 차례로 적용한다
- Phase 3로 넘기는 것: 공유 흐름 — 서재 공개 스위치·기본 공개 범위 변경·일괄 공개 전환 API(설계 7-4), Google Play 전자책 가격, 앱의 "N시간 전 기준" 표기
