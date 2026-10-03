# 프로젝트 문서

이 디렉터리는 제품의 근거와 구현 계획을 보관한다. 현재 동작의 기준은 테스트를 통과한 코드이며, 계획 문서는 미래 작업을 자동으로 확정하지 않는다.

## 읽는 순서

1. [제품 설계](superpowers/specs/2026-09-27-book-shelf-design.md) — 목표, 도메인 모델, 공개 경계, 제공자·캐시 정책의 기준
2. [제공자 실측](notes/providers.md) — 카카오·Google Books·정보나루의 실제 응답과 미확인 항목
3. [검색어 정규화 결정](notes/search-normalization.md) — 검색 캐시와 외부 검색어에 적용하는 규칙
4. 구현 계획 — 각 Phase의 작업 순서와 당시의 판단 근거

## 현재 상태 (2026-10-03)

| 영역 | 상태 | 근거 |
| --- | --- | --- |
| Phase 1: 공개 웹·책 검색·캐시·배포 구성 | 구현 완료 | `src/main/java`, `src/main/resources`, `deploy/` |
| Phase 2: 인증·기록 API·도서관 연동 | 구현 완료 | Flyway `V1`~`V5`, 관련 통합 테스트 |
| 로컬 개발 로그인 | 구현 완료 | `POST /api/auth/dev`, `DEV_LOGIN_ENABLED=true` |
| 실제 서버 배포·도메인 연결 | 미확인 | 배포 구성은 있으나 운영 배포 여부는 저장소에서 확인할 수 없음 |
| Phase 3: 공유 API·Google Books·Flutter 앱 | 진행 중 | 서버 기능과 Flutter 검색·스캔까지 구현. 책 상세부터 실기기 완료 확인까지 남음 |
| Apple 로그인 앱 UI·TestFlight | 보류 | Apple Developer 멤버십 가입 뒤 진행 |

## 구현 계획 읽는 법

`superpowers/plans/`은 원래 Claude의 Superpowers 작업 흐름에서 작성한 상세 실행 기록이다. 디렉터리 이름은 작성 이력을 보존할 뿐, 현재 구현에 Superpowers 설치나 사용을 요구하지 않는다.

- Phase 1·2는 완료된 구현의 당시 계획이다. 체크박스가 비어 있어도 완료 여부를 뜻하지 않는다.
- Phase 3 계획은 구현 이력과 남은 일을 함께 기록한다. 체크된 단계는 코드·테스트 또는 실제 확인으로 완료한 항목이며, 남은 단계는 아래 요약과 계획의 체크박스를 본다.
- 실제 작업은 저장소 루트의 `AGENTS.md`와 `agent-rules/`를 따른다.

## 문서와 코드의 대조

이번 정리에서 확인한 현재 의도와 계획의 차이는 다음과 같다.

| 항목 | 현재 상태 | 처리 |
| --- | --- | --- |
| 서재 공유 API·기본 공개 범위 | 구현 완료 | Phase 3 Task 1, `b583ac4` |
| 같은 책 판별·Google Books 전자책 가격 API·24시간 캐시 | 구현 및 fixture 통합 테스트 완료 | Phase 3 Task 2·3, `c88e1f8`, `a96de1d`, `b65ade6` |
| Flutter API 계층·개발 로그인·서재 목록·기록 편집 | 구현·테스트 완료 | Phase 3 Task 4~6, `b2adf8a`~`a0f903b` |
| Task 5 시뮬레이터 확인 | 완료 | 로그인 화면 확인 후, 재실행에서 Keychain 세션 복원 및 인증된 빈 서재 응답을 확인 |
| 앱 검색·바코드 | 구현·테스트 완료, 미커밋 | Phase 3 Task 7. 상세 라우트 연결 테스트는 Task 8에서 통과 |
| 책 상세 | 구현·테스트·시뮬레이터 빌드 완료, 미커밋 | Phase 3 Task 8. 실제 시뮬레이터에서 화면·링크 확인은 남음 |
| 내 도서관 등록 | 구현·테스트 완료, 시뮬레이터 확인 대기, 미커밋 | Phase 3 Task 9 |
| 설정·공유 흐름 | 미착수 | Phase 3 Task 10~11 |
| iPhone 실기기에서 MVP 완료 기준 확인 | 미착수 | Phase 3 Task 12. LAN 개발 로그인, 서명 설정 및 실기기 확인 포함 |
| `Provider.GOOGLE_BOOKS` 및 `defaultVisibility` | 구현 완료 | 실제 코드와 Phase 3 Task 1·3 참조 |

계획과 코드가 다를 때는 먼저 이 문서의 상태와 최신 테스트를 확인하고, 계획의 체크박스·진행 기록을 현재 상태에 맞게 갱신한 뒤 구현한다.
