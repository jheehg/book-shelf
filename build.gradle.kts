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
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Boot 4의 새 이름. 우리 JWT(HS256) 검증과 Apple identity token(RS256, JWKS) 검증을 Nimbus로 한다
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
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
    environment("GOOGLE_BOOKS_KEY", "test-google-key")
    // 테스트 컨텍스트에서 백그라운드 작업(갱신 큐, 백필)이 스스로 돌지 않게 한다. 테스트는 runOnce()를 직접 부른다
    environment("BACKGROUND_JOBS_ENABLED", "false")
    // 쿨다운은 싱글턴 빈의 상태라 한 테스트의 실패가 다음 테스트로 샌다.
    // 컨텍스트 전체에서는 끄고, 쿨다운 자체는 KakaoBookCatalogTest가 직접 조립해 검증한다
    systemProperty("providers.kakao.cool-down", "0s")
    systemProperty("providers.google.cool-down", "0s")
    // Phase 2 테스트용 값(모두 가짜)
    environment("JWT_SECRET", "test-jwt-secret-test-jwt-secret-0123456789abcdef")
    environment("LIBRARY_API_KEY", "test-library-key")
    environment("APPLE_CLIENT_ID", "com.bookshelf.test")
    environment("APPLE_TEAM_ID", "TESTTEAM00")
    environment("APPLE_KEY_ID", "TESTKEY000")
    // 테스트용 EC 개인키(PKCS8, 줄바꿈은 |). 실제 Apple 키가 아니다 — 어디에도 등록되지 않는다
    environment("APPLE_PRIVATE_KEY", file("src/test/resources/apple/test-client-key.p8").readText().replace("\n", "|"))
    systemProperty("providers.library.cool-down", "0s")
    // @DynamicPropertySource·@Import가 다른 테스트마다 스프링 컨텍스트가 따로 캐시된다. 컨텍스트마다
    // 커넥션 10개(기본)를 쥐면 Postgres 연결 한도(100)를 넘는다 — 테스트에서는 풀을 작게 둔다
    systemProperty("spring.datasource.hikari.maximum-pool-size", "3")
    systemProperty("spring.datasource.hikari.minimum-idle", "0")
}
