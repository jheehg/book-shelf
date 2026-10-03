# Phase 1 — 웹 선배포 (W1~W5) 구현 계획 · 기록

> 이 문서는 당시의 상세 구현 기록이다. 현재 작업에는 특정 에이전트 프레임워크를 요구하지 않으며, 저장소 루트의 `AGENTS.md`와 `agent-rules/`를 따른다. 체크박스는 작성 당시 상태라 완료 여부의 기준이 아니다.

**Goal:** 카카오 책 검색 연동(3층 캐시 + 제공자별 호출 예산), 공개 서재·책 페이지(서버 렌더링, OG 태그, "읽을 수단" 링크, 공개 경계 테스트 골격)를 갖춘 Spring Boot 서버를 OCI에 HTTPS로 배포한다. 앱과 인증이 없어도 그 자체로 살아 있는 서비스다.

**Architecture:** 도메인별 패키지(`member`, `record`, `book`, `catalog`, `provider`, `search`, `links`, `publicview`) 안에 계층을 둔다. 책 정보는 **`BookCatalog` 인터페이스** 뒤에 두고 지금의 구현은 `KakaoBookCatalog` 하나다. 모든 외부 호출은 제공자별 `CallBudget` 정책을 지난다. 공개 조회는 `publicview` 패키지의 **JPA 엔티티를 쓰지 않는 SQL 전용 쿼리**로 물리적으로 분리하고 ArchUnit이 경계를 강제한다. 공개 페이지는 제공자를 동기로 부르지 않고, 오래된 판매 상태는 갱신 큐에 넣어 백그라운드 작업이 처리한다.

**Tech Stack:** Java 21 (temurin, asdf), Spring Boot 4.1.1, Gradle 9.7.1 (Kotlin DSL), PostgreSQL 16, Flyway, Thymeleaf, RestClient(JDK HttpClient), Jackson 3(`tools.jackson`), WireMock 3.13.1, ArchUnit 1.4.1, Docker Compose, Nginx, certbot

**Spec:** `docs/superpowers/specs/2026-09-27-book-shelf-design.md` (2026-09-30 개정본). 제공자 실측: `docs/notes/providers.md`

**이 계획의 범위:** 설계 문서 9장의 W1~W5. 다음 계획으로 넘기는 것:
- Phase 2 (W6~W7): Apple 로그인, JWT, 사용자별 호출 상한, 계정 삭제(서버), 기록·검색 API, 정보나루 서버 연동(`Library`·`MemberLibrary`·`LibraryHolding`)
- Phase 3 (W8~W11): Flutter 앱, 공유 흐름, Google Play 가격(`EbookOffer`, 같은 책 확인)
- Phase 4 (W12~W14): 공개 경계 테스트 보강, TestFlight, 강제 업데이트 스위치

**설계 문서와 다르게 정한 것 하나:** 설계 문서 9장은 W2에 "전체 스키마"를 만든다고 했다. 이 계획은 **Phase 1에서 쓰는 테이블만** 만든다. `Library`·`MemberLibrary`·`LibraryHolding`의 필드는 설계 문서가 (가정)으로 표시했고 W3 실측 뒤에야 확정되며, `EbookOffer`는 W11까지 쓰지 않는다. 쓰지 않는 테이블을 먼저 만들면 실측 결과에 맞춰 다시 고쳐야 한다. Flyway로 나중에 추가하는 비용은 작다.

## Global Constraints

- 커밋·푸시·검증 규칙은 `agent-rules/workflow.md`를 따른다.
- 비밀값(`.env`, 키)은 커밋하지 않는다. 커밋 전 스테이징 내용에 키가 없는지 확인한다
- 공개 조회는 인증된 조회 코드를 절대 재사용하지 않는다. 비공개 데이터를 못 가져오게 작성한다 (spec 7-2)
- `record id`를 공개 URL에 노출하지 않는다. 공개 조회는 `slug`로만 (spec 7-2)
- 비공개·없는·탈퇴한 서재는 같은 404 (spec 7-2)
- 공개 책 페이지는 DB에 있는 책만. 없는 ISBN은 제공자를 부르지 않고 404 (spec 7-2)
- **공개 페이지는 어떤 제공자도 동기로 부르지 않는다.** 캐시가 없거나 오래됐으면 "확인 불가"와 링크만 보여주고 갱신은 큐에 넣는다 (spec 2-1)
- 책 정보가 없는 공개 기록은 회색 표지와 ISBN, 책 페이지 링크 없음 (spec 7-2)
- `RATING_ONLY` 공개용 응답 타입에는 한줄평 필드가 없다. 책 페이지에는 `RATING_ONLY` 사용자의 이름·별점도 싣지 않는다 (spec 7-3)
- 공개 집계 숫자는 공개 기록만 센다 (spec 7-3)
- visibility 기본값은 `PRIVATE` (spec 7-3)
- `ReadingRecord`는 `Book` 없이도 존재한다 (spec 5)
- 상태가 있는 칸에는 기준 시각을 붙인다. 링크 칸에는 상태를 흉내 내지 않는다 (spec 2-1)
- 카카오 `status`가 빈 값이면 "판매 정보 없음". 절판이라 단정하지 않는다 (spec 2-1)
- 카카오 `sale_price = -1`·`price = 0`은 가격 없음(null). "-1원"·"0원"을 쓰지 않는다 (spec 2-1)
- 모든 테이블은 13자리 ISBN으로 저장. 카카오 `isbn`은 공백으로 나눠 13자리 우선, 없으면 10자리를 변환, 체크섬 틀리면 버림 (spec 5)
- 카카오 상한 **27,000/일**, 백그라운드 상한 **18,000/일**, 초기화 기준 KST 자정(가정) (spec 6-3)
- 카카오 타임아웃 connect **1s** / read **2s** (spec 6-2)
- 검색은 **10권씩** (spec 2-1). 서버도 2글자 규칙을 검사한다 (spec 6-4)
- 캐시: `Book` 영구, `SaleSnapshot` **6시간**, 검색어 **24시간** (spec 6-5)
- 트랜잭션 안에서 외부 API를 호출하지 않는다 (spring-study 규칙)
- 시간은 `TIMESTAMPTZ` + `Instant`, 달력 날짜는 `DATE` + `LocalDate`. `LocalDateTime` 금지. "지금"은 `Clock` 빈에서
- 엔티티에 `@Setter`, `@Data`, `@ToString` 금지. 생성자는 `protected`, 생성은 정적 팩토리
- 도메인 사이에는 FK도, 엔티티 참조도 두지 않는다(ID만)
- 외부 응답 DTO에는 `@JsonIgnoreProperties(ignoreUnknown = true)`
- 시크릿(`KAKAO_REST_API_KEY`, `POSTGRES_PASSWORD`)에 기본값을 두지 않는다
- 사용자 입력은 `th:text`로만 출력한다. `th:utext` 금지

## Review Focus

설계 문서가 말하지 않았지만 실제 사용자(와 봇)가 가장 먼저 부딪힐 다섯 가지다. 각 줄의 테스트는 담당 Task에 들어 있다.

1. **봇이 책 페이지를 훑는다** — 책 페이지 수천 개가 크롤링돼도 요청 스레드는 카카오를 부르지 않고, 갱신 큐는 백그라운드 상한(18,000)에서 멈춘다. 카카오가 모르는 책은 "판매 정보 없음" 스냅샷을 남겨 6시간 동안 다시 큐에 들어가지 않는다 → Task 6, Task 11
2. **카카오 응답의 모양이 제각각이다** — `isbn`이 `"10 13"`/`"13"`/`" 13"`(앞 공백)으로 오고, `sale_price`가 `-1`, `status`가 빈 값이거나 처음 보는 값(예: "품절")이다. 어느 경우에도 예외 없이 규칙대로 옮긴다 → Task 4
3. **카카오가 응답하지 않는다** — 한 번 실패하면 60초 동안 백그라운드 호출을 즉시 포기하고, 공개 페이지는 캐시만으로 뜬다 → Task 4, Task 11
4. **한줄평·표시 이름에 HTML이 들어온다** — `<script>`는 이스케이프되어 글자로 보인다 → Task 11
5. **같은 책이 두 번 저장된다** — 10자리 ISBN으로 찍은 책과 13자리로 찍은 책, 검색 결과 안의 중복 항목이 한 행이 된다 → Task 2, Task 4, Task 7

## 파일 구조

```
book-shelf/
├── .tool-versions                 java temurin-21.0.12+101.0.LTS
├── .gitignore                     (있음) .env, CLAUDE.local.md
├── .claude/CLAUDE.md              (있음) 공개 규칙 — Task 1에서 프로젝트 규칙을 덧붙인다
├── build.gradle.kts / settings.gradle.kts / gradlew / gradle/
├── compose.yaml                   로컬 Postgres (5433, 테스트 DB 포함)
├── Dockerfile
├── db/init/01-test-db.sql
├── deploy/                        compose.server.yaml, nginx.http.conf, nginx.conf, seed-owner.sql, backup.sh, README.md
└── src/
    ├── main/java/com/bookshelf/
    │   ├── BookShelfApplication.java
    │   ├── common/                Isbn13, DomainException, ClockConfig, BookshelfProperties
    │   ├── member/domain|infrastructure/      Member, MemberRepository
    │   ├── record/domain|infrastructure/      ReadingRecord, ReadingStatus, Visibility, ReadingRecordRepository
    │   ├── provider/              Provider, CallPurpose, BudgetPolicy, CallBudget,
    │   │                          ProviderUnavailableException, BudgetExhaustedException
    │   ├── catalog/               BookCatalog(인터페이스), CatalogBook, CatalogPage
    │   ├── catalog/kakao/         KakaoProperties, KakaoRestClientConfig, KakaoResponse, KakaoIsbn, KakaoBookCatalog
    │   ├── book/domain|infrastructure/        Book, SaleSnapshot, SaleStatus, BookLookupFailure, 각 Repository, RefreshQueue
    │   ├── book/application/      BookView, BookWriter, BookLookupService, SaleView, SaleService,
    │   │                          RefreshWorker, BookBackfill, BackgroundJobs
    │   ├── search/                QueryNormalizer, InvalidQueryException, SearchCacheRepository, SearchWriter,
    │   │                          SearchService, SearchResult, SearchUnavailableException
    │   ├── links/                 StoreLinksProperties, StoreLink, LinkGroup, StoreLinks
    │   └── publicview/            PublicShelfQuery, PublicShelf, PublicEntry(sealed), RatedEntry, ReviewedEntry,
    │                              PublicBookQuery, PublicBook, PublicReview, SitemapQuery, AgeLabel, PublicPages,
    │                              PublicShelfController, PublicBookController, SeoController
    ├── main/resources/
    │   ├── application.yaml
    │   ├── db/migration/V1__init.sql
    │   ├── static/css/site.css
    │   └── templates/public/      fragments.html, shelf.html, book.html, not-found.html
    └── test/
        ├── java/com/bookshelf/    (각 Task에 명시) + support/: DbCleaner, Fixtures, KakaoStub, MutableClock
        └── resources/kakao/       실측 응답 (docs/notes/providers.md의 카카오 실측에서 저장한 것)
```

---

### Task 0: 리드타임이 있는 준비물과 실측 마무리 (코드 없음)

설계 문서 9장 W1. **Apple Developer 가입이 12월로 밀리면 TestFlight 자체가 불가능하고, 정보나루 실측이 W3을 넘기면 빌리기 칸이 버려진다.**

- [ ] **Step 1: Apple Developer Program 가입 신청** — https://developer.apple.com/programs/enroll/ , 연 $99. 승인까지 며칠
- [ ] **Step 2: 배포 위치 결정 — 결정 지점**

  spring-study 서버(E2.1.Micro, 954MB)는 JVM에 300~350MB밖에 줄 수 없고 Nginx가 80/443을 쓰고 있다.

  | | A. 두 번째 E2.1.Micro (권장) | B. 같은 인스턴스 |
  |---|---|---|
  | 비용 | 0 (Always Free는 AMD Micro 2대까지) | 0 |
  | 메모리 | 독립 954MB | JVM 두 개가 나눠 쓴다 |
  | 장애 격리 | spring-study 부하 실험이 서비스를 죽이지 않는다 | 결제 부하 테스트 = book-shelf 장애 |

  결정과 이유를 `docs/notes/infra.md`에 적는다. A라면 OCI 콘솔에서 인스턴스를 만들고(도쿄, Ubuntu 24.04) spring-study `docs/infra-notes.md`의 서버 준비 절차(스왑 2GB, Docker, 방화벽 80/443)를 따른다
- [ ] **Step 3: 도메인 구입** — DNS A 레코드는 Task 12에서 건다
- [ ] **Step 4: Google Books 확인 (설계 문서 8장·6-1)**
  1. Cloud 콘솔 → API 및 서비스 → Books API → 할당량에서 **Queries per day** 값
  2. 서버를 확보했으면 서버에서 한 번(`country=KR`로 원화가 나오는지). 키는 맥의 `.env`에 있는 `GOOGLE_BOOKS_KEY`를 그 자리에서만 넘긴다(서버에 저장하지 않는다):
     ```bash
     read -rs GOOGLE_BOOKS_KEY          # 키를 붙여 넣는다 (화면·기록에 남지 않는다)
     curl -s "https://www.googleapis.com/books/v1/volumes?q=intitle:소년이+온다+inauthor:한강&country=KR&key=$GOOGLE_BOOKS_KEY" \
       | python3 -c "import sys,json;[print(i['saleInfo'].get('saleability'),i['saleInfo'].get('retailPrice')) for i in json.load(sys.stdin).get('items',[])]"
     ```
     Expected: `FOR_SALE {'amount': 10800.0, 'currencyCode': 'KRW'}`가 한 줄 이상. 안 나오면 설계 문서 버리는 순서 1(Google Play 가격)을 기록한다
  3. 실측 원본 `raw/gbooks-title-9791161571188.json`(불편한 편의점)과 `raw/gbooks-title-9781847941831.json`(Atomic Habits)에서 오탐 항목의 `volumeInfo.language` 값
  결과를 `docs/notes/providers.md`의 "아직 확인하지 않은 것"에서 옮겨 적는다
- [ ] **Step 5: 약관 확인 (설계 문서 6-1 기준 6)** — 카카오 개발자 약관·운영정책, Google Books 약관, 정보나루 이용약관에서 **캐시 보관 기간 / 출처 표시 의무 / 공개 웹 게재** 세 줄씩 찾아 `docs/notes/providers.md`에 적는다. 카카오 출처 표시 의무가 있으면 Task 11의 푸터 문구를 그에 맞춘다
- [ ] **Step 6: 정보나루 — 승인되면 실측, 마감 W3 끝**

  ```bash
  K=$(grep -E '^LIBRARY_KEY=' .env | cut -d= -f2-)
  curl -sS "http://data4library.kr/api/srchDtlList?authKey=$K&isbn13=9788936434120&loaninfoYN=N&format=json" | head -c 400; echo
  ```

  `vitalizationErr`가 사라지면 설계 문서 6-7절의 확인 항목을 실측하고, 같은 절의 기준표로 결정을 `docs/notes/providers.md`에 적는다. **W3 끝까지 승인이 나지 않으면 그것도 결정이다** — 버리는 순서 3으로 기록한다
- [ ] **Step 7: GitHub 저장소 연결** — **사용자가 직접 한다**(퍼블릭 레포 생성은 외부에 공개되는 작업이다)

  ```bash
  gh repo create jheehg/book-shelf --public --source=. --remote=origin
  ```

  푸시는 Task 12 전까지 하지 않아도 된다. 이 Step은 커밋이 없다(`docs/`는 커밋하지 않는다)

---

### Task 1: 스켈레톤 — 뜨고, DB에 붙고, 스키마를 Flyway가 만든다

spring-study와 다른 결정 하나: **스키마를 Flyway로 관리한다.** spring-study는 `schema.sql`과 `migration/`을 두 벌 관리했다. 계속 살아 있어야 하는 서비스라 기동 시 자동 적용하고, 테스트 DB도 같은 경로로 만든다.

