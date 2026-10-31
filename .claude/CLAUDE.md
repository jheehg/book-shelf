# book-shelf Claude 안내

이 저장소의 공통 에이전트 지침은 `AGENTS.md`와 `agent-rules/`에서 관리한다.

작업을 시작하기 전에 `AGENTS.md`를 읽고, 그 파일이 안내하는 문서를 따른다. 브랜치, 커밋, 병합, 푸시 작업에는 `.claude/skills/`의 `repository-git-workflow` 스킬을 사용한다.

## 로컬 명령

```bash
docker compose up -d
set -a && . ./.env && set +a && ./gradlew bootRun
set -a && . ./.env && set +a && DEV_LOGIN_ENABLED=true BACKGROUND_JOBS_ENABLED=false ./gradlew bootRun
./gradlew test
```
