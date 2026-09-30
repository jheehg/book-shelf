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
