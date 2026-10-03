# 프로젝트 문서

이 디렉터리는 제품의 근거와 구현 계획을 보관한다. 현재 동작의 기준은 테스트를 통과한 코드이며, 계획 문서는 미래 작업을 자동으로 확정하지 않는다.

## 읽는 순서

1. [제품 설계](superpowers/specs/2026-09-27-book-shelf-design.md) — 목표, 도메인 모델, 공개 경계, 제공자·캐시 정책의 기준
2. [제공자 실측](notes/providers.md) — 카카오·Google Books·정보나루의 실제 응답과 미확인 항목
3. [검색어 정규화 결정](notes/search-normalization.md) — 검색 캐시와 외부 검색어에 적용하는 규칙
4. 구현 계획 — 각 Phase의 작업 순서와 당시의 판단 근거

## 현재 상태 (2026-10-02)

| 영역 | 상태 | 근거 |
| --- | --- | --- |
| Phase 1: 공개 웹·책 검색·캐시·배포 구성 | 구현 완료 | `src/main/java`, `src/main/resources`, `deploy/` |
| Phase 2: 인증·기록 API·도서관 연동 | 구현 완료 | Flyway `V1`~`V5`, 관련 통합 테스트 |
| 로컬 개발 로그인 | 구현 완료 | `POST /api/auth/dev`, `DEV_LOGIN_ENABLED=true` |
| 실제 서버 배포·도메인 연결 | 미확인 | 배포 구성은 있으나 운영 배포 여부는 저장소에서 확인할 수 없음 |
| Phase 3: 공유 API·Google Books·Flutter 앱 | 예정 | `app/`과 Google Books 클라이언트는 아직 없음 |
| Apple 로그인 앱 UI·TestFlight | 보류 | Apple Developer 멤버십 가입 뒤 진행 |

## 구현 계획 읽는 법

`superpowers/plans/`은 원래 Claude의 Superpowers 작업 흐름에서 작성한 상세 실행 기록이다. 디렉터리 이름은 작성 이력을 보존할 뿐, 현재 구현에 Superpowers 설치나 사용을 요구하지 않는다.

- Phase 1·2는 완료된 구현의 당시 계획이다. 체크박스가 비어 있어도 완료 여부를 뜻하지 않는다.
- Phase 3는 아직 시작하지 않은 계획이다. 그 안의 `Create`·`Modify` 항목은 현재 코드 변경 요구가 아니라 구현할 때의 작업 목록이다.
- 실제 작업은 저장소 루트의 `AGENTS.md`와 `agent-rules/`를 따른다.

## 문서와 코드의 대조

이번 정리에서 확인한 현재 의도와 계획의 차이는 다음과 같다.

| 항목 | 현재 상태 | 처리 |
| --- | --- | --- |
| Google Books 가격·공유 API·Flutter 앱 | 아직 구현하지 않음 | Phase 3 예정으로 유지 |
| 실기기용 사설 LAN 주소에서 개발 로그인 허용 | 현재 `localhost`, `127.0.0.1`만 허용 | Flutter 실기기 단계의 예정 작업이므로 지금 변경하지 않음 |
| `Provider.GOOGLE_BOOKS` | 호출 예산 타입만 존재 | 실제 클라이언트·캐시·API는 Phase 3에서 추가 |
| `defaultVisibility` | 회원 도메인과 `MeView`에 이미 존재 | 공유 흐름의 나머지 API는 Phase 3에서 추가 |

계획과 코드가 다를 때는 먼저 이 문서의 상태와 최신 테스트를 확인하고, 계획을 현재 요구에 맞게 갱신한 뒤 구현한다.
