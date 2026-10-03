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
├── api/            서버 통신, API 모델, 토큰 저장과 갱신
├── auth/           로그인
├── shelf/          내 서재와 기록 편집
├── search/         제목 검색과 바코드 스캔
├── book/           책 상세, 판매·전자책·도서관 정보
├── library/        내 도서관 등록
├── settings/       계정과 공개 범위 설정
└── share/          서재 공개와 공유 흐름
```

현재는 앱 골격과 API 모델·오류 처리·토큰 저장소를 구현한 단계입니다. 기능별 구현 순서는 루트의 `docs/superpowers/plans/2026-10-02-phase3-app.md`를 참고합니다.

## 주요 명령

```bash
flutter doctor   # 개발 환경 확인
flutter test     # 앱 테스트
flutter run      # 기기 또는 시뮬레이터 실행
```