**Files:**
- Create: `.tool-versions`, `settings.gradle.kts`, `build.gradle.kts`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*` (spring-study에서 복사)
- Create: `compose.yaml`, `db/init/01-test-db.sql`
- Create: `src/main/java/com/bookshelf/BookShelfApplication.java`, `common/ClockConfig.java`
- Create: `src/main/resources/application.yaml`, `src/main/resources/db/migration/V1__init.sql`
- Modify: `.gitignore` (빌드 산출물), `.claude/CLAUDE.md` (프로젝트 규칙)
- Modify: `.env` (로컬, 커밋하지 않음) — `KAKAO_REST_API_KEY` 추가
- Test: `src/test/java/com/bookshelf/BookShelfApplicationTests.java`

**Interfaces:**
- Produces: `Clock` 빈(`Asia/Seoul`). 모든 "지금"은 이 빈에서 얻는다

- [ ] **Step 1: 빌드 도구 복사**

  ```bash
  cp ~/repo/spring-study/gradlew ~/repo/spring-study/gradlew.bat .
  cp -r ~/repo/spring-study/gradle .
  cp ~/repo/spring-study/.tool-versions .
  ```

  `settings.gradle.kts`:

  ```kotlin
  rootProject.name = "book-shelf"
  ```

  `build.gradle.kts`:

  ```kotlin
  plugins {
      java
      id("org.springframework.boot") version "4.1.1"
      id("io.spring.dependency-management") version "1.1.7"
  }

  group = "com.bookshelf"
  version = "0.0.1-SNAPSHOT"

  java {
      toolchain {
          languageVersion = JavaLanguageVersion.of(21)
      }
  }

  repositories {
      mavenCentral()
  }

  dependencies {
      implementation("org.springframework.boot:spring-boot-starter-actuator")
      implementation("org.springframework.boot:spring-boot-starter-data-jpa")
      implementation("org.springframework.boot:spring-boot-starter-webmvc")
      implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
      // Boot 4는 자동설정을 모듈별로 쪼갰다. flyway-core만 넣으면 자동 적용이 안 된다
      implementation("org.springframework.boot:spring-boot-starter-flyway")
      // 보내는 쪽(RestClient.Builder 빈)은 webmvc와 별개 모듈이다. 빠지면 컴파일은 되고 기동에서 빈이 없다며 실패한다
      implementation("org.springframework.boot:spring-boot-starter-restclient")
      runtimeOnly("org.flywaydb:flyway-database-postgresql")
      runtimeOnly("org.postgresql:postgresql")
      compileOnly("org.projectlombok:lombok")
      annotationProcessor("org.projectlombok:lombok")

      testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
      testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
      testImplementation("org.wiremock:wiremock-standalone:3.13.1")
      // 공개 조회 경계를 "사람의 기억"이 아니라 "빌드 실패"로 지킨다 (PublicViewArchitectureTest)
      testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
      testCompileOnly("org.projectlombok:lombok")
      testAnnotationProcessor("org.projectlombok:lombok")
      testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  }

  tasks.withType<Test> {
      useJUnitPlatform()
      // 테스트는 별도 DB를 쓴다. 로컬 개발 데이터를 지우지 않기 위해서다
      environment("DB_URL", "jdbc:postgresql://localhost:5433/bookshelf_test")
      environment("KAKAO_REST_API_KEY", "test-kakao-key")
      // 테스트 컨텍스트에서 백그라운드 작업(갱신 큐, 백필)이 스스로 돌지 않게 한다. 테스트는 runOnce()를 직접 부른다
      environment("BACKGROUND_JOBS_ENABLED", "false")
      // 쿨다운은 싱글턴 빈의 상태라 한 테스트의 실패가 다음 테스트로 샌다.
      // 컨텍스트 전체에서는 끄고, 쿨다운 자체는 KakaoBookCatalogTest가 직접 조립해 검증한다
      systemProperty("providers.kakao.cool-down", "0s")
  }
  ```

  `.gitignore`에 빌드 산출물을 덧붙인다(기존 두 항목은 그대로):

  ```gitignore

  ### 빌드 산출물
  .gradle/
  build/
  out/
  .idea/
  *.iml
  .vscode/
  ```

  `.env`에 카카오 REST API 키를 추가한다. 실측 때 쓴 `KAKAO_CLIENT_ID`와 같은 값이다(카카오 콘솔의 "REST API 키"):

  ```bash
  # 앞에 \n을 둔다 — .env 끝에 줄바꿈이 없으면 마지막 줄 뒤에 붙어 그 값을 망가뜨린다(실제로 겪었다)
  grep -q '^KAKAO_REST_API_KEY=' .env || printf '\nKAKAO_REST_API_KEY=%s\n' "$(grep -E '^KAKAO_CLIENT_ID=' .env | cut -d= -f2-)" >> .env
  git check-ignore .env   # .env 가 출력돼야 한다
  ```

- [ ] **Step 2: 로컬 Postgres**

  `compose.yaml`:

  ```yaml
  services:
    postgres:
      image: postgres:16
      container_name: bookshelf-postgres
      environment:
        POSTGRES_DB: bookshelf
        POSTGRES_USER: bookshelf
        POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-localdev}
      ports:
        # 5432는 이 기기의 다른 Postgres가 이미 쓰고 있을 수 있다. 부딪히지 않게 비킨다
        - "5433:5432"
      volumes:
        - postgres-data:/var/lib/postgresql/data
        # 볼륨이 비어 있을 때(최초 1회)만 실행된다
        - ./db/init:/docker-entrypoint-initdb.d:ro
      healthcheck:
        test: ["CMD-SHELL", "pg_isready -U bookshelf -d bookshelf"]
        interval: 5s
        timeout: 3s
        retries: 5

  volumes:
    postgres-data:
  ```

  `db/init/01-test-db.sql`:

  ```sql
  CREATE DATABASE bookshelf_test OWNER bookshelf;
  ```

  ```bash
  docker compose up -d
  docker exec bookshelf-postgres psql -U bookshelf -lqt | cut -d'|' -f1 | grep -w bookshelf_test
  ```

  Expected: `bookshelf_test` 한 줄

- [ ] **Step 3: 실패하는 테스트** — `src/test/java/com/bookshelf/BookShelfApplicationTests.java`

  ```java
  package com.bookshelf;

  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.jdbc.core.simple.JdbcClient;

  import java.time.Clock;
  import java.time.ZoneId;

  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest
  class BookShelfApplicationTests {

      @Autowired JdbcClient jdbc;
      @Autowired Clock clock;

      @Test
      void Flyway가_테스트_DB에_이력_테이블을_만든다() {
          Integer applied = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                  .query(Integer.class).single();
          assertThat(applied).isGreaterThanOrEqualTo(1);
      }

      @Test
      void 시계는_서울_기준이다() {
          assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
      }
  }
  ```

- [ ] **Step 4: 실패 확인**

  Run: `./gradlew test --tests BookShelfApplicationTests`
  Expected: FAIL — `BookShelfApplication` 클래스가 없어 컴파일 실패

- [ ] **Step 5: 최소 구현**

  `src/main/java/com/bookshelf/BookShelfApplication.java`:

  ```java
  package com.bookshelf;

  import org.springframework.boot.SpringApplication;
  import org.springframework.boot.autoconfigure.SpringBootApplication;
  import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

  @SpringBootApplication
  @ConfigurationPropertiesScan
  public class BookShelfApplication {

      public static void main(String[] args) {
          SpringApplication.run(BookShelfApplication.class, args);
      }
  }
  ```

  `src/main/java/com/bookshelf/common/ClockConfig.java`:

  ```java
  package com.bookshelf.common;

  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;

  import java.time.Clock;
  import java.time.ZoneId;

  /**
   * "지금"을 빈으로 둔다. Instant.now()를 직접 부르면 TTL·예산의 날짜 경계를 테스트할 수 없다.
   * 제공자별 날짜 경계는 이 시계의 존이 아니라 각 제공자의 초기화 시간대를 따른다 (CallBudget).
   */
  @Configuration
  public class ClockConfig {

      public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

      @Bean
      Clock clock() {
          return Clock.system(SEOUL);
      }
  }
  ```

  `src/main/resources/application.yaml`:

  ```yaml
  spring:
    application:
      name: book-shelf
    datasource:
      # 로컬 기본값을 두고 서버는 환경변수로 덮는다. DB 비밀번호 기본값은 로컬 편의를 위한 타협이다 — 서버에서는 반드시 환경변수로 준다
      url: ${DB_URL:jdbc:postgresql://localhost:5433/bookshelf}
      username: ${DB_USERNAME:bookshelf}
      password: ${DB_PASSWORD:localdev}
    jpa:
      hibernate:
        # 스키마는 Flyway만 만든다. validate는 엔티티와 스키마가 어긋나면 기동을 실패시킨다
        ddl-auto: validate
      open-in-view: false
    flyway:
      locations: classpath:db/migration

  server:
    # Nginx 뒤에서 https·호스트를 올바로 인식한다. 없으면 앱이 만드는 절대 URL(og:url 등)이 http://로 나간다
    forward-headers-strategy: framework

  management:
    endpoints:
      web:
        exposure:
          include: health

  providers:
    kakao:
      rest-api-key: ${KAKAO_REST_API_KEY}   # 기본값 없음 — 없으면 기동 실패
      base-url: ${KAKAO_BASE_URL:https://dapi.kakao.com}
      # 실측 55~204ms (docs/notes/providers.md). 최댓값의 5배/10배
      connect-timeout: 1s
      read-timeout: 2s
      # 공식 일 30,000의 90% / 60% (설계 문서 6-3)
      daily-limit: 27000
      background-ceiling: 18000
      # 한도 초기화 시간대 (가정 — 카카오 문서·약관으로 확인할 것)
      reset-zone: Asia/Seoul
      cool-down: 60s

  bookshelf:
    site-url: ${SITE_URL:http://localhost:8080}
    sale-ttl: 6h
    search-cache-ttl: 24h
    background:
      enabled: ${BACKGROUND_JOBS_ENABLED:true}
      # @Scheduled 문자열에도 그대로 쓰므로 ISO-8601로 적는다
      refresh-interval: PT1M
      refresh-batch-size: 20
      backfill-interval: PT10M
      backfill-batch-size: 20
  ```

  `src/main/resources/db/migration/V1__init.sql` — 테이블은 Task 2에서 채운다:

  ```sql
  -- V1: 초기 스키마. 테이블은 Task 2에서 이 파일에 채운다(아직 배포 전이므로 V1을 고쳐도 된다).
  -- 첫 배포 이후에는 절대 이 파일을 고치지 않는다. 변경은 V2부터.
  SELECT 1;
  ```

- [ ] **Step 6: 통과 확인**

  Run: `./gradlew test --tests BookShelfApplicationTests`
  Expected: PASS 2개

- [ ] **Step 7: 프로젝트 규칙을 `.claude/CLAUDE.md`에 덧붙인다** (기존 "커밋" 절은 그대로 둔다)

  ~~~markdown

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
  ./gradlew test
  ```
  ~~~

- [ ] **Step 8: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `.tool-versions .gitignore .claude/CLAUDE.md settings.gradle.kts build.gradle.kts gradlew gradlew.bat gradle compose.yaml db src`

  ```bash
  git add <허용받은 경로>
  git diff --cached --name-only        # .env, docs/ 가 없어야 한다
  git commit -m "chore: 스켈레톤 — 스키마를 Flyway로, 테스트는 별도 DB로

  spring-study는 schema.sql과 migration을 두 벌 관리했다. 계속 살아 있어야 하는
  서비스라 기동 시 자동 적용으로 바꾸고 테스트 DB도 같은 경로로 만든다."
  ```

---

### Task 2: 스키마와 도메인 — 내 데이터와 남의 데이터를 나눈다

설계 문서 5장의 원칙이 테이블 모양이 된다. `reading_record`는 `book`에 FK가 없다 — **제공자에 없는 책을 찍어도 기록은 생겨야 하기 때문이다.** `member`와도 FK를 걸지 않는다(도메인 사이에는 ID만).

**Files:**
- Modify: `src/main/resources/db/migration/V1__init.sql`
- Create: `src/main/java/com/bookshelf/common/DomainException.java`, `common/Isbn13.java`
- Create: `src/main/java/com/bookshelf/record/domain/Visibility.java`, `ReadingStatus.java`, `ReadingRecord.java`
- Create: `src/main/java/com/bookshelf/member/domain/Member.java`
- Create: `src/main/java/com/bookshelf/book/domain/SaleStatus.java`
- Create: `src/main/java/com/bookshelf/book/domain/Book.java`, `SaleSnapshot.java`, `BookLookupFailure.java`
- Create: `src/main/java/com/bookshelf/{member,record,book}/infrastructure/*Repository.java`
- Test: `src/test/java/com/bookshelf/common/Isbn13Test.java`
- Test: `src/test/java/com/bookshelf/record/domain/ReadingRecordTest.java`
- Test: `src/test/java/com/bookshelf/member/domain/MemberTest.java`
- Test: `src/test/java/com/bookshelf/book/domain/BookLookupFailureTest.java`
- Test: `src/test/java/com/bookshelf/record/RecordPersistenceTest.java`
- Create (test 지원): `src/test/java/com/bookshelf/support/DbCleaner.java`, `support/Fixtures.java`

**Interfaces:**
- Produces:
  - `Isbn13.parse(String raw) -> Isbn13` (ISBN-10도 받아 13자리로), `Isbn13.tryParse(String raw) -> Optional<Isbn13>`, `#value() -> String`, `#languageHint() -> Optional<String>` (978-89·979-11 → `"ko"`, 978-0·978-1 → `"en"` — Phase 3의 같은 책 확인이 쓴다). 잘못된 입력은 `DomainException`
  - `Visibility { PUBLIC, RATING_ONLY, PRIVATE }`, `ReadingStatus { WANT, READING, DONE }`
  - `SaleStatus { ON_SALE, ORDER, UNKNOWN }` (book.domain 패키지 — 판매 중 / 주문 후 발송 / 판매 정보 없음. 우리 도메인의 개념이고 제공자가 이 값으로 번역한다. `publicview`가 `catalog`를 참조하지 않게 하려는 배치다 — Task 9)
  - `Member.register(String slug, String displayName, Instant now)`, `#openShelf()`, `#closeShelf()`, `#withdraw(Instant now)`
  - `ReadingRecord.create(Long memberId, Isbn13 isbn, ReadingStatus status, Visibility visibility, Instant now)`, `#rate(Integer)`, `#writeReview(String, Instant)`, `#changeVisibility(Visibility, Instant)`
  - `Book.of(Isbn13, String title, String author, String publisher, LocalDate pubDate, String coverUrl, Integer pageCount, Instant fetchedAt)`, `Book#refreshFrom(Book fetched)` — 단 `pageCount`는 새 값이 null이면 지키지 않고 기존 값을 둔다
  - `SaleSnapshot.of(Isbn13, Integer listPrice, Integer salePrice, SaleStatus status, Instant fetchedAt)`, `#replaceWith(SaleSnapshot)`, `#isFreshAt(Instant now, Duration ttl)`
  - `BookLookupFailure.first(Isbn13, Instant)`, `#again(Instant)`, `BookLookupFailure.backoff(int attempts)`(package-private), getter `attempts`, `nextTryAt`
  - 리포지터리: `MemberRepository`, `ReadingRecordRepository`, `BookRepository(<Book, String>)`, `SaleSnapshotRepository(<SaleSnapshot, String>)`, `BookLookupFailureRepository(<BookLookupFailure, String>)`
  - 테스트 지원: `DbCleaner.clean(JdbcClient)`, `Fixtures#member(slug, shelfPublic)`, `#withdraw(member)`, `#record(member, isbn, visibility, rating, review)`, `#book(isbn, title)`, `Fixtures.T0`

- [ ] **Step 1: 스키마 작성** — `V1__init.sql`의 `SELECT 1;`을 지우고 아래로 교체

  ```sql
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
  ```

  Task 1에서 테스트 DB에 옛 V1(`SELECT 1`)이 이미 적용되어 있다. V1을 고치면 Flyway가 체크섬 불일치로 기동을 막으므로, **배포 전에 V1을 고칠 때마다** 로컬 DB를 비운다(테스트 DB는 테스트가 매번 지우는 DB라 잃을 것이 없다):

  ```bash
  docker exec bookshelf-postgres psql -U bookshelf -d bookshelf_test -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
  # 개발 DB(bookshelf)도 bootRun으로 V1이 적용됐다면 같은 명령을 -d bookshelf 로
  ```

- [ ] **Step 2: 실패하는 단위 테스트 — ISBN**

  바코드 스캐너는 ISBN-10을 주기도 하고, 책이 아닌 상품의 EAN-13(880…)도 준다. 서버가 받는 첫 관문이자 "같은 책이 두 번 저장되지 않게"(Review Focus 5)의 첫 줄이다.

  `src/test/java/com/bookshelf/common/Isbn13Test.java`:

  ```java
  package com.bookshelf.common;

  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.CsvSource;
  import org.junit.jupiter.params.provider.ValueSource;

  import java.util.Optional;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  class Isbn13Test {

      @Test
      void 올바른_ISBN13을_받는다() {
          assertThat(Isbn13.parse("9788936434120").value()).isEqualTo("9788936434120");
      }

      @Test
      void 하이픈과_공백을_지운다() {
          assertThat(Isbn13.parse(" 978-89-364-3412-0 ").value()).isEqualTo("9788936434120");
      }

      @Test
      void ISBN10과_ISBN13은_같은_값이_된다() {
          // 89-364-3412-8 (ISBN-10) == 978-89-364-3412-0 — 카카오 실측 응답의 "8936434128 9788936434120"
          assertThat(Isbn13.parse("8936434128")).isEqualTo(Isbn13.parse("9788936434120"));
      }

      @Test
      void ISBN10의_체크숫자_X를_받는다() {
          // 0-8044-2957-X == 978-0-8044-2957-3
          assertThat(Isbn13.parse("080442957x").value()).isEqualTo("9780804429573");
      }

      @ParameterizedTest
      @ValueSource(strings = {
              "9788936434121",   // 체크섬 틀림
              "8801234567893",   // 책이 아닌 상품 바코드 (978/979가 아니다)
              "97889364341",     // 자릿수 부족
              "8936434125",      // ISBN-10 체크섬 틀림
              "abcdefghijklm",
              ""
      })
      void 잘못된_입력은_도메인_예외다(String raw) {
          assertThatThrownBy(() -> Isbn13.parse(raw)).isInstanceOf(DomainException.class);
      }

      @Test
      void null은_도메인_예외다() {
          assertThatThrownBy(() -> Isbn13.parse(null)).isInstanceOf(DomainException.class);
      }

      @Test
      void tryParse는_예외_대신_empty다() {
          assertThat(Isbn13.tryParse("9788936434121")).isEmpty();
          assertThat(Isbn13.tryParse(null)).isEmpty();
          assertThat(Isbn13.tryParse("9788936434120")).map(Isbn13::value).contains("9788936434120");
      }

      @ParameterizedTest
      @CsvSource({
              "9788936434120, ko",   // 978-89 한국
              "9791161571188, ko",   // 979-11 한국
              "9781847941831, en",   // 978-1 영어권
              "9780804429573, en",   // 978-0 영어권
      })
      void ISBN_국가_번호로_언어를_짐작한다(String isbn, String lang) {
          assertThat(Isbn13.parse(isbn).languageHint()).contains(lang);
      }

      @Test
      void 그_밖의_국가_번호는_판단하지_않는다() {
          // 978-4 일본
          assertThat(Isbn13.parse("9784101001616").languageHint()).isEqualTo(Optional.empty());
      }
  }
  ```

- [ ] **Step 3: 실패 확인**

  Run: `./gradlew test --tests Isbn13Test`
  Expected: FAIL — 컴파일 실패 (`Isbn13`, `DomainException` 없음)

- [ ] **Step 4: 구현**

  `src/main/java/com/bookshelf/common/DomainException.java`:

  ```java
  package com.bookshelf.common;

  /** 도메인 규칙 위반. Phase 2에서 API가 생기면 400으로 번역한다. */
  public class DomainException extends RuntimeException {
      public DomainException(String message) {
          super(message);
      }
  }
  ```

  `src/main/java/com/bookshelf/common/Isbn13.java`:

  ```java
  package com.bookshelf.common;

  import java.util.Optional;
  import java.util.regex.Pattern;

  /**
   * 책의 식별자. 생성되었다면 올바른 ISBN-13이다 — 검증을 여기 한 곳에서만 한다.
   * 엔티티는 String으로 저장하고, 경계(스캔·URL·외부 응답)에서 이 타입을 거친다.
   */
  public record Isbn13(String value) {

      private static final Pattern ISBN13 = Pattern.compile("97[89]\\d{10}");
      private static final Pattern ISBN10 = Pattern.compile("\\d{9}[\\dX]");

      public Isbn13 {
          if (value == null || !ISBN13.matcher(value).matches() || !checksum13Ok(value)) {
              throw new DomainException("올바른 ISBN이 아니다: " + value);
          }
      }

      public static Isbn13 parse(String raw) {
          if (raw == null) {
              throw new DomainException("ISBN이 없다");
          }
          String cleaned = raw.replaceAll("[\\s-]", "").toUpperCase();
          if (cleaned.length() == 10) {
              return fromIsbn10(cleaned);
          }
          return new Isbn13(cleaned);
      }

      /** 외부 응답처럼 틀린 값이 섞여 오는 곳에서 쓴다. 틀리면 버린다 */
      public static Optional<Isbn13> tryParse(String raw) {
          try {
              return Optional.of(parse(raw));
          } catch (DomainException e) {
              return Optional.empty();
          }
      }

      /**
       * ISBN 국가 번호로 책의 언어를 짐작한다 (설계 문서 2-1 같은 책 확인).
       * 978-89·979-11은 한국, 978-0·978-1은 영어권. 그 밖은 판단하지 않는다.
       */
      public Optional<String> languageHint() {
          if (value.startsWith("97889") || value.startsWith("97911")) {
              return Optional.of("ko");
          }
          if (value.startsWith("9780") || value.startsWith("9781")) {
              return Optional.of("en");
          }
          return Optional.empty();
      }

      private static Isbn13 fromIsbn10(String isbn10) {
          if (!ISBN10.matcher(isbn10).matches() || !checksum10Ok(isbn10)) {
              throw new DomainException("올바른 ISBN-10이 아니다: " + isbn10);
          }
          String body = "978" + isbn10.substring(0, 9);
          return new Isbn13(body + check13(body));
      }

      private static boolean checksum13Ok(String d) {
          return check13(d.substring(0, 12)) == d.charAt(12) - '0';
      }

      /** 앞 12자리에 1,3,1,3... 가중치를 곱해 더한 합으로 체크 숫자를 구한다 */
      private static int check13(String first12) {
          int sum = 0;
          for (int i = 0; i < 12; i++) {
              int digit = first12.charAt(i) - '0';
              sum += (i % 2 == 0) ? digit : digit * 3;
          }
          return (10 - sum % 10) % 10;
      }

      /** 10,9,8...1 가중치. 마지막 자리 X는 10이다. 합이 11의 배수여야 한다 */
      private static boolean checksum10Ok(String d) {
          int sum = 0;
          for (int i = 0; i < 10; i++) {
              char c = d.charAt(i);
              int digit = (c == 'X') ? 10 : c - '0';
              sum += digit * (10 - i);
          }
          return sum % 11 == 0;
      }
  }
  ```

  Run: `./gradlew test --tests Isbn13Test`
  Expected: PASS 17개 (단일 7 + 파라미터 6 + 파라미터 4)

- [ ] **Step 5: 실패하는 단위 테스트 — 기록·회원·백오프**

  `src/test/java/com/bookshelf/record/domain/ReadingRecordTest.java`:

  ```java
  package com.bookshelf.record.domain;

  import com.bookshelf.common.DomainException;
  import com.bookshelf.common.Isbn13;
  import org.junit.jupiter.api.Test;

  import java.time.Instant;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  class ReadingRecordTest {

      private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
      private static final Isbn13 ISBN = Isbn13.parse("9788936434120");

      private ReadingRecord newRecord() {
          return ReadingRecord.create(1L, ISBN, ReadingStatus.DONE, Visibility.PRIVATE, NOW);
      }

      @Test
      void 만들면_ISBN만으로_존재한다() {
          ReadingRecord r = newRecord();
          assertThat(r.getIsbn13()).isEqualTo("9788936434120");
          assertThat(r.getVisibility()).isEqualTo(Visibility.PRIVATE);
          assertThat(r.getRating()).isNull();
          assertThat(r.getReview()).isNull();
      }

      @Test
      void 별점은_1부터_5까지이고_지울_수_있다() {
          ReadingRecord r = newRecord();
          r.rate(5);
          assertThat(r.getRating()).isEqualTo(5);
          r.rate(null);
          assertThat(r.getRating()).isNull();
          assertThatThrownBy(() -> r.rate(0)).isInstanceOf(DomainException.class);
          assertThatThrownBy(() -> r.rate(6)).isInstanceOf(DomainException.class);
      }

      @Test
      void 한줄평은_앞뒤_공백을_지우고_빈_문자열은_없음으로_본다() {
          ReadingRecord r = newRecord();
          r.writeReview("  좋았다  ", NOW);
          assertThat(r.getReview()).isEqualTo("좋았다");
          r.writeReview("   ", NOW);
          assertThat(r.getReview()).isNull();
      }

      @Test
      void 한줄평은_200자까지다_이모지도_한_글자로_센다() {
          ReadingRecord r = newRecord();
          r.writeReview("📚".repeat(200), NOW);          // String.length()로는 400이다
          assertThat(r.getReview()).hasSize(400);
          assertThatThrownBy(() -> r.writeReview("가".repeat(201), NOW))
                  .isInstanceOf(DomainException.class);
      }

      @Test
      void 공개_범위를_바꾼다() {
          ReadingRecord r = newRecord();
          r.changeVisibility(Visibility.RATING_ONLY, NOW);
          assertThat(r.getVisibility()).isEqualTo(Visibility.RATING_ONLY);
      }
  }
  ```

  DB 컬럼 `VARCHAR(200)`은 PostgreSQL에서 **문자(코드포인트) 수**다. 자바 `String.length()`는 UTF-16 단위라 이모지를 2로 센다. 도메인 검사는 `codePointCount`로 DB와 같은 기준을 쓴다.

  `src/test/java/com/bookshelf/member/domain/MemberTest.java`:

  ```java
  package com.bookshelf.member.domain;

  import com.bookshelf.common.DomainException;
  import com.bookshelf.record.domain.Visibility;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.ValueSource;

  import java.time.Instant;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  class MemberTest {

      private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

      @Test
      void 가입하면_서재는_닫혀_있고_기본_공개범위는_비공개다() {
          Member m = Member.register("juhee", "주희", NOW);
          assertThat(m.isShelfPublic()).isFalse();
          assertThat(m.getDefaultVisibility()).isEqualTo(Visibility.PRIVATE);
          assertThat(m.getDeletedAt()).isNull();
      }

      @ParameterizedTest
      @ValueSource(strings = {"ab", "-juhee", "juhee-", "Juhee", "ju hee", "주희"})
      void slug_규칙을_어기면_거부한다(String slug) {
          assertThatThrownBy(() -> Member.register(slug, "주희", NOW))
                  .isInstanceOf(DomainException.class);
      }

      @Test
      void slug는_30자까지다() {
          assertThat(Member.register("a".repeat(30), "주희", NOW).getSlug()).hasSize(30);
          assertThatThrownBy(() -> Member.register("a".repeat(31), "주희", NOW))
                  .isInstanceOf(DomainException.class);
      }

      @Test
      void 서재를_열고_닫는다() {
          Member m = Member.register("juhee", "주희", NOW);
          m.openShelf();
          assertThat(m.isShelfPublic()).isTrue();
          m.closeShelf();
          assertThat(m.isShelfPublic()).isFalse();
      }

      @Test
      void 탈퇴하면_서재도_닫힌다() {
          Member m = Member.register("juhee", "주희", NOW);
          m.openShelf();
          m.withdraw(NOW);
          assertThat(m.getDeletedAt()).isEqualTo(NOW);
          assertThat(m.isShelfPublic()).isFalse();
      }
  }
  ```

  `src/test/java/com/bookshelf/book/domain/BookLookupFailureTest.java`:

  ```java
  package com.bookshelf.book.domain;

  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.CsvSource;

  import java.time.Duration;

  import static org.assertj.core.api.Assertions.assertThat;

  class BookLookupFailureTest {

      @ParameterizedTest
      @CsvSource({"1, PT1H", "2, PT4H", "3, PT16H", "4, PT64H", "5, P7D", "30, P7D"})
      void 실패가_쌓일수록_늦게_다시_묻되_7일을_넘지_않는다(int attempts, String expected) {
          assertThat(BookLookupFailure.backoff(attempts)).isEqualTo(Duration.parse(expected));
      }
  }
  ```

- [ ] **Step 6: 실패 확인**

  Run: `./gradlew test --tests ReadingRecordTest --tests MemberTest --tests BookLookupFailureTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 7: 구현 — enum과 내 데이터 엔티티**

  `src/main/java/com/bookshelf/record/domain/Visibility.java`:

  ```java
  package com.bookshelf.record.domain;

  /** 설계 문서 7-3. 기본값은 PRIVATE — 초기 버그가 나도 아무것도 새지 않는 쪽 */
  public enum Visibility {
      PUBLIC,        // 표지·별점·한줄평
      RATING_ONLY,   // 서재에 표지·별점만. 책 페이지에는 아무것도 실리지 않는다
      PRIVATE        // 읽었다는 사실조차 숨긴다
  }
  ```

  `src/main/java/com/bookshelf/record/domain/ReadingStatus.java`:

  ```java
  package com.bookshelf.record.domain;

  public enum ReadingStatus {
      WANT, READING, DONE
  }
  ```

  `src/main/java/com/bookshelf/book/domain/SaleStatus.java`:

  ```java
  package com.bookshelf.book.domain;

  /**
   * 새 책 판매 상태 (설계 문서 2-1). 카카오 실측에서 관측된 값은 "정상판매"·"주문판매"·빈 문자열뿐이다.
   * 빈 값을 절판으로 단정하지 않는다 — 모르면 UNKNOWN("판매 정보 없음")이다.
   */
  public enum SaleStatus {
      ON_SALE,   // 판매 중
      ORDER,     // 주문 후 발송
      UNKNOWN    // 판매 정보 없음
  }
  ```

  `src/main/java/com/bookshelf/record/domain/ReadingRecord.java`:

  ```java
  package com.bookshelf.record.domain;

  import com.bookshelf.common.DomainException;
  import com.bookshelf.common.Isbn13;
  import jakarta.persistence.*;
  import lombok.AccessLevel;
  import lombok.Getter;
  import lombok.NoArgsConstructor;

  import java.time.Instant;
  import java.time.LocalDate;

  @Entity
  @Table(name = "reading_record")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  public class ReadingRecord {

      private static final int REVIEW_MAX = 200;

      @Id
      @GeneratedValue(strategy = GenerationType.IDENTITY)
      private Long id;

      private Long memberId;       // Member를 참조하지 않는다 — 도메인 사이에는 ID만

      private String isbn13;       // Book을 참조하지 않는다 — 책 정보가 없어도 기록은 있다

      @Enumerated(EnumType.STRING)
      private ReadingStatus status;

      private Integer rating;

      private String review;

      private LocalDate startedAt;

      private LocalDate finishedAt;

      @Enumerated(EnumType.STRING)
      private Visibility visibility;

      private Instant createdAt;

      private Instant updatedAt;

      public static ReadingRecord create(Long memberId, Isbn13 isbn, ReadingStatus status,
                                         Visibility visibility, Instant now) {
          ReadingRecord r = new ReadingRecord();
          r.memberId = memberId;
          r.isbn13 = isbn.value();
          r.status = status;
          r.visibility = visibility;
          r.createdAt = now;
          r.updatedAt = now;
          return r;
      }

      public void rate(Integer rating) {
          if (rating != null && (rating < 1 || rating > 5)) {
              throw new DomainException("별점은 1~5다: " + rating);
          }
          this.rating = rating;
      }

      public void writeReview(String review, Instant now) {
          String stripped = review == null ? null : review.strip();
          if (stripped != null && stripped.isEmpty()) {
              stripped = null;
          }
          // DB VARCHAR(200)은 코드포인트 수다. String.length()는 이모지를 2로 센다
          if (stripped != null && stripped.codePointCount(0, stripped.length()) > REVIEW_MAX) {
              throw new DomainException("한줄평은 " + REVIEW_MAX + "자까지다");
          }
          this.review = stripped;
          this.updatedAt = now;
      }

      public void changeVisibility(Visibility visibility, Instant now) {
          this.visibility = visibility;
          this.updatedAt = now;
      }
  }
  ```

  `src/main/java/com/bookshelf/member/domain/Member.java`:

  ```java
  package com.bookshelf.member.domain;

  import com.bookshelf.common.DomainException;
  import com.bookshelf.record.domain.Visibility;
  import jakarta.persistence.*;
  import lombok.AccessLevel;
  import lombok.Getter;
  import lombok.NoArgsConstructor;

  import java.time.Instant;
  import java.util.regex.Pattern;

  @Entity
  @Table(name = "member")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  public class Member {

      /** 3~30자, 소문자·숫자·하이픈, 하이픈으로 시작·끝나지 않는다 */
      private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,28}[a-z0-9]");

      @Id
      @GeneratedValue(strategy = GenerationType.IDENTITY)
      private Long id;

      private String slug;

      private String displayName;

      private boolean shelfPublic;

      @Enumerated(EnumType.STRING)
      private Visibility defaultVisibility;

      private Instant createdAt;

      private Instant deletedAt;

      public static Member register(String slug, String displayName, Instant now) {
          if (slug == null || !SLUG.matcher(slug).matches()) {
              throw new DomainException("slug 규칙 위반: " + slug);
          }
          String name = displayName == null ? "" : displayName.strip();
          if (name.isEmpty() || name.codePointCount(0, name.length()) > 30) {
              throw new DomainException("표시 이름은 1~30자다");
          }
          Member m = new Member();
          m.slug = slug;
          m.displayName = name;
          m.shelfPublic = false;
          m.defaultVisibility = Visibility.PRIVATE;
          m.createdAt = now;
          return m;
      }

      public void openShelf() {
          this.shelfPublic = true;
      }

      public void closeShelf() {
          this.shelfPublic = false;
      }

      /** 공개 조회는 deleted_at으로도 거르지만, 스위치까지 내려 두 겹으로 닫는다 */
      public void withdraw(Instant now) {
          this.shelfPublic = false;
          this.deletedAt = now;
      }
  }
  ```

- [ ] **Step 8: 구현 — 남의 데이터 엔티티와 리포지터리**

  `src/main/java/com/bookshelf/book/domain/Book.java`:

  ```java
  package com.bookshelf.book.domain;

  import com.bookshelf.common.Isbn13;
  import jakarta.persistence.Entity;
  import jakarta.persistence.Id;
  import jakarta.persistence.Table;
  import lombok.AccessLevel;
  import lombok.Getter;
  import lombok.NoArgsConstructor;

  import java.time.Instant;
  import java.time.LocalDate;

  /** 제공자 응답의 캐시일 뿐이다. 없어져도 기록은 멀쩡해야 한다 */
  @Entity
  @Table(name = "book")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  public class Book {

      @Id
      private String isbn13;
      private String title;
      private String author;
      private String publisher;
      private LocalDate pubDate;
      private String coverUrl;
      private Integer pageCount;
      private Instant fetchedAt;

      public static Book of(Isbn13 isbn, String title, String author, String publisher,
                            LocalDate pubDate, String coverUrl, Integer pageCount, Instant fetchedAt) {
          Book b = new Book();
          b.isbn13 = isbn.value();
          b.title = title;
          b.author = author;
          b.publisher = publisher;
          b.pubDate = pubDate;
          b.coverUrl = coverUrl;
          b.pageCount = pageCount;
          b.fetchedAt = fetchedAt;
          return b;
      }

      /**
       * 제공자의 새 응답으로 갱신한다. 쪽수는 제공자마다 줄 수도 안 줄 수도 있다(카카오는 없다).
       * 새 응답에 쪽수가 없다고 이미 아는 쪽수를 지우지 않는다.
       */
      public void refreshFrom(Book fetched) {
          this.title = fetched.title;
          this.author = fetched.author;
          this.publisher = fetched.publisher;
          this.pubDate = fetched.pubDate;
          this.coverUrl = fetched.coverUrl;
          if (fetched.pageCount != null) {
              this.pageCount = fetched.pageCount;
          }
          this.fetchedAt = fetched.fetchedAt;
      }
  }
  ```

  `src/main/java/com/bookshelf/book/domain/SaleSnapshot.java`:

  ```java
  package com.bookshelf.book.domain;

  import com.bookshelf.book.domain.SaleStatus;
  import com.bookshelf.common.Isbn13;
  import jakarta.persistence.Entity;
  import jakarta.persistence.EnumType;
  import jakarta.persistence.Enumerated;
  import jakarta.persistence.Id;
  import jakarta.persistence.Table;
  import lombok.AccessLevel;
  import lombok.Getter;
  import lombok.NoArgsConstructor;

  import java.time.Duration;
  import java.time.Instant;

  /** 틀려도 되는 데이터. 대신 fetchedAt을 화면에 반드시 같이 보여준다 */
  @Entity
  @Table(name = "sale_snapshot")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  public class SaleSnapshot {

      @Id
      private String isbn13;
      private Integer listPrice;
      private Integer salePrice;
      @Enumerated(EnumType.STRING)
      private SaleStatus status;
      private Instant fetchedAt;

      public static SaleSnapshot of(Isbn13 isbn, Integer listPrice, Integer salePrice,
                                    SaleStatus status, Instant fetchedAt) {
          SaleSnapshot s = new SaleSnapshot();
          s.isbn13 = isbn.value();
          s.listPrice = listPrice;
          s.salePrice = salePrice;
          s.status = status;
          s.fetchedAt = fetchedAt;
          return s;
      }

      public void replaceWith(SaleSnapshot fetched) {
          this.listPrice = fetched.listPrice;
          this.salePrice = fetched.salePrice;
          this.status = fetched.status;
          this.fetchedAt = fetched.fetchedAt;
      }

      public boolean isFreshAt(Instant now, Duration ttl) {
          return fetchedAt.plus(ttl).isAfter(now);
      }
  }
  ```

  `src/main/java/com/bookshelf/book/domain/BookLookupFailure.java`:

  ```java
  package com.bookshelf.book.domain;

  import com.bookshelf.common.Isbn13;
  import jakarta.persistence.Entity;
  import jakarta.persistence.Id;
  import jakarta.persistence.Table;
  import lombok.AccessLevel;
  import lombok.Getter;
  import lombok.NoArgsConstructor;

  import java.time.Duration;
  import java.time.Instant;

  /**
   * 제공자에서 못 찾은 ISBN. 다음 시도 시각을 1시간 → 4시간 → 16시간 → 64시간 → 7일(상한)로 늘린다.
   * 없으면 백필이 10분마다 같은 ISBN을 물어 예산을 태운다(하루 144번).
   */
  @Entity
  @Table(name = "book_lookup_failure")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  public class BookLookupFailure {

      private static final Duration BASE = Duration.ofHours(1);
      private static final Duration MAX = Duration.ofDays(7);

      @Id
      private String isbn13;
      private int attempts;
      private Instant nextTryAt;

      public static BookLookupFailure first(Isbn13 isbn, Instant now) {
          BookLookupFailure f = new BookLookupFailure();
          f.isbn13 = isbn.value();
          f.attempts = 1;
          f.nextTryAt = now.plus(backoff(1));
          return f;
      }

      public void again(Instant now) {
          this.attempts++;
          this.nextTryAt = now.plus(backoff(attempts));
      }

      static Duration backoff(int attempts) {
          // 4^(n-1)시간. n이 커져도 넘치지 않게 지수를 제한한다
          long hours = 1L << (2 * Math.min(attempts - 1, 10));
          Duration d = BASE.multipliedBy(hours);
          return d.compareTo(MAX) > 0 ? MAX : d;
      }
  }
  ```

  리포지터리 다섯 개:

  ```java
  // src/main/java/com/bookshelf/member/infrastructure/MemberRepository.java
  package com.bookshelf.member.infrastructure;

  import com.bookshelf.member.domain.Member;
  import org.springframework.data.jpa.repository.JpaRepository;

  public interface MemberRepository extends JpaRepository<Member, Long> {
  }
  ```

  ```java
  // src/main/java/com/bookshelf/record/infrastructure/ReadingRecordRepository.java
  package com.bookshelf.record.infrastructure;

  import com.bookshelf.record.domain.ReadingRecord;
  import org.springframework.data.jpa.repository.JpaRepository;

  public interface ReadingRecordRepository extends JpaRepository<ReadingRecord, Long> {
  }
  ```

  ```java
  // src/main/java/com/bookshelf/book/infrastructure/BookRepository.java
  package com.bookshelf.book.infrastructure;

  import com.bookshelf.book.domain.Book;
  import org.springframework.data.jpa.repository.JpaRepository;

  public interface BookRepository extends JpaRepository<Book, String> {
  }
  ```

  ```java
  // src/main/java/com/bookshelf/book/infrastructure/SaleSnapshotRepository.java
  package com.bookshelf.book.infrastructure;

  import com.bookshelf.book.domain.SaleSnapshot;
  import org.springframework.data.jpa.repository.JpaRepository;

  public interface SaleSnapshotRepository extends JpaRepository<SaleSnapshot, String> {
  }
  ```

  ```java
  // src/main/java/com/bookshelf/book/infrastructure/BookLookupFailureRepository.java
  package com.bookshelf.book.infrastructure;

  import com.bookshelf.book.domain.BookLookupFailure;
  import org.springframework.data.jpa.repository.JpaRepository;

  public interface BookLookupFailureRepository extends JpaRepository<BookLookupFailure, String> {
  }
  ```

  Run: `./gradlew test --tests ReadingRecordTest --tests MemberTest --tests BookLookupFailureTest`
  Expected: PASS

- [ ] **Step 9: 테스트 지원 코드**

  `src/test/java/com/bookshelf/support/DbCleaner.java`:

  ```java
  package com.bookshelf.support;

  import org.springframework.jdbc.core.simple.JdbcClient;

  /** 통합 테스트마다 @BeforeEach에서 부른다. 테이블을 추가하면 여기도 추가한다 */
  public final class DbCleaner {

      private DbCleaner() {
      }

      public static void clean(JdbcClient jdbc) {
          jdbc.sql("""
                  TRUNCATE member, reading_record, book, sale_snapshot, book_lookup_failure,
                           refresh_request, search_cache, provider_call_counter
                  RESTART IDENTITY
                  """).update();
      }
  }
  ```

  `src/test/java/com/bookshelf/support/Fixtures.java`:

  ```java
  package com.bookshelf.support;

  import com.bookshelf.book.domain.Book;
  import com.bookshelf.book.infrastructure.BookRepository;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.member.domain.Member;
  import com.bookshelf.member.infrastructure.MemberRepository;
  import com.bookshelf.record.domain.ReadingRecord;
  import com.bookshelf.record.domain.ReadingStatus;
  import com.bookshelf.record.domain.Visibility;
  import com.bookshelf.record.infrastructure.ReadingRecordRepository;
  import org.springframework.boot.test.context.TestComponent;

  import java.time.Instant;
  import java.time.LocalDate;

  /**
   * 통합 테스트용 데이터 생성기. 테스트 클래스에 @Import(Fixtures.class)로 붙인다.
   * @TestComponent는 컴포넌트 스캔에서 빠진다 — 모든 테스트 컨텍스트에 끼어들지 않게 한다.
   */
  @TestComponent
  public class Fixtures {

      public static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

      private final MemberRepository members;
      private final ReadingRecordRepository records;
      private final BookRepository books;

      public Fixtures(MemberRepository members, ReadingRecordRepository records, BookRepository books) {
          this.members = members;
          this.records = records;
          this.books = books;
      }

      public Member member(String slug, boolean shelfPublic) {
          Member m = Member.register(slug, slug + "의 이름", T0);
          if (shelfPublic) {
              m.openShelf();
          }
          return members.save(m);
      }

      public Member withdraw(Member m) {
          m.withdraw(T0);
          return members.save(m);
      }

      public ReadingRecord record(Member m, String isbn, Visibility visibility, Integer rating, String review) {
          ReadingRecord r = ReadingRecord.create(m.getId(), Isbn13.parse(isbn), ReadingStatus.DONE, visibility, T0);
          r.rate(rating);
          r.writeReview(review, T0);
          return records.save(r);
      }

      /** 쪽수 216으로 만든다 — "이미 아는 쪽수를 지우지 않는다" 테스트가 이 값에 기댄다 */
      public Book book(String isbn, String title) {
          return books.save(Book.of(Isbn13.parse(isbn), title, "저자", "출판사",
                  LocalDate.of(2014, 5, 19), "https://image.example/cover.jpg", 216, T0));
      }
  }
  ```

- [ ] **Step 10: 통합 테스트 — 책 없는 기록** (엔티티가 이미 있으므로 바로 통과해야 한다. 실패하면 스키마와 엔티티가 어긋난 것이다)

  `src/test/java/com/bookshelf/record/RecordPersistenceTest.java`:

  ```java
  package com.bookshelf.record;

  import com.bookshelf.book.infrastructure.BookRepository;
  import com.bookshelf.member.domain.Member;
  import com.bookshelf.record.domain.Visibility;
  import com.bookshelf.record.infrastructure.ReadingRecordRepository;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.context.annotation.Import;
  import org.springframework.dao.DataIntegrityViolationException;
  import org.springframework.jdbc.core.simple.JdbcClient;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  @SpringBootTest
  @Import(Fixtures.class)
  class RecordPersistenceTest {

      @Autowired JdbcClient jdbc;
      @Autowired Fixtures fx;
      @Autowired ReadingRecordRepository records;
      @Autowired BookRepository books;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
      }

      @Test
      void 제공자에_없는_책이어도_기록은_저장된다() {
          Member m = fx.member("juhee", false);
          fx.record(m, "9791100000007", Visibility.PRIVATE, 4, "독립출판");   // 카카오 실측: total_count 0

          assertThat(books.count()).isZero();
          assertThat(records.count()).isEqualTo(1);
      }

      @Test
      void 같은_회원이_같은_책을_두_번_기록할_수_없다() {
          Member m = fx.member("juhee", false);
          fx.record(m, "9788936434120", Visibility.PRIVATE, null, null);

          assertThatThrownBy(() -> fx.record(m, "8936434128", Visibility.PUBLIC, null, null))   // 같은 책의 ISBN-10
                  .isInstanceOf(DataIntegrityViolationException.class);
      }

      @Test
      void 기본_공개범위는_DB에도_PRIVATE로_들어간다() {
          fx.member("juhee", false);
          String v = jdbc.sql("SELECT default_visibility FROM member").query(String.class).single();
          assertThat(v).isEqualTo("PRIVATE");
      }
  }
  ```

  Run: `./gradlew test --tests RecordPersistenceTest`
  Expected: PASS 3개. 기동 중 `Schema-validation` 오류가 나면 엔티티 필드와 V1의 컬럼 타입이 어긋난 것이다 — 오류 메시지의 컬럼을 V1에 맞춰 고친다

- [ ] **Step 11: 전체 테스트 후 커밋 (사용자 허용 후)**

  Run: `./gradlew test`
  Expected: 전부 PASS

  허용을 요청할 경로: `src`

  ```bash
  git add src
  git diff --cached --name-only
  git commit -m "feat: 스키마와 도메인 — 기록은 책 없이도 존재한다

  reading_record는 book에 FK가 없다. 제공자에 없는 책을 찍어도 등록 실패가 뜨면 안 된다.
  ISBN 검증은 Isbn13 한 곳에서만 — ISBN-10과 13이 같은 값이 되어 같은 책이 두 번 저장되지 않는다.
  카카오에는 쪽수가 없으므로 Book 갱신은 이미 아는 쪽수를 지우지 않는다."
  ```

---

### Task 3: 제공자별 호출 예산 — 한도를 DB 카운터 하나로 지킨다

설계 문서 6-3: 호출 카운터는 `(날짜, 제공자)` 단위이고, 날짜는 **그 제공자의 한도 초기화 시간대**를 따른다. 호출 직전에 카운터를 원자적으로 올리고, 상한에 닿았으면 올리지 않고 거부한다. 설계 문서 8장 "관측 — 오늘 제공자별로 몇 번 불렀는가"도 같은 장치다.

상한이 둘인 이유: 공개 페이지 갱신 큐와 백필은 **사람이 기다리지 않는 호출**이다. 봇이 책 페이지를 훑어도 카카오 18,000에서 멈추고, 18,000~27,000은 앱 사용자(스캔·검색)만 쓴다.

**Files:**
- Create: `src/main/java/com/bookshelf/provider/Provider.java`, `CallPurpose.java`, `BudgetPolicy.java`, `CallBudget.java`
- Create: `src/main/java/com/bookshelf/provider/ProviderUnavailableException.java`, `BudgetExhaustedException.java`
- Test: `src/test/java/com/bookshelf/provider/CallBudgetTest.java`

**Interfaces:**
- Consumes: `provider_call_counter` 테이블 (Task 2)
- Produces:
  - `enum Provider { KAKAO, LIBRARY, GOOGLE_BOOKS }` — Phase 1에서 쓰는 것은 `KAKAO`
  - `enum CallPurpose { INTERACTIVE, BACKGROUND }`
  - `record BudgetPolicy(Provider provider, int dailyLimit, int backgroundCeiling, ZoneId resetZone)`, `#limitFor(CallPurpose) -> int`
  - `CallBudget#tryAcquire(BudgetPolicy, CallPurpose) -> boolean` (true면 1회 차감됨), `#usedToday(BudgetPolicy) -> int`
  - `ProviderUnavailableException(Provider, String message[, Throwable cause])` (RuntimeException), `#provider()`. 하위 타입 `BudgetExhaustedException(Provider, CallPurpose)`

- [ ] **Step 1: 실패하는 테스트**

  `src/test/java/com/bookshelf/provider/CallBudgetTest.java`:

  ```java
  package com.bookshelf.provider;

  import com.bookshelf.support.DbCleaner;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.jdbc.core.simple.JdbcClient;

  import java.time.Clock;
  import java.time.Instant;
  import java.time.ZoneId;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.concurrent.Callable;
  import java.util.concurrent.ExecutorService;
  import java.util.concurrent.Executors;
  import java.util.concurrent.Future;

  import static com.bookshelf.provider.CallPurpose.BACKGROUND;
  import static com.bookshelf.provider.CallPurpose.INTERACTIVE;
  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest
  class CallBudgetTest {

      private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
      private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
      /** 한국 시각 2026-10-01 23:59 (LA 시각 2026-10-01 07:59) */
      private static final Instant SEOUL_BEFORE_MIDNIGHT = Instant.parse("2026-10-01T14:59:00Z");
      /** 한국 시각 2026-10-02 00:01 (LA 시각 2026-10-01 08:01) */
      private static final Instant SEOUL_AFTER_MIDNIGHT = Instant.parse("2026-10-01T15:01:00Z");

      @Autowired JdbcClient jdbc;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
      }

      private CallBudget budgetAt(Instant now) {
          return new CallBudget(jdbc, Clock.fixed(now, SEOUL));
      }

      private static BudgetPolicy kakao(int dailyLimit, int backgroundCeiling) {
          return new BudgetPolicy(Provider.KAKAO, dailyLimit, backgroundCeiling, SEOUL);
      }

      @Test
      void 상한까지_차감하고_넘으면_거부한다() {
          CallBudget b = budgetAt(SEOUL_BEFORE_MIDNIGHT);
          BudgetPolicy p = kakao(3, 3);
          assertThat(b.tryAcquire(p, INTERACTIVE)).isTrue();
          assertThat(b.tryAcquire(p, INTERACTIVE)).isTrue();
          assertThat(b.tryAcquire(p, INTERACTIVE)).isTrue();
          assertThat(b.tryAcquire(p, INTERACTIVE)).isFalse();
          assertThat(b.usedToday(p)).isEqualTo(3);   // 거부된 요청은 세지 않는다
      }

      @Test
      void 백그라운드는_낮은_상한에서_멈추고_사람의_몫은_남는다() {
          CallBudget b = budgetAt(SEOUL_BEFORE_MIDNIGHT);
          BudgetPolicy p = kakao(4, 2);
          assertThat(b.tryAcquire(p, BACKGROUND)).isTrue();
          assertThat(b.tryAcquire(p, BACKGROUND)).isTrue();
          assertThat(b.tryAcquire(p, BACKGROUND)).isFalse();
          assertThat(b.tryAcquire(p, INTERACTIVE)).isTrue();
          assertThat(b.tryAcquire(p, INTERACTIVE)).isTrue();
          assertThat(b.tryAcquire(p, INTERACTIVE)).isFalse();
      }

      @Test
      void 제공자끼리_카운터가_섞이지_않는다() {
          CallBudget b = budgetAt(SEOUL_BEFORE_MIDNIGHT);
          BudgetPolicy kakao = kakao(1, 1);
          BudgetPolicy library = new BudgetPolicy(Provider.LIBRARY, 1, 1, SEOUL);
          assertThat(b.tryAcquire(kakao, INTERACTIVE)).isTrue();
          assertThat(b.tryAcquire(kakao, INTERACTIVE)).isFalse();
          assertThat(b.tryAcquire(library, INTERACTIVE)).isTrue();   // 카카오가 바닥나도 정보나루는 멀쩡하다
      }

      @Test
      void 그_제공자의_시간대로_자정이_지나면_새로_센다() {
          BudgetPolicy p = kakao(1, 1);
          CallBudget before = budgetAt(SEOUL_BEFORE_MIDNIGHT);
          assertThat(before.tryAcquire(p, INTERACTIVE)).isTrue();
          assertThat(before.tryAcquire(p, INTERACTIVE)).isFalse();

          assertThat(budgetAt(SEOUL_AFTER_MIDNIGHT).tryAcquire(p, INTERACTIVE)).isTrue();
      }

      @Test
      void 초기화_시간대가_다르면_날짜_경계도_다르다() {
          // Google 할당량은 태평양 시간 자정에 초기화된다(가정). 한국 자정을 넘겨도 LA는 아직 같은 날이다
          BudgetPolicy google = new BudgetPolicy(Provider.GOOGLE_BOOKS, 1, 1, LA);
          assertThat(budgetAt(SEOUL_BEFORE_MIDNIGHT).tryAcquire(google, INTERACTIVE)).isTrue();
          assertThat(budgetAt(SEOUL_AFTER_MIDNIGHT).tryAcquire(google, INTERACTIVE)).isFalse();
      }

      @Test
      void 동시에_몰려도_상한을_넘지_않는다() throws Exception {
          CallBudget b = budgetAt(SEOUL_BEFORE_MIDNIGHT);
          BudgetPolicy p = kakao(30, 30);
          ExecutorService pool = Executors.newFixedThreadPool(10);
          List<Callable<Boolean>> tasks = new ArrayList<>();
          for (int i = 0; i < 50; i++) {
              tasks.add(() -> b.tryAcquire(p, INTERACTIVE));
          }
          int granted = 0;
          for (Future<Boolean> f : pool.invokeAll(tasks)) {
              if (f.get()) {
                  granted++;
              }
          }
          pool.shutdown();

          assertThat(granted).isEqualTo(30);
          assertThat(b.usedToday(p)).isEqualTo(30);
      }
  }
  ```

- [ ] **Step 2: 실패 확인**

  Run: `./gradlew test --tests CallBudgetTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 3: 구현**

  `src/main/java/com/bookshelf/provider/Provider.java`:

  ```java
  package com.bookshelf.provider;

  /** 외부 제공자. 설계 문서 6-2의 표와 같다 */
  public enum Provider {
      KAKAO,         // 책 정보·검색·새 책 상태
      LIBRARY,       // 도서관 정보나루 — 빌리기 (Phase 2)
      GOOGLE_BOOKS   // 전자책 가격 (Phase 3)
  }
  ```

  `src/main/java/com/bookshelf/provider/CallPurpose.java`:

  ```java
  package com.bookshelf.provider;

  /** 누가 기다리는가. 예산 상한과 실패 시 동작이 이것으로 갈린다 */
  public enum CallPurpose {
      /** 앱 사용자가 화면 앞에서 기다린다 — 스캔, 검색 */
      INTERACTIVE,
      /** 아무도 기다리지 않는다 — 공개 페이지 갱신 큐, 백필 */
      BACKGROUND
  }
  ```

  `src/main/java/com/bookshelf/provider/BudgetPolicy.java`:

  ```java
  package com.bookshelf.provider;

  import java.time.ZoneId;

  /**
   * 제공자 하나의 예산. 공식 한도의 90%/60% (설계 문서 6-3).
   * resetZone은 그 제공자의 한도 초기화 시간대다 — 우리 서버의 시간대가 아니다.
   */
  public record BudgetPolicy(Provider provider, int dailyLimit, int backgroundCeiling, ZoneId resetZone) {

      public int limitFor(CallPurpose purpose) {
          return purpose == CallPurpose.INTERACTIVE ? dailyLimit : backgroundCeiling;
      }
  }
  ```

  `src/main/java/com/bookshelf/provider/ProviderUnavailableException.java`:

  ```java
  package com.bookshelf.provider;

  /**
   * "지금은 이 제공자에게 물을 수 없다." 원인(타임아웃·5xx·파싱 실패·쿨다운)은 메시지와 cause에만 있다.
   * 호출하는 쪽이 할 일은 원인과 상관없이 같다 — 가진 것으로 보여주고 그 칸만 "확인 불가"다.
   */
  public class ProviderUnavailableException extends RuntimeException {

      private final Provider provider;

      public ProviderUnavailableException(Provider provider, String message) {
          super(provider + ": " + message);
          this.provider = provider;
      }

      public ProviderUnavailableException(Provider provider, String message, Throwable cause) {
          super(provider + ": " + message, cause);
          this.provider = provider;
      }

      public Provider provider() {
          return provider;
      }
  }
  ```

  `src/main/java/com/bookshelf/provider/BudgetExhaustedException.java`:

  ```java
  package com.bookshelf.provider;

  /** 우리가 정한 일일 상한에 닿았다. 백필·갱신 큐는 이것을 받으면 그 회차를 멈춘다 */
  public class BudgetExhaustedException extends ProviderUnavailableException {

      public BudgetExhaustedException(Provider provider, CallPurpose purpose) {
          super(provider, "호출 예산 소진 (" + purpose + ")");
      }
  }
  ```

  `src/main/java/com/bookshelf/provider/CallBudget.java`:

  ```java
  package com.bookshelf.provider;

  import lombok.extern.slf4j.Slf4j;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;

  import java.time.Clock;
  import java.time.LocalDate;
  import java.util.List;

  /**
   * 제공자별 일일 호출 예산.
   *
   * 조회 후 증가(SELECT → 비교 → UPDATE)로 짜면 동시 요청 두 개가 같은 값을 읽고 둘 다 통과한다.
   * 그래서 "상한 미만일 때만 올린다"를 UPDATE 한 문장의 WHERE에 넣는다. 올라갔으면 행이 돌아오고,
   * 상한이라 안 올라갔으면 아무 행도 돌아오지 않는다.
   *
   * @Transactional을 붙이지 않는다. 한 문장이라 자동 커밋으로 충분하고, 호출하는 쪽의 긴 트랜잭션에
   * 끌려 들어가면 카운터 행 락을 오래 쥔다.
   */
  @Slf4j
  @Component
  public class CallBudget {

      private final JdbcClient jdbc;
      private final Clock clock;

      public CallBudget(JdbcClient jdbc, Clock clock) {
          this.jdbc = jdbc;
          this.clock = clock;
      }

      public boolean tryAcquire(BudgetPolicy policy, CallPurpose purpose) {
          int limit = policy.limitFor(purpose);
          List<Integer> after = jdbc.sql("""
                  INSERT INTO provider_call_counter (provider, day, calls) VALUES (:provider, :day, 1)
                  ON CONFLICT (provider, day) DO UPDATE SET calls = provider_call_counter.calls + 1
                  WHERE provider_call_counter.calls < :limit
                  RETURNING calls
                  """)
                  .param("provider", policy.provider().name())
                  .param("day", today(policy))
                  .param("limit", limit)
                  .query(Integer.class)
                  .list();

          if (after.isEmpty()) {
              log.warn("{} 예산 소진 purpose={} limit={}", policy.provider(), purpose, limit);
              return false;
          }
          int calls = after.getFirst();
          if (calls == policy.dailyLimit() * 8 / 10 || calls == policy.backgroundCeiling()) {
              log.warn("{} 호출 {}회 도달 (상한 {})", policy.provider(), calls, policy.dailyLimit());
          }
          return true;
      }

      public int usedToday(BudgetPolicy policy) {
          return jdbc.sql("SELECT calls FROM provider_call_counter WHERE provider = :provider AND day = :day")
                  .param("provider", policy.provider().name())
                  .param("day", today(policy))
                  .query(Integer.class)
                  .optional()
                  .orElse(0);
      }

      private LocalDate today(BudgetPolicy policy) {
          return LocalDate.ofInstant(clock.instant(), policy.resetZone());
      }
  }
  ```

- [ ] **Step 4: 통과 확인**

  Run: `./gradlew test --tests CallBudgetTest`
  Expected: PASS 6개

- [ ] **Step 5: 대조군 — 원자성이 정말 WHERE 덕분인가**

  spring-study 10주차 교훈: **안 나뉘어도 증상이 없다.** `WHERE provider_call_counter.calls < :limit` 줄을 지우고 `동시에_몰려도_상한을_넘지_않는다`만 다시 돌린다.

  Run: `./gradlew test --tests 'CallBudgetTest.동시에*'`
  Expected: FAIL — `granted`가 50. WHERE를 되돌리고 PASS를 다시 확인한다

- [ ] **Step 6: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `src/main/java/com/bookshelf/provider src/test/java/com/bookshelf/provider`

  ```bash
  git add <허용받은 경로>
  git commit -m "feat: 제공자별 호출 예산 — 상한 검사를 UPDATE 한 문장에 넣는다

  조회 후 비교는 동시 요청이 같은 값을 읽고 둘 다 통과한다. 50건 동시 요청에 상한 30 → 정확히 30.
  날짜는 제공자의 한도 초기화 시간대로 센다. 카카오가 바닥나도 다른 제공자 카운터는 멀쩡하다."
  ```

---

### Task 4: 카카오 책 검색 — `BookCatalog`의 첫 구현

**책 정보는 `BookCatalog` 인터페이스를 통해서만 얻는다.** 서비스는 카카오를 모른다. 카카오가 알라딘처럼 닫히면 이 패키지(`catalog/kakao`)만 바꾼다(설계 문서 6-2). 카카오 호출은 전부 `KakaoBookCatalog`를 지난다 — 예산 차감, 타임아웃, 쿨다운, 응답 번역이 한 곳에 있다. 밖으로는 카카오 DTO가 아니라 `CatalogBook`/`CatalogPage`와 `ProviderUnavailableException`만 나간다.

**Files:**
- Create: `src/main/java/com/bookshelf/catalog/BookCatalog.java`, `CatalogBook.java`, `CatalogPage.java`
- Create: `src/main/java/com/bookshelf/catalog/kakao/KakaoProperties.java`, `KakaoRestClientConfig.java`, `KakaoResponse.java`, `KakaoIsbn.java`, `KakaoBookCatalog.java`
- Create: `src/test/resources/kakao/isbn-9788936434120.json`, `isbn-9791100000007.json`, `search-세이노의_가르침.json` (실측 원본)
- Create (test 지원): `src/test/java/com/bookshelf/support/KakaoStub.java`, `support/MutableClock.java`
- Test: `src/test/java/com/bookshelf/catalog/kakao/KakaoIsbnTest.java`
- Test: `src/test/java/com/bookshelf/catalog/kakao/KakaoBookCatalogTest.java`

**Interfaces:**
- Consumes: `CallBudget#tryAcquire(BudgetPolicy, CallPurpose)`, `Isbn13.tryParse`, `SaleStatus`, `Clock`
- Produces:
  - `interface BookCatalog { Optional<CatalogBook> lookup(Isbn13, CallPurpose); CatalogPage search(String query, int page, CallPurpose); }` — 제공자가 모르는 책이면 empty, 물을 수 없으면 `ProviderUnavailableException`(예산 소진은 하위 타입 `BudgetExhaustedException`)
  - `record CatalogBook(Isbn13 isbn, String title, String author, String publisher, LocalDate pubDate, String coverUrl, Integer pageCount, Integer listPrice, Integer salePrice, SaleStatus saleStatus)`
  - `record CatalogPage(List<CatalogBook> books, int total, boolean isEnd)`
  - `BookCatalog.PAGE_SIZE = 10`
  - `record KakaoProperties(String restApiKey, String baseUrl, Duration connectTimeout, Duration readTimeout, int dailyLimit, int backgroundCeiling, ZoneId resetZone, Duration coolDown)` — prefix `providers.kakao`, `#policy() -> BudgetPolicy`
  - `KakaoIsbn.parse(String raw) -> Optional<Isbn13>`
  - 테스트 지원: `KakaoStub.baseUrl()`, `server()`, `reset()`, `returns(query, body)`, `failsWith(status)`, `delays(millis, body)`, `calls()`, `doc(isbnField, title, price, salePrice, status)`, `response(total, isEnd, docs...)`, `fixture(name)`; `MutableClock(Instant)`, `#advance(Duration)`, `#set(Instant)`

- [ ] **Step 1: 실측 원본을 fixture로 옮긴다**

  카카오 실측(`docs/notes/providers.md`)에서 저장한 원본을 쓴다. 원본은 세션 스크래치 디렉터리에 있었다. 없으면 다시 받는다(호출 3회):

  ```bash
  mkdir -p src/test/resources/kakao
  K=$(grep -E '^KAKAO_REST_API_KEY=' .env | cut -d= -f2-)
  get() { curl -sS -G "https://dapi.kakao.com/v3/search/book" -H "Authorization: KakaoAK $K" "$@"; }
  get --data-urlencode "query=9788936434120" -d target=isbn -d size=5 > src/test/resources/kakao/isbn-9788936434120.json
  get --data-urlencode "query=9791100000007" -d target=isbn -d size=5 > src/test/resources/kakao/isbn-9791100000007.json
  get --data-urlencode "query=세이노의 가르침" -d target=title -d size=10 > src/test/resources/kakao/search-세이노의_가르침.json
  grep -c "$K" src/test/resources/kakao/*.json      # 모두 0 — 응답에 키가 들어가지 않는다
  python3 -m json.tool src/test/resources/kakao/search-세이노의_가르침.json | grep -E '"(isbn|price|sale_price|status)"'
  ```

  Expected: 마지막 명령에서 `"isbn": " 9791168478688"`(앞 공백), `"price": 0`, `"sale_price": -1`인 항목(별책부록)이 보인다. 이 세 가지가 Step 4 테스트의 근거다. 응답이 바뀌어 없으면 테스트는 합성 데이터(`KakaoStub.doc`)로 같은 경우를 덮으므로 그대로 진행한다

- [ ] **Step 2: 테스트 지원 코드**

  `src/test/java/com/bookshelf/support/MutableClock.java`:

  ```java
  package com.bookshelf.support;

  import java.time.Clock;
  import java.time.Duration;
  import java.time.Instant;
  import java.time.ZoneId;

  /** 테스트에서 시간을 움직인다. TTL·쿨다운·백오프 검증용 */
  public class MutableClock extends Clock {

      private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
      private Instant now;

      public MutableClock(Instant now) {
          this.now = now;
      }

      public void advance(Duration d) {
          now = now.plus(d);
      }

      public void set(Instant instant) {
          now = instant;
      }

      @Override
      public ZoneId getZone() {
          return SEOUL;
      }

      @Override
      public Clock withZone(ZoneId zone) {
          throw new UnsupportedOperationException();
      }

      @Override
      public Instant instant() {
          return now;
      }
  }
  ```

  `src/test/java/com/bookshelf/support/KakaoStub.java`:

  ```java
  package com.bookshelf.support;

  import com.github.tomakehurst.wiremock.WireMockServer;

  import java.io.IOException;
  import java.io.UncheckedIOException;
  import java.nio.file.Files;
  import java.nio.file.Path;

  import static com.github.tomakehurst.wiremock.client.WireMock.*;
  import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

  /**
   * 가짜 카카오. JVM 하나에 서버 하나를 띄워 모든 통합 테스트가 같이 쓴다.
   * 스프링 테스트는 @DynamicPropertySource로 providers.kakao.base-url을 baseUrl()로 바꾼다.
   */
  public final class KakaoStub {

      private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());
      private static final String PATH = "/v3/search/book";

      static {
          SERVER.start();
      }

      private KakaoStub() {
      }

      public static String baseUrl() {
          return SERVER.baseUrl();
      }

      /** 이 클래스에 없는 스텁·검증이 필요할 때 */
      public static WireMockServer server() {
          return SERVER;
      }

      public static void reset() {
          SERVER.resetAll();
      }

      /** query 파라미터가 정확히 이 값일 때 */
      public static void returns(String query, String body) {
          SERVER.stubFor(get(urlPathEqualTo(PATH)).withQueryParam("query", equalTo(query))
                  .willReturn(okJson(body)));
      }

      public static void failsWith(int status) {
          SERVER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                  .withBody("{\"errorType\":\"InternalError\",\"message\":\"stub\"}")));
      }

      public static void delays(int millis, String body) {
          SERVER.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(body).withFixedDelay(millis)));
      }

      public static int calls() {
          return SERVER.findAll(getRequestedFor(urlPathEqualTo(PATH))).size();
      }

      /** 카카오 documents 항목 하나. isbnField는 카카오 원형 그대로("10자리 13자리", "13자리", " 13자리" …) */
      public static String doc(String isbnField, String title, int price, int salePrice, String status) {
          return """
                  {"title":"%s","isbn":"%s","authors":["저자"],"translators":[],"publisher":"출판사",
                   "datetime":"2014-05-19T00:00:00.000+09:00","price":%d,"sale_price":%d,"status":"%s",
                   "thumbnail":"https://search1.kakaocdn.net/thumb/cover.jpg","contents":"","url":"https://search.daum.net/x"}
                  """.formatted(title, isbnField, price, salePrice, status);
      }

      public static String response(int total, boolean isEnd, String... docs) {
          return """
                  {"meta":{"total_count":%d,"pageable_count":%d,"is_end":%b},"documents":[%s]}
                  """.formatted(total, total, isEnd, String.join(",", docs));
      }

      /** src/test/resources/kakao/ 의 실측 원본 */
      public static String fixture(String name) {
          try {
              return Files.readString(Path.of("src/test/resources/kakao", name));
          } catch (IOException e) {
              throw new UncheckedIOException(e);
          }
      }
  }
  ```

- [ ] **Step 3: 실패하는 단위 테스트 — 카카오 ISBN 필드**

  `src/test/java/com/bookshelf/catalog/kakao/KakaoIsbnTest.java`:

  ```java
  package com.bookshelf.catalog.kakao;

  import com.bookshelf.common.Isbn13;
  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.CsvSource;
  import org.junit.jupiter.params.provider.ValueSource;

  import static org.assertj.core.api.Assertions.assertThat;

  /** 카카오 isbn 필드는 실측에서 세 가지 모양으로 왔다 (docs/notes/providers.md) */
  class KakaoIsbnTest {

      @ParameterizedTest
      // 앞 공백을 지키려면 ignoreLeadingAndTrailingWhitespace = false (기본값은 공백을 자른다)
      @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
              "8936434128 9788936434120|9788936434120",   // 10자리 13자리
              "9791168473690|9791168473690",              // 13자리만
              " 9791168478688|9791168478688",             // 앞에 공백
              "8936434128|9788936434120",                 // 10자리만 → 13자리로
              "8936434128 9788936434121|9788936434120",   // 13자리가 틀리면 10자리로
      })
      void 모양이_달라도_13자리_하나로_옮긴다(String raw, String expected) {
          assertThat(KakaoIsbn.parse(raw)).map(Isbn13::value).contains(expected);
      }

      @ParameterizedTest
      @ValueSource(strings = {"", "   ", "9788936434121", "abc"})
      void 쓸_수_있는_ISBN이_없으면_empty(String raw) {
          assertThat(KakaoIsbn.parse(raw)).isEmpty();
      }
  }
  ```

  Run: `./gradlew test --tests KakaoIsbnTest`
  Expected: FAIL — 컴파일 실패

  `src/main/java/com/bookshelf/catalog/kakao/KakaoIsbn.java`:

  ```java
  package com.bookshelf.catalog.kakao;

  import com.bookshelf.common.Isbn13;

  import java.util.Arrays;
  import java.util.List;
  import java.util.Optional;

  /**
   * 카카오 isbn 필드 → 13자리 하나 (설계 문서 5장 ISBN 정규화).
   * 공백으로 나눠 13자리가 있으면 그것을, 없거나 틀리면 10자리를 13자리로 바꿔 쓴다. 체크섬이 틀리면 버린다.
   */
  final class KakaoIsbn {

      private KakaoIsbn() {
      }

      static Optional<Isbn13> parse(String raw) {
          if (raw == null) {
              return Optional.empty();
          }
          List<String> parts = Arrays.stream(raw.strip().split("\\s+")).filter(s -> !s.isEmpty()).toList();
          Optional<Isbn13> thirteen = parts.stream().filter(s -> s.length() == 13)
                  .map(Isbn13::tryParse).flatMap(Optional::stream).findFirst();
          if (thirteen.isPresent()) {
              return thirteen;
          }
          return parts.stream().filter(s -> s.length() == 10)
                  .map(Isbn13::tryParse).flatMap(Optional::stream).findFirst();
      }
  }
  ```

  Run: `./gradlew test --tests KakaoIsbnTest`
  Expected: PASS 9개

- [ ] **Step 4: 실패하는 통합 테스트 — 클라이언트**

  `src/test/java/com/bookshelf/catalog/kakao/KakaoBookCatalogTest.java`:

  ```java
  package com.bookshelf.catalog.kakao;

  import com.bookshelf.catalog.CatalogBook;
  import com.bookshelf.catalog.CatalogPage;
  import com.bookshelf.book.domain.SaleStatus;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.provider.BudgetExhaustedException;
  import com.bookshelf.provider.CallBudget;
  import com.bookshelf.provider.ProviderUnavailableException;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.KakaoStub;
  import com.bookshelf.support.MutableClock;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.CsvSource;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.web.client.RestClient;

  import java.time.Duration;
  import java.time.Instant;
  import java.time.LocalDate;
  import java.time.ZoneId;

  import static com.bookshelf.provider.CallPurpose.BACKGROUND;
  import static com.bookshelf.provider.CallPurpose.INTERACTIVE;
  import static com.bookshelf.support.KakaoStub.doc;
  import static com.bookshelf.support.KakaoStub.response;
  import static com.github.tomakehurst.wiremock.client.WireMock.*;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  /**
   * 스프링 컨텍스트는 JdbcClient(예산 카운터)를 얻는 데만 쓴다.
   * 클라이언트는 테스트마다 직접 조립한다 — 시계·타임아웃·예산·쿨다운을 테스트가 정해야 하기 때문이다.
   */
  @SpringBootTest
  class KakaoBookCatalogTest {

      private static final Isbn13 ISBN = Isbn13.parse("9788936434120");
      private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");

      @Autowired JdbcClient jdbc;

      private MutableClock clock;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          KakaoStub.reset();
          clock = new MutableClock(T0);
      }

      private KakaoBookCatalog catalog(int dailyLimit, Duration readTimeout) {
          KakaoProperties props = new KakaoProperties("test-key", KakaoStub.baseUrl(), Duration.ofSeconds(1),
                  readTimeout, dailyLimit, dailyLimit, ZoneId.of("Asia/Seoul"), Duration.ofSeconds(60));
          RestClient rest = new KakaoRestClientConfig().kakaoRestClient(props, RestClient.builder());
          return new KakaoBookCatalog(rest, new CallBudget(jdbc, clock), props, clock);
      }

      private KakaoBookCatalog catalog() {
          return catalog(100, Duration.ofSeconds(2));
      }

      @Test
      void 실제_ISBN_응답을_옮긴다() {
          KakaoStub.returns(ISBN.value(), KakaoStub.fixture("isbn-9788936434120.json"));

          CatalogBook b = catalog().lookup(ISBN, INTERACTIVE).orElseThrow();

          assertThat(b.isbn()).isEqualTo(ISBN);
          assertThat(b.title()).isEqualTo("소년이 온다");
          assertThat(b.author()).isEqualTo("한강");
          assertThat(b.publisher()).isEqualTo("창비");
          assertThat(b.pubDate()).isEqualTo(LocalDate.of(2014, 5, 19));
          assertThat(b.listPrice()).isPositive();
          assertThat(b.salePrice()).isPositive();
          assertThat(b.saleStatus()).isEqualTo(SaleStatus.ON_SALE);
          assertThat(b.coverUrl()).startsWith("https://");
          assertThat(b.pageCount()).isNull();          // 카카오에는 쪽수가 없다
      }

      @Test
      void 요청에_인증_헤더와_ISBN_검색_조건을_싣는다() {
          KakaoStub.returns(ISBN.value(), KakaoStub.fixture("isbn-9788936434120.json"));

          catalog().lookup(ISBN, INTERACTIVE);

          KakaoStub.server().verify(getRequestedFor(urlPathEqualTo("/v3/search/book"))
                  .withHeader("Authorization", equalTo("KakaoAK test-key"))
                  .withQueryParam("target", equalTo("isbn"))
                  .withQueryParam("query", equalTo(ISBN.value())));
      }

      @Test
      void 모르는_책이면_empty다() {
          Isbn13 unknown = Isbn13.parse("9791100000007");
          KakaoStub.returns(unknown.value(), KakaoStub.fixture("isbn-9791100000007.json"));

          assertThat(catalog().lookup(unknown, INTERACTIVE)).isEmpty();
      }

      @Test
      void ISBN_조회_결과에_다른_책만_오면_empty다() {
          KakaoStub.returns(ISBN.value(), response(1, true, doc("9791168473690", "다른 책", 1000, 900, "정상판매")));

          assertThat(catalog().lookup(ISBN, INTERACTIVE)).isEmpty();
      }

      @Test
      void 실제_검색_응답의_가격_없음을_null로_옮긴다() {
          KakaoStub.returns("세이노의 가르침", KakaoStub.fixture("search-세이노의_가르침.json"));

          CatalogPage page = catalog().search("세이노의 가르침", 1, INTERACTIVE);

          assertThat(page.books()).isNotEmpty();
          assertThat(page.books()).allSatisfy(b -> {
              assertThat(b.listPrice()).satisfiesAnyOf(p -> assertThat(p).isNull(), p -> assertThat(p).isPositive());
              assertThat(b.salePrice()).satisfiesAnyOf(p -> assertThat(p).isNull(), p -> assertThat(p).isPositive());
          });
      }

      @Test
      void ISBN_필드의_세_가지_모양을_모두_옮긴다() {
          KakaoStub.returns("모양", response(3, true,
                  doc("8936434128 9788936434120", "10과 13", 1000, 900, "정상판매"),
                  doc("9791168473690", "13만", 1000, 900, "정상판매"),
                  doc(" 9791168478688", "앞 공백", 1000, 900, "정상판매")));

          CatalogPage page = catalog().search("모양", 1, INTERACTIVE);

          assertThat(page.books()).extracting(b -> b.isbn().value())
                  .containsExactly("9788936434120", "9791168473690", "9791168478688");
      }

      @Test
      void ISBN이_없거나_틀린_항목과_제목이_없는_항목만_건너뛴다() {
          KakaoStub.returns("건너뛰기", response(4, true,
                  doc("", "ISBN 없음", 1000, 900, "정상판매"),
                  doc("9788936434121", "체크섬 틀림", 1000, 900, "정상판매"),
                  doc("9791168473690", "", 1000, 900, "정상판매"),
                  doc("9788936434120", "소년이 온다", 15000, 13500, "정상판매")));

          assertThat(catalog().search("건너뛰기", 1, INTERACTIVE).books())
                  .extracting(CatalogBook::title).containsExactly("소년이 온다");
      }

      @Test
      void 가격이_0이나_음수면_null이다() {
          KakaoStub.returns(ISBN.value(), response(1, true, doc(ISBN.value(), "별책부록", 0, -1, "정상판매")));

          CatalogBook b = catalog().lookup(ISBN, INTERACTIVE).orElseThrow();

          assertThat(b.listPrice()).isNull();
          assertThat(b.salePrice()).isNull();
      }

      @ParameterizedTest
      @CsvSource(delimiter = '|', value = {"정상판매|ON_SALE", "주문판매|ORDER", "|UNKNOWN", "품절|UNKNOWN"})
      void 판매_상태를_옮기고_모르는_값은_판매_정보_없음이다(String kakaoStatus, SaleStatus expected) {
          String status = kakaoStatus == null ? "" : kakaoStatus;
          KakaoStub.returns(ISBN.value(), response(1, true, doc(ISBN.value(), "책", 1000, 900, status)));

          assertThat(catalog().lookup(ISBN, INTERACTIVE).orElseThrow().saleStatus()).isEqualTo(expected);
      }

      @Test
      void 검색_결과_안의_같은_책은_하나만_남긴다() {
          KakaoStub.returns("중복", response(2, true,
                  doc("8936434128 9788936434120", "소년이 온다", 15000, 13500, "정상판매"),
                  doc("8936434128", "소년이 온다(다른 항목)", 15000, 13500, "정상판매")));

          assertThat(catalog().search("중복", 1, INTERACTIVE).books()).hasSize(1);
      }

      @Test
      void 검색은_10권씩_요청하고_페이지를_넘긴다() {
          KakaoStub.returns("소년", response(0, true));

          catalog().search("소년", 3, INTERACTIVE);

          KakaoStub.server().verify(getRequestedFor(urlPathEqualTo("/v3/search/book"))
                  .withQueryParam("size", equalTo("10"))
                  .withQueryParam("page", equalTo("3")));
      }

      @Test
      void 검색어의_특수문자를_그대로_전달한다() {
          String query = "C++ & {자바} 100%";
          KakaoStub.returns(query, response(0, true));

          catalog().search(query, 1, INTERACTIVE);

          assertThat(KakaoStub.calls()).isEqualTo(1);   // query 파라미터가 정확히 일치해야 스텁에 걸린다
      }

      @Test
      void 서버_오류는_장애로_번역한다() {
          KakaoStub.failsWith(503);

          assertThatThrownBy(() -> catalog().lookup(ISBN, INTERACTIVE))
                  .isInstanceOf(ProviderUnavailableException.class);
      }

      @Test
      void 응답이_늦으면_읽기_타임아웃으로_끊는다() {
          KakaoStub.delays(1500, response(0, true));
          long start = System.nanoTime();

          assertThatThrownBy(() -> catalog(100, Duration.ofMillis(300)).lookup(ISBN, INTERACTIVE))
                  .isInstanceOf(ProviderUnavailableException.class);
          assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1200));
      }

      @Test
      void 예산이_없으면_요청을_보내지_않는다() {
          KakaoStub.returns(ISBN.value(), response(0, true));
          KakaoBookCatalog c = catalog(1, Duration.ofSeconds(2));
          c.lookup(ISBN, INTERACTIVE);

          assertThatThrownBy(() -> c.lookup(ISBN, INTERACTIVE)).isInstanceOf(BudgetExhaustedException.class);
          assertThat(KakaoStub.calls()).isEqualTo(1);
      }

      @Test
      void 실패_후_쿨다운_동안_백그라운드_호출은_보내지_않는다() {
          KakaoBookCatalog c = catalog();
          KakaoStub.failsWith(503);
          assertThatThrownBy(() -> c.lookup(ISBN, BACKGROUND)).isInstanceOf(ProviderUnavailableException.class);
          assertThat(KakaoStub.calls()).isEqualTo(1);

          // 쿨다운 중: 백그라운드는 즉시 포기한다 (요청도, 예산 차감도 없다)
          assertThatThrownBy(() -> c.lookup(ISBN, BACKGROUND)).isInstanceOf(ProviderUnavailableException.class);
          assertThat(KakaoStub.calls()).isEqualTo(1);

          // 사람이 기다리는 호출은 한 번 시도한다
          assertThatThrownBy(() -> c.lookup(ISBN, INTERACTIVE)).isInstanceOf(ProviderUnavailableException.class);
          assertThat(KakaoStub.calls()).isEqualTo(2);

          // 쿨다운이 지나면 백그라운드도 다시 보낸다
          clock.advance(Duration.ofSeconds(61));
          KakaoStub.reset();
          KakaoStub.returns(ISBN.value(), response(0, true));
          assertThat(c.lookup(ISBN, BACKGROUND)).isEmpty();
          assertThat(KakaoStub.calls()).isEqualTo(1);
      }
  }
  ```

  `판매_상태를…` 테스트의 두 번째 행(`|UNKNOWN`)은 JUnit이 빈 칸을 `null`로 넘긴다. 테스트가 `null`을 빈 문자열로 바꿔 카카오의 빈 `status`를 흉내 낸다.

- [ ] **Step 5: 실패 확인**

  Run: `./gradlew test --tests KakaoBookCatalogTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 6: 구현 — 인터페이스와 타입**

  `src/main/java/com/bookshelf/catalog/BookCatalog.java`:

  ```java
  package com.bookshelf.catalog;

  import com.bookshelf.common.Isbn13;
  import com.bookshelf.provider.CallPurpose;

  import java.util.Optional;

  /**
   * 책 정보의 유일한 입구 (설계 문서 6-2). 서비스는 이 인터페이스만 안다 — 제공자를 바꾸면 구현만 바꾼다.
   *
   * lookup: 제공자가 모르는 책이면 empty.
   * 둘 다: 물을 수 없으면 ProviderUnavailableException (예산 소진은 하위 타입 BudgetExhaustedException).
   */
  public interface BookCatalog {

      int PAGE_SIZE = 10;   // 휴대폰 한 화면이 5~6권이다 (설계 문서 2-1)

      Optional<CatalogBook> lookup(Isbn13 isbn, CallPurpose purpose);

      CatalogPage search(String query, int page, CallPurpose purpose);
  }
  ```

  `src/main/java/com/bookshelf/catalog/CatalogBook.java`:

  ```java
  package com.bookshelf.catalog;

  import com.bookshelf.book.domain.SaleStatus;
  import com.bookshelf.common.Isbn13;

  import java.time.LocalDate;

  /** 제공자 응답을 우리 말로 옮긴 것. 가격이 없으면 null (-1·0을 담지 않는다) */
  public record CatalogBook(
          Isbn13 isbn,
          String title,
          String author,
          String publisher,
          LocalDate pubDate,
          String coverUrl,
          Integer pageCount,
          Integer listPrice,
          Integer salePrice,
          SaleStatus saleStatus
  ) {
  }
  ```

  `src/main/java/com/bookshelf/catalog/CatalogPage.java`:

  ```java
  package com.bookshelf.catalog;

  import java.util.List;

  public record CatalogPage(List<CatalogBook> books, int total, boolean isEnd) {
  }
  ```

  `src/main/java/com/bookshelf/catalog/kakao/KakaoProperties.java`:

  ```java
  package com.bookshelf.catalog.kakao;

  import com.bookshelf.provider.BudgetPolicy;
  import com.bookshelf.provider.Provider;
  import org.springframework.boot.context.properties.ConfigurationProperties;

  import java.time.Duration;
  import java.time.ZoneId;

  @ConfigurationProperties("providers.kakao")
  public record KakaoProperties(
          String restApiKey,
          String baseUrl,
          Duration connectTimeout,
          Duration readTimeout,
          int dailyLimit,          // 사람이 기다리는 호출의 상한 (27,000)
          int backgroundCeiling,   // 사람이 기다리지 않는 호출의 상한 (18,000)
          ZoneId resetZone,        // 한도 초기화 시간대
          Duration coolDown        // 실패 후 백그라운드 호출을 쉬는 시간
  ) {

      public BudgetPolicy policy() {
          return new BudgetPolicy(Provider.KAKAO, dailyLimit, backgroundCeiling, resetZone);
      }
  }
  ```

  `src/main/java/com/bookshelf/catalog/kakao/KakaoResponse.java`:

  ```java
  package com.bookshelf.catalog.kakao;

  import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
  import com.fasterxml.jackson.annotation.JsonProperty;

  import java.util.List;

  /** 카카오 응답 원형. 이 패키지 밖에서 쓰지 않는다. 안 쓰는 필드(contents, url, translators)는 두지 않는다 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record KakaoResponse(Meta meta, List<Document> documents) {

      @JsonIgnoreProperties(ignoreUnknown = true)
      record Meta(@JsonProperty("total_count") int totalCount,
                  @JsonProperty("pageable_count") int pageableCount,
                  @JsonProperty("is_end") boolean isEnd) {
      }

      @JsonIgnoreProperties(ignoreUnknown = true)
      record Document(String title,
                      String isbn,
                      List<String> authors,
                      String publisher,
                      String datetime,
                      Integer price,
                      @JsonProperty("sale_price") Integer salePrice,
                      String status,
                      String thumbnail) {
      }
  }
  ```

- [ ] **Step 7: 구현 — 조립과 클라이언트**

  `src/main/java/com/bookshelf/catalog/kakao/KakaoRestClientConfig.java`:

  ```java
  package com.bookshelf.catalog.kakao;

  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.http.client.JdkClientHttpRequestFactory;
  import org.springframework.web.client.RestClient;

  import java.net.http.HttpClient;

  /**
   * 카카오 전용 RestClient. 타임아웃을 전역 빌더에 걸지 않는다(제공자마다 값이 다르다 — 설계 문서 6-2).
   *
   * HTTP/1.1 고정: JDK HttpClient는 HTTP/2를 먼저 시도하는데 WireMock이 받지 못해
   * 요청이 서버에 닿지도 않는다. 카카오도 HTTP/1.1로 충분하다.
   */
  @Configuration
  public class KakaoRestClientConfig {

      @Bean
      RestClient kakaoRestClient(KakaoProperties props, RestClient.Builder builder) {
          HttpClient http = HttpClient.newBuilder()
                  .version(HttpClient.Version.HTTP_1_1)
                  .connectTimeout(props.connectTimeout())
                  .build();
          JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
          factory.setReadTimeout(props.readTimeout());
          return builder
                  .baseUrl(props.baseUrl())
                  .defaultHeader("Authorization", "KakaoAK " + props.restApiKey())
                  .requestFactory(factory)
                  .build();
      }
  }
  ```

  `src/main/java/com/bookshelf/catalog/kakao/KakaoBookCatalog.java`:

  ```java
  package com.bookshelf.catalog.kakao;

  import com.bookshelf.catalog.BookCatalog;
  import com.bookshelf.catalog.CatalogBook;
  import com.bookshelf.catalog.CatalogPage;
  import com.bookshelf.book.domain.SaleStatus;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.provider.BudgetExhaustedException;
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
  import java.time.LocalDate;
  import java.time.format.DateTimeParseException;
  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.Optional;
  import java.util.function.Function;

  /**
   * 카카오 호출의 유일한 입구.
   *
   * 순서가 의미를 가진다: 쿨다운 → 예산 → HTTP → 번역.
   * 쿨다운을 예산보다 먼저 보는 이유: 보내지도 않을 호출로 예산을 깎지 않는다.
   * 실패한 호출도 예산에서 빠진다: 제공자는 우리가 보낸 요청을 세지, 성공만 세지 않는다고 가정한다.
   */
  @Slf4j
  @Component
  public class KakaoBookCatalog implements BookCatalog {

      private static final JsonMapper JSON = JsonMapper.builder().build();

      private final RestClient rest;
      private final CallBudget budget;
      private final KakaoProperties props;
      private final Clock clock;

      /** 마지막 실패 후 이 시각까지 백그라운드 호출을 보내지 않는다 — 카카오가 죽었을 때 매번 타임아웃을 기다리지 않게 */
      private volatile Instant coolDownUntil = Instant.MIN;

      public KakaoBookCatalog(RestClient kakaoRestClient, CallBudget budget, KakaoProperties props, Clock clock) {
          this.rest = kakaoRestClient;
          this.budget = budget;
          this.props = props;
          this.clock = clock;
      }

      @Override
      public Optional<CatalogBook> lookup(Isbn13 isbn, CallPurpose purpose) {
          KakaoResponse r = call(purpose, b -> b.path("/v3/search/book")
                  .queryParam("target", "isbn")
                  .queryParam("query", "{q}")
                  .queryParam("size", 5)
                  .build(Map.of("q", isbn.value())));
          // ISBN으로 물었어도 다른 책이 섞여 오면 버린다 — 요청한 ISBN과 같은 항목만
          return toBooks(r).stream().filter(b -> b.isbn().equals(isbn)).findFirst();
      }

      @Override
      public CatalogPage search(String query, int page, CallPurpose purpose) {
          // 검색어는 URI 변수로 넘긴다. 문자열로 붙이면 &, +, {} 가 파라미터 구조를 깨뜨린다
          KakaoResponse r = call(purpose, b -> b.path("/v3/search/book")
                  .queryParam("query", "{q}")
                  .queryParam("size", PAGE_SIZE)
                  .queryParam("page", page)
                  .queryParam("sort", "accuracy")
                  .build(Map.of("q", query)));
          int total = r.meta() == null ? 0 : r.meta().pageableCount();
          boolean isEnd = r.meta() == null || r.meta().isEnd();
          return new CatalogPage(toBooks(r), total, isEnd);
      }

      private KakaoResponse call(CallPurpose purpose, Function<UriBuilder, URI> uri) {
          if (purpose == CallPurpose.BACKGROUND && clock.instant().isBefore(coolDownUntil)) {
              throw new ProviderUnavailableException(Provider.KAKAO, "쿨다운 중 (" + coolDownUntil + "까지)");
          }
          if (!budget.tryAcquire(props.policy(), purpose)) {
              throw new BudgetExhaustedException(Provider.KAKAO, purpose);
          }
          try {
              byte[] body = rest.get().uri(uri).retrieve().body(byte[].class);
              return JSON.readValue(body, KakaoResponse.class);
          } catch (RestClientException | JacksonException e) {
              coolDownUntil = clock.instant().plus(props.coolDown());
              throw new ProviderUnavailableException(Provider.KAKAO, "호출 실패: " + e.getMessage(), e);
          }
      }

      /** 같은 ISBN이 두 번 오면 먼저 온 것만 남긴다(같은 책이 두 번 저장되지 않게). 순서는 카카오의 정확도 순서 그대로 */
      private List<CatalogBook> toBooks(KakaoResponse r) {
          Map<Isbn13, CatalogBook> byIsbn = new LinkedHashMap<>();
          if (r.documents() == null) {
              return List.of();
          }
          for (KakaoResponse.Document d : r.documents()) {
              Optional<Isbn13> isbn = KakaoIsbn.parse(d.isbn());
              if (isbn.isEmpty() || d.title() == null || d.title().isBlank()) {
                  log.debug("ISBN·제목이 없는 카카오 항목을 건너뛴다: {}", d.title());
                  continue;
              }
              byIsbn.putIfAbsent(isbn.get(), new CatalogBook(
                      isbn.get(),
                      d.title(),
                      d.authors() == null || d.authors().isEmpty() ? null : String.join(", ", d.authors()),
                      blankToNull(d.publisher()),
                      parseDate(d.datetime()),
                      blankToNull(d.thumbnail()),
                      null,                       // 카카오에는 쪽수가 없다
                      positiveOrNull(d.price()),
                      positiveOrNull(d.salePrice()),
                      saleStatus(d.status())));
          }
          return List.copyOf(byIsbn.values());
      }

      /** 실측 값은 "정상판매"·"주문판매"·빈 문자열뿐이었다. 처음 보는 값은 기록하고 "판매 정보 없음"으로 둔다 */
      private static SaleStatus saleStatus(String raw) {
          if ("정상판매".equals(raw)) {
              return SaleStatus.ON_SALE;
          }
          if ("주문판매".equals(raw)) {
              return SaleStatus.ORDER;
          }
          if (raw != null && !raw.isBlank()) {
              log.info("처음 보는 카카오 status: '{}' — 판매 정보 없음으로 둔다", raw);
          }
          return SaleStatus.UNKNOWN;
      }

      /** 카카오는 가격이 없을 때 price 0, sale_price -1을 준다 (실측 487건 중 154건) */
      private static Integer positiveOrNull(Integer value) {
          return value == null || value <= 0 ? null : value;
      }

      private static String blankToNull(String s) {
          return s == null || s.isBlank() ? null : s;
      }

      /** "2014-05-19T00:00:00.000+09:00" → 2014-05-19. 표시용이다 — 형식이 달라도 책을 버리지 않는다 */
      private static LocalDate parseDate(String raw) {
          if (raw == null || raw.length() < 10) {
              return null;
          }
          try {
              return LocalDate.parse(raw.substring(0, 10));
          } catch (DateTimeParseException e) {
              return null;
          }
      }
  }
  ```

  `KakaoRestClientConfig#kakaoRestClient`와 `KakaoIsbn`은 테스트가 같은 패키지에서 직접 부르므로 package-private으로 둔다(위 코드 그대로).

- [ ] **Step 8: 통과 확인**

  Run: `./gradlew test --tests KakaoBookCatalogTest --tests KakaoIsbnTest`
  Expected: PASS. `실제_…` 테스트만 실패하면 Step 1 fixture의 필드 이름과 `KakaoResponse`가 다른 것이다 — 실제 이름으로 고친다(합성 `KakaoStub.doc`도 같이)

- [ ] **Step 9: 실제 카카오로 한 번 기동**

  ```bash
  set -a && . ./.env && set +a && BACKGROUND_JOBS_ENABLED=false ./gradlew bootRun
  ```

  Expected: 기동 성공, 오류 없음. `KAKAO_REST_API_KEY` 없이 기동하면 **실패해야 한다**: `env -u KAKAO_REST_API_KEY ./gradlew bootRun --no-daemon` → `APPLICATION FAILED TO START` + `KAKAO_REST_API_KEY가 없다`

  **실행 중 발견(2026-09-30):** `@ConfigurationProperties` 바인딩은 치환 못 한 `${KAKAO_REST_API_KEY}`를 문자 그대로 넣고 넘어간다(`@Value`와 달리 기동이 실패하지 않는다). 그래서 `KakaoPropertiesTest`(null·빈 값·`${`로 시작하는 값 → `IllegalStateException`)를 먼저 쓰고 `KakaoProperties`의 compact 생성자에서 막는다. 이후 제공자 설정(`LibraryProperties` 등)도 같은 검사를 둔다

- [ ] **Step 10: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `src/main/java/com/bookshelf/catalog src/test/java/com/bookshelf/catalog src/test/java/com/bookshelf/support src/test/resources/kakao`

  ```bash
  git add <허용받은 경로>
  grep -rc "$(grep -E '^KAKAO_REST_API_KEY=' .env | cut -d= -f2-)" src/test/resources/kakao   # 모두 0
  git commit -m "feat: 카카오 책 검색 — BookCatalog 뒤에 두고 응답의 제각각을 경계에서 정리

  실측에서 isbn은 '10 13'/'13'/' 13' 세 모양, 가격 없음은 price 0·sale_price -1,
  status는 정상판매·주문판매·빈 값으로 왔다. 모두 13자리 ISBN·null·판매 정보 없음으로 옮긴다.
  서비스는 카카오를 모른다 — 닫히면 catalog/kakao만 바꾼다."
  ```

---

### Task 5: ISBN 조회 — 로컬 `Book` 우선 (3층 방어의 3층)

설계 문서 6-4: **"사용자가 늘수록 외부 의존이 저절로 줄어든다."** 한 번 가져온 책은 다시 묻지 않는다.

외부 호출과 DB 쓰기를 **다른 빈**에 둔다. `BookLookupService`에는 `@Transactional`이 없고, 쓰기는 `BookWriter`가 짧은 트랜잭션으로 한다. 같은 클래스 안에 두면 `@Transactional`이 프록시를 거치지 않아 무시된다(spring-study의 프록시 함정 네 번째).

**Files:**
- Create: `src/main/java/com/bookshelf/book/application/BookView.java`, `BookWriter.java`, `BookLookupService.java`
- Test: `src/test/java/com/bookshelf/book/application/BookLookupServiceTest.java`

**Interfaces:**
- Consumes: `BookCatalog#lookup`, `CatalogBook`, `BookRepository`, `SaleSnapshotRepository`, `BookLookupFailureRepository`, `Clock`
- Produces:
  - `record BookView(String isbn13, String title, String author, String publisher, LocalDate pubDate, String coverUrl, Integer pageCount)`, `BookView.of(Book)`
  - `BookLookupService#findOrFetch(Isbn13, CallPurpose) -> Optional<BookView>` — 제공자가 모르면 empty, 물을 수 없으면 `ProviderUnavailableException`
  - `BookWriter#saveLookup(CatalogBook) -> BookView` (Book 갱신 + SaleSnapshot 갱신 + 실패 표시 삭제, 한 트랜잭션)
  - `BookWriter#saveUnknownSale(Isbn13)` — 제공자가 모르는 책의 "판매 정보 없음" 스냅샷 (Task 6이 쓴다)
  - `BookWriter#recordFailure(Isbn13)` (Task 8이 쓴다)
  - `BookWriter.toBook(CatalogBook, Instant)` (static, `saveLookup` 안에서 쓴다. Task 7의 검색 저장은 `saveLookup`을 그대로 부른다)

- [ ] **Step 1: 실패하는 테스트**

  `src/test/java/com/bookshelf/book/application/BookLookupServiceTest.java`:

  ```java
  package com.bookshelf.book.application;

  import com.bookshelf.book.domain.BookLookupFailure;
  import com.bookshelf.book.infrastructure.BookLookupFailureRepository;
  import com.bookshelf.book.infrastructure.BookRepository;
  import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
  import com.bookshelf.catalog.CatalogBook;
  import com.bookshelf.book.domain.SaleStatus;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.provider.ProviderUnavailableException;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.KakaoStub;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.context.annotation.Import;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;

  import java.time.Instant;

  import static com.bookshelf.provider.CallPurpose.INTERACTIVE;
  import static com.bookshelf.support.KakaoStub.doc;
  import static com.bookshelf.support.KakaoStub.response;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  @SpringBootTest
  @Import(Fixtures.class)
  class BookLookupServiceTest {

      private static final Isbn13 ISBN = Isbn13.parse("9788936434120");

      @DynamicPropertySource
      static void kakao(DynamicPropertyRegistry r) {
          r.add("providers.kakao.base-url", KakaoStub::baseUrl);
      }

      @Autowired JdbcClient jdbc;
      @Autowired BookLookupService service;
      @Autowired BookWriter writer;
      @Autowired BookRepository books;
      @Autowired SaleSnapshotRepository sales;
      @Autowired BookLookupFailureRepository failures;
      @Autowired Fixtures fx;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          KakaoStub.reset();
      }

      private void kakaoKnows() {
          KakaoStub.returns(ISBN.value(), response(1, true, doc(ISBN.value(), "소년이 온다", 15000, 13500, "정상판매")));
      }

      @Test
      void 처음_보는_책은_제공자에서_가져와_책과_판매_상태를_저장한다() {
          kakaoKnows();

          BookView view = service.findOrFetch(ISBN, INTERACTIVE).orElseThrow();

          assertThat(view.title()).isEqualTo("소년이 온다");
          assertThat(books.findById(ISBN.value())).isPresent();
          assertThat(sales.findById(ISBN.value()).orElseThrow().getSalePrice()).isEqualTo(13500);
          assertThat(sales.findById(ISBN.value()).orElseThrow().getStatus()).isEqualTo(SaleStatus.ON_SALE);
      }

      @Test
      void 두_번째부터는_제공자를_부르지_않는다() {
          kakaoKnows();

          service.findOrFetch(ISBN, INTERACTIVE);
          service.findOrFetch(ISBN, INTERACTIVE);
          service.findOrFetch(ISBN, INTERACTIVE);

          assertThat(KakaoStub.calls()).isEqualTo(1);
      }

      @Test
      void 제공자가_모르는_책이면_empty이고_아무것도_저장하지_않는다() {
          KakaoStub.returns(ISBN.value(), response(0, true));

          assertThat(service.findOrFetch(ISBN, INTERACTIVE)).isEmpty();
          assertThat(books.count()).isZero();
      }

      @Test
      void 제공자가_죽었으면_그대로_알린다() {
          KakaoStub.failsWith(503);

          assertThatThrownBy(() -> service.findOrFetch(ISBN, INTERACTIVE))
                  .isInstanceOf(ProviderUnavailableException.class);
      }

      @Test
      void 제공자가_죽어도_이미_아는_책은_나온다() {
          kakaoKnows();
          service.findOrFetch(ISBN, INTERACTIVE);
          KakaoStub.reset();
          KakaoStub.failsWith(503);

          assertThat(service.findOrFetch(ISBN, INTERACTIVE)).map(BookView::title).contains("소년이 온다");
      }

      @Test
      void 찾으면_실패_표시를_지운다() {
          failures.save(BookLookupFailure.first(ISBN, Instant.parse("2026-01-01T00:00:00Z")));
          kakaoKnows();

          service.findOrFetch(ISBN, INTERACTIVE);

          assertThat(failures.findById(ISBN.value())).isEmpty();
      }

      @Test
      void 갱신해도_이미_아는_쪽수를_지우지_않는다() {
          fx.book(ISBN.value(), "소년이 온다");   // 쪽수 216
          CatalogBook noPages = new CatalogBook(ISBN, "소년이 온다", "한강", "창비", null, null,
                  null, 15000, 13500, SaleStatus.ON_SALE);

          writer.saveLookup(noPages);

          assertThat(books.findById(ISBN.value()).orElseThrow().getPageCount()).isEqualTo(216);
      }
  }
  ```

- [ ] **Step 2: 실패 확인**

  Run: `./gradlew test --tests BookLookupServiceTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 3: 구현**

  `src/main/java/com/bookshelf/book/application/BookView.java`:

  ```java
  package com.bookshelf.book.application;

  import com.bookshelf.book.domain.Book;

  import java.time.LocalDate;

  /** 엔티티는 서비스 밖으로 나가지 않는다 */
  public record BookView(String isbn13, String title, String author, String publisher,
                         LocalDate pubDate, String coverUrl, Integer pageCount) {

      public static BookView of(Book b) {
          return new BookView(b.getIsbn13(), b.getTitle(), b.getAuthor(), b.getPublisher(),
                  b.getPubDate(), b.getCoverUrl(), b.getPageCount());
      }
  }
  ```

  `src/main/java/com/bookshelf/book/application/BookWriter.java`:

  ```java
  package com.bookshelf.book.application;

  import com.bookshelf.book.domain.Book;
  import com.bookshelf.book.domain.BookLookupFailure;
  import com.bookshelf.book.domain.SaleSnapshot;
  import com.bookshelf.book.infrastructure.BookLookupFailureRepository;
  import com.bookshelf.book.infrastructure.BookRepository;
  import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
  import com.bookshelf.catalog.CatalogBook;
  import com.bookshelf.book.domain.SaleStatus;
  import com.bookshelf.common.Isbn13;
  import org.springframework.stereotype.Component;
  import org.springframework.transaction.annotation.Transactional;

  import java.time.Clock;
  import java.time.Instant;

  /**
   * 제공자에서 가져온 것을 DB에 적는다. 외부 호출은 여기서 하지 않는다.
   * 호출하는 서비스와 다른 빈이어야 @Transactional이 프록시를 거친다.
   */
  @Component
  public class BookWriter {

      private final BookRepository books;
      private final SaleSnapshotRepository sales;
      private final BookLookupFailureRepository failures;
      private final Clock clock;

      public BookWriter(BookRepository books, SaleSnapshotRepository sales,
                        BookLookupFailureRepository failures, Clock clock) {
          this.books = books;
          this.sales = sales;
          this.failures = failures;
          this.clock = clock;
      }

      /** ISBN 조회 결과. 책 정보를 갱신하되 이미 아는 쪽수는 지우지 않는다(Book#refreshFrom) */
      @Transactional
      public BookView saveLookup(CatalogBook c) {
          Instant now = clock.instant();
          Book fetched = toBook(c, now);
          Book book = books.findById(c.isbn().value())
                  .map(existing -> {
                      existing.refreshFrom(fetched);
                      return existing;
                  })
                  .orElseGet(() -> books.save(fetched));

          upsertSale(SaleSnapshot.of(c.isbn(), c.listPrice(), c.salePrice(), c.saleStatus(), now));
          failures.deleteById(c.isbn().value());
          return BookView.of(book);
      }

      /**
       * 제공자가 모르는 책의 판매 칸. "판매 정보 없음"도 기준 시각이 있는 스냅샷이다 —
       * 없으면 봇이 그 책 페이지를 열 때마다 갱신 큐에 다시 들어간다.
       */
      @Transactional
      public void saveUnknownSale(Isbn13 isbn) {
          upsertSale(SaleSnapshot.of(isbn, null, null, SaleStatus.UNKNOWN, clock.instant()));
      }

      @Transactional
      public void recordFailure(Isbn13 isbn) {
          Instant now = clock.instant();
          failures.findById(isbn.value())
                  .ifPresentOrElse(f -> f.again(now), () -> failures.save(BookLookupFailure.first(isbn, now)));
      }

      public static Book toBook(CatalogBook c, Instant now) {
          return Book.of(c.isbn(), c.title(), c.author(), c.publisher(), c.pubDate(),
                  c.coverUrl(), c.pageCount(), now);
      }

      private void upsertSale(SaleSnapshot snapshot) {
          sales.findById(snapshot.getIsbn13())
                  .ifPresentOrElse(existing -> existing.replaceWith(snapshot), () -> sales.save(snapshot));
      }
  }
  ```

  `src/main/java/com/bookshelf/book/application/BookLookupService.java`:

  ```java
  package com.bookshelf.book.application;

  import com.bookshelf.book.infrastructure.BookRepository;
  import com.bookshelf.catalog.BookCatalog;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.provider.CallPurpose;
  import org.springframework.stereotype.Service;

  import java.util.Optional;

  /**
   * 3층 방어의 3층: 로컬 Book 우선.
   * @Transactional을 붙이지 않는다 — 제공자 호출이 DB 커넥션을 쥔 채 기다리면 안 된다.
   */
  @Service
  public class BookLookupService {

      private final BookRepository books;
      private final BookCatalog catalog;
      private final BookWriter writer;

      public BookLookupService(BookRepository books, BookCatalog catalog, BookWriter writer) {
          this.books = books;
          this.catalog = catalog;
          this.writer = writer;
      }

      public Optional<BookView> findOrFetch(Isbn13 isbn, CallPurpose purpose) {
          Optional<BookView> local = books.findById(isbn.value()).map(BookView::of);
          if (local.isPresent()) {
              return local;
          }
          return catalog.lookup(isbn, purpose).map(writer::saveLookup);
      }
  }
  ```

- [ ] **Step 4: 통과 확인**

  Run: `./gradlew test --tests BookLookupServiceTest`
  Expected: PASS 7개

- [ ] **Step 5: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `src/main/java/com/bookshelf/book/application src/test/java/com/bookshelf/book/application`

  ```bash
  git add <허용받은 경로>
  git commit -m "feat: ISBN 조회 — 한 번 가져온 책은 다시 묻지 않는다

  같은 ISBN 세 번 조회 → 카카오 호출 1회. 카카오가 503이어도 아는 책은 나온다.
  외부 호출(BookLookupService)과 쓰기(BookWriter)를 다른 빈으로 나눠 커넥션을 쥔 채 기다리지 않는다."
  ```

---

### Task 6: 판매 상태 캐시와 갱신 큐 — 요청 스레드는 제공자를 기다리지 않는다

설계 문서 2-1: **"공개 페이지는 어떤 제공자도 동기로 부르지 않는다."** `SaleService#current`는 캐시만 읽는다. 없거나 오래됐으면 갱신 큐에 한 줄 넣고 가진 것(또는 "확인 불가")을 돌려준다. `RefreshWorker`가 1분마다 큐에서 20개를 꺼내 **백그라운드 예산**으로 갱신한다.

봇이 책 페이지 수천 개를 훑어도(Review Focus 1):
- 요청 스레드는 카카오를 기다리지 않는다 — 카톡 미리보기·검색봇이 제공자 응답 시간에 묶이지 않는다
- 큐는 ISBN당 한 줄이다 — 같은 페이지를 백 번 열어도 한 줄
- 갱신은 백그라운드 상한(18,000)에서 멈춘다
- 카카오가 모르는 책은 "판매 정보 없음" 스냅샷을 남겨 6시간 동안 다시 큐에 들어가지 않는다

**Files:**
- Create: `src/main/java/com/bookshelf/common/BookshelfProperties.java`
- Create: `src/main/java/com/bookshelf/book/infrastructure/RefreshQueue.java`
- Create: `src/main/java/com/bookshelf/book/application/SaleView.java`, `SaleService.java`, `RefreshWorker.java`, `BackgroundJobs.java`
- Test: `src/test/java/com/bookshelf/book/application/SaleRefreshTest.java`

**Interfaces:**
- Consumes: `BookCatalog#lookup(Isbn13, BACKGROUND)`, `BookWriter#saveLookup`, `BookWriter#saveUnknownSale`, `SaleSnapshotRepository`, `SaleSnapshot#isFreshAt`, `BudgetExhaustedException`, `ProviderUnavailableException`
- Produces:
  - `record BookshelfProperties(String siteUrl, Duration saleTtl, Duration searchCacheTtl, Background background)`, `record Background(boolean enabled, Duration refreshInterval, int refreshBatchSize, Duration backfillInterval, int backfillBatchSize)` — prefix `bookshelf`
  - `RefreshQueue#request(Isbn13)`, `#take(int limit) -> List<Isbn13>`(오래된 요청부터), `#done(Isbn13)`, `#size() -> int`
  - `record SaleView(SaleStatus status, Integer listPrice, Integer salePrice, Instant fetchedAt)`, `SaleView.unavailable()`, `#available() -> boolean`
  - `SaleService#current(Isbn13) -> SaleView` — **제공자를 부르지 않는다**, 예외를 던지지 않는다
  - `RefreshWorker#runOnce() -> int`(카카오를 부른 횟수)
  - `BackgroundJobs` — `bookshelf.background.enabled=true`일 때만 뜬다. Task 8이 백필 스케줄을 여기에 추가한다

- [ ] **Step 1: 실패하는 테스트**

  `src/test/java/com/bookshelf/book/application/SaleRefreshTest.java`:

  ```java
  package com.bookshelf.book.application;

  import com.bookshelf.book.domain.SaleSnapshot;
  import com.bookshelf.book.infrastructure.RefreshQueue;
  import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
  import com.bookshelf.book.domain.SaleStatus;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.KakaoStub;
  import com.bookshelf.support.MutableClock;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.test.context.TestConfiguration;
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Import;
  import org.springframework.context.annotation.Primary;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;

  import java.time.Duration;
  import java.time.Instant;
  import java.time.LocalDate;
  import java.time.ZoneId;

  import static com.bookshelf.support.KakaoStub.doc;
  import static com.bookshelf.support.KakaoStub.response;
  import static com.github.tomakehurst.wiremock.client.WireMock.get;
  import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
  import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest
  @Import(Fixtures.class)
  class SaleRefreshTest {

      private static final Isbn13 A = Isbn13.parse("9788936434120");
      private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");

      @TestConfiguration
      static class ClockOverride {
          @Bean
          @Primary
          MutableClock mutableClock() {
              return new MutableClock(T0);
          }
      }

      @DynamicPropertySource
      static void kakao(DynamicPropertyRegistry r) {
          r.add("providers.kakao.base-url", KakaoStub::baseUrl);
      }

      @Autowired JdbcClient jdbc;
      @Autowired SaleService sales;
      @Autowired RefreshWorker worker;
      @Autowired RefreshQueue queue;
      @Autowired SaleSnapshotRepository snapshots;
      @Autowired Fixtures fx;
      @Autowired MutableClock clock;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          KakaoStub.reset();
          clock.set(T0);
          fx.book(A.value(), "소년이 온다");
      }

      private void snapshotAt(Isbn13 isbn, int salePrice, Instant at) {
          snapshots.save(SaleSnapshot.of(isbn, 15000, salePrice, SaleStatus.ON_SALE, at));
      }

      // ── 요청 스레드: 캐시만 본다 ────────────────────────────

      @Test
      void 신선하면_그대로_주고_큐에_넣지_않는다() {
          snapshotAt(A, 13500, T0);
          clock.advance(Duration.ofHours(5));

          SaleView v = sales.current(A);

          assertThat(v.salePrice()).isEqualTo(13500);
          assertThat(v.fetchedAt()).isEqualTo(T0);
          assertThat(queue.size()).isZero();
          assertThat(KakaoStub.calls()).isZero();
      }

      @Test
      void 오래되면_옛_값을_기준_시각과_함께_주고_큐에_넣는다() {
          snapshotAt(A, 13500, T0);
          clock.advance(Duration.ofHours(7));

          SaleView v = sales.current(A);

          assertThat(v.salePrice()).isEqualTo(13500);
          assertThat(v.fetchedAt()).isEqualTo(T0);
          assertThat(queue.size()).isEqualTo(1);
          assertThat(KakaoStub.calls()).isZero();   // 요청 스레드는 카카오를 부르지 않는다
      }

      @Test
      void 없으면_확인_불가이고_큐에_넣는다() {
          SaleView v = sales.current(A);

          assertThat(v.available()).isFalse();
          assertThat(queue.size()).isEqualTo(1);
          assertThat(KakaoStub.calls()).isZero();
      }

      @Test
      void 같은_책을_백_번_열어도_큐는_한_줄이다() {
          for (int i = 0; i < 100; i++) {
              sales.current(A);
          }

          assertThat(queue.size()).isEqualTo(1);
      }

      // ── 백그라운드: 큐를 비운다 ────────────────────────────

      @Test
      void 큐를_비우며_갱신한다() {
          sales.current(A);
          KakaoStub.returns(A.value(), response(1, true, doc(A.value(), "소년이 온다", 15000, 12000, "정상판매")));
          clock.advance(Duration.ofMinutes(1));

          assertThat(worker.runOnce()).isEqualTo(1);

          SaleView v = sales.current(A);
          assertThat(v.salePrice()).isEqualTo(12000);
          assertThat(v.fetchedAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
          assertThat(queue.size()).isZero();
      }

      @Test
      void 카카오가_모르는_책은_판매_정보_없음을_남기고_다시_큐에_들어가지_않는다() {
          sales.current(A);
          KakaoStub.returns(A.value(), response(0, true));

          worker.runOnce();
          SaleView v = sales.current(A);

          assertThat(v.available()).isTrue();
          assertThat(v.status()).isEqualTo(SaleStatus.UNKNOWN);
          assertThat(v.salePrice()).isNull();
          assertThat(queue.size()).isZero();         // 6시간 동안 봇이 열어도 큐에 다시 넣지 않는다
      }

      @Test
      void 그_사이_신선해진_책은_부르지_않고_뺀다() {
          sales.current(A);                          // 없어서 큐에 들어감
          snapshotAt(A, 13500, T0);                  // 그 사이 앱 사용자의 스캔이 갱신했다

          worker.runOnce();

          assertThat(KakaoStub.calls()).isZero();
          assertThat(queue.size()).isZero();
      }

      @Test
      void 카카오가_죽으면_그_회차를_멈추고_큐는_남는다() {
          sales.current(A);
          KakaoStub.failsWith(503);

          worker.runOnce();

          assertThat(queue.size()).isEqualTo(1);
      }

      @Test
      void 백그라운드_상한이면_부르지_않고_큐는_남는다() {
          sales.current(A);
          LocalDate today = LocalDate.ofInstant(T0, ZoneId.of("Asia/Seoul"));
          jdbc.sql("INSERT INTO provider_call_counter (provider, day, calls) VALUES ('KAKAO', :d, 18000)")
                  .param("d", today).update();

          worker.runOnce();

          assertThat(KakaoStub.calls()).isZero();
          assertThat(queue.size()).isEqualTo(1);
      }

      @Test
      void 한_회차에_정해진_수만_처리한다() {
          // 앞 12자리 9788900000nn + 체크 숫자 — 서로 다른 ISBN 25개
          for (int n = 0; n < 25; n++) {
              String body = "9788900000%02d".formatted(n);
              Isbn13 isbn = Isbn13.parse(body + check(body));
              fx.book(isbn.value(), "책 " + n);
              sales.current(isbn);
          }
          // 어떤 ISBN이든 빈 응답 — 20개가 "판매 정보 없음"으로 처리되고 5개가 남는다
          KakaoStub.server().stubFor(get(urlPathEqualTo("/v3/search/book")).willReturn(okJson(response(0, true))));

          assertThat(worker.runOnce()).isEqualTo(20);   // application.yaml refresh-batch-size
          assertThat(queue.size()).isEqualTo(5);
      }

      private static int check(String first12) {
          int sum = 0;
          for (int i = 0; i < 12; i++) {
              int d = first12.charAt(i) - '0';
              sum += (i % 2 == 0) ? d : d * 3;
          }
          return (10 - sum % 10) % 10;
      }
  }
  ```

- [ ] **Step 2: 실패 확인**

  Run: `./gradlew test --tests SaleRefreshTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 3: 구현**

  `src/main/java/com/bookshelf/common/BookshelfProperties.java`:

  ```java
  package com.bookshelf.common;

  import org.springframework.boot.context.properties.ConfigurationProperties;

  import java.time.Duration;

  @ConfigurationProperties("bookshelf")
  public record BookshelfProperties(String siteUrl, Duration saleTtl, Duration searchCacheTtl, Background background) {

      public record Background(boolean enabled, Duration refreshInterval, int refreshBatchSize,
                               Duration backfillInterval, int backfillBatchSize) {
      }
  }
  ```

  `src/main/java/com/bookshelf/book/infrastructure/RefreshQueue.java`:

  ```java
  package com.bookshelf.book.infrastructure;

  import com.bookshelf.common.Isbn13;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Repository;

  import java.sql.Timestamp;
  import java.time.Clock;
  import java.util.List;

  /**
   * 판매 상태 갱신 요청. ISBN당 한 줄 — 같은 책을 백 번 요청해도 한 줄이다.
   * 공개 페이지(요청 스레드)가 넣고, RefreshWorker(백그라운드)가 뺀다.
   */
  @Repository
  public class RefreshQueue {

      private final JdbcClient jdbc;
      private final Clock clock;

      public RefreshQueue(JdbcClient jdbc, Clock clock) {
          this.jdbc = jdbc;
          this.clock = clock;
      }

      public void request(Isbn13 isbn) {
          jdbc.sql("INSERT INTO refresh_request (isbn13, requested_at) VALUES (:isbn, :at) ON CONFLICT (isbn13) DO NOTHING")
                  .param("isbn", isbn.value())
                  .param("at", Timestamp.from(clock.instant()))
                  .update();
      }

      public List<Isbn13> take(int limit) {
          return jdbc.sql("SELECT isbn13 FROM refresh_request ORDER BY requested_at, isbn13 LIMIT :n")
                  .param("n", limit)
                  .query(String.class)
                  .list()
                  .stream()
                  .map(Isbn13::parse)
                  .toList();
      }

      public void done(Isbn13 isbn) {
          jdbc.sql("DELETE FROM refresh_request WHERE isbn13 = :isbn").param("isbn", isbn.value()).update();
      }

      public int size() {
          return jdbc.sql("SELECT count(*) FROM refresh_request").query(Integer.class).single();
      }
  }
  ```

  `src/main/java/com/bookshelf/book/application/SaleView.java`:

  ```java
  package com.bookshelf.book.application;

  import com.bookshelf.book.domain.SaleSnapshot;
  import com.bookshelf.book.domain.SaleStatus;

  import java.time.Instant;

  /** fetchedAt이 null이면 "확인 불가". 화면은 fetchedAt을 반드시 같이 보여준다 */
  public record SaleView(SaleStatus status, Integer listPrice, Integer salePrice, Instant fetchedAt) {

      public static SaleView unavailable() {
          return new SaleView(null, null, null, null);
      }

      static SaleView of(SaleSnapshot s) {
          return new SaleView(s.getStatus(), s.getListPrice(), s.getSalePrice(), s.getFetchedAt());
      }

      public boolean available() {
          return fetchedAt != null;
      }
  }
  ```

  `src/main/java/com/bookshelf/book/application/SaleService.java`:

  ```java
  package com.bookshelf.book.application;

  import com.bookshelf.book.domain.SaleSnapshot;
  import com.bookshelf.book.infrastructure.RefreshQueue;
  import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
  import com.bookshelf.common.BookshelfProperties;
  import com.bookshelf.common.Isbn13;
  import org.springframework.stereotype.Service;

  import java.time.Clock;
  import java.util.Optional;

  /**
   * 새 책 판매 칸. **제공자를 부르지 않는다** (설계 문서 2-1).
   * 없거나 오래됐으면 큐에 넣고, 가진 것을 기준 시각과 함께 돌려준다.
   */
  @Service
  public class SaleService {

      private final SaleSnapshotRepository snapshots;
      private final RefreshQueue queue;
      private final BookshelfProperties props;
      private final Clock clock;

      public SaleService(SaleSnapshotRepository snapshots, RefreshQueue queue,
                         BookshelfProperties props, Clock clock) {
          this.snapshots = snapshots;
          this.queue = queue;
          this.props = props;
          this.clock = clock;
      }

      public SaleView current(Isbn13 isbn) {
          Optional<SaleSnapshot> snapshot = snapshots.findById(isbn.value());
          if (snapshot.isEmpty() || !snapshot.get().isFreshAt(clock.instant(), props.saleTtl())) {
              queue.request(isbn);
          }
          return snapshot.map(SaleView::of).orElseGet(SaleView::unavailable);
      }
  }
  ```

  `src/main/java/com/bookshelf/book/application/RefreshWorker.java`:

  ```java
  package com.bookshelf.book.application;

  import com.bookshelf.book.infrastructure.RefreshQueue;
  import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
  import com.bookshelf.catalog.BookCatalog;
  import com.bookshelf.catalog.CatalogBook;
  import com.bookshelf.common.BookshelfProperties;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.provider.BudgetExhaustedException;
  import com.bookshelf.provider.CallPurpose;
  import com.bookshelf.provider.ProviderUnavailableException;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.stereotype.Component;

  import java.time.Clock;
  import java.util.Optional;

  /** 갱신 큐를 백그라운드 예산으로 비운다. 제공자가 죽거나 예산이 바닥나면 그 회차를 멈추고 큐는 남긴다 */
  @Slf4j
  @Component
  public class RefreshWorker {

      private final RefreshQueue queue;
      private final SaleSnapshotRepository snapshots;
      private final BookCatalog catalog;
      private final BookWriter writer;
      private final BookshelfProperties props;
      private final Clock clock;

      public RefreshWorker(RefreshQueue queue, SaleSnapshotRepository snapshots, BookCatalog catalog,
                           BookWriter writer, BookshelfProperties props, Clock clock) {
          this.queue = queue;
          this.snapshots = snapshots;
          this.catalog = catalog;
          this.writer = writer;
          this.props = props;
          this.clock = clock;
      }

      /** @return 제공자를 부른 횟수 */
      public int runOnce() {
          int called = 0;
          for (Isbn13 isbn : queue.take(props.background().refreshBatchSize())) {
              boolean fresh = snapshots.findById(isbn.value())
                      .map(s -> s.isFreshAt(clock.instant(), props.saleTtl()))
                      .orElse(false);
              if (fresh) {
                  queue.done(isbn);   // 그 사이 다른 경로(앱 스캔)가 갱신했다
                  continue;
              }
              try {
                  called++;
                  Optional<CatalogBook> found = catalog.lookup(isbn, CallPurpose.BACKGROUND);
                  if (found.isPresent()) {
                      writer.saveLookup(found.get());
                  } else {
                      writer.saveUnknownSale(isbn);
                  }
                  queue.done(isbn);
              } catch (BudgetExhaustedException e) {
                  log.info("갱신 중단: 백그라운드 예산 소진 (남은 큐 {})", queue.size());
                  called--;   // 예산이 없어 보내지 않았다
                  break;
              } catch (ProviderUnavailableException e) {
                  log.warn("갱신 중단: {} (남은 큐 {})", e.getMessage(), queue.size());
                  break;
              }
          }
          return called;
      }
  }
  ```

  `src/main/java/com/bookshelf/book/application/BackgroundJobs.java`:

  ```java
  package com.bookshelf.book.application;

  import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.scheduling.annotation.EnableScheduling;
  import org.springframework.scheduling.annotation.Scheduled;

  /**
   * 로직(RefreshWorker, BookBackfill)과 스케줄을 나눈다. 테스트는 스케줄 없이 runOnce()를 직접 부른다.
   * initialDelay를 주는 이유: 기동 직후 배포 확인 중에 외부 호출이 섞이지 않게 한다.
   */
  @Configuration
  @EnableScheduling
  @ConditionalOnProperty(name = "bookshelf.background.enabled", havingValue = "true")
  class BackgroundJobs {

      private final RefreshWorker refresh;

      BackgroundJobs(RefreshWorker refresh) {
          this.refresh = refresh;
      }

      @Scheduled(fixedDelayString = "${bookshelf.background.refresh-interval}",
                 initialDelayString = "${bookshelf.background.refresh-interval}")
      void refresh() {
          refresh.runOnce();
      }
  }
  ```

- [ ] **Step 4: 통과 확인**

  Run: `./gradlew test --tests SaleRefreshTest`
  Expected: PASS 10개

- [ ] **Step 5: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `src/main/java/com/bookshelf/common/BookshelfProperties.java src/main/java/com/bookshelf/book src/test/java/com/bookshelf/book`

  ```bash
  git add <허용받은 경로>
  git commit -m "feat: 판매 상태 캐시와 갱신 큐 — 요청 스레드는 제공자를 기다리지 않는다

  공개 페이지는 캐시만 읽고 오래됐으면 큐에 한 줄 넣는다(ISBN당 한 줄).
  1분마다 백그라운드 예산으로 20개씩 비운다. 카카오가 모르는 책은 '판매 정보 없음'
  스냅샷을 남겨 봇이 열 때마다 큐에 다시 들어가지 않게 한다."
  ```

---

### Task 7: 검색과 검색어 캐시 (3층 방어의 2층)

설계 문서 6-4: **"같은 검색어는 24시간에 한 번만 제공자에게 간다."** 캐시에는 결과 ISBN 목록만 두고 책 정보는 `book`에 넣는다. 카카오 검색 응답에는 판매 상태·가격도 있으므로 `sale_snapshot`도 같이 갱신한다 — 검색된 책은 로컬 `Book`이 되어 이후 스캔·책 페이지가 카카오를 부르지 않는다(3층과 이어진다).

HTTP 엔드포인트는 이 Phase에서 만들지 않는다. **인증 없는 검색 API는 누구나 우리 예산을 태울 수 있는 구멍이다**(설계 문서 6-3). Phase 2에서 인증과 사용자별 상한 뒤에 연다.

**Files:**
- Create: `src/main/java/com/bookshelf/search/InvalidQueryException.java`, `QueryNormalizer.java`
- Create: `src/main/java/com/bookshelf/search/SearchCacheRepository.java`, `SearchWriter.java`
- Create: `src/main/java/com/bookshelf/search/SearchResult.java`, `SearchUnavailableException.java`, `SearchService.java`
- Create: `docs/notes/search-normalization.md` (Step 0 결정 — 커밋하지 않는다)
- Test: `src/test/java/com/bookshelf/search/QueryNormalizerTest.java`
- Test: `src/test/java/com/bookshelf/search/SearchServiceTest.java`

**Interfaces:**
- Consumes: `BookCatalog#search(String, int, INTERACTIVE)`, `CatalogPage`, `BookWriter#saveLookup`, `BookRepository`, `BookView.of`, `BookshelfProperties#searchCacheTtl`
- Produces:
  - `QueryNormalizer.normalize(String raw) -> String` (정적), 규칙 위반은 `InvalidQueryException extends DomainException`
  - `record SearchResult(List<BookView> books, int total, boolean isEnd, Instant fetchedAt)`
  - `SearchService#search(String rawQuery, int page) -> SearchResult` (page 1~10), 제공자에 물을 수 없고 캐시도 없으면 `SearchUnavailableException` (Phase 2에서 "잠시 후 다시"로 번역)

- [ ] **Step 0: 결정 지점 — 검색어 정규화 규칙**

  설계 문서 6-4: *"캐시 적중률을 좌우하며 정답이 하나가 아니다. 구현 시 직접 정한다."* 아래는 기본안이다. **코드를 쓰기 전에 사용자에게 묻고, 바꾸는 규칙은 Step 1의 테스트부터 바꾼다.** 결정과 이유를 `docs/notes/search-normalization.md`에 남긴다.

  | 규칙 | 기본안 | 이유 | 대안과 그 대가 |
  |---|---|---|---|
  | 유니코드 | NFC로 합친다 | macOS에서 복사한 한글은 NFD(자모 분리)라 같은 글자가 다른 키가 된다 | 안 하면 캐시가 조용히 두 벌이 된다 |
  | 공백 | 앞뒤 제거, 연속 공백은 하나로 | `"소년이  온다"`와 `"소년이 온다"`는 같은 의도다 | 공백을 전부 지우면 `"소년이온다"`까지 합쳐지지만 카카오 결과가 달라질 수 있다 |
  | 대소문자 | 소문자로 | `"Harry"`/`"harry"` | 카카오가 대소문자를 구분하면 결과가 달라진다 — 두 번 검색해 확인해 본다 |
  | 자모만 | 거부 | 입력 중인 `"ㅅㄴ"`은 검색 의도가 아니다 | 허용하면 초성 검색을 기대하게 되는데 카카오가 지원하는지 모른다 |
  | 길이 | 2~100 코드포인트 | 설계 문서: 2글자 이상. 상한은 캐시 키 컬럼(200)보다 작게 | 1글자 허용 시 `"책"` 같은 검색이 캐시를 도배한다 |

  **서버도 2글자 규칙을 검사한다.** 앱의 디바운스는 1층일 뿐이다 — 구버전 앱이나 앱이 아닌 클라이언트는 그것을 지키지 않는다.

- [ ] **Step 1: 실패하는 단위 테스트 — 정규화**

  `src/test/java/com/bookshelf/search/QueryNormalizerTest.java`:

  ```java
  package com.bookshelf.search;

  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.ValueSource;

  import java.text.Normalizer;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  class QueryNormalizerTest {

      @Test
      void 공백을_정리하고_소문자로_바꾼다() {
          assertThat(QueryNormalizer.normalize("  Harry   Potter \t")).isEqualTo("harry potter");
      }

      @Test
      void 자모가_분리된_한글도_같은_키가_된다() {
          String nfd = Normalizer.normalize("소년이 온다", Normalizer.Form.NFD);
          assertThat(nfd).isNotEqualTo("소년이 온다");                      // 눈에는 같지만 다른 문자열
          assertThat(QueryNormalizer.normalize(nfd)).isEqualTo("소년이 온다");
      }

      @Test
      void 두_글자면_받는다() {
          assertThat(QueryNormalizer.normalize("소년")).isEqualTo("소년");
          assertThat(QueryNormalizer.normalize("C++")).isEqualTo("c++");
      }

      @ParameterizedTest
      @ValueSource(strings = {"", "   ", "책", " a ", "ㅅㄴ", "ㅅ ㄴㅇ"})
      void 검색_의도가_아닌_입력은_거부한다(String raw) {
          assertThatThrownBy(() -> QueryNormalizer.normalize(raw)).isInstanceOf(InvalidQueryException.class);
      }

      @Test
      void 너무_길면_거부한다() {
          assertThatThrownBy(() -> QueryNormalizer.normalize("가".repeat(101)))
                  .isInstanceOf(InvalidQueryException.class);
      }

      @Test
      void null은_거부한다() {
          assertThatThrownBy(() -> QueryNormalizer.normalize(null)).isInstanceOf(InvalidQueryException.class);
      }
  }
  ```

- [ ] **Step 2: 실패 확인 → 구현 → 통과**

  Run: `./gradlew test --tests QueryNormalizerTest` → FAIL(컴파일)

  `src/main/java/com/bookshelf/search/InvalidQueryException.java`:

  ```java
  package com.bookshelf.search;

  import com.bookshelf.common.DomainException;

  public class InvalidQueryException extends DomainException {
      public InvalidQueryException(String message) {
          super(message);
      }
  }
  ```

  `src/main/java/com/bookshelf/search/QueryNormalizer.java`:

  ```java
  package com.bookshelf.search;

  import java.text.Normalizer;
  import java.util.Locale;
  import java.util.regex.Pattern;

  /** 규칙과 이유: docs/notes/search-normalization.md */
  public final class QueryNormalizer {

      private static final int MIN = 2;
      private static final int MAX = 100;
      private static final Pattern SPACES = Pattern.compile("\\s+");
      /** 한글 호환 자모(ㄱ~ㅣ)와 공백만으로 된 입력 */
      private static final Pattern JAMO_ONLY = Pattern.compile("[\\u3131-\\u318E ]+");

      private QueryNormalizer() {
      }

      public static String normalize(String raw) {
          if (raw == null) {
              throw new InvalidQueryException("검색어가 없다");
          }
          String q = Normalizer.normalize(raw, Normalizer.Form.NFC);
          q = SPACES.matcher(q.strip()).replaceAll(" ");
          q = q.toLowerCase(Locale.ROOT);

          int length = q.codePointCount(0, q.length());
          if (length < MIN || length > MAX) {
              throw new InvalidQueryException("검색어는 " + MIN + "~" + MAX + "자다");
          }
          if (JAMO_ONLY.matcher(q).matches()) {
              throw new InvalidQueryException("자모만으로는 검색하지 않는다");
          }
          return q;
      }
  }
  ```

  Run: `./gradlew test --tests QueryNormalizerTest`
  Expected: PASS 11개

- [ ] **Step 3: 실패하는 통합 테스트 — 검색 서비스**

  `src/test/java/com/bookshelf/search/SearchServiceTest.java`:

  ```java
  package com.bookshelf.search;

  import com.bookshelf.book.application.BookView;
  import com.bookshelf.book.infrastructure.BookRepository;
  import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
  import com.bookshelf.common.DomainException;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.KakaoStub;
  import com.bookshelf.support.MutableClock;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.test.context.TestConfiguration;
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Import;
  import org.springframework.context.annotation.Primary;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;

  import java.time.Duration;
  import java.time.Instant;
  import java.time.LocalDate;
  import java.time.ZoneId;

  import static com.bookshelf.support.KakaoStub.doc;
  import static com.bookshelf.support.KakaoStub.response;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  @SpringBootTest
  @Import(Fixtures.class)
  class SearchServiceTest {

      private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");
      private static final String A = "9788936434120";
      private static final String B = "9788954651134";

      @TestConfiguration
      static class ClockOverride {
          @Bean
          @Primary
          MutableClock mutableClock() {
              return new MutableClock(T0);
          }
      }

      @DynamicPropertySource
      static void kakao(DynamicPropertyRegistry r) {
          r.add("providers.kakao.base-url", KakaoStub::baseUrl);
      }

      @Autowired JdbcClient jdbc;
      @Autowired SearchService service;
      @Autowired BookRepository books;
      @Autowired SaleSnapshotRepository sales;
      @Autowired Fixtures fx;
      @Autowired MutableClock clock;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          KakaoStub.reset();
          clock.set(T0);
          KakaoStub.returns("소년이", response(2, true,
                  doc("8936434128 " + A, "소년이 온다", 15000, 13500, "정상판매"),
                  doc(B, "소년을 위로해줘", 16000, 14400, "주문판매")));
      }

      @Test
      void 결과를_순서대로_주고_책과_판매_상태를_로컬에_저장한다() {
          SearchResult r = service.search("소년이", 1);

          assertThat(r.books()).extracting(BookView::isbn13).containsExactly(A, B);
          assertThat(r.total()).isEqualTo(2);
          assertThat(r.isEnd()).isTrue();
          assertThat(books.count()).isEqualTo(2);
          assertThat(sales.findById(A).orElseThrow().getSalePrice()).isEqualTo(13500);
      }

      @Test
      void 표기만_다른_같은_검색어는_제공자에_한_번만_간다() {
          service.search("소년이", 1);
          service.search("  소년이 ", 1);
          service.search("소년이", 1);

          assertThat(KakaoStub.calls()).isEqualTo(1);
      }

      @Test
      void 이미_있는_책의_쪽수를_검색_결과가_지우지_않는다() {
          fx.book(A, "소년이 온다");          // Fixtures는 쪽수 216으로 만든다

          service.search("소년이", 1);

          assertThat(books.findById(A).orElseThrow().getPageCount()).isEqualTo(216);
      }

      @Test
      void TTL이_지나면_다시_묻는다() {
          service.search("소년이", 1);
          clock.advance(Duration.ofHours(25));
          service.search("소년이", 1);

          assertThat(KakaoStub.calls()).isEqualTo(2);
      }

      @Test
      void 제공자가_죽으면_지난_캐시라도_준다() {
          service.search("소년이", 1);
          clock.advance(Duration.ofHours(25));
          KakaoStub.reset();
          KakaoStub.failsWith(503);

          SearchResult r = service.search("소년이", 1);

          assertThat(r.books()).hasSize(2);
          assertThat(r.fetchedAt()).isEqualTo(T0);
      }

      @Test
      void 캐시도_없고_제공자도_죽었으면_잠시_후_다시다() {
          KakaoStub.reset();
          KakaoStub.failsWith(503);

          assertThatThrownBy(() -> service.search("소년이", 1)).isInstanceOf(SearchUnavailableException.class);
      }

      @Test
      void 예산이_바닥나면_잠시_후_다시다() {
          LocalDate today = LocalDate.ofInstant(T0, ZoneId.of("Asia/Seoul"));
          jdbc.sql("INSERT INTO provider_call_counter (provider, day, calls) VALUES ('KAKAO', :d, 27000)")
                  .param("d", today).update();

          assertThatThrownBy(() -> service.search("소년이", 1)).isInstanceOf(SearchUnavailableException.class);
          assertThat(KakaoStub.calls()).isZero();
      }

      @Test
      void 페이지는_1부터_10까지다() {
          assertThatThrownBy(() -> service.search("소년이", 0)).isInstanceOf(DomainException.class);
          assertThatThrownBy(() -> service.search("소년이", 11)).isInstanceOf(DomainException.class);
      }
  }
  ```

- [ ] **Step 4: 실패 확인**

  Run: `./gradlew test --tests SearchServiceTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 5: 구현**

  `src/main/java/com/bookshelf/search/SearchResult.java`:

  ```java
  package com.bookshelf.search;

  import com.bookshelf.book.application.BookView;

  import java.time.Instant;
  import java.util.List;

  public record SearchResult(List<BookView> books, int total, boolean isEnd, Instant fetchedAt) {
  }
  ```

  `src/main/java/com/bookshelf/search/SearchUnavailableException.java`:

  ```java
  package com.bookshelf.search;

  /** 캐시에도 없고 제공자에게도 물을 수 없다. Phase 2에서 "잠시 후 다시"로 번역한다 */
  public class SearchUnavailableException extends RuntimeException {
      public SearchUnavailableException(String message, Throwable cause) {
          super(message, cause);
      }
  }
  ```

  `src/main/java/com/bookshelf/search/SearchCacheRepository.java`:

  ```java
  package com.bookshelf.search;

  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Repository;

  import java.sql.Timestamp;
  import java.time.Instant;
  import java.time.OffsetDateTime;
  import java.util.Arrays;
  import java.util.List;
  import java.util.Optional;

  /** 복합 키 한 테이블이라 엔티티 대신 SQL로 둔다 */
  @Repository
  public class SearchCacheRepository {

      public record Entry(List<String> isbns, int total, Instant fetchedAt) {
      }

      private final JdbcClient jdbc;

      public SearchCacheRepository(JdbcClient jdbc) {
          this.jdbc = jdbc;
      }

      public Optional<Entry> find(String key, int page) {
          return jdbc.sql("SELECT isbns, total, fetched_at FROM search_cache WHERE query_key = :k AND page = :p")
                  .param("k", key)
                  .param("p", page)
                  .query((rs, n) -> new Entry(
                          rs.getString("isbns").isEmpty() ? List.of() : Arrays.asList(rs.getString("isbns").split(",")),
                          rs.getInt("total"),
                          rs.getObject("fetched_at", OffsetDateTime.class).toInstant()))
                  .optional();
      }

      public void upsert(String key, int page, List<String> isbns, int total, Instant fetchedAt) {
          jdbc.sql("""
                  INSERT INTO search_cache (query_key, page, isbns, total, fetched_at)
                  VALUES (:k, :p, :isbns, :total, :at)
                  ON CONFLICT (query_key, page)
                  DO UPDATE SET isbns = EXCLUDED.isbns, total = EXCLUDED.total, fetched_at = EXCLUDED.fetched_at
                  """)
                  .param("k", key)
                  .param("p", page)
                  .param("isbns", String.join(",", isbns))
                  .param("total", total)
                  .param("at", Timestamp.from(fetchedAt))
                  .update();
      }
  }
  ```

  `src/main/java/com/bookshelf/search/SearchWriter.java`:

  ```java
  package com.bookshelf.search;

  import com.bookshelf.book.application.BookWriter;
  import com.bookshelf.catalog.CatalogBook;
  import com.bookshelf.catalog.CatalogPage;
  import org.springframework.stereotype.Component;
  import org.springframework.transaction.annotation.Transactional;

  import java.time.Instant;
  import java.util.List;

  @Component
  public class SearchWriter {

      private final BookWriter books;
      private final SearchCacheRepository cache;

      public SearchWriter(BookWriter books, SearchCacheRepository cache) {
          this.books = books;
          this.cache = cache;
      }

      /**
       * 결과의 책과 판매 상태를 저장하고 캐시를 갱신한다. 한 트랜잭션.
       * 카카오 검색은 ISBN 조회와 같은 필드를 주므로 saveLookup을 그대로 쓴다 — 이미 아는 쪽수는 지킨다.
       */
      @Transactional
      public void save(String key, int page, CatalogPage result, Instant now) {
          for (CatalogBook b : result.books()) {
              books.saveLookup(b);
          }
          List<String> isbns = result.books().stream().map(b -> b.isbn().value()).toList();
          cache.upsert(key, page, isbns, result.total(), now);
      }
  }
  ```

  `src/main/java/com/bookshelf/search/SearchService.java`:

  ```java
  package com.bookshelf.search;

  import com.bookshelf.book.application.BookView;
  import com.bookshelf.book.domain.Book;
  import com.bookshelf.book.infrastructure.BookRepository;
  import com.bookshelf.catalog.BookCatalog;
  import com.bookshelf.catalog.CatalogPage;
  import com.bookshelf.common.BookshelfProperties;
  import com.bookshelf.common.DomainException;
  import com.bookshelf.provider.CallPurpose;
  import com.bookshelf.provider.ProviderUnavailableException;
  import org.springframework.stereotype.Service;

  import java.time.Clock;
  import java.time.Instant;
  import java.util.List;
  import java.util.Map;
  import java.util.Objects;
  import java.util.Optional;
  import java.util.function.Function;
  import java.util.stream.Collectors;

  /** 3층 방어의 2층. @Transactional 없음 — 외부 호출이 있다 */
  @Service
  public class SearchService {

      private static final int MAX_PAGE = 10;

      private final SearchCacheRepository cache;
      private final SearchWriter writer;
      private final BookRepository books;
      private final BookCatalog catalog;
      private final BookshelfProperties props;
      private final Clock clock;

      public SearchService(SearchCacheRepository cache, SearchWriter writer, BookRepository books,
                           BookCatalog catalog, BookshelfProperties props, Clock clock) {
          this.cache = cache;
          this.writer = writer;
          this.books = books;
          this.catalog = catalog;
          this.props = props;
          this.clock = clock;
      }

      public SearchResult search(String rawQuery, int page) {
          if (page < 1 || page > MAX_PAGE) {
              throw new DomainException("페이지는 1~" + MAX_PAGE + "다");
          }
          String key = QueryNormalizer.normalize(rawQuery);
          Instant now = clock.instant();

          Optional<SearchCacheRepository.Entry> cached = cache.find(key, page);
          if (cached.isPresent() && cached.get().fetchedAt().plus(props.searchCacheTtl()).isAfter(now)) {
              return fromCache(cached.get(), page);
          }
          try {
              CatalogPage fetched = catalog.search(key, page, CallPurpose.INTERACTIVE);
              writer.save(key, page, fetched, now);
              return fromCache(cache.find(key, page).orElseThrow(), page);
          } catch (ProviderUnavailableException e) {
              return cached.map(c -> fromCache(c, page))
                      .orElseThrow(() -> new SearchUnavailableException("검색을 잠시 쓸 수 없다", e));
          }
      }

      private SearchResult fromCache(SearchCacheRepository.Entry entry, int page) {
          Map<String, Book> byIsbn = books.findAllById(entry.isbns()).stream()
                  .collect(Collectors.toMap(Book::getIsbn13, Function.identity()));
          List<BookView> ordered = entry.isbns().stream()
                  .map(byIsbn::get)
                  .filter(Objects::nonNull)
                  .map(BookView::of)
                  .toList();
          boolean isEnd = page * BookCatalog.PAGE_SIZE >= entry.total();
          return new SearchResult(ordered, entry.total(), isEnd, entry.fetchedAt());
      }
  }
  ```

  `isEnd`는 캐시에 저장하지 않고 `total`과 페이지로 계산한다. 캐시 테이블에 컬럼을 늘리지 않기 위해서다.

- [ ] **Step 6: 통과 확인**

  Run: `./gradlew test --tests SearchServiceTest --tests QueryNormalizerTest`
  Expected: PASS

- [ ] **Step 7: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `src/main/java/com/bookshelf/search src/test/java/com/bookshelf/search` (`docs/notes/search-normalization.md`는 커밋하지 않는다)

  ```bash
  git add <허용받은 경로>
  git commit -m "feat: 검색어 캐시 — 표기만 다른 같은 검색어는 카카오에 한 번

  서버도 2글자 규칙을 검사한다(디바운스는 앱의 1층일 뿐 구버전·비앱 클라이언트는 지키지 않는다).
  검색 응답의 판매 상태도 저장해 이후 책 페이지가 카카오를 부르지 않게 한다."
  ```

---

### Task 8: 백필 — "책 정보는 나중에 붙는다"의 "나중에"

설계 문서 5장은 ISBN만으로 기록이 먼저 생긴다고 했다. 그러면 **누군가 나중에 책 정보를 붙여야 한다.** 10분마다 `book`이 없는 기록의 ISBN을 최대 20개 골라 백그라운드 예산으로 조회한다. 제공자가 모르는 ISBN은 백오프(1시간 → 4시간 → … → 7일)로 미룬다 — 안 그러면 독립출판 책 하나가 하루 144번 예산을 태운다.

**Files:**
- Create: `src/main/java/com/bookshelf/book/application/BookBackfill.java`
- Modify: `src/main/java/com/bookshelf/book/application/BackgroundJobs.java`
- Test: `src/test/java/com/bookshelf/book/application/BookBackfillTest.java`

**Interfaces:**
- Consumes: `BookLookupService#findOrFetch(Isbn13, BACKGROUND)`, `BookWriter#recordFailure`, `BookshelfProperties#background`, `reading_record`/`book`/`book_lookup_failure` 테이블
- Produces: `BookBackfill#runOnce() -> int`(채운 권수). `BackgroundJobs#backfill()` 스케줄

- [ ] **Step 1: 실패하는 테스트**

  `src/test/java/com/bookshelf/book/application/BookBackfillTest.java`:

  ```java
  package com.bookshelf.book.application;

  import com.bookshelf.book.infrastructure.BookLookupFailureRepository;
  import com.bookshelf.book.infrastructure.BookRepository;
  import com.bookshelf.member.domain.Member;
  import com.bookshelf.record.domain.Visibility;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.KakaoStub;
  import com.bookshelf.support.MutableClock;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.test.context.TestConfiguration;
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Import;
  import org.springframework.context.annotation.Primary;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;

  import java.time.Duration;
  import java.time.Instant;
  import java.time.LocalDate;
  import java.time.ZoneId;

  import static com.bookshelf.support.KakaoStub.doc;
  import static com.bookshelf.support.KakaoStub.response;
  import static org.assertj.core.api.Assertions.assertThat;

  @SpringBootTest
  @Import(Fixtures.class)
  class BookBackfillTest {

      private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");
      private static final String A = "9788936434120";
      private static final String B = "9788954651134";
      private static final String UNKNOWN = "9791100000007";

      @TestConfiguration
      static class ClockOverride {
          @Bean
          @Primary
          MutableClock mutableClock() {
              return new MutableClock(T0);
          }
      }

      @DynamicPropertySource
      static void kakao(DynamicPropertyRegistry r) {
          r.add("providers.kakao.base-url", KakaoStub::baseUrl);
      }

      @Autowired JdbcClient jdbc;
      @Autowired BookBackfill backfill;
      @Autowired BookRepository books;
      @Autowired BookLookupFailureRepository failures;
      @Autowired Fixtures fx;
      @Autowired MutableClock clock;

      private Member m;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          KakaoStub.reset();
          clock.set(T0);
          m = fx.member("juhee", false);
      }

      @Test
      void 책_정보가_없는_기록의_책을_채운다() {
          fx.record(m, A, Visibility.PRIVATE, null, null);
          KakaoStub.returns(A, response(1, true, doc(A, "소년이 온다", 15000, 13500, "정상판매")));

          assertThat(backfill.runOnce()).isEqualTo(1);
          assertThat(books.findById(A)).isPresent();
      }

      @Test
      void 이미_책이_있으면_묻지_않는다() {
          fx.book(A, "소년이 온다");
          fx.record(m, A, Visibility.PRIVATE, null, null);

          backfill.runOnce();

          assertThat(KakaoStub.calls()).isZero();
      }

      @Test
      void 모르는_ISBN은_미루고_다음_회차에_묻지_않는다() {
          fx.record(m, UNKNOWN, Visibility.PRIVATE, null, null);
          KakaoStub.returns(UNKNOWN, response(0, true));

          backfill.runOnce();
          backfill.runOnce();

          assertThat(KakaoStub.calls()).isEqualTo(1);
          assertThat(failures.findById(UNKNOWN).orElseThrow().getAttempts()).isEqualTo(1);
      }

      @Test
      void 미룬_시간이_지나면_다시_묻고_더_오래_미룬다() {
          fx.record(m, UNKNOWN, Visibility.PRIVATE, null, null);
          KakaoStub.returns(UNKNOWN, response(0, true));
          backfill.runOnce();

          clock.advance(Duration.ofMinutes(61));
          backfill.runOnce();

          assertThat(KakaoStub.calls()).isEqualTo(2);
          assertThat(failures.findById(UNKNOWN).orElseThrow().getAttempts()).isEqualTo(2);
      }

      @Test
      void 제공자가_죽으면_그_회차를_멈춘다() {
          fx.record(m, A, Visibility.PRIVATE, null, null);
          fx.record(m, B, Visibility.PRIVATE, null, null);
          fx.record(m, UNKNOWN, Visibility.PRIVATE, null, null);
          KakaoStub.failsWith(503);

          backfill.runOnce();

          assertThat(KakaoStub.calls()).isEqualTo(1);
      }

      @Test
      void 예산이_바닥나면_실패로_적지_않고_멈춘다() {
          fx.record(m, A, Visibility.PRIVATE, null, null);
          LocalDate today = LocalDate.ofInstant(T0, ZoneId.of("Asia/Seoul"));
          jdbc.sql("INSERT INTO provider_call_counter (provider, day, calls) VALUES ('KAKAO', :d, 18000)")
                  .param("d", today).update();

          backfill.runOnce();

          assertThat(KakaoStub.calls()).isZero();
          assertThat(failures.count()).isZero();   // 책 탓이 아니다
      }
  }
  ```

- [ ] **Step 2: 실패 확인**

  Run: `./gradlew test --tests BookBackfillTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 3: 구현**

  `src/main/java/com/bookshelf/book/application/BookBackfill.java`:

  ```java
  package com.bookshelf.book.application;

  import com.bookshelf.common.BookshelfProperties;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.provider.BudgetExhaustedException;
  import com.bookshelf.provider.CallPurpose;
  import com.bookshelf.provider.ProviderUnavailableException;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;

  import java.sql.Timestamp;
  import java.time.Clock;
  import java.util.List;

  /** 책 정보 없이 생긴 기록에 책 정보를 붙인다 (설계 문서 5장) */
  @Slf4j
  @Component
  public class BookBackfill {

      private final JdbcClient jdbc;
      private final BookLookupService lookup;
      private final BookWriter writer;
      private final BookshelfProperties props;
      private final Clock clock;

      public BookBackfill(JdbcClient jdbc, BookLookupService lookup, BookWriter writer,
                          BookshelfProperties props, Clock clock) {
          this.jdbc = jdbc;
          this.lookup = lookup;
          this.writer = writer;
          this.props = props;
          this.clock = clock;
      }

      public int runOnce() {
          List<String> pending = jdbc.sql("""
                  SELECT DISTINCT r.isbn13
                  FROM reading_record r
                  LEFT JOIN book b                ON b.isbn13 = r.isbn13
                  LEFT JOIN book_lookup_failure f ON f.isbn13 = r.isbn13
                  WHERE b.isbn13 IS NULL
                    AND (f.isbn13 IS NULL OR f.next_try_at <= :now)
                  LIMIT :n
                  """)
                  .param("now", Timestamp.from(clock.instant()))
                  .param("n", props.background().backfillBatchSize())
                  .query(String.class)
                  .list();

          int filled = 0;
          for (String raw : pending) {
              Isbn13 isbn = Isbn13.parse(raw);
              try {
                  if (lookup.findOrFetch(isbn, CallPurpose.BACKGROUND).isPresent()) {
                      filled++;
                  } else {
                      writer.recordFailure(isbn);
                  }
              } catch (BudgetExhaustedException e) {
                  log.info("백필 중단: 예산 소진 (채운 권수 {})", filled);
                  break;
              } catch (ProviderUnavailableException e) {
                  writer.recordFailure(isbn);
                  log.warn("백필 중단: {} isbn={}", e.getMessage(), raw);
                  break;
              }
          }
          if (!pending.isEmpty()) {
              log.info("백필 {}건 중 {}건 채움", pending.size(), filled);
          }
          return filled;
      }
  }
  ```

  `BackgroundJobs.java`를 다음으로 교체한다(갱신 스케줄은 그대로, 백필 스케줄 추가):

  ```java
  package com.bookshelf.book.application;

  import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
  import org.springframework.context.annotation.Configuration;
  import org.springframework.scheduling.annotation.EnableScheduling;
  import org.springframework.scheduling.annotation.Scheduled;

  /**
   * 로직(RefreshWorker, BookBackfill)과 스케줄을 나눈다. 테스트는 스케줄 없이 runOnce()를 직접 부른다.
   * initialDelay를 주는 이유: 기동 직후 배포 확인 중에 외부 호출이 섞이지 않게 한다.
   */
  @Configuration
  @EnableScheduling
  @ConditionalOnProperty(name = "bookshelf.background.enabled", havingValue = "true")
  class BackgroundJobs {

      private final RefreshWorker refresh;
      private final BookBackfill backfill;

      BackgroundJobs(RefreshWorker refresh, BookBackfill backfill) {
          this.refresh = refresh;
          this.backfill = backfill;
      }

      @Scheduled(fixedDelayString = "${bookshelf.background.refresh-interval}",
                 initialDelayString = "${bookshelf.background.refresh-interval}")
      void refresh() {
          refresh.runOnce();
      }

      @Scheduled(fixedDelayString = "${bookshelf.background.backfill-interval}",
                 initialDelayString = "${bookshelf.background.backfill-interval}")
      void backfill() {
          backfill.runOnce();
      }
  }
  ```

- [ ] **Step 4: 통과 확인**

  Run: `./gradlew test --tests BookBackfillTest`
  Expected: PASS 6개

- [ ] **Step 5: 스케줄이 실제로 뜨는지 확인**

  ```bash
  set -a && . ./.env && set +a && ./gradlew bootRun --args='--bookshelf.background.refresh-interval=PT10S --bookshelf.background.backfill-interval=PT10S'
  ```

  10초 뒤 로그에 오류가 없어야 한다(큐와 기록이 비어 있으므로 "갱신"·"백필" 로그도 없다). `BACKGROUND_JOBS_ENABLED=false`로 띄우면 `BackgroundJobs` 빈이 없어야 한다: `--debug`로 띄워 조건 평가 보고서에서 `BackgroundJobs`가 `Did not match`인지 본다

- [ ] **Step 6: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `src/main/java/com/bookshelf/book/application src/test/java/com/bookshelf/book/application`

  ```bash
  git add <허용받은 경로>
  git commit -m "feat: 백필 — 책 정보 없이 생긴 기록에 나중에 책을 붙인다

  모르는 ISBN은 1h→4h→16h→64h→7d로 미룬다. 없으면 독립출판 책 하나가 하루 144번 예산을 태운다.
  예산 소진은 책 탓이 아니므로 실패로 적지 않는다."
  ```

---

### Task 9: 공개 조회 — "필터를 거는" 것이 아니라 "가져올 수 없는" 구조

**이 계획에서 가장 중요한 Task다.** 설계 문서 7-2: *"실수 한 번이 전체 유출이다."* 설계 문서 8장은 이 테스트의 골격을 **공개 페이지를 만드는 주(W4)에** 쓰라고 한다 — W5부터 공개 웹이 떠 있기 때문이다.

세 겹으로 막는다.
1. **쿼리** — 공개 조회는 `publicview` 패키지의 SQL로만 한다. `WHERE`에 공개 조건이 박혀 있고, `RATING_ONLY` 행의 한줄평은 `CASE`로 **SQL 단계에서 NULL**이 된다. 비공개 한줄평은 JVM 메모리에 올라오지도 않는다
2. **타입** — `RatedEntry`에는 `review` 필드가 없다. 필드가 없으면 실수로 채울 수 없다
3. **빌드** — ArchUnit이 `publicview`가 JPA 엔티티나 `record`/`member`의 리포지터리·서비스를 참조하면 빌드를 깨뜨린다

**Files:**
- Create: `src/main/java/com/bookshelf/publicview/PublicEntry.java` (sealed), `RatedEntry.java`, `ReviewedEntry.java`, `PublicShelf.java`
- Create: `src/main/java/com/bookshelf/publicview/PublicReview.java`, `PublicBook.java`
- Create: `src/main/java/com/bookshelf/publicview/PublicShelfQuery.java`, `PublicBookQuery.java`
- Test: `src/test/java/com/bookshelf/publicview/PublicBoundaryTest.java`
- Test: `src/test/java/com/bookshelf/publicview/PublicViewArchitectureTest.java`

**Interfaces:**
- Consumes: `member`, `reading_record`, `book` 테이블(SQL로만), `ReadingStatus`(enum, 도메인 값이므로 허용)
- Produces:
  - `sealed interface PublicEntry permits RatedEntry, ReviewedEntry` — `isbn13()`, `title()`(**책 정보가 아직 없으면 null — 템플릿은 이때 책 페이지 링크를 걸지 않는다**), `author()`, `coverUrl()`, `status()`, `rating()`
  - `record RatedEntry(String isbn13, String title, String author, String coverUrl, ReadingStatus status, Integer rating)`
  - `record ReviewedEntry(String isbn13, String title, String author, String coverUrl, ReadingStatus status, Integer rating, String review)`
  - `record PublicShelf(String slug, String displayName, int publicCount, List<PublicEntry> entries)` — **member id가 없다**
  - `record PublicReview(String slug, String displayName, Integer rating, String review)`
  - `record PublicBook(String isbn13, String title, String author, String publisher, LocalDate pubDate, String coverUrl, Integer pageCount, List<PublicReview> reviews)`
  - `PublicShelfQuery#find(String slug) -> Optional<PublicShelf>` — 닫힌·없는·탈퇴한 서재는 모두 empty
  - `PublicBookQuery#find(Isbn13) -> Optional<PublicBook>` — `book` 행이 없으면 empty. **제공자를 부르지 않는다**

- [ ] **Step 1: 실패하는 경계 테스트**

  `src/test/java/com/bookshelf/publicview/PublicBoundaryTest.java`:

  ```java
  package com.bookshelf.publicview;

  import com.bookshelf.common.Isbn13;
  import com.bookshelf.member.domain.Member;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.context.annotation.Import;
  import org.springframework.jdbc.core.simple.JdbcClient;

  import java.util.List;
  import java.util.stream.Stream;

  import static com.bookshelf.record.domain.Visibility.*;
  import static org.assertj.core.api.Assertions.assertThat;

  /**
   * 설계 문서 8장 테스트 우선순위 1번. "비공개 기록이 공개 페이지·책 페이지·집계 숫자 어디에도 나오지 않는지. 빠짐없이."
   * 비밀 문자열은 전부 SECRET_으로 시작한다 — 마지막 테스트가 모든 공개 출력에서 이 접두어를 찾는다.
   * Phase 4(W12)에서 이 클래스를 보강한다.
   */
  @SpringBootTest
  @Import(Fixtures.class)
  class PublicBoundaryTest {

      private static final String A = "9788936434120";
      private static final String B = "9788954651134";
      private static final String C = "9791100000007";
      private static final String NO_BOOK = "9780804429573";   // book 행이 없는 ISBN

      @Autowired JdbcClient jdbc;
      @Autowired Fixtures fx;
      @Autowired PublicShelfQuery shelves;
      @Autowired PublicBookQuery bookPages;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          fx.book(A, "소년이 온다");
          fx.book(B, "소년을 위로해줘");
          fx.book(C, "독립출판");

          Member open = fx.member("open", true);
          fx.record(open, A, PUBLIC, 5, "공개한 한줄평");
          fx.record(open, B, RATING_ONLY, 4, "SECRET_별점만_공개한_기록의_한줄평");
          fx.record(open, C, PRIVATE, 1, "SECRET_비공개_기록의_한줄평");
          fx.record(open, NO_BOOK, PUBLIC, null, null);

          // RATING_ONLY 기록만 있는 회원 — 책 페이지에 이름조차 나오면 안 된다 (설계 문서 7-3)
          Member ratingOnly = fx.member("secret-rater", true);
          fx.record(ratingOnly, A, RATING_ONLY, 2, "SECRET_별점만_회원의_한줄평");

          Member closed = fx.member("secret-closed", false);
          fx.record(closed, A, PUBLIC, 3, "SECRET_닫힌_서재의_한줄평");

          Member gone = fx.withdraw(fx.member("secret-gone", true));
          fx.record(gone, A, PUBLIC, 2, "SECRET_탈퇴한_회원의_한줄평");
      }

      @Test
      void 공개_서재에는_PUBLIC과_RATING_ONLY만_나온다() {
          PublicShelf shelf = shelves.find("open").orElseThrow();

          assertThat(shelf.entries()).extracting(PublicEntry::isbn13).containsExactlyInAnyOrder(A, B, NO_BOOK);
      }

      @Test
      void RATING_ONLY_항목은_한줄평_필드가_없는_타입이다() {
          PublicEntry b = entry("open", B);

          assertThat(b).isInstanceOf(RatedEntry.class);
          assertThat(b.rating()).isEqualTo(4);
      }

      @Test
      void PUBLIC_항목은_한줄평을_싣는다() {
          assertThat(entry("open", A)).isInstanceOfSatisfying(ReviewedEntry.class,
                  e -> assertThat(e.review()).isEqualTo("공개한 한줄평"));
      }

      @Test
      void 책_정보가_아직_없는_공개_기록도_서재에_나오되_제목이_없다() {
          PublicEntry e = entry("open", NO_BOOK);

          assertThat(e.title()).isNull();       // 템플릿은 이때 책 페이지 링크를 걸지 않는다 (shelf.html)
          assertThat(e).isInstanceOf(RatedEntry.class);
      }

      @Test
      void 집계_숫자는_공개_기록만_센다() {
          assertThat(shelves.find("open").orElseThrow().publicCount()).isEqualTo(3);   // PRIVATE C 제외
      }

      @Test
      void 닫힌_서재_없는_slug_탈퇴한_회원은_모두_없음이다() {
          assertThat(shelves.find("secret-closed")).isEmpty();
          assertThat(shelves.find("nobody")).isEmpty();
          assertThat(shelves.find("secret-gone")).isEmpty();
      }

      @Test
      void 책_페이지_한줄평은_열린_서재의_PUBLIC만() {
          PublicBook page = bookPages.find(Isbn13.parse(A)).orElseThrow();

          assertThat(page.reviews()).extracting(PublicReview::review).containsExactly("공개한 한줄평");
          assertThat(page.reviews()).extracting(PublicReview::slug).containsExactly("open");
      }

      @Test
      void RATING_ONLY와_PRIVATE는_책_페이지에_실리지_않는다() {
          assertThat(bookPages.find(Isbn13.parse(B)).orElseThrow().reviews()).isEmpty();
          assertThat(bookPages.find(Isbn13.parse(C)).orElseThrow().reviews()).isEmpty();
      }

      @Test
      void 책_행이_없으면_책_페이지도_없다() {
          assertThat(bookPages.find(Isbn13.parse(NO_BOOK))).isEmpty();
      }

      @Test
      void 어떤_공개_출력에도_비밀_문자열이_없다() {
          List<Object> everything = Stream.<Object>concat(
                  Stream.of("open", "secret-rater", "secret-closed", "secret-gone", "nobody").map(shelves::find),
                  Stream.of(A, B, C, NO_BOOK).map(Isbn13::parse).map(bookPages::find)
          ).toList();

          // record의 toString은 모든 필드를 출력한다 — 필드 하나라도 새면 여기 걸린다.
          // 비밀 회원의 slug도 "secret-"으로 시작한다 — 책 페이지에 이름·slug가 새도 걸린다
          String out = bookPagesOnly(everything);
          assertThat(everything.toString()).doesNotContain("SECRET_");
          assertThat(out).doesNotContain("secret-");
      }

      private static String bookPagesOnly(List<Object> everything) {
          return everything.subList(5, everything.size()).toString();
      }

      private PublicEntry entry(String slug, String isbn) {
          return shelves.find(slug).orElseThrow().entries().stream()
                  .filter(e -> e.isbn13().equals(isbn)).findFirst().orElseThrow();
      }
  }
  ```

  `secret-rater`의 서재 자체는 열려 있으므로 `shelves.find("secret-rater")`는 결과가 있다(서재에는 별점만 나온다). 그래서 slug 검사는 **책 페이지 출력만** 본다(`bookPagesOnly`).

- [ ] **Step 2: 실패 확인**

  Run: `./gradlew test --tests PublicBoundaryTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 3: 구현 — 공개 타입**

  ```java
  // src/main/java/com/bookshelf/publicview/PublicEntry.java
  package com.bookshelf.publicview;

  import com.bookshelf.record.domain.ReadingStatus;

  /** 공개 서재의 한 칸. 한줄평은 ReviewedEntry에만 있다. title이 null이면 책 정보가 아직 없다 */
  public sealed interface PublicEntry permits RatedEntry, ReviewedEntry {
      String isbn13();
      String title();
      String author();
      String coverUrl();
      ReadingStatus status();
      Integer rating();
  }
  ```

  ```java
  // src/main/java/com/bookshelf/publicview/RatedEntry.java
  package com.bookshelf.publicview;

  import com.bookshelf.record.domain.ReadingStatus;

  /** RATING_ONLY, 또는 한줄평을 쓰지 않은 PUBLIC. review 필드가 없다 — 없으면 실수로 채울 수 없다 */
  public record RatedEntry(String isbn13, String title, String author, String coverUrl,
                           ReadingStatus status, Integer rating) implements PublicEntry {
  }
  ```

  ```java
  // src/main/java/com/bookshelf/publicview/ReviewedEntry.java
  package com.bookshelf.publicview;

  import com.bookshelf.record.domain.ReadingStatus;

  public record ReviewedEntry(String isbn13, String title, String author, String coverUrl,
                              ReadingStatus status, Integer rating, String review) implements PublicEntry {
  }
  ```

  ```java
  // src/main/java/com/bookshelf/publicview/PublicShelf.java
  package com.bookshelf.publicview;

  import java.util.List;

  /** member id를 담지 않는다. 공개 쪽에서 식별자는 slug뿐이다 */
  public record PublicShelf(String slug, String displayName, int publicCount, List<PublicEntry> entries) {
  }
  ```

  ```java
  // src/main/java/com/bookshelf/publicview/PublicReview.java
  package com.bookshelf.publicview;

  public record PublicReview(String slug, String displayName, Integer rating, String review) {
  }
  ```

  ```java
  // src/main/java/com/bookshelf/publicview/PublicBook.java
  package com.bookshelf.publicview;

  import java.time.LocalDate;
  import java.util.List;

  public record PublicBook(String isbn13, String title, String author, String publisher, LocalDate pubDate,
                           String coverUrl, Integer pageCount, List<PublicReview> reviews) {
  }
  ```

- [ ] **Step 4: 구현 — 쿼리**

  `src/main/java/com/bookshelf/publicview/PublicShelfQuery.java`:

  ```java
  package com.bookshelf.publicview;

  import com.bookshelf.record.domain.ReadingStatus;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;

  import java.util.List;
  import java.util.Optional;

  /**
   * 공개 서재 조회. 인증된 쪽의 리포지터리·서비스를 재사용하지 않는다 (PublicViewArchitectureTest가 강제).
   *
   * 공개 조건은 두 곳에만 있다:
   *   서재 — shelf_public = TRUE AND deleted_at IS NULL
   *   기록 — visibility IN ('PUBLIC', 'RATING_ONLY')
   * 한줄평은 visibility = 'PUBLIC'일 때만 SQL이 돌려준다. RATING_ONLY의 한줄평은 JVM에 올라오지도 않는다.
   */
  @Component
  public class PublicShelfQuery {

      private static final int MAX_ENTRIES = 200;

      private record Owner(long id, String slug, String displayName) {
      }

      private final JdbcClient jdbc;

      public PublicShelfQuery(JdbcClient jdbc) {
          this.jdbc = jdbc;
      }

      public Optional<PublicShelf> find(String slug) {
          return jdbc.sql("""
                          SELECT id, slug, display_name FROM member
                          WHERE slug = :slug AND shelf_public = TRUE AND deleted_at IS NULL
                          """)
                  .param("slug", slug)
                  .query((rs, n) -> new Owner(rs.getLong("id"), rs.getString("slug"), rs.getString("display_name")))
                  .optional()
                  .map(this::load);
      }

      private PublicShelf load(Owner owner) {
          int count = jdbc.sql("""
                          SELECT count(*) FROM reading_record
                          WHERE member_id = :id AND visibility IN ('PUBLIC', 'RATING_ONLY')
                          """)
                  .param("id", owner.id())
                  .query(Integer.class)
                  .single();

          List<PublicEntry> entries = jdbc.sql("""
                          SELECT r.isbn13, b.title, b.author, b.cover_url, r.status, r.rating,
                                 CASE WHEN r.visibility = 'PUBLIC' THEN r.review END AS review
                          FROM reading_record r
                          LEFT JOIN book b ON b.isbn13 = r.isbn13
                          WHERE r.member_id = :id AND r.visibility IN ('PUBLIC', 'RATING_ONLY')
                          ORDER BY r.created_at DESC, r.id DESC
                          LIMIT :limit
                          """)
                  .param("id", owner.id())
                  .param("limit", MAX_ENTRIES)
                  .query((rs, n) -> {
                      String isbn = rs.getString("isbn13");
                      String title = rs.getString("title");
                      String author = rs.getString("author");
                      String cover = rs.getString("cover_url");
                      ReadingStatus status = ReadingStatus.valueOf(rs.getString("status"));
                      Integer rating = rs.getObject("rating", Integer.class);
                      String review = rs.getString("review");
                      // 타입을 명시한다 — 삼항 연산자는 두 record의 교집합 타입으로 추론돼 List<PublicEntry>에 담기지 않는다
                      PublicEntry entry = review == null
                              ? new RatedEntry(isbn, title, author, cover, status, rating)
                              : new ReviewedEntry(isbn, title, author, cover, status, rating, review);
                      return entry;
                  })
                  .list();

          return new PublicShelf(owner.slug(), owner.displayName(), count, entries);
      }
  }
  ```

  `src/main/java/com/bookshelf/publicview/PublicBookQuery.java`:

  ```java
  package com.bookshelf.publicview;

  import com.bookshelf.common.Isbn13;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;

  import java.time.LocalDate;
  import java.util.List;
  import java.util.Optional;

  /**
   * 공개 책 페이지. book 행이 없으면 없음이다 — 제공자를 부르지 않는다.
   * 부르면 ISBN을 하나씩 올려가며 요청하는 것만으로 우리 예산이 바닥난다 (설계 문서 7-2).
   */
  @Component
  public class PublicBookQuery {

      private static final int MAX_REVIEWS = 50;

      private final JdbcClient jdbc;

      public PublicBookQuery(JdbcClient jdbc) {
          this.jdbc = jdbc;
      }

      public Optional<PublicBook> find(Isbn13 isbn) {
          return jdbc.sql("""
                          SELECT isbn13, title, author, publisher, pub_date, cover_url, page_count
                          FROM book WHERE isbn13 = :isbn
                          """)
                  .param("isbn", isbn.value())
                  .query((rs, n) -> new PublicBook(
                          rs.getString("isbn13"),
                          rs.getString("title"),
                          rs.getString("author"),
                          rs.getString("publisher"),
                          rs.getObject("pub_date", LocalDate.class),
                          rs.getString("cover_url"),
                          rs.getObject("page_count", Integer.class),
                          reviews(isbn)))
                  .optional();
      }

      /**
       * 서재가 열려 있고(탈퇴 안 함) 기록이 PUBLIC이고 한줄평이 있는 것만.
       * RATING_ONLY는 이름·별점도 싣지 않는다 (설계 문서 7-3) — WHERE가 PUBLIC만 고른다.
       */
      private List<PublicReview> reviews(Isbn13 isbn) {
          return jdbc.sql("""
                          SELECT m.slug, m.display_name, r.rating, r.review
                          FROM reading_record r
                          JOIN member m ON m.id = r.member_id
                          WHERE r.isbn13 = :isbn
                            AND r.visibility = 'PUBLIC'
                            AND r.review IS NOT NULL
                            AND m.shelf_public = TRUE
                            AND m.deleted_at IS NULL
                          ORDER BY r.updated_at DESC, r.id DESC
                          LIMIT :limit
                          """)
                  .param("isbn", isbn.value())
                  .param("limit", MAX_REVIEWS)
                  .query((rs, n) -> new PublicReview(
                          rs.getString("slug"),
                          rs.getString("display_name"),
                          rs.getObject("rating", Integer.class),
                          rs.getString("review")))
                  .list();
      }
  }
  ```

- [ ] **Step 5: 통과 확인**

  Run: `./gradlew test --tests PublicBoundaryTest`
  Expected: PASS 10개

- [ ] **Step 6: 대조군 — 테스트가 정말 새는 것을 잡는가**

  spring-study의 교훈: 통과만으로는 아무것도 증명되지 않는다. 하나씩 망가뜨리고 **깨지는 것을 본 뒤 되돌린다.**

  | 망가뜨릴 것 | 깨져야 하는 테스트 |
  |---|---|
  | `PublicShelfQuery` 목록 SQL의 `CASE WHEN ... END AS review`를 `r.review`로 | `RATING_ONLY_항목은...`, `어떤_공개_출력에도...` |
  | 목록 SQL의 `visibility IN (...)` 줄 삭제 | `공개_서재에는...` (`CASE`가 비밀 한줄평은 계속 막으므로 `어떤_공개_출력에도...`는 통과한다) |
  | 집계 SQL의 `visibility IN (...)` 줄 삭제 | `집계_숫자는...` |
  | `find`의 `AND deleted_at IS NULL` 삭제 | `탈퇴한_회원은_서재_스위치가_켜져_있어도…` (**실행 중 발견:** `withdraw()`가 스위치도 꺼서 원래 테스트로는 0개가 깨졌다 — 이 테스트를 추가해 메웠다) |
  | `reviews`의 `AND m.deleted_at IS NULL` 삭제 | `탈퇴한_회원은_서재_스위치가_켜져_있어도…` |
  | `reviews`의 `AND m.shelf_public = TRUE` 삭제 | `책_페이지_한줄평은...`, `어떤_공개_출력에도...` |
  | `reviews`의 `r.visibility = 'PUBLIC'`을 `IN ('PUBLIC','RATING_ONLY')`로 | `책_페이지_한줄평은...`, `어떤_공개_출력에도...` (secret-rater) |

  하나라도 깨지지 않으면 테스트에 구멍이 있는 것이다 — 원인을 찾아 테스트를 보강한다. 결과표를 커밋 메시지에 남긴다.

- [ ] **Step 7: 아키텍처 테스트**

  `src/test/java/com/bookshelf/publicview/PublicViewArchitectureTest.java`:

  ```java
  package com.bookshelf.publicview;

  import com.tngtech.archunit.core.importer.ImportOption;
  import com.tngtech.archunit.junit.AnalyzeClasses;
  import com.tngtech.archunit.junit.ArchTest;
  import com.tngtech.archunit.lang.ArchRule;
  import jakarta.persistence.Entity;

  import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

  /** 설계 문서 7-2의 규칙을 사람의 기억이 아니라 빌드로 지킨다 */
  @AnalyzeClasses(packages = "com.bookshelf", importOptions = ImportOption.DoNotIncludeTests.class)
  class PublicViewArchitectureTest {

      @ArchTest
      static final ArchRule 공개_조회는_JPA_엔티티를_쓰지_않는다 = noClasses()
              .that().resideInAPackage("..publicview..")
              .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
              .because("엔티티를 그대로 내보내면 한줄평이 딸려 나간다 (설계 7-3)");

      @ArchTest
      static final ArchRule 공개_조회는_인증된_조회_코드를_재사용하지_않는다 = noClasses()
              .that().resideInAPackage("..publicview..")
              .should().dependOnClassesThat().resideInAnyPackage(
                      "..record.application..", "..record.infrastructure..",
                      "..member.application..", "..member.infrastructure..")
              .because("기존 조회에 공개 필터 하나 추가하다 조건을 빠뜨리는 것이 가장 흔한 사고 경로다 (설계 7-2)");

      @ArchTest
      static final ArchRule 공개_조회는_제공자를_직접_부르지_않는다 = noClasses()
              .that().resideInAPackage("..publicview..")
              .should().dependOnClassesThat().resideInAnyPackage("..catalog..", "..provider..")
              .because("공개 페이지는 어떤 제공자도 동기로 부르지 않는다 (설계 2-1). 판매 칸은 SaleService의 캐시만 본다");
  }
  ```

  Run: `./gradlew test --tests PublicViewArchitectureTest`
  Expected: PASS

  대조군: `PublicShelfQuery`에 `private final com.bookshelf.record.infrastructure.ReadingRecordRepository unused = null;` 한 줄을 넣고 다시 돌린다 → **FAIL** 확인 → 되돌린다. `PublicBookQuery`에 `private final com.bookshelf.catalog.BookCatalog unused = null;`도 같은 방식으로 확인한다

- [ ] **Step 8: 전체 테스트 후 커밋 (사용자 허용 후)**

  Run: `./gradlew test`
  Expected: 전부 PASS

  허용을 요청할 경로: `src/main/java/com/bookshelf/publicview src/test/java/com/bookshelf/publicview`

  ```bash
  git add <허용받은 경로>
  git commit -m "feat: 공개 조회 — 비공개 데이터를 가져올 수 없는 구조

  RATING_ONLY 한줄평은 SQL CASE에서 NULL이 되어 JVM에 올라오지도 않고,
  RatedEntry에는 review 필드가 없다. publicview가 record/member의 리포지터리·서비스나
  제공자(catalog/provider)를 참조하면 ArchUnit이 빌드를 깨뜨린다.
  대조군: (Step 6 결과표 — 여섯 가지 모두 깨졌는지)"
  ```

---

### Task 10: 서점 링크 — API 없이, 설정 한 곳에서

설계 문서 2-1: 새 책·중고·전자책 서점은 **링크만** 둔다. 링크는 API가 필요 없어 제공자가 죽어도 항상 동작한다. 서점이 URL을 바꾸면 설정만 고친다. 두 규칙을 코드로 강제한다.
- **종이책 서점은 `{isbn}`, 전자책 서점은 `{title} {author}`** — 전자책은 ISBN이 종이책과 달라 종이책 ISBN으로는 검색되지 않는다(Google Books 실측). 전자책 템플릿에 `{isbn}`을 쓰면 **기동이 실패한다**
- 링크 문구는 "그 서점에서 이 책 검색하기"다. 상태를 흉내 내지 않는다

**Files:**
- Create: `src/main/java/com/bookshelf/links/LinkGroup.java`, `StoreLink.java`, `StoreLinksProperties.java`, `StoreLinks.java`
- Modify: `src/main/resources/application.yaml` (`store-links` 절)
- Test: `src/test/java/com/bookshelf/links/StoreLinksTest.java`

**Interfaces:**
- Produces:
  - `enum LinkGroup { NEW, USED, EBOOK }` — 새 책 / 중고 / 전자책·구독
  - `record StoreLink(String name, String url)`
  - `record StoreLinksProperties(List<Entry> entries)` prefix `store-links`, `record Entry(LinkGroup group, String name, String template)`
  - `StoreLinks#forBook(String isbn13, String title, String author) -> Map<LinkGroup, List<StoreLink>>` (그룹 순서 NEW→USED→EBOOK, 그룹 안은 설정 순서)

- [ ] **Step 1: 실패하는 테스트**

  `src/test/java/com/bookshelf/links/StoreLinksTest.java`:

  ```java
  package com.bookshelf.links;

  import org.junit.jupiter.api.Test;

  import java.util.List;
  import java.util.Map;

  import static com.bookshelf.links.LinkGroup.*;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  class StoreLinksTest {

      private static StoreLinks links(StoreLinksProperties.Entry... entries) {
          return new StoreLinks(new StoreLinksProperties(List.of(entries)));
      }

      @Test
      void 종이책_서점은_ISBN으로_만든다() {
          StoreLinks l = links(new StoreLinksProperties.Entry(NEW, "교보문고", "https://kyobo.example/search?keyword={isbn}"));

          assertThat(l.forBook("9788936434120", "소년이 온다", "한강").get(NEW))
                  .containsExactly(new StoreLink("교보문고", "https://kyobo.example/search?keyword=9788936434120"));
      }

      @Test
      void 전자책_서점은_괄호_부제를_뺀_제목과_첫_저자로_만들고_인코딩한다() {
          StoreLinks l = links(new StoreLinksProperties.Entry(EBOOK, "리디", "https://ridi.example/search?q={title}+{author}"));

          String url = l.forBook("9788983927620", "해리 포터와 마법사의 돌 1(20주년 개정판)", "조앤 K. 롤링, 강동혁")
                  .get(EBOOK).getFirst().url();

          assertThat(url).isEqualTo("https://ridi.example/search?q="
                  + "%ED%95%B4%EB%A6%AC+%ED%8F%AC%ED%84%B0%EC%99%80+%EB%A7%88%EB%B2%95%EC%82%AC%EC%9D%98+%EB%8F%8C+1"
                  + "+%EC%A1%B0%EC%95%A4+K.+%EB%A1%A4%EB%A7%81");
      }

      @Test
      void 저자가_없으면_제목만_쓴다() {
          StoreLinks l = links(new StoreLinksProperties.Entry(EBOOK, "리디", "https://ridi.example/search?q={title}+{author}"));

          assertThat(l.forBook("9788936434120", "소년이 온다", null).get(EBOOK).getFirst().url())
                  .isEqualTo("https://ridi.example/search?q=%EC%86%8C%EB%85%84%EC%9D%B4+%EC%98%A8%EB%8B%A4");
      }

      @Test
      void 그룹_순서와_설정_순서를_지킨다() {
          StoreLinks l = links(
                  new StoreLinksProperties.Entry(EBOOK, "리디", "https://ridi.example/?q={title}"),
                  new StoreLinksProperties.Entry(NEW, "예스24", "https://yes24.example/?q={isbn}"),
                  new StoreLinksProperties.Entry(NEW, "교보문고", "https://kyobo.example/?q={isbn}"),
                  new StoreLinksProperties.Entry(USED, "알라딘 중고", "https://aladin.example/?q={isbn}"));

          Map<LinkGroup, List<StoreLink>> m = l.forBook("9788936434120", "소년이 온다", "한강");

          assertThat(m.keySet()).containsExactly(NEW, USED, EBOOK);
          assertThat(m.get(NEW)).extracting(StoreLink::name).containsExactly("예스24", "교보문고");
      }

      @Test
      void 전자책_링크에_ISBN을_쓰면_기동이_실패한다() {
          assertThatThrownBy(() -> links(new StoreLinksProperties.Entry(EBOOK, "리디", "https://ridi.example/?q={isbn}")))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("리디");
      }

      @Test
      void https가_아니거나_자리표시가_없으면_기동이_실패한다() {
          assertThatThrownBy(() -> links(new StoreLinksProperties.Entry(NEW, "교보문고", "http://kyobo.example/?q={isbn}")))
                  .isInstanceOf(IllegalStateException.class);
          assertThatThrownBy(() -> links(new StoreLinksProperties.Entry(NEW, "교보문고", "https://kyobo.example/")))
                  .isInstanceOf(IllegalStateException.class);
      }
  }
  ```

  두 번째 테스트의 기대값은 `URLEncoder.encode("해리 포터와 마법사의 돌 1", UTF_8)` + `+` + `URLEncoder.encode("조앤 K. 롤링", UTF_8)`이다. 제목의 `(20주년 개정판)`이 빠지고, 저자는 쉼표 앞 첫 사람만 남는다.

- [ ] **Step 2: 실패 확인**

  Run: `./gradlew test --tests StoreLinksTest`
  Expected: FAIL — 컴파일 실패

- [ ] **Step 3: 구현**

  `src/main/java/com/bookshelf/links/LinkGroup.java`:

  ```java
  package com.bookshelf.links;

  /** 화면의 칸 순서 그대로 (설계 문서 2-1) */
  public enum LinkGroup {
      NEW,     // 새 책
      USED,    // 중고
      EBOOK    // 전자책·구독
  }
  ```

  `src/main/java/com/bookshelf/links/StoreLink.java`:

  ```java
  package com.bookshelf.links;

  /** "그 서점에서 이 책 검색하기" — 그 서점에 책이 있다는 뜻이 아니다 */
  public record StoreLink(String name, String url) {
  }
  ```

  `src/main/java/com/bookshelf/links/StoreLinksProperties.java`:

  ```java
  package com.bookshelf.links;

  import org.springframework.boot.context.properties.ConfigurationProperties;

  import java.util.List;

  @ConfigurationProperties("store-links")
  public record StoreLinksProperties(List<Entry> entries) {

      public record Entry(LinkGroup group, String name, String template) {
      }
  }
  ```

  `src/main/java/com/bookshelf/links/StoreLinks.java`:

  ```java
  package com.bookshelf.links;

  import org.springframework.stereotype.Component;

  import java.net.URLEncoder;
  import java.nio.charset.StandardCharsets;
  import java.util.ArrayList;
  import java.util.EnumMap;
  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;

  /**
   * 서점 검색 링크. API를 부르지 않는다 — 제공자가 모두 죽어도 이 칸은 동작한다.
   * 잘못된 설정은 기동에서 실패시킨다: 조용히 깨진 링크가 공개 페이지에 쌓이는 것보다 낫다.
   */
  @Component
  public class StoreLinks {

      private final List<StoreLinksProperties.Entry> entries;

      public StoreLinks(StoreLinksProperties props) {
          for (StoreLinksProperties.Entry e : props.entries()) {
              validate(e);
          }
          this.entries = List.copyOf(props.entries());
      }

      public Map<LinkGroup, List<StoreLink>> forBook(String isbn13, String title, String author) {
          Map<LinkGroup, List<StoreLink>> byGroup = new EnumMap<>(LinkGroup.class);
          for (StoreLinksProperties.Entry e : entries) {
              String url = e.template()
                      .replace("{isbn}", isbn13)
                      .replace("{title}", encode(searchTitle(title)))
                      .replace("+{author}", author == null || author.isBlank() ? "" : "+" + encode(firstAuthor(author)))
                      .replace("{author}", author == null ? "" : encode(firstAuthor(author)));
              byGroup.computeIfAbsent(e.group(), g -> new ArrayList<>()).add(new StoreLink(e.name(), url));
          }
          Map<LinkGroup, List<StoreLink>> ordered = new LinkedHashMap<>();
          for (LinkGroup g : LinkGroup.values()) {
              if (byGroup.containsKey(g)) {
                  ordered.put(g, List.copyOf(byGroup.get(g)));
              }
          }
          return ordered;
      }

      private static void validate(StoreLinksProperties.Entry e) {
          String t = e.template();
          if (t == null || !t.startsWith("https://")) {
              throw new IllegalStateException("서점 링크는 https여야 한다: " + e.name());
          }
          if (!t.contains("{isbn}") && !t.contains("{title}")) {
              throw new IllegalStateException("서점 링크에 {isbn}이나 {title}이 없다: " + e.name());
          }
          if (e.group() == LinkGroup.EBOOK && t.contains("{isbn}")) {
              throw new IllegalStateException("전자책 서점은 종이책 ISBN으로 검색되지 않는다 — {title}을 써라: " + e.name());
          }
      }

      /** "해리 포터와 마법사의 돌 1(20주년 개정판)" → "해리 포터와 마법사의 돌 1". 판 표시는 검색을 좁히기만 한다 */
      private static String searchTitle(String title) {
          return title == null ? "" : title.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "").strip();
      }

      /** "조앤 K. 롤링, 강동혁" → "조앤 K. 롤링". 저자가 여럿이면 KakaoBookCatalog가 쉼표로 잇는다 — 첫 사람만 쓴다 */
      private static String firstAuthor(String author) {
          return author.split(",")[0].strip();
      }

      private static String encode(String s) {
          return URLEncoder.encode(s, StandardCharsets.UTF_8);
      }
  }
  ```

- [ ] **Step 4: 통과 확인**

  Run: `./gradlew test --tests StoreLinksTest`
  Expected: PASS 6개

- [ ] **Step 5: 실제 링크 설정과 눈으로 확인**

  `application.yaml` 끝에 추가한다. **아래 URL 형식은 초기값이다 — 브라우저로 하나씩 열어 확인한 뒤 틀린 것을 고친다**(설계 문서 2-1 표시 규칙 6):

  ```yaml
  # 서점 검색 링크 (설계 문서 2-1). 형식이 바뀌면 여기만 고친다.
  # 확인 방법: {isbn}=9788936434120, {title}=소년이 온다, {author}=한강 으로 열어 그 책이 보이는가
  # 마지막 확인: (Step 5를 한 날짜)
  store-links:
    entries:
      - { group: NEW,   name: 교보문고,        template: "https://search.kyobobook.co.kr/search?keyword={isbn}" }
      - { group: NEW,   name: 예스24,          template: "https://www.yes24.com/Product/Search?domain=BOOK&query={isbn}" }
      - { group: NEW,   name: 알라딘,          template: "https://www.aladin.co.kr/search/wsearchresult.aspx?SearchTarget=Book&SearchWord={isbn}" }
      - { group: NEW,   name: 영풍문고,        template: "https://www.ypbooks.co.kr/search.yp?query={isbn}" }
      - { group: USED,  name: 알라딘 중고,     template: "https://www.aladin.co.kr/search/wsearchresult.aspx?SearchTarget=Used&SearchWord={isbn}" }
      - { group: USED,  name: 예스24 중고,     template: "https://www.yes24.com/Product/Search?domain=USED&query={isbn}" }
      - { group: EBOOK, name: Google Play 도서, template: "https://play.google.com/store/search?q={title}+{author}&c=books" }
      - { group: EBOOK, name: 리디,            template: "https://ridibooks.com/search?q={title}+{author}" }
      - { group: EBOOK, name: 교보eBook,       template: "https://search.kyobobook.co.kr/search?keyword={title}+{author}&gbCode=EBK" }
      - { group: EBOOK, name: 밀리의서재,      template: "https://www.millie.co.kr/v3/search/result?keyword={title}" }
  ```

  확인용 URL을 뽑아 하나씩 연다:

  ```bash
  # 공개 페이지는 Task 11에서 생기므로 여기서는 템플릿만 펼쳐 URL을 뽑는다
  python3 - <<'PY'
  import re, urllib.parse
  isbn, title, author = "9788936434120", "소년이 온다", "한강"
  for line in open("src/main/resources/application.yaml", encoding="utf-8"):
      m = re.search(r'name: ([^,]+),\s*template: "([^"]+)"', line)
      if m:
          q = lambda s: urllib.parse.quote_plus(s)
          print(m.group(1).strip(), m.group(2).replace("{isbn}", isbn).replace("{title}", q(title)).replace("{author}", q(author)))
  PY
  ```

  각 URL을 브라우저로 열어 **소년이 온다가 결과에 보이는지** 본다. 안 보이는 서점은 그 사이트에서 직접 검색해 주소창의 형식을 옮겨 적는다. 끝나면 yaml의 "마지막 확인" 날짜를 채운다. 확인 결과(서점별 OK/수정)를 `docs/notes/providers.md` 끝에 "서점 링크" 절로 남긴다(커밋하지 않는다)

  기동 확인: `set -a && . ./.env && set +a && BACKGROUND_JOBS_ENABLED=false ./gradlew bootRun`이 오류 없이 뜨면 10개 템플릿이 모두 `validate`를 통과한 것이다

- [ ] **Step 6: 커밋 (사용자 허용 후)**

  허용을 요청할 경로: `src/main/java/com/bookshelf/links src/test/java/com/bookshelf/links src/main/resources/application.yaml`

  ```bash
  git add <허용받은 경로>
  git commit -m "feat: 서점 링크 — API 없이 설정 한 곳에서, 전자책은 제목으로

  제공자가 모두 죽어도 링크 칸은 동작한다. 전자책은 종이책 ISBN으로 검색되지 않아(Google Books 실측)
  전자책 템플릿에 {isbn}을 쓰면 기동이 실패한다. 10개 서점 URL은 브라우저로 확인했다."
  ```

---

### Task 11: 공개 웹페이지 — 봇이 읽는 화면은 서버가 완성된 HTML을 준다

설계 문서 3장: *"사람이 아닌 것(검색봇, 카톡 봇)이 읽는 화면은 서버가 완성된 HTML을 줘야 한다."* Thymeleaf로 `/u/{slug}`(공개 서재)와 `/book/{isbn}`(책 페이지 — 읽을 수단 한 화면의 웹판)을 렌더링하고 `<head>`에 OG 태그를 넣는다. `sitemap.xml`과 `robots.txt`로 검색 유입의 문을 연다.

책 페이지의 칸 (설계 문서 2-1, 공개 웹판):

| 칸 | 이 Phase에서 |
|---|---|
| 빌리기 | 칸 없음. "동네 도서관 대출 여부는 앱에서 확인할 수 있어요 (앱 준비 중)" 안내만 |
| 새 책 | `SaleService#current` — 캐시만. 판매 중/주문 후 발송/판매 정보 없음, 가격, 기준 시각. 없으면 "확인 불가" |
| | 새 책 서점 링크 (Task 10) |
| 중고 | 중고 서점 링크 |
| 전자책·구독 | 전자책 서점 링크. Google Play 가격은 Phase 3 |

**Files:**
- Create: `src/main/java/com/bookshelf/publicview/AgeLabel.java`, `PublicPages.java`
- Create: `src/main/java/com/bookshelf/publicview/PublicShelfController.java`, `PublicBookController.java`
- Create: `src/main/java/com/bookshelf/publicview/SitemapQuery.java`, `SeoController.java`
- Create: `src/main/resources/templates/public/fragments.html`, `shelf.html`, `book.html`, `not-found.html`
- Create: `src/main/resources/static/css/site.css`
- Test: `src/test/java/com/bookshelf/publicview/AgeLabelTest.java`
- Test: `src/test/java/com/bookshelf/publicview/PublicPagesTest.java`

**Interfaces:**
- Consumes: `PublicShelfQuery#find`, `PublicBookQuery#find`, `SaleService#current`, `SaleView`, `SaleStatus`(book.domain), `StoreLinks#forBook`, `LinkGroup`, `BookshelfProperties#siteUrl`, `Isbn13.parse`
- Produces:
  - `AgeLabel.of(Instant fetchedAt, Instant now) -> String` — `"방금 기준"`, `"N분 전 기준"`, `"N시간 전 기준"`, `"N일 전 기준"`
  - HTTP: `GET /u/{slug}`, `GET /book/{isbn}`, `GET /sitemap.xml`, `GET /robots.txt`. 없는 것은 모두 같은 404 HTML
  - `SitemapQuery#publicSlugs() -> List<String>`, `#bookIsbns() -> List<String>`

- [ ] **Step 1: 실패하는 단위 테스트 — 기준 시각 문구**

  `src/test/java/com/bookshelf/publicview/AgeLabelTest.java`:

  ```java
  package com.bookshelf.publicview;

  import org.junit.jupiter.params.ParameterizedTest;
  import org.junit.jupiter.params.provider.CsvSource;

  import java.time.Duration;
  import java.time.Instant;

  import static org.assertj.core.api.Assertions.assertThat;

  class AgeLabelTest {

      private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

      @ParameterizedTest
      @CsvSource({
              "PT30S, 방금 기준",
              "PT5M,  5분 전 기준",
              "PT59M, 59분 전 기준",
              "PT1H,  1시간 전 기준",
              "PT3H10M, 3시간 전 기준",
              "PT23H59M, 23시간 전 기준",
              "PT24H, 1일 전 기준",
              "P3D,   3일 전 기준"
      })
      void 경과_시간을_사람의_말로(String elapsed, String expected) {
          assertThat(AgeLabel.of(NOW.minus(Duration.parse(elapsed)), NOW)).isEqualTo(expected);
      }
  }
  ```

  Run: `./gradlew test --tests AgeLabelTest` → FAIL(컴파일)

  `src/main/java/com/bookshelf/publicview/AgeLabel.java`:

  ```java
  package com.bookshelf.publicview;

  import java.time.Duration;
  import java.time.Instant;

  /** 설계 문서 2-1: 상태가 있는 칸은 "언제 기준인지"를 반드시 같이 보여준다 */
  public final class AgeLabel {

      private AgeLabel() {
      }

      public static String of(Instant fetchedAt, Instant now) {
          Duration d = Duration.between(fetchedAt, now);
          if (d.toMinutes() < 1) {
              return "방금 기준";
          }
          if (d.toHours() < 1) {
              return d.toMinutes() + "분 전 기준";
          }
          if (d.toDays() < 1) {
              return d.toHours() + "시간 전 기준";
          }
          return d.toDays() + "일 전 기준";
      }
  }
  ```

  Run: `./gradlew test --tests AgeLabelTest` → PASS 8개

- [ ] **Step 2: 실패하는 웹 테스트**

  `src/test/java/com/bookshelf/publicview/PublicPagesTest.java`:

  ```java
  package com.bookshelf.publicview;

  import com.bookshelf.book.domain.SaleSnapshot;
  import com.bookshelf.book.domain.SaleStatus;
  import com.bookshelf.book.infrastructure.RefreshQueue;
  import com.bookshelf.book.infrastructure.SaleSnapshotRepository;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.member.domain.Member;
  import com.bookshelf.support.DbCleaner;
  import com.bookshelf.support.Fixtures;
  import com.bookshelf.support.KakaoStub;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.test.context.DynamicPropertyRegistry;
  import org.springframework.test.context.DynamicPropertySource;
  import org.springframework.test.web.servlet.MockMvc;

  import java.time.Duration;
  import java.time.Instant;

  import static com.bookshelf.record.domain.Visibility.*;
  import static org.assertj.core.api.Assertions.assertThat;
  import static org.hamcrest.Matchers.containsString;
  import static org.hamcrest.Matchers.not;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(Fixtures.class)
  class PublicPagesTest {

      private static final String A = "9788936434120";
      private static final String B = "9788954651134";
      private static final String NO_BOOK = "9780804429573";

      @DynamicPropertySource
      static void kakao(DynamicPropertyRegistry r) {
          r.add("providers.kakao.base-url", KakaoStub::baseUrl);
      }

      @Autowired MockMvc mvc;
      @Autowired JdbcClient jdbc;
      @Autowired Fixtures fx;
      @Autowired SaleSnapshotRepository sales;
      @Autowired RefreshQueue queue;

      @BeforeEach
      void setUp() {
          DbCleaner.clean(jdbc);
          KakaoStub.reset();
          KakaoStub.failsWith(503);          // 기본은 "카카오가 죽어 있다" — 공개 페이지는 그래도 떠야 한다
          fx.book(A, "소년이 온다");
          Member open = fx.member("open", true);
          fx.record(open, A, PUBLIC, 5, "<script>alert(1)</script>");
          fx.record(open, NO_BOOK, PUBLIC, null, null);
          fx.member("closed", false);
      }

      private String body(String url) throws Exception {
          return mvc.perform(get(url)).andReturn().getResponse().getContentAsString();
      }

      // ── 공개 서재 ──────────────────────────────────────────

      @Test
      void 공개_서재는_OG_태그가_들어간_완성된_HTML이다() throws Exception {
          mvc.perform(get("/u/open"))
                  .andExpect(status().isOk())
                  .andExpect(content().contentTypeCompatibleWith("text/html"))
                  .andExpect(content().string(containsString("property=\"og:title\" content=\"open의 이름의 서재\"")))
                  .andExpect(content().string(containsString("property=\"og:url\" content=\"http://localhost:8080/u/open\"")))
                  .andExpect(content().string(containsString("property=\"og:image\" content=\"https://image.example/cover.jpg\"")))
                  .andExpect(content().string(containsString("공개한 책 2권")))
                  .andExpect(content().string(containsString("소년이 온다")));
      }

      @Test
      void 닫힌_서재와_없는_서재는_같은_404다() throws Exception {
          mvc.perform(get("/u/closed")).andExpect(status().isNotFound());
          mvc.perform(get("/u/nobody")).andExpect(status().isNotFound());

          assertThat(body("/u/closed")).isEqualTo(body("/u/nobody"));
      }

      @Test
      void 한줄평의_HTML은_글자로_보인다() throws Exception {
          mvc.perform(get("/u/open"))
                  .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                  .andExpect(content().string(not(containsString("<script>alert(1)"))));
      }

      @Test
      void 표시_이름의_HTML도_글자로_보인다() throws Exception {
          jdbc.sql("UPDATE member SET display_name = '<b>굵게</b>' WHERE slug = 'open'").update();

          mvc.perform(get("/u/open"))
                  .andExpect(content().string(not(containsString("<b>굵게</b>"))))
                  .andExpect(content().string(containsString("&lt;b&gt;굵게&lt;/b&gt;")));
      }

      @Test
      void 책_정보가_없는_기록은_ISBN으로_보이고_책_페이지_링크가_없다() throws Exception {
          mvc.perform(get("/u/open"))
                  .andExpect(content().string(containsString(NO_BOOK)))
                  .andExpect(content().string(not(containsString("href=\"/book/" + NO_BOOK + "\""))))
                  .andExpect(content().string(containsString("href=\"/book/" + A + "\"")));
      }

      @Test
      void 서재_페이지는_카카오를_부르지_않는다() throws Exception {
          mvc.perform(get("/u/open")).andExpect(status().isOk());

          assertThat(KakaoStub.calls()).isZero();
      }

      // ── 책 페이지 ──────────────────────────────────────────

      @Test
      void 책_페이지는_판매_상태와_가격과_기준_시각을_보여준다() throws Exception {
          sales.save(SaleSnapshot.of(Isbn13.parse(A), 15000, 13500, SaleStatus.ON_SALE,
                  Instant.now().minus(Duration.ofMinutes(190))));

          mvc.perform(get("/book/" + A))
                  .andExpect(status().isOk())
                  .andExpect(content().string(containsString("판매 중")))
                  .andExpect(content().string(containsString("13,500원")))
                  .andExpect(content().string(containsString("15,000원")))
                  .andExpect(content().string(containsString("3시간 전 기준")))
                  .andExpect(content().string(containsString("property=\"og:title\" content=\"소년이 온다\"")));
      }

      @Test
      void 판매_기록이_없으면_확인_불가이고_카카오를_부르지_않고_큐에_넣는다() throws Exception {
          mvc.perform(get("/book/" + A))
                  .andExpect(status().isOk())
                  .andExpect(content().string(containsString("판매 상태 확인 불가")))
                  .andExpect(content().string(containsString("소년이 온다")));

          assertThat(KakaoStub.calls()).isZero();
          assertThat(queue.size()).isEqualTo(1);
      }

      @Test
      void 판매_정보_없음은_숫자를_쓰지_않는다() throws Exception {
          sales.save(SaleSnapshot.of(Isbn13.parse(A), null, null, SaleStatus.UNKNOWN, Instant.now()));

          String html = body("/book/" + A);

          assertThat(html).contains("판매 정보 없음");
          assertThat(html).doesNotContain("-1원").doesNotContain("0원");
      }

      @Test
      void 책_페이지는_모든_구매처로_가는_링크를_보여준다() throws Exception {
          String html = body("/book/" + A);

          assertThat(html).contains("https://search.kyobobook.co.kr/search?keyword=" + A);          // 새 책 — ISBN
          assertThat(html).contains("SearchTarget=Used&amp;SearchWord=" + A);                        // 중고 — ISBN
          assertThat(html).contains("https://ridibooks.com/search?q=%EC%86%8C%EB%85%84%EC%9D%B4");   // 전자책 — 제목
          assertThat(html).contains("에서 검색");                                                     // "판다"고 말하지 않는다
      }

      @Test
      void 빌리기는_앱으로_안내한다() throws Exception {
          assertThat(body("/book/" + A)).contains("동네 도서관 대출 여부는 앱에서");
      }

      @Test
      void 책_페이지는_열린_서재의_한줄평을_이스케이프해서_싣는다() throws Exception {
          mvc.perform(get("/book/" + A))
                  .andExpect(content().string(containsString("&lt;script&gt;")))
                  .andExpect(content().string(containsString("href=\"/u/open\"")));
      }

      @Test
      void 책_페이지는_출처를_밝힌다() throws Exception {
          assertThat(body("/book/" + A)).contains("도서 정보 제공");
      }

      @Test
      void DB에_없는_ISBN은_카카오를_부르지_않고_404다() throws Exception {
          mvc.perform(get("/book/" + B)).andExpect(status().isNotFound());

          assertThat(KakaoStub.calls()).isZero();
          assertThat(queue.size()).isZero();
      }

      @Test
      void 잘못된_ISBN도_같은_404다() throws Exception {
          mvc.perform(get("/book/12345")).andExpect(status().isNotFound());

          assertThat(body("/book/12345")).isEqualTo(body("/u/nobody"));
      }

      // ── 검색 유입 ──────────────────────────────────────────

      @Test
      void 사이트맵은_열린_서재와_책만_담는다() throws Exception {
          mvc.perform(get("/sitemap.xml"))
                  .andExpect(status().isOk())
                  .andExpect(content().contentTypeCompatibleWith("application/xml"))
                  .andExpect(content().string(containsString("<loc>http://localhost:8080/u/open</loc>")))
                  .andExpect(content().string(not(containsString("/u/closed"))))
                  .andExpect(content().string(containsString("<loc>http://localhost:8080/book/" + A + "</loc>")));
      }

      @Test
      void robots는_사이트맵을_가리킨다() throws Exception {
          mvc.perform(get("/robots.txt"))
                  .andExpect(status().isOk())
                  .andExpect(content().string(containsString("Sitemap: http://localhost:8080/sitemap.xml")));
      }
  }
  ```

  `판매_정보_없음은_숫자를_쓰지_않는다`의 `"0원"` 검사는 `"10,000원"` 같은 정상 가격에도 걸린다 — 이 테스트는 가격이 모두 null인 스냅샷이라 페이지에 가격이 하나도 없어야 하므로 그대로 둔다.

- [ ] **Step 3: 실패 확인**

  Run: `./gradlew test --tests PublicPagesTest`
  Expected: FAIL — 404(컨트롤러 없음) 또는 컴파일 실패

  `책_페이지는_모든_구매처로_가는_링크를_보여준다`는 Task 10 Step 5에서 확인한 URL 형식에 기댄다. 그 단계에서 교보문고·알라딘 중고·리디 형식을 고쳤다면 이 테스트의 기대값도 같이 고친다.

- [ ] **Step 4: 구현 — 컨트롤러**

  `src/main/java/com/bookshelf/publicview/PublicPages.java`:

  ```java
  package com.bookshelf.publicview;

  import org.springframework.http.HttpStatus;
  import org.springframework.web.servlet.ModelAndView;

  /** 공개 페이지 공통. 404는 이유와 상관없이 한 모양이다 — 다르면 그 차이가 존재를 누설한다 */
  final class PublicPages {

      private PublicPages() {
      }

      static ModelAndView notFound() {
          return new ModelAndView("public/not-found", HttpStatus.NOT_FOUND);
      }

      static ModelAndView page(String view, String title, String description, String image, String url) {
          ModelAndView mav = new ModelAndView(view);
          mav.addObject("pageTitle", title);
          mav.addObject("pageDescription", description);
          mav.addObject("pageImage", image);
          mav.addObject("pageUrl", url);
          return mav;
      }
  }
  ```

  `src/main/java/com/bookshelf/publicview/PublicShelfController.java`:

  ```java
  package com.bookshelf.publicview;

  import com.bookshelf.common.BookshelfProperties;
  import com.bookshelf.record.domain.ReadingStatus;
  import org.springframework.stereotype.Controller;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.servlet.ModelAndView;

  import java.util.HashMap;
  import java.util.Map;
  import java.util.Objects;

  @Controller
  public class PublicShelfController {

      private static final Map<ReadingStatus, String> STATUS_LABELS = Map.of(
              ReadingStatus.WANT, "읽고 싶어요",
              ReadingStatus.READING, "읽는 중",
              ReadingStatus.DONE, "읽었어요");

      private final PublicShelfQuery query;
      private final BookshelfProperties props;

      public PublicShelfController(PublicShelfQuery query, BookshelfProperties props) {
          this.query = query;
          this.props = props;
      }

      @GetMapping("/u/{slug}")
      public ModelAndView shelf(@PathVariable String slug) {
          return query.find(slug).map(this::render).orElseGet(PublicPages::notFound);
      }

      private ModelAndView render(PublicShelf shelf) {
          String image = shelf.entries().stream()
                  .map(PublicEntry::coverUrl)
                  .filter(Objects::nonNull)
                  .findFirst()
                  .orElse(null);
          ModelAndView mav = PublicPages.page("public/shelf",
                  shelf.displayName() + "의 서재",
                  "공개한 책 " + shelf.publicCount() + "권",
                  image,
                  props.siteUrl() + "/u/" + shelf.slug());
          mav.addObject("shelf", shelf);
          mav.addObject("statusLabels", STATUS_LABELS);
          mav.addObject("reviews", reviewsByIsbn(shelf));
          return mav;
      }

      /**
       * 템플릿은 타입을 모른다. 한줄평은 ReviewedEntry에서만 꺼내 따로 넘긴다 —
       * 공통 뷰 모델에 review 칸을 만들면 RatedEntry가 필드를 갖지 않게 한 의미가 사라진다.
       */
      private static Map<String, String> reviewsByIsbn(PublicShelf shelf) {
          Map<String, String> reviews = new HashMap<>();
          for (PublicEntry e : shelf.entries()) {
              if (e instanceof ReviewedEntry r) {
                  reviews.put(r.isbn13(), r.review());
              }
          }
          return reviews;
      }
  }
  ```

  `src/main/java/com/bookshelf/publicview/PublicBookController.java`:

  ```java
  package com.bookshelf.publicview;

  import com.bookshelf.book.application.SaleService;
  import com.bookshelf.book.application.SaleView;
  import com.bookshelf.book.domain.SaleStatus;
  import com.bookshelf.common.BookshelfProperties;
  import com.bookshelf.common.DomainException;
  import com.bookshelf.common.Isbn13;
  import com.bookshelf.links.LinkGroup;
  import com.bookshelf.links.StoreLink;
  import com.bookshelf.links.StoreLinks;
  import org.springframework.stereotype.Controller;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.servlet.ModelAndView;

  import java.time.Clock;
  import java.util.List;
  import java.util.Map;
  import java.util.Optional;

  /** 책 페이지 — 읽을 수단 한 화면의 웹판 (설계 문서 2-1). 제공자를 부르지 않는다 */
  @Controller
  public class PublicBookController {

      private static final Map<SaleStatus, String> SALE_LABELS = Map.of(
              SaleStatus.ON_SALE, "판매 중",
              SaleStatus.ORDER, "주문 후 발송",
              SaleStatus.UNKNOWN, "판매 정보 없음");

      private final PublicBookQuery query;
      private final SaleService sales;
      private final StoreLinks links;
      private final BookshelfProperties props;
      private final Clock clock;

      public PublicBookController(PublicBookQuery query, SaleService sales, StoreLinks links,
                                  BookshelfProperties props, Clock clock) {
          this.query = query;
          this.sales = sales;
          this.links = links;
          this.props = props;
          this.clock = clock;
      }

      @GetMapping("/book/{isbn}")
      public ModelAndView book(@PathVariable String isbn) {
          Isbn13 parsed;
          try {
              parsed = Isbn13.parse(isbn);
          } catch (DomainException e) {
              return PublicPages.notFound();
          }
          Optional<PublicBook> found = query.find(parsed);   // 없으면 제공자를 부르지 않고 끝난다
          if (found.isEmpty()) {
              return PublicPages.notFound();
          }
          PublicBook b = found.get();
          SaleView sale = sales.current(parsed);             // 캐시만. 오래됐으면 큐에 넣는다
          Map<LinkGroup, List<StoreLink>> byGroup = links.forBook(b.isbn13(), b.title(), b.author());

          ModelAndView mav = PublicPages.page("public/book",
                  b.title(),
                  description(b),
                  b.coverUrl(),
                  props.siteUrl() + "/book/" + b.isbn13());
          mav.addObject("book", b);
          mav.addObject("sale", sale);
          mav.addObject("saleLabel", sale.available() ? SALE_LABELS.get(sale.status()) : null);
          mav.addObject("saleAge", sale.available() ? AgeLabel.of(sale.fetchedAt(), clock.instant()) : null);
          // 템플릿에서 enum 키로 맵을 꺼내지 않도록 칸별 목록으로 나눠 넘긴다
          mav.addObject("newLinks", byGroup.getOrDefault(LinkGroup.NEW, List.of()));
          mav.addObject("usedLinks", byGroup.getOrDefault(LinkGroup.USED, List.of()));
          mav.addObject("ebookLinks", byGroup.getOrDefault(LinkGroup.EBOOK, List.of()));
          return mav;
      }

      private static String description(PublicBook b) {
          StringBuilder sb = new StringBuilder();
          if (b.author() != null) {
              sb.append(b.author());
          }
          if (b.publisher() != null) {
              sb.append(sb.isEmpty() ? "" : " · ").append(b.publisher());
          }
          return sb.isEmpty() ? b.title() : sb.toString();
      }
  }
  ```

  `src/main/java/com/bookshelf/publicview/SitemapQuery.java`:

  ```java
  package com.bookshelf.publicview;

  import org.springframework.jdbc.core.simple.JdbcClient;
  import org.springframework.stereotype.Component;

  import java.util.List;

  /** 사이트맵도 공개 조회다. 서재 조건은 PublicShelfQuery와 같다 */
  @Component
  public class SitemapQuery {

      /** 사이트맵 한 파일의 상한은 50,000 URL. 넘으면 사이트맵 인덱스로 나눈다(그때 가서) */
      private static final int MAX_SHELVES = 10_000;
      private static final int MAX_BOOKS = 40_000;

      private final JdbcClient jdbc;

      public SitemapQuery(JdbcClient jdbc) {
          this.jdbc = jdbc;
      }

      public List<String> publicSlugs() {
          return jdbc.sql("""
                          SELECT slug FROM member
                          WHERE shelf_public = TRUE AND deleted_at IS NULL
                          ORDER BY id LIMIT :n
                          """)
                  .param("n", MAX_SHELVES)
                  .query(String.class)
                  .list();
      }

      public List<String> bookIsbns() {
          return jdbc.sql("SELECT isbn13 FROM book ORDER BY isbn13 LIMIT :n")
                  .param("n", MAX_BOOKS)
                  .query(String.class)
                  .list();
      }
  }
  ```

  `src/main/java/com/bookshelf/publicview/SeoController.java`:

  ```java
  package com.bookshelf.publicview;

  import com.bookshelf.common.BookshelfProperties;
  import org.springframework.http.MediaType;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.RestController;

  @RestController
  public class SeoController {

      private final SitemapQuery query;
      private final BookshelfProperties props;

      public SeoController(SitemapQuery query, BookshelfProperties props) {
          this.query = query;
          this.props = props;
      }

      @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
      public String robots() {
          return """
                  User-agent: *
                  Allow: /
                  Disallow: /actuator/
                  Sitemap: %s/sitemap.xml
                  """.formatted(props.siteUrl());
      }

      /** slug는 [a-z0-9-], ISBN은 숫자뿐이라 XML 이스케이프가 필요 없다 (Member·Isbn13이 보장한다) */
      @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
      public String sitemap() {
          StringBuilder xml = new StringBuilder("""
                  <?xml version="1.0" encoding="UTF-8"?>
                  <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  """);
          for (String slug : query.publicSlugs()) {
              xml.append("<url><loc>").append(props.siteUrl()).append("/u/").append(slug).append("</loc></url>\n");
          }
          for (String isbn : query.bookIsbns()) {
              xml.append("<url><loc>").append(props.siteUrl()).append("/book/").append(isbn).append("</loc></url>\n");
          }
          return xml.append("</urlset>\n").toString();
      }
  }
  ```

- [ ] **Step 5: 구현 — 템플릿**

  **모든 사용자 입력은 `th:text`/`th:content`/`th:href`로만 출력한다. `th:utext`를 쓰지 않는다.** 푸터의 출처 문구는 Task 0 Step 5에서 확인한 카카오 표시 조건에 맞춘다(조건이 없으면 아래 그대로).

  `src/main/resources/templates/public/fragments.html`:

  ```html
  <!DOCTYPE html>
  <html lang="ko" xmlns:th="http://www.thymeleaf.org">
  <head th:fragment="head(title, description, image, url)">
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title th:text="${title}">book-shelf</title>
      <meta name="description" th:content="${description}">
      <link rel="canonical" th:href="${url}">
      <!-- 카톡·검색봇은 자바스크립트를 실행하지 않는다. 이 태그들이 미리보기 카드의 전부다 -->
      <meta property="og:type" content="website">
      <meta property="og:site_name" content="book-shelf">
      <meta property="og:title" th:content="${title}">
      <meta property="og:description" th:content="${description}">
      <meta property="og:url" th:content="${url}">
      <meta th:if="${image != null}" property="og:image" th:content="${image}">
      <meta name="twitter:card" content="summary">
      <link rel="stylesheet" href="/css/site.css">
  </head>
  <body>
  <footer th:fragment="footer" class="site-footer">
      <p>도서 정보 제공: 카카오 책 검색</p>
  </footer>
  </body>
  </html>
  ```

  `src/main/resources/templates/public/shelf.html`:

  ```html
  <!DOCTYPE html>
  <html lang="ko" xmlns:th="http://www.thymeleaf.org">
  <head th:replace="~{public/fragments :: head(${pageTitle}, ${pageDescription}, ${pageImage}, ${pageUrl})}"></head>
  <body>
  <main class="shelf-page">
      <h1 th:text="${pageTitle}">이름의 서재</h1>
      <p class="count" th:text="${pageDescription}">공개한 책 N권</p>
      <ul class="shelf">
          <li th:each="e : ${shelf.entries}" th:with="review=${reviews[e.isbn13]}">
              <!-- 책 정보가 있으면 책 페이지로. 없으면 링크 없이 회색 칸과 ISBN (설계 문서 7-2) -->
              <a th:if="${e.title != null}" th:href="@{/book/{isbn}(isbn=${e.isbn13})}">
                  <img th:if="${e.coverUrl != null}" th:src="${e.coverUrl}" th:alt="${e.title}" loading="lazy">
                  <div th:if="${e.coverUrl == null}" class="cover-placeholder"></div>
                  <span class="title" th:text="${e.title}">제목</span>
              </a>
              <div th:if="${e.title == null}" class="no-book">
                  <div class="cover-placeholder"></div>
                  <span class="title" th:text="${e.isbn13}">ISBN</span>
                  <span class="pending">책 정보 준비 중</span>
              </div>
              <span class="status" th:text="${statusLabels[e.status]}">읽었어요</span>
              <span class="rating" th:if="${e.rating != null}" th:text="${#strings.repeat('★', e.rating)}">★★★</span>
              <p class="review" th:if="${review != null}" th:text="${review}">한줄평</p>
          </li>
      </ul>
  </main>
  <footer th:replace="~{public/fragments :: footer}"></footer>
  </body>
  </html>
  ```

  `src/main/resources/templates/public/book.html`:

  ```html
  <!DOCTYPE html>
  <html lang="ko" xmlns:th="http://www.thymeleaf.org">
  <head th:replace="~{public/fragments :: head(${pageTitle}, ${pageDescription}, ${pageImage}, ${pageUrl})}"></head>
  <body>
  <main class="book-page">
      <img th:if="${book.coverUrl != null}" class="cover" th:src="${book.coverUrl}" th:alt="${book.title}">
      <div th:if="${book.coverUrl == null}" class="cover-placeholder"></div>
      <h1 th:text="${book.title}">제목</h1>
      <p class="meta" th:text="${pageDescription}">저자 · 출판사</p>
      <p class="meta" th:if="${book.pageCount != null}" th:text="${book.pageCount} + '쪽'">216쪽</p>

      <section class="ways">
          <h2>이 책을 읽는 방법</h2>

          <div class="way">
              <h3>빌리기</h3>
              <p class="muted">동네 도서관 대출 여부는 앱에서 확인할 수 있어요 (앱 준비 중)</p>
          </div>

          <div class="way">
              <h3>새 책</h3>
              <th:block th:if="${sale.available()}">
                  <p>
                      <strong th:text="${saleLabel}">판매 중</strong>
                      <span th:if="${sale.salePrice != null}" th:text="${#numbers.formatInteger(sale.salePrice, 1, 'COMMA') + '원'}">13,500원</span>
                      <span class="muted" th:if="${sale.listPrice != null}" th:text="${'(정가 ' + #numbers.formatInteger(sale.listPrice, 1, 'COMMA') + '원)'}">(정가 15,000원)</span>
                  </p>
                  <p class="as-of" th:text="${saleAge}">3시간 전 기준</p>
              </th:block>
              <p th:unless="${sale.available()}" class="muted">판매 상태 확인 불가</p>
              <ul class="links">
                  <li th:each="l : ${newLinks}"><a th:href="${l.url}" rel="noopener" th:text="${l.name + '에서 검색'}">교보문고에서 검색</a></li>
              </ul>
          </div>

          <div class="way">
              <h3>중고</h3>
              <ul class="links">
                  <li th:each="l : ${usedLinks}"><a th:href="${l.url}" rel="noopener" th:text="${l.name + '에서 검색'}">알라딘 중고에서 검색</a></li>
              </ul>
          </div>

          <div class="way">
              <h3>전자책·구독</h3>
              <ul class="links">
                  <li th:each="l : ${ebookLinks}"><a th:href="${l.url}" rel="noopener" th:text="${l.name + '에서 검색'}">리디에서 검색</a></li>
              </ul>
          </div>
      </section>

      <section class="reviews" th:if="${!book.reviews.isEmpty()}">
          <h2>한줄평</h2>
          <ul>
              <li th:each="r : ${book.reviews}">
                  <a th:href="@{/u/{slug}(slug=${r.slug})}" th:text="${r.displayName}">이름</a>
                  <span class="rating" th:if="${r.rating != null}" th:text="${#strings.repeat('★', r.rating)}">★★★★★</span>
                  <p th:text="${r.review}">한줄평</p>
              </li>
          </ul>
      </section>
  </main>
  <footer th:replace="~{public/fragments :: footer}"></footer>
  </body>
  </html>
  ```

  `src/main/resources/templates/public/not-found.html` — **모델 값을 하나도 쓰지 않는다.** 이유와 상관없이 바이트까지 같아야 한다:

  ```html
  <!DOCTYPE html>
  <html lang="ko">
  <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <meta name="robots" content="noindex">
      <title>페이지를 찾을 수 없어요</title>
      <link rel="stylesheet" href="/css/site.css">
  </head>
  <body>
  <main class="not-found">
      <h1>페이지를 찾을 수 없어요</h1>
      <p>주소가 바뀌었거나 없는 페이지예요.</p>
  </main>
  </body>
  </html>
  ```

  `src/main/resources/static/css/site.css`:

  ```css
  :root { --fg: #1f1f1f; --muted: #6b6b6b; --line: #e5e5e5; --bg: #fff; --placeholder: #d9d9d9; }
  @media (prefers-color-scheme: dark) {
    :root { --fg: #ececec; --muted: #9a9a9a; --line: #333; --bg: #141414; --placeholder: #3a3a3a; }
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--bg); color: var(--fg);
         font: 16px/1.6 -apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Noto Sans KR", sans-serif; }
  main { max-width: 720px; margin: 0 auto; padding: 24px 16px; }
  a { color: inherit; }
  .count, .meta, .as-of, .status, .muted, .pending { color: var(--muted); font-size: 14px; }
  .shelf { list-style: none; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 20px; }
  .shelf img, .cover-placeholder { width: 100%; aspect-ratio: 2 / 3; object-fit: cover; border-radius: 4px; background: var(--placeholder); display: block; }
  .shelf .title { display: block; margin-top: 6px; font-size: 14px; text-decoration: none; word-break: break-all; }
  .shelf a { text-decoration: none; }
  .rating { color: #e0a100; }
  .review { margin: 4px 0 0; font-size: 14px; }
  .book-page .cover, .book-page .cover-placeholder { width: 160px; }
  .ways { border-top: 1px solid var(--line); margin: 16px 0; }
  .way { border-bottom: 1px solid var(--line); padding: 12px 0; }
  .way h3 { margin: 0 0 6px; font-size: 15px; }
  .links { list-style: none; padding: 0; margin: 6px 0 0; display: flex; flex-wrap: wrap; gap: 8px; }
  .links a { display: inline-block; padding: 6px 10px; border: 1px solid var(--line); border-radius: 6px; font-size: 14px; text-decoration: none; }
  .reviews ul { list-style: none; padding: 0; }
  .reviews li { border-bottom: 1px solid var(--line); padding: 12px 0; }
  .site-footer { max-width: 720px; margin: 0 auto; padding: 24px 16px; color: var(--muted); font-size: 12px; }
  ```

- [ ] **Step 6: 통과 확인**

  Run: `./gradlew test --tests PublicPagesTest`
  Expected: PASS 17개.
  `${book.title}`처럼 **record 접근자**를 SpEL이 못 읽으면 `EL1008E: Property or field 'title' cannot be found`가 난다. 그때는 해당 식을 `${book.title()}`처럼 메서드 호출로 바꾼다(`sale.available()`이 그 형태다)

- [ ] **Step 7: 아키텍처 테스트 재확인**

  Run: `./gradlew test --tests PublicViewArchitectureTest`
  Expected: PASS — 컨트롤러가 `book.application`(SaleService), `book.domain`(SaleStatus enum), `links`만 쓰고 `catalog`·`provider`·`record.infrastructure`·`member.infrastructure`와 JPA 엔티티를 참조하지 않는다

- [ ] **Step 8: 눈으로 확인**

  ```bash
  set -a && . ./.env && set +a && ./gradlew bootRun --args='--bookshelf.background.refresh-interval=PT10S'
  # 다른 터미널
  docker exec -i bookshelf-postgres psql -U bookshelf -d bookshelf <<'SQL'
  INSERT INTO member (slug, display_name, shelf_public, default_visibility, created_at)
  VALUES ('demo', '데모', TRUE, 'PRIVATE', now());
  INSERT INTO book (isbn13, title, author, publisher, cover_url, fetched_at)
  VALUES ('9788936434120', '소년이 온다', '한강', '창비', NULL, now());
  INSERT INTO reading_record (member_id, isbn13, status, rating, review, visibility, created_at, updated_at)
  SELECT id, v.isbn, 'DONE', v.rating, v.review, 'PUBLIC', now(), now()
  FROM member, (VALUES ('9788936434120', 5, '오래 남는다'), ('9791100000007', NULL, NULL)) AS v(isbn, rating, review)
  WHERE slug = 'demo';
  SQL
  open http://localhost:8080/u/demo
  open http://localhost:8080/book/9788936434120      # 처음엔 "판매 상태 확인 불가" → 10초 뒤 새로고침하면 판매 중
  curl -s http://localhost:8080/u/demo | grep 'og:'
  ```

  확인할 것:
  - 표지가 없으면 회색 박스
  - `9791100000007`은 링크 없는 회색 칸과 ISBN
  - 휴대폰 폭(개발자 도구 375px)에서 가로 스크롤 없음
  - 다크 모드에서 글자가 보임
  - 책 페이지 첫 요청이 **즉시** 뜨고(카카오를 기다리지 않는다) 10초 뒤 판매 칸이 채워짐 — 로그에 갱신 기록
  - 링크 10개가 각 서점 검색으로 열림

  끝나면 로컬 데이터를 지운다: `docker exec bookshelf-postgres psql -U bookshelf -d bookshelf -c "TRUNCATE member, reading_record, book, sale_snapshot, refresh_request"`

- [ ] **Step 9: 전체 테스트 후 커밋 (사용자 허용 후)**

  Run: `./gradlew test`
  Expected: 전부 PASS

  허용을 요청할 경로: `src/main/java/com/bookshelf/publicview src/main/resources/templates src/main/resources/static src/test/java/com/bookshelf/publicview`

  ```bash
  git add <허용받은 경로>
  git commit -m "feat: 공개 서재·책 페이지 — 봇이 읽는 화면은 완성된 HTML로, 제공자는 기다리지 않는다

  OG 태그가 카톡 미리보기의 전부다(봇은 JS를 실행하지 않는다).
  책 페이지는 읽을 수단 한 화면의 웹판: 판매 칸은 캐시만, 구매처·중고·전자책은 링크, 빌리기는 앱 안내.
  DB에 없는 ISBN은 제공자를 부르지 않고 404. 404는 이유와 상관없이 바이트까지 같다."
  ```

---

### Task 12: ★ 웹 배포 — 이 시점에 "살아있는 서비스"가 생긴다

설계 문서 9장: **"앱이 늦어지거나 Flutter에서 막혀도 11월 초부터 배포된 서비스가 존재한다."** 앱과 로그인이 아직 없으므로 배포 확인용 본인 서재는 SQL로 만든다(`deploy/seed-owner.sql`). 백필이 10분 안에 책 정보를 채우고, 갱신 큐가 판매 칸을 채우면 공개 서재가 완성된다. 그 링크를 카톡으로 보내 미리보기 카드를 확인하는 것이 이 Task의 완료 기준이다.

**푸시와 퍼블릭 저장소 공개는 사용자가 직접 한다.**

**Files:**
- Create: `Dockerfile`
- Create: `deploy/compose.server.yaml`, `deploy/nginx.http.conf`, `deploy/nginx.conf`
- Create: `deploy/seed-owner.sql`, `deploy/backup.sh`, `deploy/README.md`

**Interfaces:**
- Consumes: Task 0의 서버·도메인, `.env`의 `KAKAO_REST_API_KEY`, `/actuator/health`, `bookshelf.site-url`
- Produces: `https://<도메인>/u/<slug>`, `https://<도메인>/book/<isbn>`, `https://<도메인>/sitemap.xml`

- [ ] **Step 1: Dockerfile** — spring-study와 같은 2단 빌드

  ```dockerfile
  # ── 빌드 스테이지 ─────────────────────────────
  FROM eclipse-temurin:21-jdk AS builder
  WORKDIR /app
  COPY gradlew build.gradle.kts settings.gradle.kts ./
  COPY gradle gradle
  RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
  COPY src src
  RUN ./gradlew bootJar --no-daemon -x test

  # ── 실행 스테이지 ─────────────────────────────
  FROM eclipse-temurin:21-jre
  WORKDIR /app
  COPY --from=builder /app/build/libs/*.jar app.jar
  EXPOSE 8080
  # 컨테이너 메모리 한도(mem_limit)의 75%를 힙으로. 954MB 서버에서 mem_limit 512m → 힙 약 384MB
  ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
  ```

  로컬 확인: `docker build -t bookshelf-app . && docker images bookshelf-app`

- [ ] **Step 2: 서버용 compose** — `deploy/compose.server.yaml`

  ```yaml
  # 볼륨·컨테이너 이름 접두어를 고정한다(기본값은 디렉터리 이름 "deploy"라 헷갈린다)
  name: bookshelf

  services:
    postgres:
      image: postgres:16
      container_name: bookshelf-postgres
      environment:
        POSTGRES_DB: bookshelf
        POSTGRES_USER: bookshelf
        POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD를 지정해야 한다}
      volumes:
        - postgres-data:/var/lib/postgresql/data
      # 포트를 퍼블리시하지 않는다. Docker의 -p 포트 공개는 호스트 방화벽(iptables INPUT)을 거치지 않아 DB가 인터넷에 열린다. 접속은 SSH 터널로
      healthcheck:
        test: ["CMD-SHELL", "pg_isready -U bookshelf -d bookshelf"]
        interval: 5s
        timeout: 3s
        retries: 5
      restart: unless-stopped
      mem_limit: 256m

    app:
      # 서버에서 빌드하지 않는다. 맥에서 amd64로 크로스 빌드해 docker load 한다 (README 3절)
      image: bookshelf-app:amd64
      container_name: bookshelf-app
      depends_on:
        postgres:
          condition: service_healthy
      environment:
        DB_URL: jdbc:postgresql://postgres:5432/bookshelf
        DB_USERNAME: bookshelf
        DB_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD를 지정해야 한다}
        KAKAO_REST_API_KEY: ${KAKAO_REST_API_KEY:?KAKAO_REST_API_KEY를 지정해야 한다}
        SITE_URL: "${SITE_URL:?SITE_URL을 지정해야 한다 (예 https://example.com)}"   # 따옴표: 값 안의 ": "를 YAML이 키로 읽는다
        # 평소에는 비워 둔다. 장애 흉내(Step 10)에서만 덮는다
        KAKAO_BASE_URL: ${KAKAO_BASE_URL:-https://dapi.kakao.com}
      mem_limit: 512m
      restart: unless-stopped

    nginx:
      image: nginx:1.27-alpine
      container_name: bookshelf-nginx
      depends_on:
        - app
      ports:
        - "80:80"
        - "443:443"
      volumes:
        # 인증서 발급 전에는 NGINX_CONF=./nginx.http.conf 로 띄운다 (README 4절)
        - ${NGINX_CONF:-./nginx.conf}:/etc/nginx/conf.d/default.conf:ro
        - certbot-webroot:/var/www/certbot:ro
        - certbot-conf:/etc/letsencrypt:ro
      restart: unless-stopped
      mem_limit: 64m

  volumes:
    postgres-data:
    certbot-webroot:
    certbot-conf:
  ```

- [ ] **Step 3: Nginx 설정 두 벌**

  `deploy/nginx.http.conf` — 인증서가 없을 때:

  ```nginx
  server {
      listen 80;
      server_name _;

      location /.well-known/acme-challenge/ {
          root /var/www/certbot;
      }

      location / {
          proxy_pass http://app:8080;
          proxy_set_header Host $host;
      }
  }
  ```

  `deploy/nginx.conf` — HTTPS. `DOMAIN`을 실제 도메인으로 바꿔 커밋한다(비밀값이 아니다):

  ```nginx
  server {
      listen 80;
      server_name DOMAIN www.DOMAIN;

      location /.well-known/acme-challenge/ {
          root /var/www/certbot;
      }

      location / {
          return 301 https://DOMAIN$request_uri;
      }
  }

  server {
      listen 443 ssl;
      http2 on;
      server_name DOMAIN www.DOMAIN;

      ssl_certificate     /etc/letsencrypt/live/DOMAIN/fullchain.pem;
      ssl_certificate_key /etc/letsencrypt/live/DOMAIN/privkey.pem;

      # www는 하나로 모은다. 검색엔진이 같은 페이지를 두 주소로 세지 않게 (canonical과 같은 이유)
      if ($host = www.DOMAIN) {
          return 301 https://DOMAIN$request_uri;
      }

      # 액추에이터는 health만 연다. 정확 일치가 접두 일치보다 먼저 걸린다
      location = /actuator/health {
          proxy_pass http://app:8080;
      }
      location /actuator/ {
          return 404;
      }

      location / {
          proxy_pass http://app:8080;
          # 이 헤더들과 server.forward-headers-strategy가 둘 다 있어야 앱이 https로 인식한다. 없으면 og:url이 http://로 만들어진다
          proxy_set_header Host              $host;
          proxy_set_header X-Real-IP         $remote_addr;
          proxy_set_header X-Forwarded-Proto $scheme;
          proxy_set_header X-Forwarded-Host  $host;
          proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
      }
  }
  ```

- [ ] **Step 4: 본인 서재 시드와 백업 스크립트**

  `deploy/seed-owner.sql` — **Phase 2에서 Apple 로그인을 붙이면 이 회원에 Apple `sub`를 연결한다.** 새로 만들지 않는다:

  ```sql
  -- 사용법 (README 6절):
  --   docker exec -i bookshelf-postgres psql -U bookshelf -d bookshelf -v ON_ERROR_STOP=1 \
  --     -v slug=juhee -v name='주희' -f - < deploy/seed-owner.sql
  INSERT INTO member (slug, display_name, shelf_public, default_visibility, created_at)
  VALUES (:'slug', :'name', TRUE, 'PRIVATE', now())
  ON CONFLICT (slug) DO NOTHING;
  ```

  `deploy/backup.sh` — `reading_record`는 **절대 잃으면 안 되는 것**이다(설계 문서 5장):

  ```bash
  #!/usr/bin/env bash
  # 매일 03:30 cron. 7일치를 남긴다.
  #   30 3 * * * /home/ubuntu/book-shelf/deploy/backup.sh >> /home/ubuntu/backup.log 2>&1
  # 한계: 같은 서버 디스크에 있다. 인스턴스가 사라지면 같이 사라진다 → 서버 밖 복사는 Phase 4
  set -euo pipefail
  DIR=/home/ubuntu/backups
  mkdir -p "$DIR"
  FILE="$DIR/bookshelf-$(date +%F).sql.gz"
  docker exec bookshelf-postgres pg_dump -U bookshelf -d bookshelf | gzip > "$FILE"
  # 빈 파일이면 실패로 본다 — pg_dump가 조용히 실패해도 gzip은 성공한다
  [ "$(gzip -dc "$FILE" | wc -c)" -gt 1000 ] || { echo "백업이 비었다: $FILE"; exit 1; }
  find "$DIR" -name 'bookshelf-*.sql.gz' -mtime +7 -delete
  echo "ok $FILE"
  ```

  `chmod +x deploy/backup.sh`

- [ ] **Step 5: `deploy/README.md` 작성** — 아래 절차를 그대로 적는다. Step 7~11은 이 문서를 따라 실행하는 것이다

  ~~~markdown
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
  ~~~

- [ ] **Step 6: 커밋 (사용자 허용 후, 배포 전)**

  `deploy/nginx.conf`의 `DOMAIN`을 실제 도메인으로 바꾼 뒤 커밋한다.

  허용을 요청할 경로: `Dockerfile deploy`

  ```bash
  git add <허용받은 경로>
  git diff --cached | grep -iE 'KAKAO_REST_API_KEY=.|POSTGRES_PASSWORD=.' && echo "비밀값이 섞였다 — 멈춘다" || true
  git commit -m "chore: 배포 구성 — 인증서 전후 nginx 두 벌, 이미지는 맥에서 amd64로

  서버(1GB)에서 Gradle 빌드를 돌리지 않는다. X-Forwarded 헤더는 처음부터 넣는다(spring-study 3주차 지뢰).
  reading_record는 잃으면 안 되므로 첫 배포부터 매일 pg_dump. 서버 밖 복사는 Phase 4."
  ```

  **푸시는 사용자가 직접 한다**: `git push -u origin main`

- [ ] **Step 7: 배포** — `deploy/README.md` 1~4절을 실행한다

  Expected:
  - `curl -sI https://<도메인>/robots.txt` → `HTTP/2 200`
  - `curl -sI http://<도메인>/robots.txt` → `301` + `location: https://<도메인>/robots.txt`
  - `curl -sI https://www.<도메인>/robots.txt` → `301` (www 정규화)
  - `curl -s https://<도메인>/actuator/health` → `{"status":"UP"}`
  - `curl -so /dev/null -w '%{http_code}' https://<도메인>/actuator/env` → `404`
  - `docker stats --no-stream` 결과(메모리)를 `docs/notes/infra.md`에 적는다
  - Task 0 Step 4의 Google `country=KR` 확인을 서버에서 아직 안 했다면 지금 한다

- [ ] **Step 8: 본인 서재 만들기** — README 6절. 본인이 실제로 가진 책 5권 이상의 ISBN으로 기록을 넣는다

  Expected (10분 뒤):
  - `docker logs bookshelf-app 2>&1 | grep 백필` → `백필 N건 중 N건 채움`
  - `https://<도메인>/u/<slug>`에 표지가 보인다
  - 책 페이지를 한 번 열고 1분 뒤 다시 열면 판매 칸이 "판매 중 · N원 · 방금 기준"으로 채워져 있다
  - `curl -s https://<도메인>/u/<slug> | grep 'og:url'` → `https://<도메인>/u/<slug>` (**`http://`면 X-Forwarded 설정이 빠진 것이다**)

- [ ] **Step 9: 카카오 미리보기 확인 — 이 Task의 완료 기준**

  1. 카카오 공유 디버거(https://developers.kakao.com/tool/debugger/sharing)에 `https://<도메인>/u/<slug>`를 넣고 제목·설명·이미지가 나오는지 본다. 고쳤는데 옛 카드가 나오면 디버거의 "캐시 초기화"를 누른다
  2. 카톡 "나와의 채팅"에 링크를 보낸다 → 카드가 뜨고, 누르면 앱 설치 없이 브라우저에서 서재가 열린다
  3. 책 페이지 링크도 같은 방법으로 확인한다

  스크린샷을 `docs/notes/infra.md`에 붙인다.

- [ ] **Step 10: 봇과 장애를 흉내 낸다 (Review Focus 1·3 운영 확인)**

  ```bash
  count() { ssh bookshelf "docker exec bookshelf-postgres psql -U bookshelf -d bookshelf -tAc \"SELECT coalesce(max(calls),0) FROM provider_call_counter WHERE provider='KAKAO' AND day = (now() AT TIME ZONE 'Asia/Seoul')::date\""; }
  # 체크 숫자까지 맞는(형식 검사를 통과하는) 없는 ISBN 20개 — DB 조회까지 가야 의미가 있다
  isbns=$(python3 -c "
  for n in range(20):
      b = '9791100001%02d' % n
      c = (10 - sum(int(d) * (1 if i % 2 == 0 else 3) for i, d in enumerate(b)) % 10) % 10
      print(b + str(c))")
  before=$(count)
  for isbn in $isbns; do curl -so /dev/null -w '%{http_code} ' "https://<도메인>/book/$isbn"; done; echo
  after=$(count)
  echo "before=$before after=$after"
  ```

  Expected: 전부 `404`, `before == after`.

  카카오 장애 흉내 — 라우팅되지 않는 주소로 connect 타임아웃을 만든다:

  ```bash
  ssh bookshelf 'cd book-shelf && KAKAO_BASE_URL=http://10.255.255.1 docker compose -f deploy/compose.server.yaml --env-file .env up -d app'
  ssh bookshelf "docker exec bookshelf-postgres psql -U bookshelf -d bookshelf -c \"UPDATE sale_snapshot SET fetched_at = now() - interval '7 hours'\""
  curl -so /dev/null -w '%{http_code} %{time_total}\n' https://<도메인>/book/<isbn>   # 1회
  curl -so /dev/null -w '%{http_code} %{time_total}\n' https://<도메인>/book/<isbn>   # 2회
  ```

  Expected: **두 번 모두 200이고 빠르다(< 0.3s)** — 책 페이지는 카카오를 기다리지 않는다. 판매 칸은 "N시간 전 기준"의 옛 값이다. 1분 뒤 `docker logs bookshelf-app 2>&1 | grep '갱신 중단'`에 카카오 호출 실패가 한 줄 있고, 그 뒤 60초 동안은 새 실패 로그 없이 쿨다운으로 넘어간다. 끝나면 `KAKAO_BASE_URL` 없이 다시 기동한다. 측정값을 `docs/notes/infra.md`에 적는다

- [ ] **Step 11: 검색 유입의 문을 연다**

  1. Google Search Console(https://search.google.com/search-console)에 도메인 속성을 추가하고 DNS TXT로 소유권을 확인한다
  2. `https://<도메인>/sitemap.xml`을 제출한다
  3. 백업 cron을 등록하고(`crontab -e`, `deploy/backup.sh` 첫 주석 줄) 한 번 수동 실행해 `ok`를 확인한다

- [ ] **Step 12: `CLAUDE.local.md`에 서버 정보를 적는다** (커밋하지 않는다 — IP·도메인 등록처는 로컬 메모)

  ```markdown

  ## 배포 서버
  - `ssh bookshelf` → (인스턴스 종류, IP, 리전)
  - 도메인 `<도메인>` (등록처, 만료일)
  - 절차: `deploy/README.md`
  - 본인 서재 slug: `<slug>` — Phase 2에서 Apple `sub`를 이 회원에 연결한다
  ```

---

## 이 계획이 끝나면

- **설계 문서 9장 W5의 ★가 찍힌다.** 앱이 없어도 `https://<도메인>/u/<slug>`가 카톡에서 카드로 열리고, 책 페이지가 "읽을 수단"을 보여준다
- 설계 문서 6-7절의 **정보나루 실측 마감(W3)**이 이 계획 안에 지나간다(Task 0 Step 6). 그 결정이 Phase 2의 범위를 정한다
- 아직 없는 것과 넘기는 곳:
  - 기록을 만들 방법이 SQL뿐이다 → **Phase 2**: Apple 로그인(서버가 Apple 공개키로 서명 검증), JWT, 사용자별 호출 상한, 계정 삭제(서버), 기록 API, 인증 뒤의 검색 API(`SearchUnavailableException` → "잠시 후 다시", `InvalidQueryException` → 400), 정보나루 연동(`Library`·`MemberLibrary`·`LibraryHolding`)
  - 앱 화면, 공유 흐름, Google Play 가격(같은 책 확인, `EbookOffer`, Google Books 키를 서버 IP로 제한) → **Phase 3**
  - 제공자 호출 경보(지금은 로그와 테이블뿐 — 사람이 봐야 안다), 서버 밖 백업, 강제 업데이트 스위치, 공개 경계 테스트 보강 → **Phase 4**
