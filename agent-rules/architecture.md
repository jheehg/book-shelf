# 애플리케이션 구조

- 서버는 Java 21의 Spring Boot 4로 만들며, PostgreSQL과 Thymeleaf로 렌더링하는 공개 페이지를 사용한다.
- 서버 코드는 기능(도메인) 우선으로 묶고, 기능 안에서는 역할별 하위 패키지를 사용한다. 새 기능은 아래 구조를 기본으로 한다.

  ```text
  <feature>/
    domain/                         # 상태, 값 객체, 불변식, 엔티티
    application/                    # 유스케이스와 기능 내부 결과 모델
    infrastructure/
      persistence/                 # JPA/JDBC 구현, 쿼리, 캐시, 큐, 락
      provider/<provider>/         # 외부 제공자별 클라이언트와 매핑
    presentation/                   # HTTP 컨트롤러와 요청/응답 모델
  ```

- 각 하위 패키지는 실제 역할이 있는 경우에만 만든다. 작다는 이유만으로 의미가 다른 역할을 한 패키지에 섞지 않고, 파일 수를 채우려고 빈 계층을 만들지도 않는다.
- 기능 루트에는 클래스를 두지 않는다. 예외는 `BookShelfApplication` 진입점과 여러 기능에서 공유하는 `common`뿐이다. `ops`처럼 전역 운영 기능도 같은 역할별 패키지를 따른다.
- `domain`은 기능의 상태와 규칙을 표현한다. `application`은 도메인 객체와 어댑터를 조합해 유스케이스를 수행하며 SQL/JDBC를 직접 사용하지 않는다.
- `infrastructure/persistence`는 데이터베이스 접근 경계다. Spring Data/JPA repository와 `JdbcClient` 구현을 모두 이곳에 둔다. `infrastructure/provider/<provider>`는 해당 외부 API의 통신과 응답 매핑을 소유한다.
- `presentation`은 입력을 검증하고 application 결과를 HTTP/HTML 형식으로 바꾼다. 컨트롤러에서 SQL을 실행하거나 외부 제공자를 직접 호출하지 않는다.
- 레이어 사이 이동은 기능의 기존 패턴을 그대로 복제하지 말고 위 역할을 기준으로 판단한다. 경계가 불명확한 새 타입은 임의로 루트에 추가하지 말고 역할을 먼저 결정한다.
- 트랜잭션 안에서 외부 API를 호출하지 않는다. 외부 호출 뒤의 DB 쓰기는 별도 `*Writer` 빈에 둔다.
- 외부 제공자는 하나의 클라이언트로만 호출한다. 그 클라이언트가 호출 예산을 차감하므로 우회하면 카운터가 틀어진다.
- 엔티티는 정적 팩토리로 생성한다. Lombok `@Setter`, `@Data`, `@ToString`을 엔티티에 추가하지 않는다.
- 사용자가 제어할 수 있는 값은 Thymeleaf `th:text`로 출력한다. `th:utext`는 사용하지 않는다.
