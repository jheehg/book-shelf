# 데이터와 시간

- 스키마 변경은 `src/main/resources/db/migration`에 새 Flyway 마이그레이션을 추가하는 방식으로만 한다.
- 이미 배포된 버전 마이그레이션(`V*.sql`)은 절대 수정하지 않는다.
- 시각은 `TIMESTAMPTZ`로 저장하고 Java에서는 `Instant`로 모델링한다.
- 애플리케이션 코드에서 시스템 시간을 직접 호출하지 않고 Spring `Clock` 빈으로 현재 시각을 얻는다.
