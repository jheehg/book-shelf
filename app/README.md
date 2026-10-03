# book-shelf 앱

바코드로 책을 기록하고, 도서관 소장 여부·서점 링크·전자책 가격을 한 화면에서 확인하는 iOS 우선 Flutter 앱입니다. 서버 API는 저장소 루트의 Spring Boot 애플리케이션이 제공합니다.

## 실행

프로젝트 루트에서 서버와 PostgreSQL을 먼저 실행합니다.

```bash
docker compose up -d
set -a && . ./.env && set +a && DEV_LOGIN_ENABLED=true ./gradlew bootRun
```

앱은 `app/`에서 실행합니다.

```bash
cd app
flutter run
```

iOS 시뮬레이터는 기본 주소인 `http://localhost:8080`을 사용합니다. iPhone 실기기에서는 맥의 LAN 주소를 지정합니다.

```bash
flutter run --dart-define=API_BASE_URL=http://<맥의-LAN-주소>:8080
```

## 구조

```text
lib/
├── main.dart       앱 시작점
├── app.dart        화면 이동과 앱 테마
├── api/            서버 통신, API 모델, 토큰 저장과 갱신
├── auth/           개발 로그인과 인증 상태
├── shelf/          내 서재 목록과 기록 조회
└── widgets/        표지 등 공통 위젯
```

현재는 API 계층, 개발 로그인, 내 서재 목록까지 구현한 단계입니다. 검색·스캔·기록 편집 등 나머지 화면은 아직 구현하지 않았습니다. 화면 구현 순서는 루트의 `docs/superpowers/plans/2026-10-02-phase3-app.md`를 참고합니다.

개발 로그인은 서버를 `DEV_LOGIN_ENABLED=true`로 실행했을 때만 작동합니다. 공개 환경에서는 사용하지 마세요.

## 주요 명령

```bash
flutter doctor   # 개발 환경 확인
flutter test     # 앱 테스트
flutter run      # 기기 또는 시뮬레이터 실행
```
