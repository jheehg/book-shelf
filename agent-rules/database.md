# 데이터와 시간

- 스키마 변경은 `src/main/resources/db/migration`에 새 Flyway 마이그레이션을 추가하는 방식으로만 한다.
- 이미 배포된 버전 마이그레이션(`V*.sql`)은 절대 수정하지 않는다.
- 시각은 `TIMESTAMPTZ`로 저장하고 Java에서는 `Instant`로 모델링한다.
- 애플리케이션 코드에서 시스템 시간을 직접 호출하지 않고 Spring `Clock` 빈으로 현재 시각을 얻는다.
- JPA와 `JdbcClient`를 함께 사용할 수 있다. 엔티티 생명주기와 단순 조회/변경은 JPA를 우선하고, 명시적 projection, 원자적 upsert, 락/큐, 대량 처리처럼 SQL 제어가 중요한 경우 JDBC를 사용한다.
- JPA repository 구현과 JDBC SQL은 모두 기능의 `infrastructure/persistence`에 둔다. `application`과 `presentation`에서 `JdbcClient` 또는 SQL을 직접 사용하지 않는다.
- JDBC 선택 시에는 JPA보다 적합한 이유를 쿼리/클래스의 역할이 드러나도록 유지한다. 단순히 기존 코드와 맞추기 위해 새 JDBC 접근 경로를 추가하지 않는다.
